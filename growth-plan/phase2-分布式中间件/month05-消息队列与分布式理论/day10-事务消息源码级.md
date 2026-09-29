# Day 10 · 事务消息源码级：半消息、回查与 M4 伏笔兑现

> **今日目标**：兑现 M4 day27 埋下的三个"不明觉厉"：半消息存在哪？回查谁触发？为什么"先落库再发消息"不行？从源码层面讲透事务消息，断电模拟验证回查兜底。
> **时长**：源码 2.5h / 断电实验 1.5h / 面试表达 1h
> **今日产出**：三个伏笔的源码级答案 + 断电回查实验报告

## 1. 知识地图：先还三个伏笔（M4 day27 的债）

```
伏笔①：半消息（Half Message）存在哪？普通消息看不到它啊？
  答案：就在 CommitLog 里！只是 Topic 被偷梁换柱成 RMQ_SYS_TRANS_HALF_TOPIC，
       queueId=0（系统内部主题，消费者订阅不到 = 对业务"隐形"）。
       事务结束后（commit/rollback）再被搬回原 Topic 或加 RMQ_SYS_TRANS_OP_HALF_TOPIC 标记删除。
       ——没有专门的"半消息存储"，一个"隐藏 Topic"就实现了，这就是工程上的小聪明。

伏笔②：回查（Check Back）是谁触发的？Broker 重启后怎么知道哪些半消息悬着？
  答案：Broker 端有个定时任务（TransactionMessageCheckService）：
       对比 HALF 与 OP 两个系统主题，不在 OP 里的半消息 = 悬而未决 → 回查 Producer
       （checkImmunityTime 默认 6s 后开始，间隔 60s，默认最多 15 次回查）
       消费者永不感知半消息。Producer 持有 TransactionListener 引用，
       Broker 的回查请求通过网络回到 Producer，调用 executeLocalTransactionState 查本地事务状态。

伏笔③：为什么"先落库再发消息"不行？"先发消息再落库"也不行？
  先落库再发消息：库提交成功、发消息失败（网络抖动）→ 库有消息无 → 不一致
  先发消息再落库：消息发出去、落库失败 → 消费者处理了不存在的业务 → 更糟
  事务消息的解法：把"发消息"拆成两阶段——
       半消息（此时消费者不可见，发了等于没发）→ 执行本地事务 → commit（消息可见）/rollback
       两阶段之间任何一步失败 → 回查兜底 → 决定 commit/rollback
  理论名字：这本质是"两阶段提交 2PC + 消息中间件作为协调者"的变体（day20 决策树里的
       "可靠消息最终一致"方案，理论根基 day15-16 补）。
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Half Message | 半消息：已存储但对消费者不可见的预备消息 |
| RMQ_SYS_TRANS_HALF_TOPIC | 半消息隐藏主题 |
| RMQ_SYS_TRANS_OP_HALF_TOPIC | 已处理半消息的标记主题 |
| Execute Local Transaction | 生产者执行的本地事务（与半消息同生共死） |
| Check Local Transaction | 回查接口：Broker 问 Producer "那笔事务到底成没成" |
| Transaction Listener | TransactionListener 两方法（上面两行） |
| Check Immunity Time | 半消息免检时间（默认 6s 后才回查） |
| End Transaction | 终结操作：COMMIT_MESSAGE / ROLLBACK_MESSAGE / NOT_EXISTS |

## 3. 动手实操：断电模拟验证回查兜底

```java
// learning/month05-mq-theory/src/TxProducerDemo.java（骨架，M4 秒杀代码的"理论注释版"）
TransactionMQProducer producer = new TransactionMQProducer("tx_demo_g");
producer.setTransactionListener(new TransactionListener() {
    @Override public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        // 本地事务：扣库存（stock 表 UPDATE ... WHERE stock>0）
        // 成功 → COMMIT_MESSAGE；失败 → ROLLBACK_MESSAGE；UNKNOWN → 等 Broker 回查
        // 关键：本地事务里同时写一张 tx_log 表（bizId+state），回查时查它！
    }
    @Override public LocalTransactionState checkLocalTransaction(MessageExt msg) {
        // 回查实现：SELECT state FROM tx_log WHERE bizId=? 
        // COMMIT → COMMIT_MESSAGE / ROLLBACK → ROLLBACK_MESSAGE / 查不到 → ROLLBACK
        // ⚠ 回查里绝不能再执行业务，只能"查状态"——业务要么成了要么没成
    }
});
producer.sendMessageInTransaction(msg, null);
```

```powershell
# 断电实验三连（每轮记录时间线）：
# 实验A：executeLocalTransaction 里 Thread.sleep(60_000) 模拟"本地事务执行中进程挂"
#   → 重启 Producer → 6~66s 内 Broker 回查 → checkLocalTransaction 查 tx_log
#   → 库已扣（tx_log=SUCCESS）则 COMMIT，消息投递成功 → 消费者收到
#   → 结论：哪怕进程死亡，回查也能把链路救活
# 实验B：executeLocalTransaction 抛异常（本地事务失败）
#   → ROLLBACK → 半消息进 OP 标记 → 消费者永远收不到 → 库存没扣订单也没有 = 一致
# 实验C：checkLocalTransaction 返回 UNKNOWN 的次数验证
#   → 观察日志回查间隔（6s 起步，15 次上限后进死信处理路径——半消息 Checker 放弃，
#   生产上要给 tx_log 做兜底对账，day26 故障演练里做）
# 全程用 Dashboard 的"消息轨迹/查询"观察半消息从 HALF→OP 的流转
```

## 4. 面试连接

**Q：事务消息的原理？比本地消息表好在哪？**
> 三段式答：①两阶段——发半消息（消费者不可见，存储上是隐藏 Topic）→ 执行本地事务 → commit/rollback；②回查——Broker 定时对比 HALF/OP 系统主题找出悬空半消息，回调 Producer 的查状态接口（所以本地事务必须落一张事务状态表）；③语义——保证"本地事务与消息发送"的原子性，最终一致。对比本地消息表：事务消息把"消息表+扫表补偿"下沉到中间件，业务只实现两个 Listener；但强依赖 RocketMQ、回查接口要求无副作用。本地消息表通用（任何 MQ）但业务要自己建表+扫表——选型到这层就是 P7 表达。

**Q：回查时本地事务"正在进行中"怎么办？**
> 三个层次的答案：①接口设计就支持返回 UNKNOWN（本次不决，等下次回查）；②回查查的是 tx_log 落库状态，事务进行中自然查不到终态 → UNKNOWN；③若回查 15 次都 UNKNOWN（超长事务），半消息被 Broker 丢弃并告警 → 生产上配超长事务告警+人工介入，或者把"执行"与"提交"拆得更细。核心观点：事务消息不承诺"任意长事务都安全"，承诺的是"状态可查+终局可决"。

## 5. 今日验收清单

- [ ] 三个伏笔的源码级答案写成笔记（HALF/OP 主题机制图）
- [ ] TxProducerDemo 运行通过（含 tx_log 状态表）
- [ ] 断电实验 A/B/C 全部完成，时间线记录
- [ ] "事务消息 vs 本地消息表"选型观点能讲 2 分钟
- [ ] Dashboard 观察过半消息流转（HALF→OP）
- [ ] `git add . && git commit -m "day10: transaction message"`

---
[← Day 09](day09-延迟消息与时间轮.md) | [本月目录](README.md) | [Day 11 · Kafka架构与副本同步 →](day11-Kafka架构与副本同步.md)
