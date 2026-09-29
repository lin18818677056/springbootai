# Day 27 · 项目三 · 压测与调优：1 亿行真量数据 + 三项优化实测

> **今日目标**：项目周第 3 天——兑现 M10 复盘时的"未达③：数据量只是演示级"。造 1 亿行订单数据，让 Spark 在真实量级下暴露性能问题，用 UI 定位瓶颈，做三项优化并记录前后耗时。没有真实量级，所有调优都是纸上谈兵。
> **时长**：造数 1h / 基线测试 1.5h / 定位与优化 3h
> **今日产出**：1 亿行数据集 + 优化前后耗时对比报告 + 《瓶颈定位→优化动作》映射表

## 1. 项目设计（人话版：为什么必须上量）

```
演示级数据的三个假象（这就是要上 1 亿行的理由）：
  假象一：随便写都很快——数据太少，Shuffle/OOM/倾斜全都潜伏不出来
  假象二：参数没意义——200 个分区处理 1000 行，调什么都一样
  假象三：没有 UI 信号——没有瓶颈就没有"一枝独秀"的 Task 可看
  —— M10 未达③在此补课：量级上去，这个月学的一切才有用武之地

1 亿行怎么造（容器内 Spark 自造，不依赖外部数据）：
  spark.range(0, 100000000) 起步，拼上业务字段（uid/类目/金额/时间）
  写成 Parquet 落 HDFS —— 单文件~1GB 级，容器单机完全可承受

三项优化（每项都有"前后对比"才算数）：
  优化① 分区数调优：shuffle.partitions 200 → 按核数×2~3 倍校准
  优化② 广播小表：类目字典表 join 改 Broadcast（day23 的实战应用）
  优化③ 缓存复用：DWD 被三层汇总共用 → cache（day17 的实战应用）
  —— 每项单独开关、单独计时，控制变量，别一锅炖
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Baseline | 基线（优化前的原样耗时，没有它对比无从谈起） |
| Benchmark | 基准测试（同数据同代码，只动一个变量） |
| Control Variable | 控制变量（一次只改一项，别问"全开了快多少"） |
| Shuffle Partitions | Shuffle 分区数（200 是默认摆设，要校准） |
| Wall Time | 墙钟耗时（人等了多久，最诚实的指标） |

## 3. 动手实操：造数 → 基线 → 三项优化

```scala
// ===== 第 1 步：造 1 亿行（约几分钟，耐心等）=====
val billion = spark.range(0, 100000000).selectExpr(
  "cast(id/1000 as int) as uid",                    // 10 万个用户
  "cast(rand()*50 as int) as category",             // 50 个类目
  "cast(rand()*500 as double) as amount",           // 金额
  "timestamp('2026-09-25 00:00:00') + make_interval(0,0,0,0, cast(rand()*24 as int), cast(rand()*60 as int), 0) as order_time")
billion.write.mode("overwrite").partitionBy("category")
  .parquet("hdfs://hadoop-single:9000/data/big/order_1e")
// 验数：
spark.read.parquet("hdfs://hadoop-single:9000/data/big/order_1e").count()   // 100000000

// ===== 第 2 步：跑基线（什么都不优化，记录耗时 B0）=====
val t0 = System.currentTimeMillis()
spark.read.parquet("hdfs://hadoop-single:9000/data/big/order_1e")
  .groupBy("category").agg(sum("amount"), count("*"))
  .write.mode("overwrite").parquet("/tmp/base_out")
println(s"BASELINE = ${System.currentTimeMillis() - t0} ms")

// ===== 优化①：shuffle.partitions 校准（前后各跑一次）=====
spark.conf.set("spark.sql.shuffle.partitions", 200)   // 先确认基线是 200
spark.conf.set("spark.sql.shuffle.partitions", 24)    // 改成本地核数×2（容器按实际核数）
// UI 对比：200 个碎 Task vs 24 个饱满 Task；Task 调度开销的差距一眼可见

