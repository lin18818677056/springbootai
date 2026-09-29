# Day 15 · RDD 与两类算子：会记仇的账本

> **今日目标**：吃透 Spark 最核心的抽象 RDD（弹性分布式数据集）——重点理解它的"记仇"特性（血缘：每一步怎么来的都记着，数据丢了顺着账本重算）；分清两类算子（转换=记账，行动=开工），理解"惰性求值"为什么是 Spark 能做全局优化的前提。
> **时长**：RDD 特性 1.5h / 两类算子与惰性 1.5h / 实验 2h
> **今日产出**：血缘链实验记录（toDebugString）+ 两类算子分类表

## 1. 知识地图

```
RDD 是什么？——"分布式环境里一张会记仇的账本"
  先说人话：你手里的一份数据被切成 N 份放在 N 台机器上（分区），
  而这份数据"是怎么来的"被完整记录下来（血缘）——
  哪台机器的哪份坏了，不用备份恢复，顺着账本重算那一份就行。

五大特性按重要度记三条（面试够用）：
  ① 一组分区（数据切格：并行度的基础，day04 Kafka 分区思想同款）
  ② 一个依赖列表（血缘：记住父 RDD 和经过的算子——容错的根基）
  ③ 一个计算函数（每个分区怎么算：apply 到每条数据上）
  （再加两个可选：分区器如 hash 分区/优先位置如"数据在哪台机器就在哪算"）

血缘（Lineage）为什么是"弹性"的精髓：
  弹性≠可以伸缩（那是分区的事），弹性=【容错的智慧】：
    传统思路：怕丢就复制三份（HDFS 的做法，存层面冗余）
    RDD 思路：中间结果不复制——丢了就重算（算层面冗余）
    取舍：中间数据"重新算"往往比"存三份"便宜（尤其过滤后数据变小）
    —— 注意边界：血缘太长重算代价大 → checkpoint 截断账本（day17 讲）

两类算子（一动一静，写代码时随时自问"这条触发了吗"）：
  Transformation 转换（记账不干活，返回新 RDD）：
    map/filter/flatMap、groupByKey/reduceByKey、join、distinct…
  Action 行动（开工令，触发真正计算并返回结果/写存储）：
    collect/take/count/saveAsTextFile/foreach/first…
  惰性求值 Lazy Evaluation：
    写十行转换 = 画了十笔图纸，一行都不执行；直到遇到第一个 Action 才开工
    好处：开工时引擎拿着完整图纸做全局优化（day12 牌②的前提）——
    若每行都立即执行，引擎就只能见步行步，无从优化
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| RDD (Resilient Distributed Dataset) | 弹性分布式数据集（切格+记仇的分布式账本） |
| Lineage / 血缘 | 出身记录（每一步怎么来的，丢了按此重算） |
| Transformation | 转换算子（记账：定义怎么变，不执行） |
| Action | 行动算子（开工令：触发执行） |
| Lazy Evaluation | 惰性求值（先画全图纸再开工） |
| Partition | 分区（数据的格，并行度单位） |

## 3. 动手实操：血缘链与惰性观察

```scala
// ===== 进入 spark-shell（沿用 day13 容器）：docker exec -it spark spark-shell =====
// ===== 实验①：惰性求值现场（转换不执行，行动才执行）=====
val text = spark.sparkContext.textFile("hdfs://hadoop:9000/data/ods/order_detail/dt=2026-09-25")
val mapped = text.flatMap(_.split(",")).filter(_.nonEmpty)   // 两个转换：无任何输出！
// 控制台没动静——图纸画完了，没人开工
println(mapped.count())        // 行动①：瞬间触发 job……number x
val first3 = mapped.take(3)    // 行动②：又触发一次 job（注意：转换会重算！）
// 观察点：两次行动 = 两次 job = textFile+flatMap+filter 执行了两遍
// —— 这就是为什么要 cache（day17）：多行动共享中间结果时要"把菜先做好放着"
// ===== 实验②：血缘链（toDebugString 看记仇账本）=====
val wc = mapped.map((_, 1)).reduceByKey(_ + _)
println(wc.toDebugString)
// 输出类似：
// (2) MapPartitionsRDD[xx] at reduceByKey ...   ← ShuffledRDD 前的分界
//  |  MapPartitionsRDD[yy] at map ...
//  |  MapPartitionsRDD[zz] at flatMap ...
//  |  HDFSFilesRDD[0] at textFile ...
// —— 缩进层级就是"谁是谁儿子"，一行就是一步记账
// ===== 实验③：模拟数据丢失看重算（理解弹性）=====
// 思路演示（单机不易真丢块）：手动重跑血缘=手动"恢复"
val fromLineage = spark.sparkContext.textFile("hdfs://hadoop:9000/data/ods/order_detail/dt=2026-09-25")
  .flatMap(_.split(",")).filter(_.nonEmpty)     // 同一条血缘链重算——结果必然一致
