# Day 11 · IM 可靠投递：ACK / 离线 / 去重

> **今日目标**：实现"断网不丢、重试不重"的可靠投递链路——发送确认（ACK+超时重试）、离线消息（游标拉取）、幂等去重（msg_client_id）；产出可靠投递链路验证代码。
> **时长**：可靠性分析 1.5h / 链路代码与故障注入测试 2h / RFC 素材整理 0.5h
> **今日产出**：ReliableDelivery 代码（故障注入验证）+ 《可靠投递时序图》+ 超时预算表

## 1. 知识地图

```
可靠性的定义要先收窄（IM RFC §2 的目标节）：不丢（至少一次）+ 不重（幂等消费）+ 有序（会话内 seq 排序）
  ——严格一次（exactly-once）在分布式消息里不可达，工程答案是"至少一次投递+端侧幂等"

不丢的实现链（每跳都要有"确认"才算闭环）：
  发送端 A → 服务端：①客户端生成 msg_client_id（UUID，端侧唯一）②发送 ③等服务端 ACK
    超时未 ACK → 重试（同一 msg_client_id！）1s→2s→4s 指数退避，上限 5 次
    ——M8 day05 超时预算思想：重试预算=1+2+4+8+16=31s 总预算，超预算进"发送失败"态让用户手动重发
  服务端落库成功 → 回 ACK（带服务端 msg_id+seq）
  ——为什么重试不换 id：服务端靠 msg_client_id 幂等，换 id=重复消息
  服务端 → 接收端 B：推送成功不算数，B 回 RECEIPT ACK 才算送达
    B 断线 → 消息留在线（t_message 本来就存了）+ 收件箱游标不动 → B 上线后拉取（离线消息）

离线消息（"不丢"的补全）：
  关键认知：消息永远先落库（conv 维度一份），"在线推送"只是加速器不是必经路
  B 上线 → 带 last_read_seq 拉取：SELECT * FROM t_message WHERE conv_id=? AND seq>? ORDER BY seq
  游标即续传点——断点续传天然成立，不需要单独的"离线箱"存储
  收到消息 → 上报 ACK（last_read_seq 推进）→ 未读数随之正确

不重的实现链：
  服务端幂等：msg_client_id 唯一约束（或 Redis SETNX 短窗去重）——重复提交返回首次结果
  接收端幂等：seq 判重——已见过的 seq 直接丢弃（本地记录已渲染的 max_seq）
  ——两层幂等各管一段：服务端管"落库不重"，端侧管"渲染不重"

消息可靠性时序（全链一张图）：
  A ──send(client_id)──▶ S ──落库+分配 seq──▶ 存储
  A ◀──ACK(msg_id,seq)── S                              ← A 收到 ACK 才标记"已发送"
  S ──push(seq)──▶ B ──RECEIPT ACK──▶ S ──投递成功态
  B 掉线：push 失败 → 无 RECEIPT → B 上线后按游标拉取（兜底路径）
  消息状态机（服务端视角）：SENDING → STORED → DELIVERED → READ（M7 状态机模式第四次复用）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| At-least-once | 至少一次投递（不丢但可能重） |
| Idempotency Key | 幂等键（msg_client_id） |
| ACK / RECEIPT | 发送确认 / 送达回执 |
| Exponential Backoff | 指数退避（1s→2s→4s） |
| Cursor-based Sync | 游标同步（seq 即断点） |
| Offline Message | 离线消息（上线拉取补全） |

## 3. 动手实操：可靠投递验证（故障注入）

```java
// learning/month09-system-design/src/ReliableDelivery.java（模拟丢包/重复/断线，验证不丢不重）
import java.util.*;
import java.util.concurrent.*;

public class ReliableDelivery {
    static final Set<String> storedIds = ConcurrentHashMap.newKeySet();      // 服务端落库（幂等依据）
    static final Map<String, Long> clientSeq = new ConcurrentHashMap<>();    // 端侧已渲染 max_seq（去重依据）
    static final List<String> rendered = Collections.synchronizedList(new ArrayList<>());
    static final Random r = new Random(42);

