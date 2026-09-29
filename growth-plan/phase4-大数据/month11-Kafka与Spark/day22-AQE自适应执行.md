# Day 22 · AQE 自适应执行：让 Spark 运行中"看着数据改计划"

> **今日目标**：搞懂 Spark 3.0 最大的特性 AQE（Adaptive Query Execution，自适应查询执行）——计划跑一半还能改：小分区自动合并、Join 策略运行时切换、倾斜分区自动拆分。三个能力各做一次验证实验。
> **时长**：原理 1.5h / 三能力实验 3h
> **今日产出**：三能力各一份前后对比证据 + 《AQE 参数卡》

## 1. 知识地图

```
静态优化的死穴：Catalyst 在执行前改计划，但它【不知道真实数据量】
  —— 统计信息过期/缺失时，"以为 9GB 的大表"实际跑起来只有 8MB（过滤后）
     → 该广播的没广播，该合并的小分区一堆，该拆的倾斜没拆
  M10 的对应痛点：Hive 里要不要 Map Join，得【人肉】看表大小提前判断；
     判断错了就翻车。AQE = Spark 替你"看一眼真实数据再决定"

AQE 三大能力（Spark 3.0+，默认已开 adaptive.enabled）：

能力① 合并小分区 coalesce（快递站合并空车）
  200 个 Shuffle 分区，实际每分区只有 1MB → 一个 Task 处理多个小分区
  参数：spark.sql.adaptive.coalescePartitions.enabled（默认 true）
        advisoryPartitionSizeInBytes=64MB（目标大小，是"建议"不是硬性）

能力② Join 策略运行时切换（中途改剧本）
  SortMergeJoin 跑到一半发现 Shuffle 后的"大表"侧只有 8MB
  → 现场改成 BroadcastHashJoin（数据已在 Shuffle 落盘，直接读来广播）
  —— 这解决了 CBO 依赖统计信息的难题：不看预报，看实测

能力③ 拆倾斜分区 skew join（超载车厢拆成两节）
  某个分区 800MB、其他都是 50MB → AQE 把大分区的【两侧各拆 N 份】，
  大表的一份拆片对应小表的 N 份副本，配对后并行处理
  参数：skewJoin.enabled / skewJoin.skewedPartitionFactor=5（超中位数 5 倍算倾斜）
        skewJoin.skewedPartitionThresholdInBytes=256MB（且要够大才拆）

哲学总结：Catalyst 是"赛前部署"（RBO/CBO，静态），AQE 是"中场调整"
（运行时按实测数据改战术）——两层配合，正好呼应 day20 的五站流水线。
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| AQE | 自适应查询执行（跑一半看真实数据改计划） |
| coalesce Partitions | 合并小分区（空车并成一班） |
| Dynamic Join Switch | Join 策略运行时切换（中场换剧本） |
| Skewed Partition | 倾斜分区（一节车厢挤爆其他空） |
| Shuffle Query Stage | Shuffle 阶段（AQE 的观察哨：落盘了才知道真实大小） |
| statistics | 统计信息（CBO 的预报，可能过期；AQE 的实测不会） |

## 3. 动手实操：三能力逐一验证

```scala
// 环境准备：确认 AQE 已开（Spark 3.5 默认 true）
spark.conf.get("spark.sql.adaptive.enabled")     // 应为 true
// 为看清效果，先关掉 AQE 跑一遍做对照组

// ===== 实验①：小分区合并 =====
// 造一张"过滤后剩一点点"的表：
val big = spark.range(0, 2000000).selectExpr("id", "id % 100 as k")
big.createOrReplaceTempView("t_big")
spark.conf.set("spark.sql.adaptive.enabled", "false")
spark.sql("SELECT k, count(*) FROM t_big WHERE k=1 GROUP BY k")
  .write.mode("overwrite").parquet("/tmp/no_aqe")     // 看物理计划：200 分区 Task
spark.conf.set("spark.sql.adaptive.enabled", "true")
spark.sql("SELECT k, count(*) FROM t_big WHERE k=1 GROUP BY k")
  .write.mode("overwrite").parquet("/tmp/with_aqe")
// 对比 UI：关 AQE=200 个碎 Task；开 AQE=合并成 1~2 个 Task（合并证据截图）

