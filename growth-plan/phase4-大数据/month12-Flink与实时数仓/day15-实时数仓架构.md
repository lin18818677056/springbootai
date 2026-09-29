# Day 15 · 实时数仓架构：分层照旧，水管常开

> **今日目标**：项目周第 1 天——先画图纸再施工。实时数仓的分层（ods/dwd/dws/ads 主题化）和 M10/M11 的离线数仓同名同义，但链路换成"常开的水管"；设计本月项目的端到端架构（Kafka→Flink→Kafka/MySQL→大屏）；明确"实离一致"的质量策略。
> **时长**：架构设计 2h / 与离线数仓的异同 1.5h / 图纸输出 1.5h
> **今日产出**：项目端到端架构图 + 《实时 vs 离线数仓对照表》+ 项目任务分解表

## 1. 知识地图（先讲人话：同一张地图，换条路走）

```
分层思想完全照旧（M10 学的四层在实时世界全部适用）：
  ODS 原始层：Kafka 原始主题（app_event 原样保存，M11 已有！）
  DWD 明细层：清洗后的明细流（过滤/去重/格式化 → dwd_order_rt 主题）
  DWS 汇总层：按维度预聚合（分钟 GMV/小时 UV → dws_gmv_1min）
  ADS 应用层：直接喂大屏的结果（gmv_realtime 表 → MySQL → 前端）

链路对照（架构图主体）：
  离线（M10/M11）：Kafka → 落地 HDFS → ODS 表 → Spark 批算 → ADS 表
    —— T+1，但精确（全量重算+对拍兜底）
  实时（M12）：Kafka → Flink 常驻作业 → DWD/DWS 流 → ADS 表(MySQL)
    —— 秒级，但要用水位线/语义/监控守住正确性
  —— 两条链路【并行共存】：实时保新鲜，离线保精确
     —— 这就是"Lambda 双链路"的朴素版（day26 综合项目正式合体）

实离一致（实时数仓的质量哲学，day19 的铺路）：
  实时算的 GMV 和离线算的 GMV 每天核对，差异超阈值告警
  —— 实时链路必有误差源（乱序丢弃/水位线边界/重复），承认并量化它
  —— 离线是"最终真相"，实时是"最好新鲜的近似"
  —— 大屏产品话术：实时看趋势，T+1 看结算

项目任务分解（day16~19 四天）：
  day16 项目一：Kafka 埋点 → Flink 清洗 → DWD 明细流（回 Kafka）
  day17 项目二：DWD 流 → 分钟 GMV 聚合 → ADS 表（MySQL/print 演示）
  day18 项目三：DWD 流 → TopN 热销榜（持续更新的大屏榜）
  day19 实离对拍：实时 GMV vs M11 离线 GMV 三本账核对
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| RT（Realtime） | 实时 | 秒级出数（主题名后缀 _rt 的约定） |
| Lambda Architecture | Lambda 架构 | 实时+离线双链路并行，离线兜底修正 |
| Kappa Architecture | Kappa 架构 | 只留实时链路，重算靠 Kafka 回放（理想态） |
| Stream-Batch 融合 | 流批一体 | 一套代码两种跑法（Flink 的愿景，day25 讨论） |
| 实离对拍 | 实离核对 | 实时结果 vs 离线结果每日核对，差异告警 |
| Data Freshness | 数据新鲜度 | 大屏数据的"年龄"（SLA：分钟级） |

## 3. 动手实操：画图纸 + 环境盘点

```
项目架构图（手绘一遍，day28 大串讲复用）：

 业务埋点
    │
    ▼
 Kafka: app_event（ODS，M11 已建，3 分区）
    │
    ▼ 作业①（day16）：清洗/去重/打标
 Kafka: dwd_order_rt（DWD 明细流，新建）
    │
    ├──▼ 作业②（day17）：1 分钟滚动窗口聚合
    │      └→ ads_gmv_realtime（MySQL 表，upsert）
    │
    └──▼ 作业③（day18）：TopN 先聚再排
           └→ item_topn_realtime（MySQL 表，持续更新）
 ........................
 离线链路（M11 已建）：落地 HDFS → Spark → shop.ads_gmv_daily
           │
           └───── day19：实离对拍（实时账 vs 离线账）

