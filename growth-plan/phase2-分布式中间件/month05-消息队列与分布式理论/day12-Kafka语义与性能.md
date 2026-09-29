# Day 12 · Kafka 语义与性能：acks、幂等生产者与精确一次

> **今日目标**：讲清 acks 三档与"可靠性-性能"档位，理解幂等生产者（PID+序列号）与 Kafka 事务如何拼出 Exactly-Once，三档 acks 压测拿实测数据。
> **时长**：原理 2h / 压测 2h / 总结 1h
> **今日产出**：acks 三档压测报告 + Exactly-Once 全景图

## 1. 知识地图

```
生产端可靠性三档（acks）：
  acks=0：发完即忘。最快（不等确认），Broker 挂了就丢——日志埋点可容忍
  acks=1：Leader 写入即确认。Leader 落盘后、Follower 同步前挂 → 丢；吞吐中等
  acks=all(-1)：等 ISR 全部写入。配合 min.insync.replicas=2 + RF=3 是"不丢"配置
      ⚠ all 只等"当前 ISR"——若 ISR 缩到 1 个就等于 acks=1！
      所以不丢三件套：acks=all + min.insync.replicas≥2 + unclean=false（昨天讲过）

幂等生产者 Idempotent Producer（enable.idempotence=true）：
  问题：acks=all 下客户端超时重试 → Broker 存了两条（写成功但 ACK 丢了）
  解法：每个 Producer 分配 PID（Producer ID），每条消息带 (PID, Partition, SeqNum)
       Broker 端缓存每个 (PID,Partition) 最近 5 批的序列号：
       Seq 连续 → 正常写入；Seq 重复 → 拒绝（去重）；Seq 跳跃 → OutOfOrderSequence 异常
  限界：只保证"单 Producer、单 Partition、单会话"的幂等——跨会话（重启 PID 变）与跨分区不行

事务 Exactly-Once（transactional.id）：
  三件事拼图：
    ① 幂等生产者（单分区去重）
    ② 跨分区/跨 Topic 原子写（事务标记：begin/commit/abort，写入控制消息 Control Batch）
    ③ 消费端 read_committed 模式（只读已提交，LSO 隔离）
  完整公式：Exactly-Once = 幂等生产 + Kafka 事务 + 消费端位移提交纳入同一事务 + read_committed
  ⚠ 端到端"真精确一次"还差一环：消费→业务处理→再生产，业务处理本身的幂等仍要自己做
     （Kafka 只保证"消费-位移-生产"三件事原子，不能保证你的 DB 操作原子！）

消费端 Offset 提交：
  自动提交 enable.auto.commit=true（每 5s）：可能重复消费（先处理后被提交前崩）
  手动 commitSync/commitAsync：先处理后提交（At-Least-Once）或提交后处理（At-Most-Once 丢数据）
  生产标配：手动提交 + 业务幂等（和 RocketMQ day06 完全同构——规律跨 MQ 通用）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| acks | 确认级别（0/1/all，可靠性与性能的旋钮） |
| min.insync.replicas | ISR 最少副本数（配合 acks=all 的"不丢"底线） |
| PID (Producer ID) | 生产者 ID（幂等的会话级标识） |
| Sequence Number | 序列号（Broker 端单分区去重依据） |
| Transactional ID | 事务 ID（跨会话，恢复挂起事务/防僵尸 Producer） |
| Control Message | 控制消息（事务 COMMIT/ABORT 标记） |
| LSO (Last Stable Offset) | 最后稳定位移（read_committed 只读到这） |
| Zombie Fence | 僵尸隔离（新 epoch 的 Producer 让旧实例的写失效） |
| Read Committed | 已提交读（消费者隔离级别） |

## 3. 动手实操：三档 acks 压测

```powershell
# 用 kafka-producer-perf-test 自带工具（不需要写代码）：
docker exec -it kafka kafka-producer-perf-test.sh `
  --topic bench-acks0 --num-records 500000 --record-size 1024 `
  --throughput -1 --producer-props bootstrap.servers=localhost:9092 acks=0
# 同样命令分别跑 acks=1、acks=all(加 --producer-props ... acks=all min.insync.replicas=1 [单机降级])
# 记录三项：avg throughput (MB/s)、avg latency (ms)、p99 latency
# 参考量级（单机学习环境，趋势对就行）：0 > 1 > all，差距随副本/持久化配置放大
# 把三组数据做成表格写进实验报告 —— 面试里"我有实测数据"比"我知道概念"值钱 10 倍

# 幂等验证实验（可选）：
# producer 打开 enable.idempotence=true，用代理脚本丢弃"ACK 响应"模拟网络异常
# 观察重试后 Broker 消息总数：开幂等 = 预期条数；关幂等 = 出现重复
# （简单版：直接在实验报告里画图讲解 PID/Seq 去重时序，注明"纸面推演"）

# 事务小实验：kafka-console-producer 不支持事务，用秒杀工程加个 Java 用例
#   initTransactions/beginTransaction/send/commitTransaction，消费端 isolation.level=read_committed
#   故意 abort 一次 → 验证消费端读不到 abort 的事务消息
```

## 4. 面试连接

**Q：Kafka 怎么实现 Exactly-Once？真的精确一次吗？**
> 分两段答"框架内"与"端到端"。框架内：幂等生产者（PID+Seq 单分区去重）解决重试重复，事务（transactional.id + 控制消息 + LSO）解决跨分区原子与消费端隔离，消费位移提交纳入事务 → 消费-处理-生产-位移在一个事务里，read_committed 保证不读未提交。端到端：业务侧 DB 写入不受 Kafka 事务保护，仍需业务幂等或事务性输出（如 Flink 的两阶段提交 Sink）。结论："Kafka 的 Exactly-Once 是流处理框架边界内的精确一次，端到端精确一次=框架+业务幂等共同构成"——这个边界感就是 P7。

**Q：你的项目里 Kafka 配置怎么定？**
> 档位化回答：埋点日志 acks=0/1+批量拉满（要吞吐不要强可靠）；订单类 acks=all + min.insync.replicas=2 + RF=3 + unclean=false（不丢三件套）+ 幂等生产者防重试重复；消费端手动提交+幂等消费（规律同 RocketMQ）。收尾："这些档位是我压测出来的——acks 三档吞吐差 X%，具体数据在我报告里。"（又见"数据说话"）

## 5. 今日验收清单

- [ ] acks 三档 + 不丢三件套手绘图
- [ ] PID/Seq 幂等去重时序图（含三种 Seq 情况）
- [ ] Exactly-Once 拼图全景图（含 LSO/read_committed）
- [ ] acks 三档压测数据表完成
- [ ] "边界感"观点（框架内 vs 端到端）能讲清
- [ ] `git add . && git commit -m "day12: kafka semantics"`

---
[← Day 11](day11-Kafka架构与副本同步.md) | [本月目录](README.md) | [Day 13 · 消息积压治理 →](day13-消息积压治理.md)
