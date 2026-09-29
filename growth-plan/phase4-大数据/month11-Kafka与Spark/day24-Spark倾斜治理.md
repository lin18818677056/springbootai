# Day 24 · Spark 倾斜治理：M10 四武器平移 + AQE 的新解法

> **今日目标**：数据倾斜是 Spark 调优的头号现场问题。把 M10 的四武器（加盐/两阶段聚合/Map Join/热点拆分）平移到 Spark，再叠加 day22 的 AQE Skew Join；用三个真实案例做"治理前后耗时对比"。
> **时长**：诊断方法 1h / 三案例实战 4h
> **今日产出**：三案例前后耗时对比表 + 《倾斜治理决策卡 v2》（含 Spark 新武器）

## 1. 知识地图

```
先会诊断（会不会开药，先看会不会看病）：
  UI 特征：整个 Stage 99% 的 Task 几十秒完事，1~2 个 Task 跑 20 分钟
  ——"一枝独秀"=倾斜铁证（不是资源不够，是数据不均）
  日志特征：那几个慢 Task 的 Shuffle Read Size 明显巨大
  SQL 特征：groupBy/join 的 key 上有没有"超级热点"
  （9 成倾斜都是它：默认 uid=0 的游客/null、爆款商品、头部大 V）

武器平移（M10 四武器 → Spark 对应实现）：

武器① 两阶段聚合（加盐打散）：groupBy 的倾斜神器
  第一阶段：key 加随机盐 k_0/k_1... 先局部聚合（200 份热点变 200 份小活）
  第二阶段：去掉盐后缀再全局聚合
  Spark 实现：同样 SQL 可写，或干脆用 Day18 的 reduceByKey 思想
  （map 端 combine 本质就是"免费的第一阶段"——但单 key 巨大时 combine 也救不了）

武器② 广播 Join（免 Shuffle = 免倾斜）：join 的倾斜神器
  倾斜大多发生在 Shuffle 边（join/agg）；小表广播后 join 变本地查找
  ——没有 Shuffle 就没有"一个 key 聚到一堆"的问题
  Spark 实现：hint /*+ BROADCAST(t) */ 或调阈值（day23）
  ——这就是 M10 Hive Map Join 的直系同款，思想 100% 平移

武器③ 热点 key 单独处理（分流手术）：
  把 uid=0/null 先 filter 出来单独算，正常 key 正常 join，最后 union
  —— M10 的"隔离区"思想（day25 脏行隔离）同构：脏的/热的都分流

武器④ 加盐扩容 repartition：物理层打散
  df.repartition(800, $"uid") 把分区数扩到 800，让哈希分布更均匀
  ——治"分区太少导致的伪倾斜"（不是 key 热点，是分区分粗了）

Spark 新武器⑤ AQE Skew Join（day22）：自动挡兜底
  Join 阶段倾斜自动拆分，参数门槛 256MB+5 倍中位数
  ——覆盖日常六七成，聚合倾斜和超热点仍要手动（day22 已论证）

选型口诀：
  groupBy 倾斜 → 武器①加盐两阶段（AQE 不帮忙）
  join 倾斜   → 先看小表能否广播（武器②），不行开 AQE（武器⑤）兜底
  null/游客等超级热点 → 武器③分流
  分区分粗了   → 武器④扩分区
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Data Skew | 数据倾斜（个别 key 的数据量碾压其他） |
| Salting | 加盐（key 加随机后缀打散热点） |
| Two-phase Agg | 两阶段聚合（先局部后全局） |
| Isolate Hot Key | 热点分流（脏活单独干，别拖累大家） |
| Straggler Task | 拖后腿 Task（"一枝独秀"的长尾） |
| Skew Join | AQE 的倾斜自动拆分 |

## 3. 动手实操：三案例前后对比

```scala
// 造倾斜数据：99% 订单都属于 uid=0（模拟"游客单"热点）
val raw = spark.range(0, 10000000).selectExpr(
  "CASE WHEN rand() < 0.99 THEN 0 ELSE cast(id/1000 as int) END as uid",
  "id as oid", "cast(rand()*100 as int) as amount")
raw.createOrReplaceTempView("t_orders")

