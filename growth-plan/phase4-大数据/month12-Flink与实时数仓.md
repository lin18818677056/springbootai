# 第 12 月：Flink（Apache Flink 流处理引擎）与实时数仓

> **本月一句话目标**：掌握流处理核心理论（时间语义、状态、一致性快照）与 Flink 全套实战（Watermark/Window/State/Checkpoint/两阶段提交/Flink CDC），完成实时数仓项目并接入 Grafana 大屏。本阶段（阶段四）收官。**详细教程配合阅读 `big-data/06`。**

## 本月目标与验收标准

- [ ] 讲清 Event Time（事件时间）/ Processing Time（处理时间）与 Watermark（水位线）机制
- [ ] 讲清 Checkpoint（检查点：Chandy-Lamport 分布式快照变体）与 Exactly-Once 端到端实现
- [ ] 掌握 State（状态）管理：Keyed State/Operator State、RocksDB StateBackend（状态后端）、状态过期与 TTL（Time To Live）
- [ ] 掌握 Window（窗口）/CEP（Complex Event Processing 复杂事件处理）入门
- [ ] 用 Flink CDC 实现 MySQL→实时链路的免开发同步
- [ ] 实时数仓项目上线：Kafka→Flink→ClickHouse/Redis→Grafana 大屏

---

## 每日计划

### 第 1 周：流处理理论与 Flink 基础

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 流与批的统一 | 流处理 vs 批处理（无限数据集 Unbounded vs 有界 Bounded）、Lambda 架构（批流双链路）与 Kappa 架构（Kappa：一切皆流，重放即可重算）；为什么业界转向流批一体 | 画出 Lambda vs Kappa 对比图 | 能讲清 Kappa 的重放（Replay）依赖什么 |
| D2 | Flink 架构 | JobManager（作业管理器：JobMaster/Dispatcher/ResourceManager）与 TaskManager（任务管理器）、TaskSlot（任务槽：资源隔离单位）、并行度（Parallelism）与算子链（Operator Chaining 优化） | Docker 部署 Flink 集群跑通 WordCount | 能讲清 Slot 与并行度的关系 |
| D3 | 时间语义 | Event Time/Processing Time/Ingestion Time（摄入时间）；为什么乱序（Out-of-Order）必须用事件时间；Window 触发与延迟数据 | 构造乱序流观察不同时间语义结果 | 能讲清"乱序 5 秒"业务上意味着什么 |
| D4 | Watermark 机制 | Watermark（水位线：时间进展的承诺，wm = 最大事件时间 - 允许乱序度）、传递与取最小、周期性生成（200ms）、迟到数据处理（Allowed Lateness + 侧输出流 Side Output） | 实现带 Watermark 的窗口统计 + 侧输出收集迟到数据 | 迟到数据不丢、进了旁路 |
| D5 | Window 窗口全景 | 滚动窗口（Tumbling）/滑动窗口（Sliding）/会话窗口（Session：动态间隔）/全局窗口；增量聚合（ReduceFunction/AggregateFunction）vs 全量（ProcessWindowFunction） | 四种窗口各写一例 | 能按业务选择窗口类型 |
| D6 | State 状态管理 | Keyed State（ValueState/ListState/MapState/ReducingState）、Operator State（列表状态，用于扩缩容重分配）、状态可见性与清理、TTL（状态过期策略） | 用 MapState 实现去重统计 | 能讲清状态膨胀（State Bloat）治理 |
| D7 | 周复盘 | 流理论+Flink 基础串讲 | 周记 + 《Watermark 推导练习 10 题》 | 练习全对 |

