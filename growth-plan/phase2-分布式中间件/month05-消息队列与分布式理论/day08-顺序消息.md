# Day 08 · 顺序消息：分区有序与两端加锁

> **今日目标**：实现订单"创建→支付→发货"分区顺序消费，压测验证 0 乱序；讲清顺序消息在 Broker 端与消费端的两把锁，以及全局顺序为什么几乎不用。
> **时长**：原理 1.5h / 实战 2.5h / 压测总结 1h
> **今日产出**：OrderProducer/OrderConsumer（可运行）+ 0 乱序压测数据

## 1. 知识地图

```
顺序的三种境界：
  完全乱序：Concurrently 消费，多线程多队列，最快
  分区顺序 Partitioned Order（生产标配）：同一业务键（如订单号）的消息
      → 固定进同一个队列 → 队列内串行消费
      = "局部有序"，如同一订单的 创建→支付→发货 不乱，但不同订单间无所谓
  全局顺序 Global Order：Topic 只有 1 个读写队列 + 1 个消费者线程
      = 吞吐归零。只在 binlog 同步、金融流水等极端场景用

分区顺序的两个关键件：
  ① 生产端：MessageQueueSelector 按业务键选队列
       int q = Math.abs(orderId.hashCode()) % mqs.size();  → send(msg, selector, arg)
  ② 消费端：MessageListenerOrderly
       队列级锁：一个队列同时只被一个线程消费（消费端锁，ProcessQueue 锁）
  ③ Broker 端还有一把：分布式队列锁（保证一个队列不会被组内两个消费者同时持有）
       ——顺序消费下 Rebalance 迁移要等锁释放，这也是顺序消费慢的一部分

两端加锁的代价（面试要点）：
  ① 吞吐：队列内串行 → 并行度=队列数（所以要合理设队列数）
  ② 失败处理：Concurrently 失败可跳过继续下一条；Orderly 消费失败会"原地挂起重试"
      （suspend 当前队列毫秒级重试，默认 Integer.MAX_VALUE 次数）→ 一条毒消息可能堵整队
      → 顺序消费必须配 maxReconsumeTimes + 跳过策略
  ③ Rebalance 更慢：要等队列锁迁移
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| MessageQueueSelector | 队列选择器，按业务键路由到固定队列 |
| MessageListenerOrderly | 顺序消费监听器（队列内单线程） |
| ProcessQueue | 消费端队列快照/缓冲，持有序列锁 |
| Broker Queue Lock | Broker 端分布式队列锁（默认 lock 持有 60s，定期续） |
| Suspend Current Queue | 顺序消费失败时挂起当前队列重试 |
| Sharding Key | 分片键（我们用订单号；类比 M4 分库分表的分片键，同源思想） |

## 3. 动手实操：订单状态机顺序消费

```java
// learning/month05-mq-theory/src/OrderProducer.java（骨架）
// 同一订单的三个事件（CREATE→PAID→SHIPPED）必须按序被消费
String orderId = "ORDER-" + orderIdSeq;                    // 分片键=订单号
Message m = new Message("ORDER_EVENT_TOPIC", tag, orderId,
        (orderId + "|" + action).getBytes(StandardCharsets.UTF_8));
producer.send(m, (mqs, msg, arg) -> {                      // 队列选择器
    String oid = (String) arg;
    int idx = Math.abs(oid.hashCode()) % mqs.size();       // 同一订单 → 同一队列
    return mqs.get(idx);
}, orderId);

// learning/month05-mq-theory/src/OrderConsumer.java（骨架）
consumer.registerMessageListener((MessageListenerOrderly) (msgs, ctx) -> {
    for (MessageExt m : msgs) {                            // 队列内天然串行
        // 打印：orderId + action + 时间戳；消费逻辑校验状态机：
        // CREATE→(允许)PAID→(允许)SHIPPED；用一张 order_state 表记录当前状态
        // UPDATE order_state SET state=? WHERE order_no=? AND state=?  （乐观校验）
        // affected=0 → 乱序发生了 → 记录乱序日志（压测应 0 条）
    }
    return ConsumeOrderlyStatus.SUCCESS;
});
```

```powershell
# 压测 0 乱序：
# ① 生产端：1000 个订单 × 3 个事件，交错发送（多线程同时发 CREATE/PAID/SHIPPED）
# ② 消费端：Orderly 消费 + 状态机校验，乱序计数器
# ③ 对照组：换成 Concurrently 消费再跑一遍 → 观察乱序率（通常 >30%）
# ④ 对照组 2：消费端故意 sleep(10) + 抛异常一次 → 观察 Orderly 挂起重试行为
# 预期：Orderly 乱序 0 / Concurrently 乱序数百 / 记录两组吞吐对比
# （常规结论：Orderly 吞吐约为 Concurrently 的 1/3~1/2，取决于队列数与单条耗时）
```

## 4. 面试连接

**Q：怎么保证消息顺序？先反问再作答。**
> 先反问一句"是全局有序还是局部有序？"——这一问直接区分菜鸟和老手。答：99% 的业务要的是局部（分区）有序：生产端 MessageQueueSelector 按订单号哈希到固定队列，消费端 Orderly 监听器保证队列内串行，Broker 端还有队列锁保证迁移安全。全局顺序把 Topic 限成 1 个队列，吞吐归零，基本只在 binlog 场景用。最后补代价：毒消息会挂起整队，必须配最大重试次数与告警——"知道代价"是顺序消息话题的加分收尾。

**Q：顺序消息怎么和你的分库分表呼应？**
> 同一个思想：按分片键路由。M4 里 userId 基因决定库表，这里 orderId 哈希决定队列——都是"同一实体的数据落同一物理单元，单元内串行/单点，跨单元并行"。所以规则一致性很重要：分片键和顺序键要是同一个业务键，否则同一订单跨库又跨队列，双重乱序。

## 5. 今日验收清单

- [ ] 顺序三种境界 + 两端锁手绘图
- [ ] OrderProducer/OrderConsumer 运行通过
- [ ] 压测数据：Orderly 0 乱序 / Concurrently 对照数据
- [ ] 毒消息挂起实验记录（maxReconsumeTimes 效果）
- [ ] "分片键=顺序键"的呼应观点能讲
- [ ] `git add . && git commit -m "day08: ordered message"`

---
[← Day 07](day07-第一周复盘.md) | [本月目录](README.md) | [Day 09 · 延迟消息与时间轮 →](day09-延迟消息与时间轮.md)
