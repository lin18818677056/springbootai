# Day 19 · 退款与异步化：本地消息表 / 超时关单

> **今日目标**：退款链路设计与异步化——本地消息表 vs 事务消息选型（兑现 M5 伏笔）、超时关单（延迟消息）、可退额度模型；产出退款+关单链路代码。
> **时长**：退款链路 1.5h / 异步化选型与实现 2h / 关单方案 0.5h
> **今日产出**：RefundService 代码 + 《事务消息 vs 本地消息表选型表》+ 超时关单方案

## 1. 知识地图

```
退款的业务规则（比支付多一层"额度"约束）：
  可退额度 = 支付金额 − 已退金额（累计校验，day18 资损第 4 条的落地）
  部分退款：多次退、每次校验累计不超支付金额——refund_no 唯一防重退
  退款也走渠道：调渠道退款接口 → 渠道异步退款回调（同支付回调的三防：验签/幂等/金额核对）
  退款状态机：REFUNDING → SUCCESS / FAILED（失败可重试，走人工）

退款的核心难题：跨系统一致性（本地退款单 vs 渠道退款 vs 商城订单回滚）：
  方案 A 本地消息表（Local Message Table）：
    业务操作（创建退款单）与消息记录同库同事务：INSERT refund + INSERT msg_outbox 原子提交
    → 后台任务扫 msg_outbox 未发送记录 → 调渠道退款 → 成功后标记已发送
    → 渠道回调确认 → 更新终态 → 发 MQ 通知商城
    ✓ 100% 可靠（消息不丢）/ ✓ 无中间件强依赖 / ✗ 业务库多一张表+扫描任务
  方案 B 事务消息（RocketMQ 半消息）：
    发 half message → 执行本地事务 → commit/rollback → 消息投递消费者
    ✓ 业务库无侵入 / ✓ 事务回查机制兜底 / ✗ 依赖特定 MQ（换 MQ 要改造）
  选型对比表（M5 伏笔兑现——当时商城秒杀用了事务消息，支付域重新决策）：
  | 维度 | 本地消息表 | 事务消息 |
  |------|-----------|---------|
  | 可靠性 | 高（同事务落库） | 高（半消息+回查） |
  | 侵入性 | 业务库加表 | 无 |
  | 中间件耦合 | 低（任何 MQ） | 高（绑定 RocketMQ） |
  | 运维复杂度 | 扫表任务自维护 | 依赖 MQ 稳定性 |
  | 团队现状 | - | M5-M8 已深度使用 ✓ |
  决策：支付域用事务消息（团队已有运维经验+回查机制成熟）；跨公司系统用本地消息表
  （不强求对方 MQ 类型）——"同一问题不同约束下不同解"，这是选型的成熟标志

超时关单（订单/支付单的"未支付 24h 自动取消"）：
  三方案：①定时扫表（简单但延迟高、全表扫描压力大）②JDK DelayQueue（内存态，重启丢失）
  ③RocketMQ 延迟消息（延迟精度 18 个等级/或 5.x 任意延迟）✓
  链路：创建支付单 → 发 24h 延迟消息（key=payNo）→ 24h 后消费者检查：
    status 仍=PAYING → 关单（CLOSED）+ 释放库存（调商城）
    status=SUCCESS → 忽略（已支付，消息自然作废——消费端幂等判状态）
  ——延迟消息的幂等消费：检查-再动作两段式，消息到时状态可能已变，一切以 DB 现状为准

与商城的衔接（M8 day25 伏笔兑现——"天然 TCC"的最后一环）：
  秒杀链路 Redis 预扣+DB 条件更新本质是 Try/Confirm，缺 Cancel 兜底——超时关单就是 Cancel：
  支付超时关单 → 发"释放库存"消息 → 商城回补库存（Redis INCR + DB 条件更新）
  ——至此 mall-seckill 的资源预留闭环：预留（Try）→ 支付成功（Confirm）→ 超时关单（Cancel）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Local Message Table | 本地消息表（业务与消息同事务） |
| Transactional Message | 事务消息（半消息+回查） |
| Delayed Message | 延迟消息（超时关单载体） |
| Refundable Amount | 可退额度（支付额−已退额） |
| Order Timeout Close | 超时关单（资源释放兜底） |
| Two-phase Close | 两段关单（查状态→再动作） |

## 3. 动手实操：退款与超时关单

```java
// learning/month09-system-design/src/RefundService.java（可退额度+延迟消息关单语义）
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class RefundService {
    record PayOrder(String payNo, long paidCents, AtomicLong refundedCents, String status) {}
    static final Map<String, PayOrder> orders = new ConcurrentHashMap<>();
    record DelayMsg(String payNo, long dueMs) {}
    static final List<DelayMsg> delayedQueue = new CopyOnWriteArrayList<>();  // 模拟 RocketMQ 延迟队列

    /** 创建支付单：同时投递 24h 延迟关单消息（demo 缩短为 2s） */
    public static void createPay(String payNo, long amountCents) {
        orders.put(payNo, new PayOrder(payNo, amountCents, new AtomicLong(0), "PAYING"));
        delayedQueue.add(new DelayMsg(payNo, System.currentTimeMillis() + 2000));
        System.out.println("[create] " + payNo + " PAYING → 投递延迟关单消息(2s)");
    }
    /** 部分退款：可退额度校验（累计退款≤支付金额——day18 资损第 4 条） */
    public static synchronized boolean refund(String payNo, long refundCents, String refundNo) {
        var o = orders.get(payNo);
        if (o == null || !"SUCCESS".equals(o.status())) { System.out.println("  [reject] 单不存在或未支付"); return false; }
        long refunded = o.refundedCents().get();
        if (refunded + refundCents > o.paidCents()) {                     // 可退额度原子校验
            System.out.println("  [reject] 超可退额度: 已退 " + refunded + " + 本次 " + refundCents + " > " + o.paidCents());
            return false;
        }
        o.refundedCents().addAndGet(refundCents);
        System.out.println("  [ok] 退款 " + refundNo + ": " + refundCents + " 分 → 累计已退 " + o.refundedCents().get());
        return true;
    }
    /** 延迟消息消费者：两段式关单（先查 DB 现状再动作——幂等的关键） */
    public static void consumeDelay(DelayMsg m) {
        var o = orders.get(m.payNo());
        if (o == null) return;
        if ("PAYING".equals(o.status())) {                                // 状态仍是待支付才关
            orders.put(m.payNo(), new PayOrder(m.payNo(), o.paidCents(), o.refundedCents(), "CLOSED"));
            System.out.println("[close] " + m.payNo() + " 超时未支付 → 关单 + 释放库存（TCC 的 Cancel）");
        } else {
            System.out.println("[skip] " + m.payNo() + " 已是 " + o.status() + " → 关单消息作废");
        }
    }
    public static void main(String[] a) throws Exception {
        createPay("P1", 10_000);                                          // 将被关单
        createPay("P2", 20_000);
        // P2 在关单前支付成功（模拟回调）
        orders.put("P2", new PayOrder("P2", 20_000, new AtomicLong(0), "SUCCESS"));
        System.out.println("[pay] P2 支付成功（回调更新）");
        // P2 部分退款两次（第二次超额度被拒）
        refund("P2", 5_000, "R1");
        refund("P2", 16_000, "R2");                                       // 5000+16000 > 20000 → 拒
        refund("P2", 15_000, "R3");                                       // 累计 20000 = 支付额 ✓
        // 延迟消息到期（2s 后）
        Thread.sleep(2100);
        delayedQueue.forEach(RefundService::consumeDelay);
        // 输出：P1 超时关单（释放库存）/ P2 skip（已支付）/ 退款 R2 被可退额度拦截
    }
}
```

```text
《事务消息 vs 本地消息表选型表》（RFC《支付系统设计》§异步化，M5 伏笔兑现）：
决策句式："支付域选事务消息——团队 M5 起运维 RocketMQ 事务消息两年、回查机制生产验证过；
跨公司集成选本地消息表——不强求对方 MQ 同构。同一问题不同约束选不同解，
选型依据是团队现状与运维成本，不是技术先进度。"
```

```powershell
cd D:\mywork\springbootai\learning\month09-system-design\src
javac -encoding UTF-8 -d ../out RefundService.java ; java -cp ../out RefundService
# 验证：可退额度拦截超退、延迟消息两段式关单（P1 关/P2 skip）
cd D:\mywork\springbootai ; git add . ; git commit -m "day09-19: refund async"
```

## 4. 面试连接

**Q：退款怎么设计？怎么防止重复退款或超额退款？（退款专项）**
> 三个机制。额度模型：每笔支付单维护已退金额，可退额度=支付金额减已退金额，每次退款原子校验累计不超支付金额——部分退款、多次退款都在这个额度框架内，我的测试里 200 元支付退 50 后再申请 160 被拒、改 150 成功，累计正好 200。防重：refund_no 唯一约束加幂等返回首次退款单，重试安全。链路：创建退款单和调渠道退款之间是跨系统操作，用事务消息保证"退款单已建则渠道调用必达"，渠道退款回调走和支付回调同款三防（验签、幂等应答、金额核对），失败可重试走人工。还有一个容易被忽略的点：退款和支付回调可能并发——退款时支付回调还没到，可退额度校验要基于"已支付"状态，所以退款前置检查支付单必须 SUCCESS，状态机天然挡住了"未支付先退款"的非法路径。资金域的每个接口都要过一遍"并发下最坏会怎样"，这是 day18 资损推演在退款站的落地。

**Q：订单 30 分钟未支付自动取消，怎么实现？（超时关单标准题）**
> 三方案对比：定时扫表实现最简单但延迟高且大表扫描伤库；JDK DelayQueue 精度高但纯内存态，重启全丢，只能单机玩具；选 RocketMQ 延迟消息——创建订单时发一条延迟消息，到期消费者检查订单状态，仍待支付就关单加释放库存，已支付就忽略。两个关键细节：一是两段式消费——消息到期时订单状态可能已经变了（刚好在到期前一秒支付成功），所以必须"先查 DB 现状再动作"，消息本身不是决策依据只是时间触发器，这就是消费端幂等；二是关单动作要发"释放库存"事件——这就接上了 M8 秒杀的闭环：Redis 预扣加 DB 条件更新是 Try 和 Confirm，超时关单触发的库存回补就是 Cancel，至此秒杀的资源预留才是完整的 TCC 语义——虽然我们从来没有显式写过 TCC 框架代码，三个阶段是由支付状态机自然串起来的。延迟消息还有个工程点：RocketMQ 4.x 只有 18 个固定等级，任意延迟要 5.x 或延迟队列服务，选型时要确认业务需要的延迟粒度。

## 5. 今日验收清单

- [ ] 可退额度模型（累计校验）+ 超退拦截验证通过
- [ ] 本地消息表 vs 事务消息选型表（含团队现状维度）入库
- [ ] RefundService 运行：P1 关单/P2 skip/超退被拒
- [ ] 超时关单 = TCC 的 Cancel（与 M8 秒杀闭环）能讲
- [ ] `git add . && git commit -m "day09-19: refund"`

---
[← Day 18](day18-资金安全与资损防控.md) | [本月目录](README.md) | [Day 20 · 支付 RFC 定稿 →](day20-支付RFC定稿.md)
