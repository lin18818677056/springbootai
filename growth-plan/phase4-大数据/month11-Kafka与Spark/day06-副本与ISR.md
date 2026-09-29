# Day 06 · 副本与 ISR：每个格口配复印员

> **今日目标**：搞懂 Kafka 的数据安全网——分区副本（Leader/Follower）、ISR 名单（跟得上的复印员名单）、acks 三档（发货员等多久才放心）。这是"M10 的三副本思想 + M8 的可靠性取舍"在 Kafka 里的合体，也是消息"丢不丢"问题的 broker 侧答案。
> **时长**：副本与 ISR 机制 1.5h / acks 与可靠性账 1.5h / 实验 2h
> **今日产出**：acks 三档对比实验记录 + 《acks 选型决策卡》+ ISR 机制白板图

## 1. 知识地图

```
副本模型（Partition-level Replication）——"每个格口配 N 个复印员"
  副本因子 replication-factor=3：每个分区在 3 台 broker 各有一份完整拷贝
  Leader：唯一干活的——生产者写它，消费者读它
  Follower：唯一的活是"抄作业"——不停从 Leader 拉数据保持一致（不对外读！）
  （对比 Redis 主从：从库可读；Kafka 从副本不读——读写都聚在 Leader，
    好处是分区内顺序天然有保障，代价是 Leader 所在 broker 扛全部读写压力）

ISR（In-Sync Replicas）："跟得上的复印员名单"
  Follower 拉取落后超过 replica.lag.time.max.ms（默认 10s）→ 移出 ISR
  追上来 → 移回 ISR；ISR 名单动态变化
  为什么不数"落后多少条"改数"落后多久"？（1.0 版本的重要修正）
    ——高峰期所有 follower 都可能落后几万条但都健康；条数阈值会误杀，
     时间阈值衡量的是"这人还活着且在干活"
  （和 M10 HDFS 的三副本对比：HDFS 副本是"静态复印存档"，
    Kafka 副本是"活的工作组"——ISR 是它的考勤表）

acks 三档——发货员等多久才放心（可靠性与吞吐的天平）：
  acks=0  扔了就走：不等任何确认——快，但网络一抖就丢（日志采集可忍？也不行）
  acks=1  队长收到就行：Leader 落盘即回——平衡；风险：Leader 应答后还没同步
          给 follower 就挂了 → 这条消息丢（选主后新 Leader 没有它）
  acks=all ISR 全员收到才回——最稳；配合 min.insync.replicas>=2 才有意义：
          否则 ISR 缩到只剩 Leader 自己时，all 退化成 1（假高可用）
  —— min.insync.replicas=2 + replication.factor=3：容忍 1 台挂，且不假写

不可不提的取舍题：unclean.leader.election.enable（脏选主）
  ISR 全空（3 台全挂过）时，允许不在 ISR 的旧副本当 Leader 吗？
  = false（默认）：宁可分区不可用，不许丢数据——金融/订单域
  = true：可用性优先，接受数据回退——埋点日志域可考虑
  （C/A 取舍第 N 次出现：M5 RocketMQ 刷盘、M8 容灾演练，全是这一题的变体）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Leader / Follower | 主副（唯一干活的/只抄作业的） |
| ISR (In-Sync Replicas) | 同步名单（跟得上进度的副本考勤表） |
| replica.lag.time.max.ms | 掉队判定（落后超 10 秒移出 ISR） |
| acks | 确认档位（0/1/all：等多久才回"发货成功"） |
| min.insync.replicas | 最小同步数（ISR 少于它就拒写，防假高可用） |
| Unclean Leader Election | 脏选主（名单全空时让不合规副本上位） |
| KRaft / Controller | 元数据仲裁（旧版靠 ZK，新版 Raft 内置；M8 多数派同款） |

## 3. 动手实操：acks 对比与 ISR 观察

```powershell
# ===== 实验①：acks 三档吞吐对比（perf-test 支持 acks 参数）=====
foreach ($a in @('1','all')) {
  docker exec kafka kafka-producer-perf-test.sh --topic perf-p3 `
    --num-records 300000 --record-size 200 --throughput -1 `
    --producer-props bootstrap.servers=localhost:9092 acks=$a linger.ms=100 2>&1 | Select-Object -First 1
  Write-Host "---- acks=$a ----"
}
# 单机下 all 只比 1 慢一点（同一台机器同步零成本）；真实跨机集群差距会拉开
# —— 结论记录：acks 的代价来自"等网络往返"，机器越远越贵
# ===== 实验②：看分区的 Leader 和 ISR 真身 =====
docker exec kafka kafka-topics.sh --describe --topic perf-p3 `
  --bootstrap-server localhost:9092
# 输出：Topic: perf-p3  Partition: 0  Leader: 0  Replicas: 0  Isr: 0
# 单机副本因子 1，Replicas 和 Isr 都只有自己——逻辑完整可见
# （多副本实验记录为 TODO：单机起多 broker 是 M12 前的选修，先掌握机制）
# ===== 实验③：观察 KRaft 元数据（新版的"总调度员"在哪）=====
docker exec kafka kafka-metadata-quorum.sh describe --status `
  --bootstrap-server localhost:9092
