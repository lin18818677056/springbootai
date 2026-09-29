# Day 18 · Spark Shuffle：MR 同款问题的聪明解法

> **今日目标**：吃透宽依赖背后的 Shuffle 机制——数据怎么按 key 重新分发（Write/Read 两阶段）、三代演进（Hash→Sort→Bypass/推送式）、reduceByKey 比 groupByKey 聪明在哪（Map 端预聚合，和 M8 计数三层同构）。Shuffle 是 Spark 作业"钱花在哪"的最大头，也是倾斜治理的物理舞台。
> **时长**：两阶段流程 1.5h / 三代演进 1.5h / 预聚合实验 2h
> **今日产出**：SortShuffle 流程图 + reduceByKey vs groupByKey 实验数据 + 《Shuffle 治理卡》

## 1. 知识地图

```
Shuffle 两阶段（宽依赖的物理执行，day16 的"全厂大调货"落地）：
  Shuffle Write（Map 侧）：每个 Task 把自己的输出按"目标分区号"分堆写本地磁盘
    ——为每个下游分区生成一个"数据段"（一个数据文件+一份索引）
  Shuffle Read（Reduce 侧）：下游 Task 按索引把自己的那一段从各上游节点拉来
    ——网络拉取（fetch）+ 合并排序 + 交给算子计算
  代价三件：磁盘 IO（写+读）/ 网络传输 / 序列化反序列化
  ——这就是为什么优化第一原则：能不 Shuffle 就不 Shuffle（广播/分桶/预聚合）

三代演进（每代都在省上一代的浪费）：
  第一代 Hash Shuffle：每个 Map Task 为每个下游分区单独建一个文件
    ——M 个 Map × R 个分区 = M×R 个小文件，万级 Task 直接小文件灾难（M10 小文件教训同款）
  第二代 Sort Shuffle（默认）：每个 Map Task 只写【一个】数据文件+一份索引
    ——内部按分区号排序分段；文件数从 M×R 降到 M
    为什么敢排序？：排序换来"随机写变顺序写"（Kafka day03 的哲学再现！）+合并方便
  第三代① Bypass SortShuffle：分区数少（≤spark.shuffle.sort.bypassMergeThreshold
    默认 200）且不需要 Map 端聚合时——跳过排序，直接按分区写文件再并成一个
    （省掉排序 CPU；条件不满足自动退回标准 SortShuffle）
  第三代② push-based Shuffle（3.2+，K8s 场景）：Map 侧主动推给下游合并，
    减少 Reduce 拉取的随机 IO（了解即可）

Map 端预聚合——reduceByKey vs groupByKey（必考对比，M8 同构）：
  groupByKey：原样把 (word,1) 全部 Shuffle 过去再分组——网络传 100 万条
  reduceByKey：Map 侧先局部求和（hello 在本分区出现 800 次先合成 1 条 (hello,800)）
    ——Shuffle 数据量骤减，Reduce 端轻了
  同构记忆：M8 计数三层（LongAdder 分段加→flush→落库）= Map 预聚合→Shuffle→Reduce
  ——能用 reduceByKey/aggregateByKey 就别用 groupByKey（面试直接给结论）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Shuffle Write / Read | 调货的出货/收货两阶段（按分区分堆→按索引拉取） |
| SortShuffle | 排序洗牌（一个数据文件+索引，顺序写） |
| Bypass | 跳过排序（小分区数时免排序直接分堆） |
| Map-side Combine | Map 端预聚合（出货前先局部打包） |
| shuffle.spill | 溢写（内存装不下落磁盘） |
| shuffle partitions | 收货方分区数（决定下游 Task 数） |

## 3. 动手实操：Shuffle 观察+预聚合对比

```scala
// ===== 实验①：UI 上看 Shuffle 读写量（认识"钱花在哪"的仪表盘）=====
// 跑一条 reduceByKey 链后打开 4040 → Stages → 点 reduceByKey 的 Stage
// 看 Shuffle Read Size / Records 与 Shuffle Write 列——记录数字（后续实验的基线）
// ===== 实验②：reduceByKey vs groupByKey（Shuffle 量对比，核心实验）=====
val raw = spark.sparkContext.textFile("hdfs://hadoop:9000/data/ods/order_detail/dt=2026-09-25")
val pairs = raw.flatMap(_.split(",")).filter(_.nonEmpty).map((_, 1))
// 方案 A：groupByKey（无预聚合）
val g = pairs.groupByKey().mapValues(_.sum); g.count()
// 方案 B：reduceByKey（Map 端预聚合）
val r = pairs.reduceByKey(_ + _); r.count()
// UI 对照两个 job：方案 A 的 Shuffle Write Records ≈ 全部记录数；
// 方案 B 明显更小（相同 key 已局部合并）——截图记录两份 Shuffle 数字
// 总耗时：单机小数据差距不大（重点看 Shuffle 字节数），真实集群耗时差 2~5 倍
// ===== 实验③：Bypass 参数感受（记录验证思路）=====
spark.conf.get("spark.shuffle.sort.bypassMergeThreshold")   // 默认 200
// 分区数 < 200 且无 Map 端聚合（如 groupByKey）→ 走 Bypass（无排序）
// 用 UI 的 Stage 详情看 "aggregated metrics" / 执行时间线差异（教学演示级别即可）
// ===== 实验④：Shuffle 分区数对单 Task 压力的影响（为 day19 埋点）=====
spark.conf.set("spark.sql.shuffle.partitions", 4)
spark.sql("SELECT value, COUNT(*) FROM strings_view GROUP BY value").show()
// 改 4 vs 64 跑同一 SQL：UI 看 Shuffle Read Size 的 max（单 Task 最大拉取量）
// —— 4 分区时单 Task 扛 1/4 数据（内存压力+倾斜温床）；64 分区摊薄但空转多
```

## 4. 面试连接

**Q：讲讲 Spark 的 Shuffle 机制？和 MR 的 Shuffle 有什么区别？（核心机制题）**
> 先讲两阶段：Shuffle Write 阶段，每个上游 Task 把输出按目标分区号分堆写到本地磁盘——为每个下游分区生成数据段并建索引；Shuffle Read 阶段，下游 Task 按索引从各上游节点把自己的数据段拉过来，合并后交给算子。代价是磁盘 IO+网络+序列化三件套，所以优化第一原则是能免则免（广播替代、预聚合减量）。演进讲两代关键变化：早期 Hash Shuffle 每个 Map Task 给每个下游分区单独写一个文件，M×R 个文件在小文件灾难（和我们 M10 治理的 HDFS 小文件同款问题）；现在的 SortShuffle 每个 Map Task 只写一个数据文件加一份索引，内部按分区号排序分段——文件数降到 M。这里有个漂亮的呼应：排序的本质是"把随机写变顺序写"，和 Kafka 顺序写快是同一条哲学。两个补充边界：分区数不超过 bypassMergeThreshold（默认 200）且无 Map 端聚合时走 Bypass 免排序；3.2+ 有 push-based Shuffle 让 Map 侧主动推并合并，改善 Reduce 端随机拉取。和 MR 的区别收束成两点：一是 MR 强制排序（Reduce 端分组依赖排序），Spark 默认只在需要时排序；二是 MR 的 Shuffle 是 Job 间的硬边界（必落 HDFS），Spark 只在 Stage 边界 Shuffle 且中间结果可驻内存——这就是 day12"快 3~10 倍"在 Shuffle 层面的解释。

**Q：reduceByKey 和 groupByKey 有什么区别？为什么推荐前者？（对比送分题）**
> 一句话：reduceByKey 在 Map 端先做局部聚合再 Shuffle，groupByKey 原样全量 Shuffle。数据说话：100 万条 (word,1)，其中 hello 出现 80 万次——groupByKey 会把这 100 万条全部过网络传到 Reduce 端再分组求和；reduceByKey 在每个 Map Task 内部先把相同 key 局部合并（hello 在本分区的几千条先合成一条 (hello, 几千)），Shuffle 传的记录数骤降一个量级，Reduce 端内存压力也同步下降——groupByKey 还有第二个问题：同一 key 的所有值装进一个 Iterable，热点 key 直接撑爆单 Task（倾斜的温床）。所以结论直接给：能用 reduceByKey/aggregateByKey 就永远别用 groupByKey——前者语义上等价（都是"按 key 聚合"），物理上便宜得多。这个思想我有个熟悉的同构：M8 写高并发计数时用的是 LongAdder 分段累加再 flush 落库，Map 端预聚合→Shuffle→Reduce 的形状一模一样——"数据在搬运前先变小"是分布式系统的通用省钱术，也是后面倾斜治理"预聚合兜底"的思想源头（day24）。面试里能把"对比题"答出"同构高度"，比背参数表高一个档次。

## 5. 今日验收清单

- [ ] Shuffle 两阶段流程图+三件代价能白板画
- [ ] 三代演进逻辑（文件数 M×R→M；排序换顺序写）能讲
- [ ] Bypass 触发条件（<200 且无 Map 聚合）能背
- [ ] reduceByKey vs groupByKey 实验完成（Shuffle 记录数对比截图）
- [ ] 与 M8 LongAdder 同构点能展开（数据搬运前先变小）
- [ ] `git add . && git commit -m "day11-18: shuffle"`

---
[← Day 17](day17-持久化与广播.md) | [本月目录](README.md) | [Day 19 · 内存与资源调优 →](day19-内存与资源.md)