### 第 2 周：一致性、容错与 Exactly-Once

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | Checkpoint 原理 | 分布式快照：Barrier（屏障）随流传播、对齐（Alignment）/非对齐（Unaligned Checkpoint，反压时用）、快照内容（状态 + 位移）、与 Savepoint（保存点：手工触发，用于升级迁移）区别 | 手动触发 Savepoint 并从 Savepoint 恢复 | 能画出 Barrier 对齐过程图 |
| D2 | 状态后端 | StateBackend 演进：HashMapStateBackend（内存/JM 堆）vs EmbeddedRocksDBStateBackend（RocksDB 嵌入式 LSM 存储：大状态、增量快照 Incremental Checkpoint）；配置选型 | 两种后端压测对比（状态 1GB 场景） | 有吞吐/恢复时长对比数据 |
| D3 | 端到端 Exactly-Once | 内部一致（Checkpoint 保证）vs 端到端一致（Source 可重放 + Sink 幂等/事务）；两阶段提交（2PC：TwoPhaseCommitSinkFunction，Kafka 事务）；幂等写（Upsert） | 实现 Kafka→Kafka 事务链路 + Kafka→DB 幂等链路 | kill -9 恢复后 0 丢 0 重 |
| D4 | 容错与恢复 | 作业重启策略（Fixed Delay/Exponential Delay/Failure Rate）、恢复时长优化（本地恢复 Local Recovery）、反压（Backpressure）识别（Web UI 反压状态/inPoolUsage）与治理（数据倾斜/资源不足/外部瓶颈） | 制造反压并用 UI 三板斧定位 | 反压定位路径可复述 |
| D5 | Flink SQL 入门 | Table API/SQL、动态表（Dynamic Table）与流表转换（toChangelogStream）、Retract（回撤流）/Upsert 流、Temporal Join（时态表 Join：维表版本关联） | 用 Flink SQL 重写水位线窗口作业 | 理解流上 SQL 的语义（更新回撤） |
| D6 | 维表关联实战 | 维表 Join 三方案：同步广播（小维表）、异步 IO（Async I/O + 缓存）、外存点查（Redis/Lettuce）；一致性权衡 | 实现订单流关联商品维表（异步 IO + 本地缓存） | 缓存命中率 > 90% |
| D7 | 周复盘 | 一致性专题串讲 | 周记 + 博客素材：《一张图讲透 Flink Checkpoint》 | 达成周验收 |

### 第 3 周：Flink CDC 与实时链路组件

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | CDC 概念 | CDC（Change Data Capture 变更数据捕获）：查询式 vs 日志式（Binlog）、Canal vs Debezium vs Flink CDC 对比 | 阅读 `big-data/06` CDC 章节 | 三者定位差异讲得清 |
| D2 | Flink CDC 实战 | Flink CDC Connector（MySQL Source：全量+增量一体化、无锁读取 Lock-Free、断点续传 Checkpoint 对齐） | 实现 MySQL→Kafka 实时同步（含 Schema 变更） | 增删改实时可达下游 |
| D3 | OLAP 引擎选型 | ClickHouse（列存 + 向量化执行 Vectorized Execution，极致单表查询）、Doris（Join 友好、运维简单）、Druid/Presto 定位；OLAP vs OLTP（联机分析处理 vs 联机事务处理） | Docker 部署 ClickHouse + Doris（选一深入） | 选型文档有对比矩阵 |
| D4 | ClickHouse 核心 | MergeTree（合并树引擎家族）、分区与主键稀疏索引（Primary Key Sparse Index）、物化视图（Materialized View）、常见坑（频繁小写入/Alter 代价） | 建表写入 1 亿行并做典型查询 | 单表聚合查询秒级内 |
| D5 | Redis 在实时数仓的角色 | 实时指标存储（Hash/ZSet）、大屏数据源、过期与更新策略；与 ClickHouse 分工（明细查询 vs 指标展示） | 设计大屏指标存储方案 | 每个指标有选型理由 |
| D6 | 数据管道与治理 | Schema 管理（Schema Registry 模式注册中心思想）、字段演进兼容策略（向前/向后兼容）、实时任务监控（Flink UI + Prometheus + Grafana） | 搭 Flink 监控大盘 | 吞吐/延迟/反压/Checkpoint 四类指标可见 |
| D7 | 周复盘 | 实时链路组件串讲 | 周记 + 《实时链路选型手册》 | 达成周验收 |

