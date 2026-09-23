# 项目三：实时数据分析平台（Realtime Analytics Platform）

> **项目目标**：搭建一条"业务库变更 + 用户埋点 → Kafka → Flink 实时计算 → ClickHouse/Redis → Grafana 实时大屏"的完整实时数据链路，掌握 Flink 核心机制并交付可演示的实时数仓。
> **预计周期**：6-8 周（配合学习阶段四）
> **最终成果**：实时大屏（GMV/UV/TopN/转化漏斗）+ Checkpoint 恢复演练记录 + 架构复盘文档

## 业务场景

电商运营需要一个实时作战大屏（大促指挥室标配）：
- **GMV 实时曲线**（Gross Merchandise Volume，商品交易总额）：秒级更新
- **分城市交易额 TopN** 与 **热销商品 TopN**
- **实时 UV**（Unique Visitor，独立访客）与**下单转化漏斗**
- 数据**不丢不重**：kill 进程恢复后数字依然正确

## 数据流架构

```
┌────────────┐   Flink CDC    ┌─────────────┐
│ MySQL 业务库 │ ────────────→ │             │
└────────────┘                │    Kafka    │
┌────────────┐    SDK 埋点     │ (dwd 层Topic)│
│ 前端/APP 埋点 │ ────────────→ │             │
└────────────┘                └──────┬──────┘
                                     │ Flink SQL / DataStream
                        ┌────────────┴────────────┐
                 实时明细 DWD            实时汇总 DWS（窗口聚合/去重/TopN）
                        │                        │
                  ClickHouse(明细)         Redis(指标) + ClickHouse
                        └──────────┬───────────┘
                             Grafana 实时大屏 + 告警
```

## 与传统后端开发的思维差异（先建立认知）

| 后端思维 | 数据思维 |
|---------|---------|
| 数据是"当前状态"（update） | 数据是"事件流"（append + 重放） |
| 一次请求一次响应 | 7×24 不停处理，进程永远不"重启完成" |
| 一致性靠事务 | 一致性靠 Checkpoint + 幂等写 |
| 调优看 RT | 调优看吞吐、反压、Checkpoint 时长 |

## 分步教程

| Step | 文件 | 内容 | 完成标志 |
|------|------|------|---------|
| 1 | `step01-埋点采集与Kafka接入.md` | 埋点设计、Flink CDC 同步业务库、Kafka 主题规划 | 两路数据实时进 Kafka |
| 2 | `step02-Flink实时计算.md` | Flink SQL 窗口聚合、TopN、UV 去重、维表关联、Checkpoint | DWD/DWS 两层算好 |
| 3 | `step03-存储与大屏.md` | ClickHouse 建表写入、Redis 指标、Grafana 大屏、告警与演练 | 大屏可演示 + 演练记录 |

## 面试价值点

1. Flink 怎么做到 Exactly-Once？（Checkpoint + 两阶段提交/幂等写）
2. Watermark 解决什么问题？迟到数据去哪了？（乱序 + 侧输出）
3. UV 精确去重为什么贵？你怎么权衡的？（Set vs HyperLogLog vs BitMap）
4. 数据积压了怎么办？（反压定位 + 扩并行度 + 分区扩容）
5. 实时数仓和离线数仓怎么配合？（Lambda/Kappa 与实离一致性核对）