println(fromLineage.count())                    // 与 mapped.count() 相同：账本可信
// 记录：容错=重算，代价=血缘长度（长链要 checkpoint 截断——day17 实验预告）
```

## 4. 面试连接

**Q：讲讲 RDD？为什么叫"弹性"分布式数据集？（定义必考题）**
> 先一句话定义：RDD 是不可变的分布式数据集，数据切成多个分区放在集群各节点，每个 RDD 记录着"自己从哪个父 RDD 经过什么算子而来"（血缘），并提供两类算子（转换/行动）来操作它。"弹性"这个词最容易被答成"可以伸缩"，真正的精髓在容错设计：数据丢失时不需要从备份恢复，而是沿着血缘重算——"算层面冗余"替代"存层面冗余"。为什么敢这么设计？因为中间数据往往是过滤/转换后的，重算的成本通常低于存多份副本的成本，而且血缘是确定性的（同样输入必得同样输出），重算结果可信。两个边界要主动交代：一是血缘太长会导致重算代价指数级，所以有 checkpoint 机制定期"截断账本"（把中间结果真正落盘，之后从 checkpoint 重启血缘）；二是血缘链上的分区级重算可以只算丢的那几个分区，不用全量重来——这才是"弹性"的完整含义：按需、部分、确定性重算。这套"账本+重算"思想和数据库 redo log、消息回放（我们 Kafka 的 offset 重拨）在哲学上是一致的：状态不可靠时，靠"可重放的日志"重建状态。

**Q：什么是惰性求值？它带来什么好处和坑？（机制题）**
> 惰性求值是"写转换不执行、见行动才开工"。你写十行 map/filter，Spark 只是在画图纸（构建 DAG），直到遇到 collect/count/save 这类 Action 才真正计算。好处两个：一是全局优化成为可能——引擎开工时拿到完整执行图，可以合并窄依赖算子做流水线、在 Shuffle 处切 Stage、做谓词下推（day20 的 SQL 优化全部建立在"先有全图"之上）；二是自动跳过无用计算——如果图纸后半段根本不被任何 Action 引用，前半段也不会执行。坑也有三个，我在实验里都撞过：坑一，多 Action 共享中间结果会重算——take 和 count 各触发一次完整 job，同样的转换链跑了两遍（解法：cache，day17）；坑二，调试困难——写错算子参数时错误延迟到 Action 才爆，报错栈离案发现场很远（解法：小数据量上高频用 take(3) 快速验证）；坑三，循环里嵌 Action 是性能杀手——for 循环里每次 collect 都是一个完整 job（解法：把循环逻辑改成一次转换+一次行动）。理解了惰性，你就理解了 Spark 一切优化行为的出发点：引擎永远在等一张完整图纸。

## 5. 今日验收清单

- [ ] RDD 三大特性+"弹性"的真正含义（重算而非复制）能讲
- [ ] 两类算子各举 4 个+惰性求值图（图纸/开工令）能白板画
- [ ] 三个实验完成（惰性现场/toDebugString 血缘/重算验证）
- [ ] 惰性的三个坑各带解法能展开
- [ ] `git add . && git commit -m "day11-15: rdd"`

---
[← Day 14](day14-第二周复盘.md) | [本月目录](README.md) | [Day 16 · 宽窄依赖与 Stage →](day16-宽窄依赖与Stage.md)