// ===== 案例①：groupBy 倾斜 → 加盐两阶段 =====
// 治理前：
spark.sql("SELECT uid, sum(amount) s FROM t_orders GROUP BY uid")
  .write.mode("overwrite").parquet("/tmp/skew_before")   // UI：1 个超长 Task，记耗时 T1
// 治理后（加盐）：
spark.sql("""
  SELECT split(salted_uid,'_')[0] uid, sum(s) FROM (
    SELECT concat(uid,'_',cast(rand()*20 as int)) salted_uid, sum(amount) s
    FROM t_orders GROUP BY concat(uid,'_',cast(rand()*20 as int))
  ) GROUP BY split(salted_uid,'_')[0]""")
  .write.mode("overwrite").parquet("/tmp/skew_after")    // UI：Task 均匀，记耗时 T2
// 对比：最长 Task 时间与总时间（预期 T2 明显短，热点被 20 份摊薄）

// ===== 案例②：join 倾斜 → 广播分流 =====
val user = spark.range(0, 50000).selectExpr("id as uid", "concat('u',id) uname")
user.createOrReplaceTempView("t_users")
// 治理前：正常 SortMergeJoin（uid=0 那格分区巨大）
spark.sql("""SELECT o.oid, u.uname FROM t_orders o JOIN t_users u ON o.uid=u.uid""")
  .write.mode("overwrite").parquet("/tmp/j_before")
// 治理后：广播（零 Shuffle，倾斜消失）
spark.sql("""SELECT /*+ BROADCAST(u) */ o.oid, u.uname
             FROM t_orders o JOIN t_users u ON o.uid=u.uid""")
  .write.mode("overwrite").parquet("/tmp/j_after")
// UI 对比：j_before 有超长 Task；j_after 的 Shuffle Stage 直接消失

// ===== 案例③：null/0 热点分流（业务层最常用）=====
spark.sql("""
  SELECT oid, uname FROM (
    SELECT oid, null as uname FROM t_orders WHERE uid=0          -- 热点单独算
    UNION ALL
    SELECT o.oid, u.uname FROM t_orders o
      JOIN t_users u ON o.uid=u.uid WHERE o.uid<>0              -- 正常 join
  )""")
  .explain()   // 正常 join 侧不再有超级热点，且可叠加广播
```

## 4. 面试连接

**Q：生产上遇到 Spark 数据倾斜怎么处理？（必考，考实战完整度）**
> 我按"诊断→选型→验证"三步。诊断看 Spark UI：一个 Stage 里 99% Task 秒完、个别 Task 跑二十分钟，Shuffle Read Size 巨大——先确认是 key 热点而不是资源问题。选型看倾斜发生的位置：groupBy 倾斜用加盐两阶段聚合——第一阶段 key 加随机后缀把热点摊成 20 份局部聚合，第二阶段去盐全局聚合，代价是多一轮 Shuffle；join 倾斜先看小表能不能广播——广播后 join 是本地查找，压根没有 Shuffle 聚集点，这是最干净的解法；有超级热点 key（比如 uid=0 的游客单）就分流——热点单独算、正常 key 正常算再 union；另外 Spark 3 开了 AQE Skew Join 能自动拆 join 倾斜，但它有门槛（256MB+5 倍中位数）且不管聚合倾斜，只能当兜底。验证必做：治理前后用同一份数据跑，对比 UI 里最长 Task 时间和总耗时，我实测过 2000 万行、99% 热点在 uid=0 的数据，加盐后总耗时降了一个数量级。这道题想答满分，关键是展示"选型逻辑"而不是背武器清单——倾斜位置决定武器，代价意识决定先后。

## 5. 今日验收清单

- [ ] 诊断特征（UI"一枝独秀"+Read Size）能讲
- [ ] 武器①~⑤及各自适用位置能脱稿
- [ ] 三案例实验完成，前后耗时对比表留存
- [ ] "选型逻辑"（倾斜位置决定武器）能白板讲
- [ ] 《倾斜治理决策卡 v2》成文（M10 版基础上加 AQE）
- [ ] `git add . && git commit -m "day11-24: skew"`

---
[← Day 23](day23-Join策略全景.md) | [本月目录](README.md) | [Day 25 · 项目一 · 接入与明细 →](day25-项目一接入与明细.md)
