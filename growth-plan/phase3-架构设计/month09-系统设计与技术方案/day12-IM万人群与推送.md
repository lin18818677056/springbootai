# Day 12 · IM 万人群与推送路由

> **今日目标**：大群方案——10 万人群的读写模型（day10 读扩散的落地）、跨节点推送路由（连接注册中心）、消息风暴治理；产出《10 万人群聊方案》+ 推送路由代码。
> **时长**：大群模型 1.5h / 推送路由代码 1.5h / 风暴治理方案 1h
> **今日产出**：大群方案文档 + GroupRouter 代码（模拟跨节点路由）+ 风暴治理清单

## 1. 知识地图

```
10 万人群的本质矛盾：一条消息的价值/成本比急剧下降
  消息价值：对发送者=1，对每个接收者递减（10 万人里你在意的发言者<10 个）
  传递成本：写扩散=10 万次写；即使读扩散，10 万人在线推送一次=10 万次下发
  → 设计原则：大群必须"降级传播"——不做全员强一致投递，按需分级

大群三层架构（读扩散+在线广播的组合）：
  ① 存储层（读扩散，day10 已定）：消息写会话一份（t_message 按 conv_id 分片）
     成员靠 last_read_seq 游标拉取——存储侧天然扛住（写一次）
  ② 推送层（在线广播，本日核心）：谁在线推谁，离线不管（游标拉取兜底）
     ——和普通群的本质区别：放弃"全员必达"，改成"在线尽力推+离线自取"
  ③ 降级层（风暴治理）：消息产生速度 > 推送能力时的自保手段（见下）

跨节点推送路由（单聊同款机制，大群把它推向极限）：
  连接注册中心（Redis）：uid → gateway_node 映射（网关心跳时上报，TTL 续期）
  推送一条群消息：
    ① LogicSvc 落库 → ② 查成员表拿在线成员列表（Redis set group_online:{convId}）
    ③ 按 gateway_node 分组聚合：同一节点上的 8000 个成员合成一次"节点内广播"
    ④ 节点间：MQ（或 RPC）投递给各网关节点 → 节点本地遍历 channel 下发
  ——关键优化：按节点聚合，10 万成员可能只分布在 50 个网关节点 → 跨节点消息数=50 不是 10 万
  注册中心数据结构：
    gateway_nodes（set）：存活网关节点列表（心跳续期）
    uid:{uid} → node（string，TTL 30s）：用户所在节点
    group_online:{convId}（set）：群在线成员（网关上下线事件维护）

消息风暴治理（大群的"熔断限流"——M8 day17 四件套迁移）：
  现象：大群突发热点（ Celebrity 发言）→ 消息速率 > 网关下发能力 → 连接堆积→雪崩
  四招（按优先级）：
  ① 网关限流：单节点下发 QPS 上限（如 5 万/s），超限消息进本地队列排队
  ② 端侧合并：同会话 200ms 内多条消息合并渲染（客户端批量拉）——体验损失最小
  ③ 抽样降级：风暴时段非 @我 消息按 10% 抽样推，游标拉取保证最终完整（推送降级≠丢失）
  ④ 熔断切换：网关队列水位>80% → 切换"纯拉模式"（停推送，客户端 2s 轮询游标）
  ——核心思想：推送是优化，拉取是保底；任何时刻系统都处于"推送+拉取兜底"双轨状态

万人群的量化校验（估算进 RFC）：
  10 万人群、5% 在线=5000 在线；消息峰值 100 条/s
  下发压力=100×5000=50 万条/s ÷ 50 节点=每节点 1 万条/s（单节点 netty 可承 3~5 万/s）✓
  热点群再翻 10 倍（1000 条/s）→ 触发限流+抽样——数字决定哪些招要提前上
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Online Broadcast | 在线广播（推在线、离线自取） |
| Connection Registry | 连接注册中心（uid→node） |
| Node-level Fanout | 节点级聚合（跨节点数=节点数） |
| Message Storm | 消息风暴（速率超下发能力） |
| Sampling Downgrade | 抽样降级（推送降级不丢消息） |
| Pull Fallback | 拉取兜底（推送是优化，拉取是保底） |

## 3. 动手实操：跨节点路由模拟

```java
// learning/month09-system-design/src/GroupRouter.java（节点聚合路由+限流降级，语义模拟）
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class GroupRouter {
    // 模拟注册中心：成员→所在网关节点（真实实现：Redis hash + 心跳 TTL）
    static final Map<String, String> uid2node = new ConcurrentHashMap<>();
    static final Map<String, Integer> nodeLoad = new ConcurrentHashMap<>();   // 节点当前下发水位
    static final int NODE_LIMIT = 3;                                          // 单节点下发限流（demo 缩小）

    static void online(String uid, String node) { uid2node.put(uid, node); }
    static void offline(String uid) { uid2node.remove(uid); }

    /** 群消息推送：按节点聚合 → 每节点一次投递 → 节点内限流检查 */
    static long fanout(String convId, String msg) {
        var byNode = uid2node.entrySet().stream()
                .collect(Collectors.groupingBy(Map.Entry::getValue,
                        Collectors.mapping(Map.Entry::getKey, Collectors.toList())));
        long total = 0;
        for (var e : byNode.entrySet()) {
            String node = e.getKey(); var members = e.getValue();
            if (nodeLoad.getOrDefault(node, 0) + members.size() > NODE_LIMIT) {
                System.out.printf("  [limited] %s 水位超限 → %d 人转拉模式（游标自取）%n", node, members.size());
                continue;                                                     // 降级：不推了，离线游标兜底
            }
            nodeLoad.merge(node, members.size(), Integer::sum);
            System.out.printf("  [push] → %s（%d 人合批）: %s%n", node, members.size(), msg);
            total += members.size();
        }
        return total;                                                          // 实际推送人数
    }
    public static void main(String[] a) {
        // 12 人在线分布 4 节点（模拟 10 万人 5% 在线分布 50 节点的缩比模型）
        for (int i = 1; i <= 12; i++) online("u" + i, "gw-" + (i % 4));
        long pushed = fanout("conv-big", "群消息#1");
        System.out.println("在线 12 人，实际推送 " + pushed + " 人（跨节点投递仅 4 次——聚合的效果）");
        // 第二条消息：节点水位已满 → 触发限流转拉模式
        long pushed2 = fanout("conv-big", "群消息#2");
        System.out.println("第二条推送 " + pushed2 + " 人（限流生效，未推的走游标拉取，消息不丢）");
        // 输出要点：①12 人只产生 4 次跨节点投递（聚合比 3:1）②超限节点转拉模式而非丢弃
    }
}
```

```text
《10 万人群聊方案》核心表（docs/rfc/im-rfc.md §大群）：
| 层 | 方案 | 关键参数 |
|----|------|---------|
| 存储 | 读扩散：t_message 一份+成员游标 | conv_id 分片，写压力 O(1) |
| 推送 | 在线广播：注册中心→节点聚合 | 跨节点投递数=在线节点数（≈50）|
| 限流 | 单节点 5 万条/s，队列排队 | 水位 80% 熔断切拉模式 |
| 降级 | 风暴抽样 10%（非@我）| 游标保证最终完整，降级≠丢失 |
| 撤回 | 撤回也是一条消息（seq 递增）| 客户端按 seq 重放自然生效 |
@提醒：mentions 字段随消息存，推送时 @我 消息跳过抽样（优先级通道）
```

```powershell
cd D:\mywork\springbootai\learning\month09-system-design\src
javac -encoding UTF-8 -d ../out GroupRouter.java ; java -cp ../out GroupRouter
# 验证：节点聚合（12 人 4 次投递）、限流触发转拉模式
cd D:\mywork\springbootai ; git add . ; git commit -m "day09-12: big group"
```

## 4. 面试连接

**Q：设计一个 10 万人群聊，和普通群有什么不同？（大群专项）**
> 差异在传播语义的降级。普通群写扩散，消息进每个人收件箱，全员必达；10 万人群写扩散是 10 万次写、读扩散在线推也是 10 万次下发——成本对价值完全不成比例。所以大群三层架构：存储层读扩散，消息写会话一份，成员靠已读游标自取，写压力 O(1)；推送层改"在线广播"，谁在线推谁、离线不管，反正游标拉取兜底，消息不丢只是不实时；治理层上风暴四招——网关限流、端侧合并、非 @我 消息抽样推送、水位超限熔断切纯拉模式。有个关键优化是节点聚合：10 万在线成员可能只分布在 50 个网关节点，先查注册中心按节点分组，跨节点投递是 50 次而不是 10 万次，节点内本地遍历 channel，这是数量级级别的省法。量化校验：5% 在线率、峰值百条消息每秒，下发 50 万条每秒摊到 50 节点每节点 1 万条，netty 承 3~5 万没问题；热点事件翻十倍才触发限流——方案里每个阈值都有数字依据。

**Q：消息风暴时你怎么办？丢了消息算不算事故？（降级哲学追问）**
> 先回答语义再给手段：风暴治理的底线是"存储必达、展示尽力"——消息永远先落库且游标可重放，所以任何降级手段都不会丢消息，丢的只是"实时推送"这个加速器，这就是"降级≠丢失"。四招按优先级：网关限流是第一道，单节点下发上限配队列缓冲；端侧合并是第二道，200ms 窗口内同会话消息批量渲染，用户无感；抽样降级是第三道，风暴时段非 @我 消息按 10% 抽推，@我 消息走优先级通道必推——分级是因为"与我相关"的价值密度完全不同；熔断切换是最后防线，队列水位超 80% 整体切纯拉模式，客户端 2 秒轮询游标，系统从"推"退化为"拉"但依然正确。这套思路和 M8 大促预案同源：每招都有触发条件、执行动作、回滚方式，风暴过去自动恢复推送。核心认知：高可用架构里，推送这种"尽力而为"的优化路径必须永远有拉取这条"确定正确"的保底路径托着。

## 5. 今日验收清单

- [ ] 大群三层架构（读扩散存储/在线广播推送/风暴治理）能画
- [ ] GroupRouter 运行：节点聚合+限流转拉验证通过
- [ ] "跨节点投递数=节点数"的聚合优化能讲清
- [ ] 《10 万人群聊方案》量化表（每层带参数）入库
- [ ] `git add . && git commit -m "day09-12: big group"`

---
[← Day 11](day11-IM可靠投递.md) | [本月目录](README.md) | [Day 13 · RFC 写作（下） →](day13-RFC写作下.md)