    /** 发送端：指数退避重试，msg_client_id 全程不变（幂等的根） */
    static void sendWithRetry(String clientId, String payload) {
        long backoff = 1000;
        for (int i = 1; i <= 5; i++) {
            if (serverReceive(clientId, payload)) return;                    // ACK 到手即成功
            System.out.println("  [retry " + i + "] " + clientId + " 未 ACK，退避 " + backoff + "ms 后重试");
            try { Thread.sleep(backoff / 10); } catch (InterruptedException ignored) {}  // demo 加速 10 倍
            backoff = Math.min(backoff * 2, 16_000);
        }
        throw new IllegalStateException("重试预算耗尽 → 进失败态（用户可见）");
    }
    /** 服务端：msg_client_id 幂等落库，分配 seq 后回 ACK */
    static synchronized boolean serverReceive(String clientId, String payload) {
        if (r.nextInt(10) < 3) return false;                                 // 故障注入：30% 丢包（ACK 丢失）
        if (!storedIds.add(clientId)) {                                      // 幂等：重复提交返回"成功"但不重复落库
            System.out.println("  [dedup] " + clientId + " 重复提交 → 返回首次 ACK");
            return true;
        }
        System.out.println("  [store] " + clientId + " 落库成功 seq=" + storedIds.size());
        return true;
    }
    /** 接收端：按 seq 拉取+渲染，seq 判重 */
    static void receive(long seq, String payload) {
        Long seen = clientSeq.get("conv1");
        if (seen != null && seq <= seen) { System.out.println("  [dedup-r] seq=" + seq + " 已渲染过，丢弃"); return; }
        clientSeq.put("conv1", seq); rendered.add(payload);
    }
    public static void main(String[] a) {
        // 场景1：发送端丢包重试（30% 丢包率下仍不丢）
        sendWithRetry("cid-001", "hello");
        // 场景2：同 id 重复提交（模拟重试撞上慢 ACK）
        sendWithRetry("cid-001", "hello");                                   // 应触发 [dedup]
        // 场景3：接收端乱序/重复投递（seq 判重）
        receive(1, "m1"); receive(2, "m2"); receive(2, "m2-dup"); receive(3, "m3");
        System.out.println("最终渲染: " + rendered + " / 落库: " + storedIds.size() + " 条");
        // 输出关键行：[dedup] cid-001 重复提交 → 返回首次 ACK；[dedup-r] seq=2 已渲染过，丢弃
        // 结论：发送端 1 个 id + 服务端幂等 + 端侧 seq 判重 = 至少一次投递下的"不丢不重"
    }
}
```

```text
《超时预算表》（M8 day05 思想在 IM 的落地，RFC §4 引用）：
| 环节 | 超时 | 重试 | 总预算 | 耗尽动作 |
|------|------|------|--------|---------|
| 发送→ACK | 1s/次 | 5 次指数 | ~31s | 红点"发送失败"可手点重发 |
| 推送→RECEIPT | 5s/次 | 3 次 | ~35s | 转离线路径（上线拉取兜底）|
| 客户端重连 | 1s 起步 | 指数退避 | 上限 60s | 提示网络异常 |
设计纪律：每一跳的超时和重试次数必须有预算——无预算重试=重试风暴制造机（M8 压测教训）
```

```powershell
cd D:\mywork\springbootai\learning\month09-system-design\src
javac -encoding UTF-8 -d ../out ReliableDelivery.java ; java -cp ../out ReliableDelivery
# 验证：30% 丢包下消息不丢（重试兜底）、重复提交不重复落库、乱序投递不重复渲染
cd D:\mywork\springbootai ; git add . ; git commit -m "day09-11: reliable delivery"
```

## 4. 面试连接

**Q：IM 怎么保证消息不丢不重？（可靠性标准题）**
> 先定义再实现："严格一次"在分布式系统不可达，工程答案是至少一次投递加两层幂等——这是把"不丢不重"从愿望翻译成可实现契约。不丢链路：发送端给每条消息生成全局唯一的 msg_client_id，服务端落库成功才回 ACK，超时未 ACK 就带同一个 id 重试、指数退避五次，总预算 31 秒，超了进"发送失败"让用户手点重发——重试预算必须有数，这是 M8 做压测踩过的教训，无预算重试就是重试风暴。接收侧同理，推送后等 RECEIPT 回执，收不到就转离线路径——关键认知是消息永远先落库，在线推送只是加速器，接收方上线按已读游标拉增量，断点续传天然成立。不重链路分两层：服务端靠 msg_client_id 唯一约束，重复提交返回首次 ACK 不重复落库；端侧靠 seq 判重，见过的序号直接丢渲染。我写过故障注入测试：30% 丢包率下消息零丢失、重复 id 只落库一次、乱序 seq 不重复渲染——可靠性是测出来的不是声称出来的。

**Q：用户手机断网 10 分钟，期间收到的消息怎么补全？（离线路径）**
> 分两段说。断网期间：服务端推送收不到 RECEIPT，重试几次后转离线态——但消息早就落库了（conv 维度一份），所以不需要任何"离线存储"，游标机制就是离线消息的存储方案。重新上线：客户端带着本地已渲染的 max_seq 发起同步——WHERE conv_id=? AND seq>?，只拉增量；拉到后按 seq 排序渲染，已读后上报新游标，未读数自动正确。这里有个容易漏的细节：多端同步——手机和电脑同时在线，游标要以"最落后的端"为基准推未读数，以"最领先的端"为基准同步已读，两套游标语义分开设计，不然会出现"电脑看完了手机还狂闪红点"的体验 bug。游标同步的本质和 M7 订单对账是同一类思想：用单调递增的序号做两端的共识锚点，增量永远可以重放。

## 5. 今日验收清单

- [ ] 可靠投递全链时序图（含两段 ACK+离线兜底）能手画
- [ ] ReliableDelivery 运行：30% 丢包不丢+双重幂等验证通过
- [ ] 《超时预算表》三环节入库（每跳有预算）
- [ ] 多端游标同步（落后端推未读/领先端同步已读）能讲
- [ ] `git add . && git commit -m "day09-11: reliability"`

---
[← Day 10](day10-IM消息模型.md) | [本月目录](README.md) | [Day 12 · IM 万人群与推送路由 →](day12-IM万人群与推送.md)
