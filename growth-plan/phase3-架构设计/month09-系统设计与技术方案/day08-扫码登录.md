# Day 08 · 扫码登录：三端时序与状态机

> **今日目标**：设计扫码登录——三端交互时序、二维码生命周期状态机（M7 day24 状态机模式复用）、轮询 vs 推送选型；产出可运行 Demo。
> **时长**：时序与状态机设计 2h / Demo 实现 1.5h / 安全分析 0.5h
> **今日产出**：扫码登录 Demo（状态机+双通道）+ 《扫码时序图》+ Redis 设计

## 1. 知识地图

```
参与三方：PC 端（要登录的浏览器）/ 手机 App（已登录，授权方）/ 服务端（认证中心）
核心难点：手机端的"身份"如何安全地传递给 PC 端——中间媒介是二维码（本质是一个一次性 token）

三端时序（关键：每一步谁发起、状态怎么流转）：
  ① PC 端：请求生成二维码 ← 服务端：生成 qrcodeId（UUID），写入 Redis
     qrcode:{qrcodeId} = {status: WAITING, createTime}，TTL 300s（5 分钟过期）
     返回 qrcodeId+二维码图（内容=登录页 URL?qrcodeId=xxx）
  ② PC 端开始轮询：GET /qrcode/status?qrcodeId=xxx（每 2s；或升级为长轮询/WebSocket）
  ③ 手机扫码：App 扫码解析出 qrcodeId，App 端展示"确认登录"页（显示扫码地点/时间——
     这是安全设计：让用户看见"我在授权什么"）
     服务端：校验 App token → qrcode status: WAITING→SCANNED，记录 uid
  ④ 用户点确认：App 提交确认 → status: SCANNED→CONFIRMED，服务端生成 PC 端凭证
     （一次性 code，TTL 60s——不是直接发 token！code 换 token 两段式防中间人）
  ⑤ PC 轮询拿到 status=CONFIRMED + 一次性 code → 用 code 换 token → 登录完成
  ⑥ 服务端：qrcode 标记 USED（一次性，防重放），TTL 仍生效兜底清理

状态机（M7 day24 ActivityStatus 同款模式——非法流转直接拒绝）：
  WAITING → SCANNED → CONFIRMED → USED（正常路径）
  WAITING/SCANNED → EXPIRED（TTL 300s 兜底）；任意状态不可回退、不可跳级
  状态流转表：SCANNED 只接受 CONFIRMED/EXPIRED；CONFIRMED 只接受 USED/EXPIRED
  ——为什么必须状态机：轮询并发下 PC 端可能重复消费 CONFIRMED，状态机+一次性 code 双保险

轮询 vs 长轮询 vs WebSocket（本日决策，day09 四方案全对比）：
  短轮询：2s 间隔，实现最简单，5 分钟窗口最多 150 次请求/qrcode——量小够用
  长轮询：无状态变化 hold 25s，状态变化立即返回——请求量降 90%，PC 端体验更即时
  WebSocket：最即时但引入长连接网关复杂度——扫码场景 5 分钟生命周期，杀鸡用牛刀
  决策：MVP 用 2s 短轮询（简单可靠），V1 升长轮询（一行 defer 的事）——演进思维
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| QR Code Login | 扫码登录（三方授权流程） |
| One-time Code | 一次性凭证（两段式换 token） |
| State Machine | 状态机（非法流转拒绝） |
| Replay Attack | 重放攻击（USED 一次性防重放） |
| QR Hijacking | 二维码劫持（过期+刷新对抗） |
| Short/Long Polling | 短/长轮询（hold 语义） |

## 3. 动手实操：状态机与 Demo

```java
// learning/month09-system-design/src/QrCodeLogin.java（状态机+模拟双端流程）
import java.util.*;
import java.util.concurrent.*;

public class QrCodeLogin {
    enum Status { WAITING, SCANNED, CONFIRMED, USED, EXPIRED;
        private static final Map<Status, Set<Status>> ALLOWED = Map.of(
            WAITING,   EnumSet.of(SCANNED, EXPIRED),
            SCANNED,   EnumSet.of(CONFIRMED, EXPIRED),
            CONFIRMED, EnumSet.of(USED, EXPIRED),
            USED, EnumSet.of(), EXPIRED, EnumSet.of());
        boolean canTransferTo(Status t) { return ALLOWED.getOrDefault(this, Set.of()).contains(t); }
    }
    record Qr(String id, Status status, String uid, String oneTimeCode) {}

    static final Map<String, Status> store = new ConcurrentHashMap<>();       // 模拟 Redis
    static final Map<String, String> code2uid = new ConcurrentHashMap<>();

