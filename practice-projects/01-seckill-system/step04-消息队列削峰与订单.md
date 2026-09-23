# Step 4：消息队列削峰 —— 事务消息、异步落单、消费幂等、超时关单

> **本步目标**：把下单从"同步落库"改造为"事务消息 + 异步消费"，下单 RT 降到 50ms 内；实现三层消费幂等与延迟消息超时关单，保证订单**不丢、不重、不超时泄漏**。

## 前置术语

| 英文 | 中文 |
|------|------|
| Peak Clipping | 削峰 |
| Transactional Message | 事务消息（半消息机制） |
| Consumer Idempotency | 消费幂等 |
| Delay Message / Time Wheel | 延迟消息 / 时间轮 |
| Dead Letter Queue (DLQ) | 死信队列 |
| Message Backlog | 消息积压 |

## 1. 改造前后的链路对比

```
【改造前·同步】 下单 → Redis预扣 → DB扣库存 → 插订单 → 返回    RT ≈ 200ms+（DB 是瓶颈）
【改造后·异步】 下单 → Redis预扣 → 发事务消息 → 返回"排队中"   RT < 50ms
                消费者按 DB 能力匀速消费：DB扣库存 + 插订单
                前端轮询 /seckill/result 获取最终结果
```

## 2. 事务消息发送（保证"预扣成功 ↔ 消息必达"）

半消息机制：先发半消息（Half Message，消费者不可见）→ 执行本地动作 → 提交/回滚 → 失败则 Broker 回查（Check Back）。

```java
@RocketMQTransactionListener
public class SeckillTxListener implements RocketMQLocalTransactionListener {

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        // 本地动作：把"预扣成功"的凭证写入 Redis（幂等凭证），失败返回 ROLLBACK
        try {
            preDeductService.markPreDeducted(msg);     // SETNX 抢占预扣凭证
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            preDeductService.rollback(msg);            // 回滚 Redis 预扣库存
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        // Broker 回查：查凭证是否存在，决定提交/回滚（应对发送后进程崩溃）
        return preDeductService.exists(msg)
                ? RocketMQLocalTransactionState.COMMIT
                : RocketMQLocalTransactionState.ROLLBACK;
    }
}
```

## 3. 消费端：DB 扣减 + 落单（按 DB 能力匀速消费）

```java
@RocketMQMessageListener(topic = "seckill-order-topic",
        consumerGroup = "seckill-order-consumer",
        consumeThreadNumber = 20)            // 压测校准：MySQL 单行更新能力内
public class OrderCreateConsumer implements RocketMQListener<OrderMsg> {

    @Transactional
    public void onMessage(OrderMsg msg) {
        // ===== 第一层幂等：Redis 预判（挡住 99% 重复）=====
        if (!idempotentRedis.tryLock(msg.orderNo(), 60)) return; // 已处理过，直接 ACK

        try {
            // ===== 第二层幂等：订单表唯一索引兜底 =====
            int rows = goodsMapper.deductStock(msg.goodsId());   // UPDATE ... WHERE stock>0
            if (rows == 0) {
                // 理论不可达：Redis/DB 偏差 → 告警 + 记录对账表，人工介入
                reconciliationService.recordMismatch(msg);
                return;
            }
            orderMapper.insert(Order.of(msg));   // order_no 唯一索引，重复插入会抛异常→捕获后忽略

            // ===== 第三层：延迟消息关单（15 分钟未支付自动关闭+回补库存）=====
            producer.syncSendDelay("seckill-close-topic",
                    MessageBuilder.withPayload(new CloseMsg(msg.orderNo(), msg.userId(), msg.goodsId())).build(),
                    15 * 60);                     // RocketMQ 5.x 支持任意延迟秒数
        } finally {
            idempotentRedis.complete(msg.orderNo());
        }
    }
}
```

## 4. 超时关单与库存回补（延迟消息）

```java
@RocketMQMessageListener(topic = "seckill-close-topic", consumerGroup = "seckill-close-consumer")
public class OrderCloseConsumer implements RocketMQListener<CloseMsg> {
    @Transactional
    public void onMessage(CloseMsg msg) {
        // 状态机校验：只有"待支付"才能关（用 UPDATE 条件更新，天然幂等）
        int rows = orderMapper.closeIfUnpaid(msg.orderNo());   // WHERE status=0 → status=2
        if (rows > 0) {
            goodsMapper.restoreStock(msg.goodsId());           // 回补 DB 库存
            redisTemplate.opsForValue().increment(stockKey(msg.goodsId())); // 回补 Redis 预扣
        }
        // 已支付则什么都不做（rows=0），天然幂等
    }
}
```

> 关单与"用户恰好在此刻支付"的并发冲突：支付侧用 `UPDATE ... WHERE status=0` 同样的条件更新抢占状态，两边只有一个能成功——**状态机 + 条件更新 = 并发安全**。

## 5. 可靠性保障全景（面试重点）

| 环节 | 风险 | 对策 |
|------|------|------|
| 生产端 | 消息没发出去 | 事务消息半消息 + 回查；发送失败回滚 Redis 预扣 |
| Broker | 宕机丢消息 | 刷盘 SYNC_FLUSH（关键 Topic）+ 主从同步 SYNC_MASTER |
| 消费端 | 重复投递 | 三层幂等（Redis 预判 + 唯一索引 + 条件更新） |
| 消费端 | 消费失败 | 重试 16 次递增间隔 → 死信队列 → 人工/自动补偿 |
| 积压 | 消费能力不足 | 监控 Lag（积压量）→ 扩消费者/临时转发批量消费 |

## 验收清单

- [ ] 下单接口 RT：压测 P99 < 50ms，响应"排队中"
- [ ] 轮询结果：最终全部拿到 成功/失败 明确状态
- [ ] 幂等测试：手工重发同一条 MQ 消息 10 次，订单只有 1 条
- [ ] 故障演练：杀掉消费者进程 → 重启 → 消息继续消费，无丢失
- [ ] 超时关单：不支付订单 15 分钟后自动关闭，Redis 与 DB 库存双双回补
- [ ] 模拟消费抛异常 → 观察重试与死信

## 常见坑

1. **事务消息回调里做重活**：回查必须轻量（查凭证），别回查里再做业务。
2. **consumeThreadNumber 盲目调大**：消费线程数超过 MySQL 单行锁吞吐只会增加锁等待，压测定参。
3. **幂等锁无过期**：Redis 幂等 key 必须带 TTL，否则异常中断后永久阻塞。
4. **延迟消息滥用于精确调度**：秒级延迟消息适合关单这类弱实时场景，精确调度用时间轮/调度平台。

> 完成后进入 `step05-压测监控与复盘.md`。
