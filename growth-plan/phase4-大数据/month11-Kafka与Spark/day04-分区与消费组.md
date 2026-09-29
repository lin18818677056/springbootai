# Day 04 · 分区与消费组：并行天花板与"搬家小队"

> **今日目标**：把 day02 的消费组规则学深——组内怎么分格口、进度条存在哪（`__consumer_offsets` 内部主题）、分区数怎么规划（吞吐倒推公式）。今天之后你能给一个真实业务算分区数。
> **时长**：消费组机制 1.5h / 分区规划方法论 1.5h / 压测实验 2h
> **今日产出**：《分区数规划计算书》+ 压测分区数 vs 吞吐的实测曲线

## 1. 知识地图

```
消费组三条铁律（搬家小队规则）：
  ① 组内独占：一个格口同一时刻只归小队内一个人搬——不然两个人抢一件货
  ② 组间广播：两个小队（不同 group.id）互不相干，各拉各的进度条
     ——同份数据，数仓小队和风控小队各消费一份（day01 "多订阅者"的机制根基）
  ③ 人少货多：消费者数 ≤ 分区数才有活干；消费者 > 分区数，多的人闲着

进度条存在哪？——broker 里有个"内部货架"：__consumer_offsets
  每个消费组的所有 offset 按条目存进去（默认 50 分区）；
  消费者定期提交（enable.auto.commit 默认每 5 秒自动拨进度条）——
  进度条存在 broker 而不是消费者本地，所以消费者挂了换人，
  新人从 broker 读进度条接着干（状态外置！M7 状态机思想同款）
  —— 手动提交的坑留 day08 详讲

分区数规划（吞吐倒推法，面试可白板演算）：
  目标吞吐 T（如 10 万条/s）
  ÷ 单分区实测吞吐 t（压测得，如 1 万条/s，day03 实验②的数字）
  = 最少分区数 10 → 留 50% 余量 → 取 16（整数次幂便于均匀分布）
  再过三道闸：分区上限（单 broker 几千，防文件句柄/选举慢）
            × key 路由（分区只能加不能减——扩容会改变 key→分区的映射）
            × 下游消费能力（分区多了消费端也要跟得上，别只顾上游）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Group Coordinator | 消费组管家（broker 侧负责给小队分格子的人） |
| __consumer_offsets | 进度条仓库（broker 内部主题，存所有组的 offset） |
| Offset Commit | 提交位移（把"我读到哪了"汇报给 broker） |
| Throughput-based Sizing | 吞吐倒推法（用目标吞吐除以单分区吞吐算分区数） |
| Partition Scaling | 分区扩容（只能加不能减） |

## 3. 动手实操：分区数压测曲线

```powershell
# ===== 实验①：不同分区数的吞吐对比（控制变量：只改分区数）=====
# 建 3 分区和 12 分区两个 Topic（其余参数一致）：
docker exec kafka kafka-topics.sh --create --topic perf-p3 `
  --partitions 3 --replication-factor 1 --bootstrap-server localhost:9092
docker exec kafka kafka-topics.sh --create --topic perf-p12 `
  --partitions 12 --replication-factor 1 --bootstrap-server localhost:9092
# 各发 50 万条、不压吞吐（throughput=-1 全速），记录 Records/sec：
foreach ($t in @('perf-p3','perf-p12')) {
  docker exec kafka kafka-producer-perf-test.sh --topic $t `
    --num-records 500000 --record-size 200 --throughput -1 `
    --producer-props bootstrap.servers=localhost:9092 linger.ms=100 2>&1 | Select-Object -First 1
}
# 预期：12 分区比 3 分区吞吐高但不是 4 倍（单机容器里磁盘是共享瓶颈——
# 真实集群多 broker 才能吃满分区并行的红利；这个"不线性"现象本身就是重要认知）

# ===== 实验②：看进度条仓库（__consumer_offsets 真身）=====
docker exec kafka kafka-consumer-groups.sh --list --bootstrap-server localhost:9092
docker exec kafka kafka-console-consumer.sh --topic __consumer_offsets `
  --formatter "kafka.coordinator.group.GroupMetadataManager\$OffsetsMessageFormatter" `
  --from-beginning --max-messages 2 --bootstrap-server localhost:9092 2>/dev/null | head -4
# 能看到 [group, topic, partition]::offset 的条目——进度条真被存成了普通消息

# ===== 实验③：消费者多于分区数（有人闲着）=====
# perf-p3（3 分区）起 4 个消费者同组：kafka-consumer-groups --describe 看分配
# 预期：3 人各有分区、第 4 人 CURRENT-OFFSET 为空——闲着，白吃工资
```

## 4. 面试连接

**Q：给你一个新业务，Topic 分区数怎么定？（工程计算题）**
> 吞吐倒推三步+三道闸。第一步定目标吞吐：问清业务峰值（如埋点 10 万条/秒），别用均值算容量——这是 M8 压测学过的"用 P99 峰值设计，不是均值"。第二步测单分区吞吐：用 kafka-producer-perf-test 拿真实消息大小（我们按 200B）压出单分区 1 万条/s 左右——注意单分区瓶颈常在"分区所在磁盘顺序写+单线程处理"，所以它是有物理上限的。第三步做除法留余量：10 万÷1 万=10 个分区，按峰值余量 1.5 倍取 16。然后过三道闸：①分区只能加不能减（扩容改变 key 路由映射，窗口期乱序），所以初始宁可稍多；②单 broker 分区上限几千（文件句柄/ISR 追赶/Controller 选举都随分区数恶化）；③下游消费力要同步规划——分区给了 16 并行度，消费组也得配够实例数，否则上游扩了下游堵。我们项目埋点 Topic 实际给了 6 分区 2 消费实例，压测单实例 8k/s，总 4.8 万/s，峰值余量充足——数字都是实测的，不是拍脑袋。

**Q：消费组的 offset 存在哪？为什么这么设计？（机制理解题）**
> 存在 broker 集群内部的一个特殊主题 `__consumer_offsets` 里（默认 50 分区），每个消费组在每个分区上的进度是一条普通 Kafka 消息。这个设计有两个精妙处：第一是**状态外置**——进度条存在 broker 而非消费者内存，消费者宕机换人后，新人从 broker 读进度接着干，集群无感知（和我在 M7 做的工作流状态外置、M8 的会话外置是同一个思想：实例可以死，状态不能死）。第二是**用 Kafka 自己存 Kafka 的元数据**——offset 也是消息，就自然继承了分区/副本/持久化全套能力，不用另建存储组件，运维成本为零。要补的细节：提交有自动（enable.auto.commit 每 5 秒）和手动（commitSync/commitAsync）两档，自动提交在"处理到一半宕机"时会重复消费——这就是 day08 三种消费语义的入口，先记住：进度条什么时候拨，决定了你丢消息还是重消息。

## 5. 今日验收清单

- [ ] 消费组三铁律+__consumer_offsets 机制能白板讲
- [ ] 分区规划吞吐倒推法完整演算一遍（写进《分区数规划计算书》）
- [ ] 压测曲线完成（3 vs 12 分区，记录"不线性"现象及原因）
- [ ] 实验②③跑通（进度条真身+第 4 人闲着）
- [ ] "状态外置"与 M7/M8 同构点能讲
- [ ] `git add . && git commit -m "day11-04: group sizing"`

---
[← Day 03](day03-Kafka为什么快.md) | [本月目录](README.md) | [Day 05 · Rebalance 深度 →](day05-Rebalance深度.md)
