# Day 26 · 项目二 · 汇总与对拍：同一份账，两本引擎算出同一个数

> **今日目标**：项目周第 2 天——在 Spark 里完成 DWS/ADS 两层汇总，然后做本月最重要的仪式：**用 Spark 算出来的 GMV 和 M10 用 Hive 算出来的 GMV 对拍**。两个引擎、同一条元数据账本，数字必须一模一样。
> **时长**：DWS/ADS 构建 2.5h / 对拍与复盘 2.5h
> **今日产出**：dws/ads 两张新表 + 双引擎对拍报告（一致性证明）

## 1. 项目设计（人话版：这步在干什么）

```
数仓分层施工（对照 M10 day26 的同构作业，引擎换成 Spark）：
  DWD（昨天完成）：点击/订单明细，干净的"一行一件事"
  DWS（今天上半场）：按用户/类目做日汇总——"每人每天点了多少/每类每天卖多少"
  ADS（今天下半场）：面向应用的聚合结果——"GMV 每日大屏"

对拍（今天的灵魂）：
  M10 用 Hive-MR 在 shop.ads_gmv_daily 算过一笔 GMV（dt=2026-09-25）
  今天用 Spark 从同一份 DWD 出发再算一遍
  —— 两个引擎的答案逐分逐毫一致 → 证明：
     ① 元数据共享打通（Spark/Hive 看到同一张表）
     ② 清洗口径一致（M10 定义的规则两边都执行了）
     ③ 计算正确性交叉验证（引擎可以换，口径不能换）
  —— 这就是 M8 的对账思想在数仓工程里的落地：不信任单点，信任交叉验证
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| DWS | 汇总层（把明细按维度滚成"每人/每类的日账"） |
| ADS | 应用层（直接喂大屏/报表的结果表） |
| GMV | 商品交易总额（电商最核心的指标） |
| Cross-engine Recheck | 双引擎对拍（同一口径两引擎算，答案必须一致） |
| Metric Caliber | 指标口径（"算什么"的定义，比"怎么算"更重要） |

## 3. 动手实操：两层汇总 + 对拍仪式

```scala
// spark-shell（继续昨天的会话或重开，务必带 Hive 支持）
// ===== 上半场：DWS 两张宽表（对照 M10 day26 的同名逻辑）=====
val dwd = spark.table("shop.dwd_order_detail_di").where($"dt" === "2026-09-25")
// DWS-1：用户日汇总
dwd.groupBy($"uid").agg(
  count("*").as("order_cnt"),
  sum($"amount").as("order_amt"))
  .write.mode("overwrite").partitionBy("dt")
  .saveAsTable("shop.dws_user_order_1d_spark")
// DWS-2：类目日汇总
dwd.groupBy($"category").agg(
  count("*").as("order_cnt"),
  sum($"amount").as("order_amt"))
  .write.mode("overwrite").partitionBy("dt")
  .saveAsTable("shop.dws_cat_order_1d_spark")

// ===== 下半场：ADS GMV（与 M10 ads_gmv_daily 同口径）=====
dwd.agg(sum($"amount").as("gmv"), count("*").as("order_cnt"))
  .write.mode("overwrite").partitionBy("dt")
  .saveAsTable("shop.ads_gmv_daily_spark")

// ===== 对拍仪式（本月最重要的 10 行代码）=====
spark.sql("""
  SELECT 'hive_engine' src, gmv, order_cnt FROM shop.ads_gmv_daily WHERE dt='2026-09-25'
  UNION ALL
  SELECT 'spark_engine' src, gmv, order_cnt FROM shop.ads_gmv_daily_spark WHERE dt='2026-09-25'
""").show()
// 预期：两行数字【完全一致】（M10 算的是 12 单 / 对应金额）
// 再加一层 DWD 直算交叉验证（第三本账）：
spark.sql("SELECT sum(amount), count(*) FROM shop.dwd_order_detail_di WHERE dt='2026-09-25'").show()
// —— 三本账一致（Hive ADS = Spark ADS = DWD 直算）→ 对拍通过，截图存证
```

```powershell
# 收尾：Hive 侧独立验证（换引擎查 Spark 写的表——元数据共享的终极证明）
docker exec -it hive-server beeline -u "jdbc:hive2://localhost:10000" `
  -e "SELECT * FROM shop.ads_gmv_daily_spark WHERE dt='2026-09-25';"
# —— Hive 能直接读 Spark 写的 Parquet 表：账本共享的实战证据
```

## 4. 面试连接

**Q：你怎么保证数仓指标的准确性？（考工程方法论，本题是最佳素材）**
> 我的答案是一套"三道闸"：第一道闸是口径先行——算之前把指标定义写成文档（GMV=成功订单金额合计，含退款与否要说死），口径不定义，算得再快都是错的；第二道闸是分层对账——每层都有对账 SQL，ODS 原始数→DWD 清洗数（差值=过滤+去重的笔数，一笔都能说清）→DWS 汇总数（SUM 对得上明细 COUNT）→ADS 结果数，数字沿链路传递且每站可验证；第三道闸是交叉验证——同一口径用两个引擎各算一遍（Hive 和 Spark），或者结果表和明细直算对拍，两本账一致才放行。我项目里做过实战：M10 用 Hive 算的 GMV 和 M11 用 Spark 重算的完全一致，还做了 DWD 直算的第三本账交叉验证。这套方法的思想来自更早的分布式训练（M8 幂等+对账）：分布式系统里"没验证过的一致"不叫一致。

**Q：数仓为什么分 ODS/DWD/DWS/ADS 这么多层？不能一竿子捅到底吗？（回到根本的追问）**
> 短期看一竿子捅到底确实快，但三个账长期算下来分层更便宜：复用——明细洗一次，所有下游共用（DWD 是公共资产），一竿子捅到底=每个需求各自洗一遍，脏活重复干；治理——脏行隔离在 ODS→DWD 一站处理掉，越早拦截成本越低（错误数据漏到 ADS，大屏挂出来就是事故）；演进——引擎从 Hive 换 Spark（我这两天的实战）、存储从 TEXTFILE 换 Parquet，只换中间层实现，上下游接口（表结构）不动。一句话：分层是把"数据加工"标准化成流水线，每站有质检（对账）、有接口（元数据），这才撑得起多人协作和多引擎共存。

## 5. 今日验收清单

- [ ] dws 两张+ads 一张表建成（SHOW TABLES 截图）
- [ ] 三本账对拍一致（Hive ADS=Spark ADS=DWD 直算，截图存证）
- [ ] Hive 能读 Spark 写的表（元数据共享终极证明）
- [ ] "三道闸"方法论能脱稿讲
- [ ] 分层价值的三个账（复用/治理/演进）能展开
- [ ] `git add . && git commit -m "day11-26: proj2-aggregate"`

---
[← Day 25](day25-项目一接入与明细.md) | [本月目录](README.md) | [Day 27 · 项目三 · 压测与调优 →](day27-项目三压测与调优.md)