环境盘点清单（开工前过一遍）：
□ flink-jm/flink-tm 存活（docker ps）
□ kafka 主题 app_event 有历史数据（M11 造的 12 条埋点）
□ 降级路径确认：无 kafka connector → datagen 模拟（day03 已演练）
□ 《Checklist v1》（day14）打印在手边
```

```sql
-- 预热实验：确认 Flink SQL 端到端姿势（datagen→窗口→print 的"迷你链路"）
-- （这就是项目作业②的降级版骨架，day17 会换成真 Kafka 源）
CREATE TABLE mini_src (
  item_id INT, amt DOUBLE,
  et AS CURRENT_TIMESTAMP,
  WATERMARK FOR et AS et - INTERVAL '3' SECOND
) WITH (
  'connector' = 'datagen', 'rows-per-second' = '5',
  'fields.item_id.kind' = 'random', 'fields.item_id.min' = '1', 'fields.item_id.max' = '5',
  'fields.amt.min' = '10', 'fields.amt.max' = '500'
);
SELECT window_start, window_end, SUM(amt) gmv
FROM TABLE(TUMBLE(TABLE mini_src, DESCRIPTOR(et), INTERVAL '30' SECOND))
GROUP BY window_start, window_end;
-- 跑通即图纸验证完毕（截图存档）
```

## 4. 面试连接

**Q：设计一个实时数仓，你的架构是什么？（系统设计题，本月主答案）**
> 我按四层+双链路设计。分层沿用离线数仓的语义：ODS 是 Kafka 原始主题（埋点原样保存，保留期足够回放重算）；DWD 是清洗后的明细流（Flink 作业做过滤/去重/格式化，回写 Kafka 的 dwd 主题——分层之间用 Kafka 解耦，每层可独立扩容重算）；DWS 是按维度预聚合的窗口结果（分钟 GMV/UV，upsert 格式）；ADS 是喂应用的最终表（MySQL，大屏直连）。旁边并行一条离线链路（M11 建过：落地 HDFS→Spark→ADS 表），两链路做实离对拍——实时保新鲜、离线保精确，差异超阈值告警。每层的可靠性设计：Source 可重放靠 Kafka offset 入快照，计算一致靠 Checkpoint，输出幂等靠 MySQL 主键 upsert；监控三件套是 Lag、Checkpoint 时长、大屏延迟。这个架构我在本地容器环境完整搭过（四天项目：明细流→实时 GMV→TopN→实离对拍），单机演示版和生产版的差别主要在集群规模和多 topic 的容量规划，链路结构一致。如果面试官追问 Lambda vs Kappa：小团队维护两套链路成本高，Kappa（只留实时+回放重算）是方向，但"离线兜底"在企业落地的第一阶段几乎是必选项——我会先双链路跑稳，再逐步把离线层的职责迁给回放。

**Q：实时数仓和离线数仓最大的三个不同？（基础概念题，快速答）**
> 一是时间模型：离线按天分区、T+1 重算，实时按事件时间+水位线持续出数；二是正确性策略：离线靠全量重算+对拍，天然精确，实时靠语义三段拼图+实离对拍兜底，是"新鲜的近似"；三是运维形态：离线作业跑完即走，实时作业 7×24 常驻——所以实时多出一整套"养作业"的活：Checkpoint、状态 TTL、反压监控、Savepoint 升级。共同点是分层思想和方法论（口径先行、逐层对账）完全一致——数仓的"道"不变，变的只是"术"。

## 5. 今日验收清单

- [ ] 端到端架构图手绘（含两条链路+对拍点）
- [ ] 《实时 vs 离线对照表》成文（三个不同+一个相同）
- [ ] 迷你链路预热实验跑通
- [ ] Lambda/Kappa 的取舍观点能讲
- [ ] 项目四天任务分解表确认
- [ ] `git add . && git commit -m "day12-15: rt-arch"`

---
[← Day 14](day14-第二周复盘.md) | [本月目录](README.md) | [Day 16 · 项目一 · 明细流 →](day16-项目一明细流.md)
