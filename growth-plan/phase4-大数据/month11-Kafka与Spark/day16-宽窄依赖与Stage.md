# Day 16 · 宽窄依赖与 Stage：流水线与分会场

> **今日目标**：学会 Spark 最实用的"读图能力"——看宽窄依赖（决定要不要 Shuffle）、懂 Stage 切分规则（在宽依赖处切刀）、知道 Task 数由什么决定。这是你以后看 Spark UI 排障、做 day24 倾斜治理的全部地基。
> **时长**：宽窄依赖 1.5h / Stage 切分与并行度 1.5h / 画图验证 2h
> **今日产出**：5 算子链 Stage 划分图（100% 准确）+ Spark UI 对照记录

## 1. 知识地图

```
宽窄依赖：父 RDD 的分区"喂"给子 RDD 的方式——决定要不要 Shuffle

窄依赖 Narrow：一对一或多对一——"独家供货"
  每个子分区只依赖父分区中的【固定几个】（通常 1 个）
  算子：map/filter/flatMap/union/sample
  特点：不需要搬数据！父子分区可以在同一节点流水线化执行
  （类比：1 号车间加工完直接传给 2 号车间，货不出厂门）

宽依赖 Wide（Shuffle Dependency）：多对多——"全厂大调货"
  子分区依赖父分区的【全部】分区（按 key 重新洗牌分配）
  算子：groupByKey/reduceByKey/join(非同分区)/distinct/repartition
  特点：必须 Shuffle——所有数据按 key 重新分发，网络+落盘双开销
  （类比：全校按新班级重新分班——每个人都要挪窝，操场集合排队）

Stage 切分规则（白板必考，一句话记住）：
  从最后一个 RDD 往前推，遇到【宽依赖就切一刀】；
  切出来的每一段是一个 Stage，窄依赖链全部合并进同一 Stage（流水线化）
  —— day12 的"牌②DAG 全局优化"落地细节：能拼的拼在一起，不能拼的切会场

Task 数与并行度（排障常问）：
  一个 Stage 的 Task 数 = 该 Stage 末端 RDD 的分区数
  （Spark SQL 场景：spark.sql.shuffle.partitions 默认 200——
    小数据量 200 个分区=200 个空转 Task；大数据量 200 不够=倾斜——day19/24 再调）
  Job（一个 Action 触发一个）> Stage（宽依赖切分）> Task（分区级并行）
  层级记忆：图纸一次开工（Job）→ 按工序分会场（Stage）→ 每会场多个工位（Task）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Narrow Dependency | 窄依赖（独家供货，不搬数据） |
| Wide / Shuffle Dependency | 宽依赖（全厂调货，必须 Shuffle） |
| Stage | 阶段（宽依赖切出来的分会场） |
| Pipeline | 流水线化（同一 Stage 内算子连着算，不落中间盘） |
| Parallelism | 并行度（Task 数=末端分区数） |
| shuffle partitions | Shuffle 后的分区数（SQL 默认 200） |

## 3. 动手实操：画 Stage 图+UI 对照

```scala
// ===== 实验①：5 算子链的 Stage 划分（先笔试后机试）=====
// 链：textFile → flatMap → map → reduceByKey(宽!) → filter → collect
// 笔试画图（先自己画）：
//   [textFile] --窄--> [flatMap] --窄--> [map] ==宽==> [reduceByKey] --窄--> [filter]
//   切刀位置：reduceByKey 处（宽）→ 两个 Stage
//   Stage0 Task 数 = textFile 分区数（HDFS 块数）；Stage1 Task 数 = reduceByKey 分区数
// 机试验证：
val r1 = spark.sparkContext.textFile("hdfs://hadoop:9000/data/ods/order_detail/dt=2026-09-25")
val r2 = r1.flatMap(_.split(",")).map((_, 1))
val r3 = r2.reduceByKey(_ + _).filter(_._2 > 3)
r3.collect()
println(r3.toDebugString)   // 看缩进突变处=Stage 分界（ShuffledRDD 标记）
// ===== 实验②：Spark UI 对照（在 4040 界面看真身）=====
// collect 运行期间开 http://localhost:4040 → Jobs → 该 job 的 DAG Visualization
// 核对：两个 Stage 方块+中间 Shuffle 边（绿色波浪）；点 Stage 看 Task 数
// 记录截图：笔试图 vs UI 真身——不一致的地方回炉
// ===== 实验③：感受 shuffle.partitions 的 200 默认（为 day19 埋点）=====
spark.conf.set("spark.sql.shuffle.partitions", 8)
val df = spark.read.textFile("hdfs://hadoop:9000/data/ods/order_detail/dt=2026-09-25")
df.select(df(0) === null)  // 占位：真实实验用 SQL groupBy：
// spark.sql("SELECT value, COUNT(*) c FROM strings GROUP BY value").show()
// 把 200 改成 8 前后跑同一 SQL：UI 对比 Task 数与耗时（小数据 8 更快——空转少了）
// ===== 实验④：join 的两种命运（同 key 分区=窄，否则=宽）=====
// 同分区器 join（两个 RDD 都按同一 key hash 分区）→ 窄（day17 分桶 join 思想伏笔）
// 无分区器 join → 宽（Shuffle）——EXPLAIN/UI 验证
```

## 4. 面试连接

**Q：Stage 是怎么划分的？给你一条算子链你能切吗？（白板高频题）**
> 规则一句话：从最后一个 RDD 往前回溯，遇到宽依赖就切一刀，窄依赖全部流水线合并——切出来的每段是一个 Stage，Stage 内 Task 数等于末端 RDD 的分区数。我用最常见的一条链演示：textFile → flatMap → map → reduceByKey → filter → collect。reduceByKey 是宽依赖（子分区要按 key 收全所有父分区的数据），所以在它前后切刀：前半段 textFile/flatMap/map 是 Stage0（窄依赖流水线，map 的输出直接在内存里喂给下一个算子，不落盘），后半段 reduceByKey/filter 是 Stage1；Stage0 的 Task 数=textFile 的 HDFS 块数，Stage1 的 Task 数=Shuffle 后的分区数（RDD API 由 reduceByKey 的默认分区器决定，SQL 由 shuffle.partitions 决定）。两个常见追问也准备着：一是"为什么窄依赖能流水线"——因为数据不跨节点搬运，同一分区的计算链在同一节点一口气算完；二是"为什么要按宽依赖切而不是切得更细"——宽依赖必须等上游全部分区算完才能开始 Shuffle（数据不齐没法按 key 分发），这是天然同步屏障，切刀位置就是物理执行的现实约束。这道题我建议拿白板画一遍——我画的时候才真正搞懂 toDebugString 里缩进突变的含义。

**Q：Task 数量怎么确定？200 个分区跑 1MB 数据会怎样？（并行度排障题）**
> Task 数由 Stage 末端 RDD 的分区数决定，追到源头有三个来源：读文件时=块数/文件数（小文件问题在这里放大，M10 day04 的教训直接适用）；Shuffle 时=spark.sql.shuffle.partitions（SQL 默认 200）；手动 repartition/coalesce 指定值。1MB 数据 200 个分区的后果：调度开销远大于计算本身——每个 Task 有调度+启动+心跳的固定成本，200 个 Task 每个算几毫秒，99% 时间在空转，UI 上呈现为"大量毫秒级 Task+总耗时却不短"。我实验过把默认 200 调成 8：同一个聚合 SQL 总耗时明显下降。反过来大批量数据 200 分区不够时，单 Task 数据过大（Shuffle read 均值超过 GB 级）、内存压力大还容易倾斜。经验公式：Shuffle 分区数 ≈ 总 Shuffle 数据量 ÷ 目标单分区大小（128MB~1GB），并且向上取整到核数的整数倍。看 UI 的两个信号反向验证：Task 秒级完成且数量巨大→减分区；个别 Task 耗时是中位数的 3 倍以上→倾斜（day24 主战场）。并行度调优没有万能值，"分区数×单分区大小"两个变量一起看才是正解。

## 5. 今日验收清单

- [ ] 宽窄依赖定义+类比（独家供货 vs 全厂调货）能讲
- [ ] Stage 切分规则白板画（5 算子链切对+Task 数算对）
- [ ] Spark UI 对照完成（DAG 方块+Shuffle 边截图）
- [ ] 200 分区跑小数据的空转实验完成（改 8 前后对比）
- [ ] 并行度经验公式+两个 UI 反向信号能展开
- [ ] `git add . && git commit -m "day11-16: stage"`

---
[← Day 15](day15-RDD与算子.md) | [本月目录](README.md) | [Day 17 · 持久化与广播变量 →](day17-持久化与广播.md)