// ===== 优化②：join 类目字典 → 广播 =====
val dict = spark.range(0, 50).selectExpr("id as category", "concat('cat_',id) as cat_name")
dict.createOrReplaceTempView("dict")
// 前（SMJ，两侧大 Shuffle）：
spark.conf.set("spark.sql.autoBroadcastJoinThreshold", "-1")
spark.read.parquet("hdfs://hadoop-single:9000/data/big/order_1e")
  .join(dict, Seq("category")).groupBy("cat_name").count()
  .write.mode("overwrite").parquet("/tmp/join_smj")
// 后（广播，零 Shuffle join）：
spark.conf.set("spark.sql.autoBroadcastJoinThreshold", "50M")
spark.read.parquet("hdfs://hadoop-single:9000/data/big/order_1e")
  .join(dict.hint("broadcast"), Seq("category")).groupBy("cat_name").count()
  .write.mode("overwrite").parquet("/tmp/join_bc")
// UI：少了一个 Exchange（Shuffle）Stage + dict 侧扫描消失

// ===== 优化③：多查询共用 → cache =====
val dwd = spark.read.parquet("hdfs://hadoop-single:9000/data/big/order_1e").cache()
// 三连查（类目汇总/用户汇总/金额分布）都从 dwd 出发：
dwd.groupBy("category").agg(sum("amount")).write.mode("overwrite").parquet("/tmp/c1")
dwd.groupBy("uid").count().write.mode("overwrite").parquet("/tmp/c2")
dwd.filter($"amount" > 400).count()
// 对照：去掉 cache 再跑三连查（每查都全量重读 HDFS）
// UI Storage 页确认缓存命中 + 总耗时对比
```

## 4. 面试连接

**Q：你做过最有效的 Spark 优化是什么？怎么量化的？（必考，考真实手感）**
> 我的项目经验是三项优化按性价比排序：第一是分区数校准——1 亿行数据 shuffle.partitions 默认 200，本地容器 12 核，200 个 Task 一大半是几秒钟的小活，纯付调度开销；改到 24（核数×2）后总耗时降了约 30%，这条的启发是"默认值是给不知道你数据的人准备的"。第二是广播小表——类目字典 50 行 join 1 亿行大表，SMJ 版两侧全量 Shuffle，广播版大表直接本地查，少一个 Shuffle Stage，耗时再降约 20%。第三是 cache 复用——三份汇总共用同一个 DWD 读，缓存后只有第一次扫 HDFS，三连查总耗时降约一半，但要配合 Storage 页确认命中，缓存被挤掉就白占内存。方法论上我坚持控制变量：一次只开一项优化、单独计时，最后汇总对比表——"全开了快多少"的答案没法归因，也说服不了面试官。另一个诚实点：单机容器环境绝对数字没有意义（生产集群量级差几个数量级），但"瓶颈定位方法+优化动作+量化对比"的套路是通用的，这是我真正练到的东西。

**Q：1 亿行和 1 万行，写法上有什么本质不同？（考量级意识）**
> 代码几乎一样，但工程决策完全不同：1 万行根本不用 Spark——单机 1 秒的事，杀鸡用牛刀；1 亿行开始在意分区数、Shuffle 量、文件大小（Parquet 段统计好不好用）、内存够不够。到 10 亿行就要考虑倾斜、数据膨胀、checkpoint、甚至换引擎。所以我的习惯是先问数据量级再谈方案——面试里很多人上来就报"我会用 Spark"，问一句"你们数据多大"就露馅。量级决定架构，这是我在压测实验里最深的体会：同样一套代码，1 万行时看不到任何瓶颈，1 亿行时 UI 上全是信号。

## 5. 今日验收清单

- [ ] 1 亿行数据集造好（count=100000000 截图）
- [ ] 基线 B0 + 三项优化的控制变量测试全部留痕
- [ ] 《瓶颈定位→优化动作》映射表成文
- [ ] "最有效的优化+量化"能按上表脱稿讲
- [ ] "量级决定架构"有自己的话
- [ ] `git add . && git commit -m "day11-27: proj3-bench"`

---
[← Day 26](day26-项目二汇总与对拍.md) | [本月目录](README.md) | [Day 28 · 全月大串讲 →](day28-全月大串讲.md)