# 看到 LeaderId/LeaderEpoch——KRaft 模式下元数据由内置 Raft 仲裁（不再依赖 ZooKeeper）
```

## 4. 面试连接

**Q：Kafka 的 ISR 机制讲一下？为什么用"落后时间"不用"落后条数"？（机制必考）**
> 每个分区一主多备，Leader 干活、Follower 只抄作业不对外读。ISR 是"跟得上的副本名单"：Follower 定期向 Leader 拉数据，落后超过 replica.lag.time.max.ms（默认 10 秒）就移出 ISR，追上再移回——名单动态进出，生产写入时 acks=all 只需要等 ISR 内的副本确认，而不是全部副本。为什么 1.0 之后从"落后条数"改成"落后时间"？因为高峰期所有 Follower 都可能落后几万条——但它们都活着且在追赶，这是健康状态；条数阈值会把健康副本误杀出 ISR，导致 ISR 频繁抖动、甚至 ISR 只剩 Leader，acks=all 形同虚设。时间阈值衡量的是"存活且在工作"，和流量高低解耦。延伸两点加分：①ISR 缩水到小于 min.insync.replicas 时 broker 拒绝写入（NotEnoughReplicas 异常）——这是"宁可不可写，不可假成功"的保护；②这套"动态考勤+多数派"思想在 HDFS JournalNode、Redis 哨兵里都是近亲——M10 学过 JournalNode 的奇数多数派，Kafka 的 KRaft 元数据仲裁也是 Raft 多数派。

**Q：acks 你生产上怎么配？min.insync.replicas 为什么必须一起配？（选型题）**
> 我的决策卡是按数据价值分域。订单/支付类 Topic：acks=all + min.insync.replicas=2 + 副本因子 3 + 生产者开幂等（enable.idempotence=true）——容忍 1 台 broker 挂，且杜绝"ISR 只剩 Leader 时 all 退化成 1"的假高可用；生产端用带回调的异步发送，失败进本地重试+告警。埋点日志类 Topic：acks=1 甚至评估 acks=0——丢一两条日志业务无感，但吞吐和延迟收益明显；不过实践中我建议至少 acks=1，因为 acks=0 连"broker 挂了"都不知道，排障成本比丢的数据还贵。为什么 min.insync.replicas 必须一起配？举个例子说明反面：副本 3、min.insync.replicas 默认是 1——如果两台 Follower 都被踢出 ISR，acks=all 实际上只等 Leader 自己确认，可靠性瞬间退化到 acks=1，而你的监控和 SLA 还认为是"全配置"——这是最隐蔽的假高可用。配上 2 之后，ISR 缩到 1 时 broker 直接拒写，客户端立刻知道异常——宁可短时不可写（触发生产端重试堆积），不给下游"成功"的假象。这和 M8 的"宁可拒绝服务，不可静默出错"是同一条纪律。

## 5. 今日验收清单

- [ ] Leader/Follower/ISR 机制白板图（含"落后时间"修正的原因）
- [ ] acks 三档决策卡成文（分域配置+min.insync.replicas 反面案例）
- [ ] 实验①②③完成（吞吐对比/Leader-ISR 输出/KRaft 状态）
- [ ] "假高可用"陷阱能独立讲清
- [ ] `git add . && git commit -m "day11-06: isr acks"`

---
[← Day 05](day05-Rebalance深度.md) | [本月目录](README.md) | [Day 07 · 第一周复盘 →](day07-第一周复盘.md)
