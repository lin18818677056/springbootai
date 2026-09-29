# Day 23 · Join 策略全景：四种 Join 怎么选，一张决策树说清

> **今日目标**：把 Spark SQL 的四种 Join（广播哈希/排序归并/Shuffle 哈希/嵌套循环）一次讲透——每种的工作原理、适用条件、代价；画一张决策树；用 threshold 参数亲手切换策略做验证。
> **时长**：四种 Join 原理 2h / 决策树与实验 3h
> **今日产出**：Join 决策树图 + 四策略切换实验记录 + 《Join 选型速查卡》

## 1. 知识地图

```
类比先行：两张表 join = 两摞档案按工号配对
  ① BroadcastHashJoin（把小摞复印发给每个人）
     小表全量广播到每个 Executor，大表 Task 本地查哈希表配对
     ——【零 Shuffle】！代价=广播占内存，小表必须装得下
     条件：一侧表大小 < autoBroadcastJoinThreshold（默认 10MB）
  ② SortMergeJoin（两摞都按工号排好队，齐步走对齐配对）
     两侧按 key 排序 → 归并扫描配对。稳、不怕大表，代价=大 Shuffle+双排序
     条件：默认兜底策略（Spark 2.x 起替代 ShuffleHashJoin 成默认）
  ③ ShuffleHashJoin（快递按工号分组寄达后，各自建哈希表查）
     先按 key Shuffle 重分区，一侧（较小）在每个分区建哈希表
     比 SMJ 省排序，但要内存装下分区级哈希表——不稳，默认不用
  ④ BroadcastNestedLoopJoin / Cartesian（实在没条件可对，全员两两试）
     无等值条件（非 equi-join）时兜底，O(n×m) 爆炸——见到它就该警报

决策树（背下来）：
  有等值条件吗？ ──否──→ NestedLoop/Cartesian（业务上尽量改写掉）
        │是
  有一侧 < 广播阈值吗？（或可被 hint/统计信息判定）
        │是 → BroadcastHashJoin    ★ 首选
        │否
  ShuffleHash 可行吗？（spark.sql.join.preferSortMergeJoin=false 且
        内存装得下一侧分区）→ ShuffleHashJoin
        │否/默认
        └→ SortMergeJoin            ★ 默认兜底

参数三件套：
  autoBroadcastJoinThreshold=10MB     广播资格线（AQE 可动态放宽，day22）
  spark.sql.join.preferSortMergeJoin  默认 true（SMJ 稳；false 才考虑 SHJ）
  hint 强制：/*+ BROADCAST(t) */ /*+ MERGE(t) */ /*+ SHUFFLE_HASH(t) */
  —— 生产教训：hint 是"你比引擎懂业务"的强声明，写进 SQL 注释留痕
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| BroadcastHashJoin | 广播哈希 Join（小表复印给全员，零 Shuffle） |
| SortMergeJoin | 排序归并 Join（排队对齐，稳但贵） |
| ShuffleHashJoin | 分组建表 Join（省排序但吃内存） |
| NestedLoopJoin | 嵌套循环（两两硬试，最后手段） |
| equi-join | 等值 Join（on a=b，能用哈希的前提） |
| hint | 提示（写在 SQL 里强制指定策略） |

## 3. 动手实操：亲手切四种策略

```scala
// 准备两张表：dwd 明细（M10 的 shop 库）+ 一张小字典表
val detail = spark.table("shop.dwd_order_detail_di").where($"dt" === "2026-09-25")
val dict = spark.range(0, 100).selectExpr("id as category", "concat('cat_', id) as name")
dict.createOrReplaceTempView("tmp_dict")

// ===== 实验①：默认行为（dict 很小 → 应该自动广播）=====
detail.join(dict.hint("ignore"), Seq("category"))   // ignore hint：不受强制
  .explain()   // Physical Plan：BroadcastHashJoin（小表自动广播）

// ===== 实验②：把阈值压到 1 字节 → 强制走 SortMergeJoin =====
spark.conf.set("spark.sql.autoBroadcastJoinThreshold", "1")
detail.join(dict, Seq("category")).explain()   // SortMergeJoin（阈值挡掉广播）
spark.conf.set("spark.sql.autoBroadcastJoinThreshold", "10M")   // 还原

