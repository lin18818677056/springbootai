# Day 09 · 延迟消息与时间轮：订单超时自动关单

> **今日目标**：实现秒杀订单 15 分钟未支付自动关单（延迟消息版，替代轮询），讲清 18 个固定 Level 的实现原理与 5.0 Timer Wheel 时间轮，手画时间轮图。
> **时长**：原理 2h / 实战 2h / 时间轮画图 30min
> **今日产出**：DelayCloseDemo（可运行）+ 时间轮手绘图

## 1. 知识地图

```
订单超时关单的四种方案对比（面试经典题）：
  ① 定时轮询 DB：每分钟扫超时订单——延迟不准（最大 1 个周期）、DB 压力大、订单多时慢
  ② JDK DelayQueue：单机内存队列——重启丢、不能水平扩展
  ③ Redis 过期监听：过期事件不保证及时（惰性删除）、丢单风险
  ④ MQ 延迟消息：下单成功发一条"15 分钟后投递"的消息 → 到点投给关单消费者
      准时、不丢（有可靠性保障）、天然分布式——生产首选

RocketMQ 延迟消息两代实现：
  4.x 固定级别 Delay Level（18 档，消息属性 DELAY 属性）：
      1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h
      原理：发消息时改 Topic 为 SCHEDULE_TOPIC_XXXX，queueId=level-1
      → 定时任务（ScheduleMessageService）每个 level 一个线程扫表
      → 到期后恢复原 Topic/queueId 重新写入 CommitLog（被正常消费）
      ——本质是"延迟投递"，不是精确时间点（只能选档位）
  5.x 任意时间 Timer Wheel（TimerMessageStore，enableProperty timerEnable=true）：
      TimerWheel：环形数组（槽 Slot，默认精度 1s，一圈 7天）
      TimerLog：每个定时消息的追加日志（指针串联同槽消息）
      DelayedMessage：重新编码后存 CommitLog（复用存储！）
      到期：滚轮扫描到点槽 → 按链表取出全部消息 → 恢复投递
      支持任意毫秒级延迟 + delete/persist 指令

时间轮 Timer Wheel 思想（手画重点，Kafka/Netty/5.x 都在用）：
  秒针数组 + 指针：每秒走一格，任务挂在 (当前时间+延迟)%圈长 的槽上
  任务延迟 > 一圈：round 计数（多转几圈）或"层级时间轮"（Kafka：月/周/日层层降级）
  O(1) 插入、O(槽内任务数) 触发——对比堆 O(logN)，海量定时任务时碾压
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Delay Level | 固定延迟级别（4.x，1~18 档） |
| SCHEDULE_TOPIC | 延迟消息的中转主题（SCHEDULE_TOPIC_XXXX） |
| Timer Wheel | 时间轮（5.x，环形槽数组） |
| TimerLog | 时间轮的追加日志（同槽任务链表） |
| Precision / Tick | 时间轮精度/每次指针步进（默认 1s） |
| Layered Time Wheel | 层级时间轮（Kafka 方案，大延迟降级到外层轮） |
| Delayed Message | 延迟消息 |

## 3. 动手实操：15 分钟自动关单

```java
// learning/month05-mq-theory/src/DelayCloseProducer.java（骨架）
Message order = new Message("ORDER_CLOSE_TOPIC", "close",
        orderId, (orderId + "|UNPAID").getBytes(StandardCharsets.UTF_8));
// 15 分钟 = level 14（20m）或 level 15 附近；严格 15m 用 5.x 任意延迟：
order.setProperty("TIMER_DELAY_MS", "900000");            // 5.x: setDelayTimeMs(900000)
producer.send(order);                                      // 4.x: setDelayTimeLevel(14, msg)

// DelayCloseConsumer.java（骨架）：消息到达=订单到期
//   ① 幂等：只关"UNPAID 且未超期关闭过"的单（状态机条件 UPDATE）
//   ② 再查一次支付状态（防止"刚好在延迟期内付了"的竞态）：
//      若已支付 → 直接 ACK 忽略（关单是"尽力而为"的补偿，不是必达）
//   ③ 未支付 → UPDATE orders SET status='CLOSED', 回补库存（调 M4 的库存回补逻辑）
```

```powershell
# 实验一（4.x 固定级别）：下单 → 发 level 3（10s）延迟消息 → 观察 10s 后消费者收到
# 实验二（关单链路）：写死延迟 30s 当"15 分钟"用：未支付单被关、已支付单被忽略、
#                     关单后库存回补正确（对账 SQL：库存数+已售+未支付锁定 = 初始库存）
# 实验三（时间轮观察，5.x）：broker.conf 加 timerEnable=true，重发任意延迟消息，
#                     看 store/timerwheel 与 timerlog 目录的生成
# 手画时间轮：圆形数组 8 槽、指针、槽上挂任务链表、round 计数法 vs 层级法
```

## 4. 面试连接

**Q：订单超时自动关单怎么做？**
> 先对比四方案（轮询 DB/内存 DelayQueue/Redis 过期监听/MQ 延迟消息），选 MQ 延迟消息并说理由：准点投递、复用 MQ 可靠性（不丢）、免轮询扫表。实现：下单成功发延迟消息（4.x 18 档选最近档 / 5.x 任意毫秒），到期消费者先查支付状态防竞态，未付才关单并回补库存，全链路幂等。加分细节：关单是补偿语义不是必达语义，允许极少量延迟到达，用对账兜底——区分"核心必达"与"补偿尽力"两种可靠性等级。

**Q：时间轮为什么比优先队列（堆）快？**
> 堆插入/弹出 O(logN)，且海量任务时内存指针开销大；时间轮插入 O(1)（算槽+挂链表），触发时只遍历到点槽，把"全局排序"换成"分桶"。精度换吞吐：精度 1s 意味着 1 秒内任务不保证先后——定时关单场景完全够。Kafka 层级时间轮再解决"长延迟"问题：短延迟在底层轮，到点降级到上层轮，永远 O(1) 插入。Netty HashedWheelTimer、5.x TimerMessageStore 同源。

## 5. 今日验收清单

- [ ] 四种关单方案对比能脱稿
- [ ] 固定 Level 原理（SCHEDULE_TOPIC 中转→到期恢复）讲清
- [ ] 手画时间轮（含 round 与层级两种长延迟处理）
- [ ] 关单链路三实验完成（正常关/已付忽略/库存回补对账）
- [ ] DelayCloseDemo 编译运行通过
- [ ] `git add . && git commit -m "day09: delay message"`

---
[← Day 08](day08-顺序消息.md) | [本月目录](README.md) | [Day 10 · 事务消息源码级 →](day10-事务消息源码级.md)
