# Day 18 · 消息最终一致整合：三方案混布与链路分级

> **今日目标**：把 M5 的消息最终一致（本地消息表/事务消息）与本周 AT/TCC 组合成商城完整一致性方案；按链路分级选型；非核心链路（积分/通知）消息化改造。
> **时长**：方案设计 2h / 改造落地 3h
> **今日产出**：《商城事务一致性总方案》设计文档 + 积分链路消息化代码

## 1. 知识地图

```
核心认知：没有"最好的一致性方案"，只有"每条链路最合适的方案"
  ——按两个维度给链路分级：实时性要求（同步看到 vs 可异步）× 一致性强度（强一致 vs 最终一致）

商城下单链路拆解（一个"下单"藏着四种方案）：
  订单创建+券核销（低冲突同步）→ AT：@GlobalTransactional（day15）
  库存扣减（热点资源同步）→ TCC 冻结版（day17）或 M4 Redis 预扣
  积分发放（可异步）→ RocketMQ 事务消息（M5 day10 源码级）/本地消息表（M5 day22）
  短信/推送通知（可丢可延迟）→ 普通消息+消费重试（丢了走对账兜底，day19）

事务消息 vs 本地消息表（M5 两种方案怎么选）：
  本地消息表：业务库插消息记录+定时任务扫表发送——多一张表+扫描任务，但 100% 可控可查
  事务消息（RocketMQ 半消息+回查）：少一张表，但依赖 MQ 的回查逻辑正确实现
  选择：已有 RocketMQ 且业务能实现回查 → 事务消息；消息需要业务审计/重试策略复杂 → 本地消息表
  我们商城：积分用事务消息（链路标准），营销活动通知用本地消息表（要审计+按活动批量重发）

xid 的传递（跨方案联动细节）：
  AT/TCC 靠 Feign 拦截器透传 xid；MQ 链路不同——消息里带业务单号，消费方按单号幂等
  ⚠ 千万别在 MQ 消费里再开 AT 全局事务（消息消费不在 xid 上下文，注解形同虚设）
  ——分层纪律：同步链路归 Seata 管，异步链路归消息+幂等+对账管，两套体系不混用

积分链路改造（今日动手）：
  下单成功（AT 全局事务内）→ 发事务消息"order.paid"（半消息）
  → 全局事务提交 → 消息 commit → marketing 服务消费 → 加积分（幂等表防重复消费）
  全局事务回滚 → 半消息删除 → 积分不会加——"订单没成积分不加"最终一致达成
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Link Classification | 链路分级（实时性×一致性矩阵） |
| Mixed Strategy | 混布策略（多方案组合） |
| Transactional Message | 事务消息（半消息+回查） |
| Local Message Table | 本地消息表 |
| Eventual Consistency | 最终一致性 |
| Consumer Idempotency | 消费幂等（按业务单号） |
| Outbox Pattern | 发件箱模式（本地消息表的学术名） |
| Compensation Chain | 补偿链（SAGA 思想在消息链路的应用） |

## 3. 动手实操：积分链路事务消息化

```java
// mall-order：下单事务里发事务消息（半消息）
@GlobalTransactional(rollbackFor = Exception.class)
public Long createOrder(CreateOrderRequest req) {
    orderMapper.insert(order);
    productClient.deductStock(...);                     // TCC 冻结（day17）
    // 事务消息：先发半消息，本地事务提交后 MQ 才投递
    Message msg = new Message("ORDER_PAID_TOPIC", JSON.toJSONString(
            new OrderPaidEvent(order.getId(), req.getUserId(), order.getAmount())));
    TransactionSendResult r = producer.sendMessageInTransaction(msg, order.getId());
    if (SendStatus.SEND_OK != r.getSendStatus()) throw new BizException(5003, "消息发送失败");
    return order.getId();
}
// 回查监听器（M5 day10 的原理落地）：查本地事务状态决定 commit/rollback
@Component
@RocketMQTransactionListener
public class OrderTxListener implements RocketMQLocalTransactionListener {
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        return RocketMQLocalTransactionState.COMMIT;    // 本地事务已在 @GlobalTransactional 里
    }
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        Long orderId = parse(msg);                       // 回查：订单存在且已支付→COMMIT
        return orderMapper.existsAndPaid(orderId) ? COMMIT : ROLLBACK;
    }
}

// mall-marketing：消费方（幂等消费）
@RocketMQMessageListener(topic = "ORDER_PAID_TOPIC", consumerGroup = "marketing-group")
public class OrderPaidConsumer implements RocketMQListener<OrderPaidEvent> {
    public void onMessage(OrderPaidEvent event) {
        if (!idempotentTable.tryInsert("add_points", event.getOrderId())) return; // 重复消费直接丢
        pointService.addPoints(event.getUserId(), calc(event.getAmount()));
    }
}
```

```powershell
# 验证实验：
# ① 正常下单：订单+扣库存+几秒后积分到账（看 marketing 日志消费记录）
# ② 下单异常（人为让 deductStock 抛异常）：订单回滚+半消息删除+积分不加——
#     全链路一致性证据链（三库数据截图）
# ③ 消费幂等：手动重发同一条消息 → 积分只加一次（幂等表命中）
# ④ 回查验证：发消息后 kill order 服务再启动 → 观察 MQ 回查日志 → 按 DB 状态 commit
# 架构图收尾：《商城事务一致性总方案》一页纸——四条链路四种方案+每条的兜底策略
```

## 4. 面试连接

**Q：你们系统的一致性方案是怎么设计的？**
> 按"链路分级选型"讲：同步强一致链路用 Seata——低冲突的订单+券核销走 AT（day16 压测结论：AT 怕热点行），热点库存走 TCC 冻结或 Redis 预扣；异步最终一致链路用事务消息——积分发放订单成功后半消息转正、消费侧幂等表防重；可容忍通知类用普通消息+对账兜底。强调混布理由："一致性方案的选型本质是给每条链路定'不一致的时间窗口容忍度'——积分晚 3 秒到没人体感，库存负了就是资损，所以库存要 TCC/Redis 原子扣，积分用消息就够。一把梭全用 AT 或全用消息，要么性能崩要么一致性崩。"（把 M5 六方案决策树+本周实测数据全串起来）

**Q：事务消息和本地消息表怎么选？**
> 都是"先保证本地事务成功再让消息可靠出去"。本地消息表：业务事务里插消息记录，定时任务扫表发送+失败重发——多一张表和扫描任务，但消息状态完全在自己库里，可审计可批量重发。事务消息：RocketMQ 半消息+回查机制——少维护一张表，但回查逻辑要自己写对（查订单状态），且强绑 RocketMQ。我们的实践："积分这类标准链路用事务消息；营销通知需要按活动批量补发+审计，用本地消息表——消息即数据，要审计就落库。"（M5 day22 已本地消息表实战过，这里做架构级收口）

## 5. 今日验收清单

- [ ] 一致性总方案一页纸完成（四链路四方案+兜底）
- [ ] 积分链路事务消息化跑通（三库一致截图）
- [ ] 异常场景：订单回滚积分不加（证据链）
- [ ] 消费幂等 + MQ 回查实验通过
- [ ] 事务消息 vs 本地消息表选型能讲
- [ ] `git add . && git commit -m "day18: eventual consistency"`

---
[← Day 17](day17-TCC框架级实战.md) | [本月目录](README.md) | [Day 19 · 对账体系与资损防控 →](day19-对账体系与资损防控.md)