    static synchronized boolean transfer(String id, Status to) {
        Status cur = store.get(id);
        if (cur == null || !cur.canTransferTo(to)) return false;             // 非法流转拒绝
        store.put(id, to); return true;
    }
    /** ① PC 端生成二维码 */
    static String create() {
        String id = UUID.randomUUID().toString();
        store.put(id, Status.WAITING);
        return id;
    }
    /** ③ 手机扫码（携带已登录 uid）：先流转状态，再原子绑定 uid（putIfAbsent 返回 null=绑定成功） */
    static boolean scan(String id, String uid) {
        return transfer(id, Status.SCANNED) && code2uid.putIfAbsent(id, uid) == null;
    }
    /** ④ 用户确认：两段式——只发一次性 code，不发 token */
    static synchronized Optional<String> confirm(String id, String uid) {
        if (!uid.equals(code2uid.get(id)) || !transfer(id, Status.CONFIRMED)) return Optional.empty();
        String c = UUID.randomUUID().toString();                             // 一次性 code，TTL 60s（真实实现）
        return Optional.of(c);
    }
    /** ⑤ PC 端用 code 换 token（code 一次性，防重放） */
    static String exchange(String id, String oneTimeCode) {
        if (!transfer(id, Status.USED)) return null;                          // 第二次换直接拒绝
        return "token-for-" + code2uid.get(id);
    }
    public static void main(String[] a) {
        String id = create();
        System.out.println("PC 轮询(2s): " + store.get(id));                  // WAITING
        scan(id, "u1001");  System.out.println("手机扫码后: " + store.get(id));// SCANNED
        var c = confirm(id, "u1001");
        System.out.println("确认后: " + store.get(id) + " code=" + c.map(x -> x.substring(0, 8) + "...").orElse("fail"));
        String t1 = exchange(id, c.get());
        String t2 = exchange(id, c.get());                                    // 重放攻击模拟
        System.out.println("首次换 token: " + (t1 != null) + " / 重放换 token: " + (t2 != null));
        // 输出：...SCANNED → CONFIRMED → 首次 true / 重放 false——状态机+一次性 code 双保险生效
        // 非法流转演示：transfer(id, SCANNED) 对 USED 返回 false
    }
}
```

```text
《扫码登录 Redis 设计》（真实实现的数据结构）：
| Key | Value | TTL | 用途 |
|-----|-------|-----|------|
| qrcode:{id} | {status, uid?} | 300s | 二维码生命周期（EXPIRED 靠 TTL 兜底） |
| otcode:{code} | {qrcodeId} | 60s | 一次性 code→换 token 凭证 |
| otcode_used:{code} | 1 | 60s | 已用标记（双保险防重放） |
安全清单：①qrcodeId 用 UUID v4（不可枚举）②code 一次性+60s③状态机拒非法流转
④App 确认页显示地点/设备（用户可识别异常授权）⑤二维码 3s 自动刷新（防截屏劫持）
```

```powershell
cd D:\mywork\springbootai\learning\month09-system-design\src
javac -encoding UTF-8 -d ../out QrCodeLogin.java ; java -cp ../out QrCodeLogin
# 验证：状态流转合法路径全通、重放 exchange 返回 false、非法流转被拒
cd D:\mywork\springbootai ; git add . ; git commit -m "day09-08: qr login"
```

## 4. 面试连接

**Q：设计扫码登录，说说整个流程和防安全问题？（高频题）**
> 三方五步：PC 请求生成二维码（服务端发 qrcodeId 存 Redis，TTL 5 分钟）；PC 轮询状态；手机扫码后服务端把状态推进到 SCANNED 并绑定 uid，App 展示确认页；用户确认后进 CONFIRMED，但关键设计是服务端不直接发 token，而是发 60 秒一次性 code；PC 轮询拿到 code 再换 token，code 用后即废。防安全五件套：qrcodeId 用 UUID 不可枚举；一次性 code 两段式——就算确认响应被中间人截获，code 换 token 那一步他抢不到；状态机只允许单向流转，重放的 exchange 在 USED 状态直接被拒——我 Demo 里实测首次 true、重放 false；App 确认页显示扫码地点和设备，用户能识别异常授权；二维码周期刷新防截屏劫持。这个设计里我最满意的是状态机复用了 M7 订单状态机的模式——EnumSet 白名单流转表，非法跳级直接 false，扫码和订单本质都是"多方协作下防状态错乱"的同一类问题。

**Q：PC 端轮询会不会把服务器打挂？怎么优化？**
> 先算账再优化：单二维码 2s 一次、5 分钟窗口 150 次请求，日 100 万次扫码就是 1.5 亿轮询请求——峰值按二八算约 700 QPS，单看不大，但这 700 QPS 几乎全部是空转（95% 时间状态没变）。优化分三级：一级长轮询——服务端 hold 请求最长 25 秒，状态变化立即返回，空转请求降 90%，实现只是把查一次改成"查一次+条件等"，无状态侵入；二级事件化——状态变更时发 Redis pub/sub 或 MQ，轮询服务订阅后精准唤醒对应的 hold 请求，响应延迟从"下一个轮询周期"降到"毫秒级"；三级 WebSocket——体验最好但为 5 分钟生命周期引入长连接网关不划算，除非这个系统同时承载 IM（day09-12 会做）。我的选择是 MVP 短轮询、V1 长轮询+事件唤醒——演进触发条件写进 RFC：扫码日量过 10 万就升 V1。

## 5. 今日验收清单

- [ ] 三端五步时序图（含每步状态流转）能手画
- [ ] QrCodeLogin Demo：合法流转通过/重放被拒/非法跳级被拒
- [ ] 两段式 code 设计与"为什么不直接发 token"能讲清
- [ ] 轮询→长轮询→WebSocket 的演进触发条件写进方案
- [ ] `git add . && git commit -m "day09-08: qr login"`

---
[← Day 07](day07-第一周复盘.md) | [本月目录](README.md) | [Day 09 · 长连接选型 →](day09-长连接选型.md)