// ===== 实验②：Join 策略运行时切换 =====
val small = spark.range(0, 1000).selectExpr("id as k", "id as v")
small.createOrReplaceTempView("t_small")
spark.conf.set("spark.sql.autoBroadcastJoinThreshold", "-1")   // 禁掉静态广播！
// 强制 SortMergeJoin：spark.sql.join.preferSortMergeJoin=true（默认）
val j = spark.sql("SELECT /*+ MERGE(t_big) */ * FROM t_big JOIN t_small ON t_big.k=t_small.k")
j.explain()   // Physical Plan：SortMergeJoin（静态计划以为 t_big 很大）
j.count()     // 跑完看 UI：实际执行的是 BroadcastHashJoin！
// —— SQL 提示+阈值都堵死了，AQE 看实测数据（过滤后真身 8MB）现场切广播

// ===== 实验③：倾斜拆分 =====
// 造倾斜数据：99% 的行 k=0
val skew = spark.range(0, 5000000).selectExpr(
  "CASE WHEN rand() < 0.99 THEN 0 ELSE id END as k", "id as v")
skew.createOrReplaceTempView("t_skew")
spark.conf.set("spark.sql.adaptive.skewJoin.enabled", "true")
spark.conf.set("spark.sql.adaptive.skewJoin.skewedPartitionFactor", "5")
val sj = spark.sql("SELECT * FROM t_skew JOIN t_small ON t_skew.k=t_small.k")
sj.count()
// UI 对照：AQE 的 Stage 图里倾斜分区被切成 [0-0], [0-1]...（拆分证据）
// 关闭 skewJoin 再跑一遍对比最长 Task 耗时（预期差 3~5 倍，记录数字）
```

## 4. 面试连接

**Q：讲讲 AQE？它解决了什么静态优化解决不了的问题？（Spark 3.x 必考题）**
> AQE 是运行时优化：每完成一个 Shuffle Stage，就用落盘数据的真实统计（分区大小/行数）重新优化后续计划。它解决的是静态优化的死穴——Catalyst 的 RBO 只看 SQL 形状，CBO 依赖的统计信息经常过期或缺失，"预报"不可靠；AQE 不看预报看实测。三大能力：合并小分区——过滤后 200 个分区只剩几 MB，自动并成少量 Task，省掉一堆调度开销；Join 策略切换——计划里是 SortMergeJoin，实际运行发现某侧 Shuffle 后只有 8MB，现场切成 BroadcastHashJoin 免掉大 Shuffle；倾斜拆分——发现某分区超过中位数 5 倍且大于 256MB，把它和对应的另一侧分区各拆成多份并行。加分点：说清楚"为什么等 Shuffle 才能优化"——因为 Shuffle 落盘是数据的"体检报告"，这时候数据量才测得准；以及 AQE 和 M10 Hive 的呼应——Hive 要不要 Map Join 靠人肉看表大小，AQE 是引擎替你看，这是两代引擎的代差。

**Q：开了 AQE 还需要手工治理倾斜吗？（追问，考实战判断）**
> 要，AQE 只覆盖一部分场景：它只治 Join 阶段的倾斜（skewJoin），聚合阶段的倾斜（groupBy 某个超大 key）不归它管，得用两阶段聚合/加盐（day24 详讲）；拆分的粒度也有限——倾斜到几百倍的单 key，拆成两三份还是最慢的，根治还是要从业务侧想（预聚合/热点拆分）。另外 AQE 参数有触发门槛：分区要超过 256MB 才拆，很多真实倾斜的分区只有几十 MB，够不着门槛但拖慢整批 Task。我的结论：AQE 是"自动挡"，能兜住六七成日常场景；剩下的大盘倾斜、聚合倾斜还是要手动挡——两者是兜底和精调的关系，不是替代。

## 5. 今日验收清单

- [ ] AQE 三大能力+触发时机（Shuffle 落盘后）能脱稿讲
- [ ] 实验①②③完成，三张前后对比证据留存
- [ ] "为什么等 Shuffle 才能优化"能讲（实测 vs 预报）
- [ ] "AQE 还要不要手工治倾斜"有自己的判断和理由
- [ ] 《AQE 参数卡》成文（3 个开关+2 个阈值）
- [ ] `git add . && git commit -m "day11-22: aqe"`

---
[← Day 21](day21-第三周复盘.md) | [本月目录](README.md) | [Day 23 · Join 策略全景 →](day23-Join策略全景.md)