### 第 4 周：实时数仓项目 + 阶段毕业

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 项目架构 | 实时数仓项目：MySQL 业务库 + 埋点日志 → Flink CDC/Kafka → Flink 计算（清洗/宽表/聚合）→ ClickHouse + Redis → Grafana 大屏 | 完成 `practice-projects/03-realtime-analytics/` 架构设计 | 架构图 + 指标清单完成 |
| D2 | 实时明细层 | Flink SQL 构建 DWD：埋点解析（JSON→行）、维度关联（异步 IO）、脏数据侧输出 | 完成 DWD 层 | 明细可查、脏数据可回收 |
| D3 | 实时汇总层 | DWS：分钟级滚动窗口交易额（分城市）、TopN 商品（窗口 + 状态）、UV 去重（HyperLogLog 近似 vs 精确 Set 权衡） | 完成 DWS 层 | 大屏数字与明细聚合对拍一致 |
| D4 | 大屏与告警 | Grafana 配置（数据源 ClickHouse/Redis）、大屏布局（GMV/曲线/TopN/漏斗）、指标告警（异动检测：环比超阈值） | 完成大屏 + 3 条告警 | 大屏可演示、告警可触达 |
| D5 | 可靠性演练 | 杀进程恢复（Checkpoint 恢复，验证 0 丢失）、Kafka 扩分区、ClickHouse 写入压力、反压治理 | 完成 3 项演练并记录 | 恢复后数据一致 |
| D6 | 成本与演进 | 实时链路成本评估（资源/存储/开发维护）、Lambda→Kappa 迁移思考、与离线数仓的一致性核对（实离一致性对比 Job） | 输出项目复盘文档 | 复盘含 3 条可落地改进 |
| D7 | 月度+阶段总复盘 | 大数据阶段全回顾 | 博客：《从 0 到 1 搭建实时数仓：Java 后端的实践笔记》发布；整理《数据架构能力自评表》 | 达成里程碑 M4 |

---

## 本月产出清单

1. 《Watermark 推导练习》+ Checkpoint 图解博客
2. 《实时链路选型手册》（CDC/OLAP/维表 Join 三选型）
3. 实时数仓项目（DWD/DWS/大屏/告警/演练记录）
4. 博客 1 篇 + 阶段四毕业自评表

## 本月术语表

| 英文 | 中文 |
|------|------|
| Event Time / Processing Time | 事件时间 / 处理时间 |
| Watermark | 水位线（事件时间进展的度量，触发窗口计算） |
| Barrier | 屏障（Checkpoint 的流内标记） |
| State Backend | 状态后端（状态的存储实现） |
| Savepoint | 保存点（手动一致性快照，用于升级迁移） |
| Backpressure | 反压（下游处理不及导致的背压传播） |
| Operator Chaining | 算子链（同 Slot 线程内合并执行减少序列化） |
| CDC (Change Data Capture) | 变更数据捕获 |
| OLAP / OLTP | 联机分析处理 / 联机事务处理 |
| Materialized View | 物化视图（预计算并存储的查询结果） |
| Retract Stream | 回撤流（先撤旧值再插新值的更新流） |
| HyperLogLog | 基数估计算法（UV 近似统计，误差 ~0.8%） |

## 阶段四毕业检查（对照 README 里程碑 M4）

- [ ] 实时数仓项目完整可演示，大屏数字与明细对拍一致
- [ ] Hive 数据倾斜 5 种治理方案（加盐/空值/MapJoin/两阶段/拆分）能结合实测数据讲清
- [ ] 能向 P8 面试官讲清"实时数仓 vs 离线数仓"的架构与成本权衡

> 下一阶段：`../phase5-AI工程化/`——进入 AI 领域，教程详解在 `../../ai-learning/` 目录。