// ===== 实验③：用 hint 强制各策略（观察 Plan 变化）=====
detail.join(dict.hint("broadcast"), Seq("category")).explain()  // BroadcastHashJoin
detail.join(dict.hint("merge"), Seq("category")).explain()      // SortMergeJoin
detail.join(dict.hint("shuffle_hash"), Seq("category")).explain() // ShuffleHashJoin

// ===== 实验④：性能对拍（用真实数据量测）=====
// 小实验数据看不出差距 → 造大点：
val bigDict = spark.range(0, 3000000).selectExpr("id as uid", "concat('u',id) as name")
bigDict.createOrReplaceTempView("tmp_bigdict")
// SMJ 版：
spark.sql("""SELECT /*+ MERGE(d) */ count(*) FROM shop.dwd_order_detail_di d
  JOIN tmp_bigdict b ON d.uid=b.uid WHERE d.dt='2026-09-25'""").count()
// 广播版（若 dwd 在几千行量级，可能超不了 10MB，先把阈值调到 200M 试）：
spark.sql("""SELECT /*+ BROADCAST(b) */ count(*) FROM shop.dwd_order_detail_di d
  JOIN tmp_bigdict b ON d.uid=b.uid WHERE d.dt='2026-09-25'""").count()
// UI 对比两个 job 的 Stage 数与总耗时（广播版少一整个 Shuffle Stage）
// 记录：耗时差 ____ 倍；Stage 数差 ____ 个 —— 这就是你面试的实测数字
```

## 4. 面试连接

**Q：Spark 有哪些 Join 策略？怎么选？（必考，考体系）**
> 四种：BroadcastHashJoin——小表广播到各 Executor 建哈希表，大表本地配对，零 Shuffle，是最快首选，条件是一侧小于 autoBroadcastJoinThreshold 且能装进内存；SortMergeJoin——两侧按 key Shuffle 后排序归并，不依赖内存装下，是默认兜底，稳但带来大 Shuffle 和双排序；ShuffleHashJoin——Shuffle 后一侧分区建哈希表，省了排序但要求分区级内存够用，默认关闭（preferSortMergeJoin=true）；最后是 NestedLoop/Cartesian，非等值 Join 的兜底，O(n×m)，生产见到就要警惕。选择逻辑就是决策树：先看有没有等值条件，再判一侧是否够小可广播，都不行落 SMJ。两种干预手段：改参数阈值，或者 SQL hint 强制（BROADCAST/MERGE/SHUFFLE_HASH），生产上我倾向 hint+注释留痕，因为"为什么走这个策略"是查询意图的一部分。加分点：可以接 AQE——运行时发现某侧实际只有 8MB，即使静态计划是 SMJ 也会现场切广播（day22），这是 Spark 3 相对 Hive 的代差。

**Q：广播 Join 的表多大算"小"？怎么估算？（考工程细节）**
> 不能只看阈值数字：autoBroadcastJoinThreshold 比较的是【统计信息里的表大小】——Hive 表靠 ANALYZE 统计，没统计就按文件大小估，Parquet 压缩后 100MB 解压后可能 500MB。我的估算三步：看表行数×行宽估内存大小；广播要的是【全量进每个 Executor】的堆内空间，所以还要对照 executor-memory 和 Storage 区余量（day19 的内存布局）；再留出哈希表的膨胀系数（一般 2~3 倍）。实操判断：explain() 看走的什么 Join + UI 看 BroadcastExchange 的实际大小，超了会直接广播失败报错或退化。我的纪律：广播的上限意识——10MB 默认线，调到 100M+ 之前必须确认 Executor 内存真的装得下，否则就是"为了免 Shuffle 把内存炸了"。

## 5. 今日验收清单

- [ ] 四种 Join 原理+代价能脱稿讲（复印/排队/建表/硬试）
- [ ] 决策树能白板画
- [ ] 实验①②③④完成：阈值切换+hint 强制+性能对拍留痕
- [ ] 广播表"多大算小"的估算三步能讲
- [ ] 《Join 选型速查卡》成文贴墙
- [ ] `git add . && git commit -m "day11-23: join-strategy"`

---
[← Day 22](day22-AQE自适应执行.md) | [本月目录](README.md) | [Day 24 · Spark 倾斜治理 →](day24-Spark倾斜治理.md)
