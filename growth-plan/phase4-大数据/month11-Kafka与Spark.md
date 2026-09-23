# 第 11 月：Kafka（大数据视角）与 Spark（Apache Spark 批处理引擎）

> **本月一句话目标**：把 Kafka 从"会收发消息"升级为"懂分区副本与语义"，并系统掌握 Spark 核心（RDD/DAG/Shuffle/内存管理）+ Spark SQL + Structured Streaming，能完成离线批处理作业调优与数据倾斜治理。**详细教程配合阅读 `big-data/04~05`。**

## 本月目标与验收标准

- [ ] 讲清 Kafka 分区（Partition）与副本机制、消费者组（Consumer Group）与 Rebalance（重平衡）
- [ ] 讲清 Spark RDD（Resilient Distributed Dataset 弹性分布式数据集）、DAG（Directed Acyclic Graph 有向无环图）、宽窄依赖（Wide/Narrow Dependency）
- [ ] 讲清 Spark Shuffle 三代演进（Hash→Sort→Tungsten）与内存管理（Unified Memory Model 统一内存模型）
- [ ] 掌握 Spark SQL（Catalyst 优化器、AQE 自适应查询执行）与 3 种数据倾斜治理实操
- [ ] 完成"日活跃用户分析"批处理项目（含调度与数据质量校验）
- [ ] 产出 1 篇博客

---

## 每日计划

### 第 1 周：Kafka 深度（大数据视角）

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 分区与并行度 | Partition 数与消费者数关系（消费者数 > 分区数则空闲）、分区数规划（吞吐倒推法）、Key 路由与粘性分区（Sticky Partitioner 批次优化） | 规划一个日志 Topic 的分区数并验证 | 规划过程有计算依据 |
| D2 | 副本与选举 | Replica（副本：Leader/Follower）、ISR 收缩与扩张（replica.lag.time.max.ms）、Controller 选举（基于 Raft 的 KRaft 模式 vs 旧 ZK 模式）、Preferred Leader（优先副本均衡） | 观察 ISR 变化与 Leader 均衡 | 能讲清 Leader 切换时数据风险点 |
| D3 | Rebalance 深度 | 触发条件（成员变化/订阅变化）、分配策略（Range/RoundRobin/Sticky/CooperativeSticky 协作式）、Rebalance 风暴与停止-the-world 问题、静态成员（group.instance.id） | 制造频繁 Rebalance 并用 Cooperative 策略优化 | 能讲清为何 Cooperative 减少停止 |
| D4 | 位移与语义 | Offset 提交（自动/手动 commitSync/commitAsync）、__consumer_offsets 内部主题、三种语义（At-Most-Once 至多一次/At-Least-Once 至少一次/Exactly-Once） | 实现手动提交 + 处理重复消费 | 三种语义的配置组合能写对 |
| D5 | Kafka Streams 概览 | 流处理库（Streams：KStream/KTable）、窗口（滚动/滑动/会话 Session Window）、与 Flink 的定位差异 | 用 Streams 做一个实时 WordCount | 能对比 Streams vs Flink 选型 |
| D6 | Kafka 运维要点 | 镜像与压缩（LZ4/ZSTD）、消息保留（Retention：时间/大小/Compact 压实）、磁盘与页缓存（Page Cache 依赖：为什么 Kafka 顺序写+零拷贝快）、优雅扩容（分区重分配 Reassignment） | 完成一次分区扩容迁移演练 | 迁移进度与数据均衡可观测 |
| D7 | 周复盘 | Kafka 串讲 | 周记 + 《Kafka 生产治理清单》（分区/副本/保留/监控） | 达成周验收 |

### 第 2 周：Spark Core 核心

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | Spark 架构与部署 | Driver（驱动器）/Executor（执行器）、Cluster Manager（Standalone/YARN/K8s）、Deploy Mode（Client vs Cluster）；PySpark vs Scala API（Java 工程师建议 Scala 或 Java API） | 本机部署 Spark on YARN 跑通 pi 例子 | 能讲清 Driver 职责与单点问题 |
| D2 | RDD 核心概念 | RDD 五大特性（分区列表/依赖计算/分区器/首选位置）、转换算子（Transformation 惰性 Lazy）vs 行动算子（Action 触发 Job）、血缘（Lineage）与容错（重新计算） | 写 RDD 词频统计并观察 Job/DAG 视图 | 能讲清一个 Job 的 DAG 切分 |
| D3 | 宽窄依赖与 Stage | 窄依赖（Narrow：map/filter，流水线 Pipeline）、宽依赖（Wide/Shuffle Dependency，切分 Stage 边界）、Stage 任务并行度（Task 数 = 末端分区数） | 画出 5 个算子链的 Stage 划分图 | Stage 划分 100% 准确 |
| D4 | 持久化与共享变量 | cache/persist 级别（MEMORY_ONLY/MEMORY_AND_DISK 序列化）、checkpoint（截断血缘）、广播变量（Broadcast：减少 Shuffle 的 Join 优化）、累加器（Accumulator） | 用广播 Join 替换大表 Join 小表 | 对比性能差异 |
| D5 | Shuffle 机制 | Shuffle Write/Read、Bypass 机制（小分区免排序）、SortShuffle（数据按目标分区排序）、Tungsten 内存管理（Unsafe 内存+缓存友好的序列化 Serialized）、push-based shuffle（3.2+） | 观察不同分区数下的 Shuffle 文件 | 能画出 SortShuffle 数据流 |
| D6 | 内存与资源 | 统一内存模型（Execution 执行内存 + Storage 存储内存可互借）、spark.memory.fraction、Executor 资源参数（cores/memory/overhead 堆外）、动态分配（Dynamic Allocation） | 调优一组 Executor 参数并压测 | 参数选择有数据支撑 |
| D7 | 周复盘 | Spark Core 串讲 | 周记 + 《Spark 作业调优 Checklist v1》 | 达成周验收 |

### 第 3 周：Spark SQL 与调优

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | DataFrame 与执行流程 | DataFrame/Dataset（编译期类型安全）、执行流程（未解析逻辑计划 Unresolved→解析→优化 Optimized→物理计划 Physical→执行）、SparkSession | 用 EXPLAIN 观察 SQL 执行计划 | 能读懂物理计划关键算子 |
| D2 | Catalyst 优化器 | 规则优化（谓词下推 Predicate Pushdown/列裁剪 Column Pruning/常量折叠 Constant Folding）、RBO（基于规则 Rule-Based）vs CBO（基于成本 Cost-Based：统计信息） | 验证谓词下推与列裁剪生效 | 每个优化点能用 EXPLAIN 证明 |
| D3 | AQE 自适应执行 | AQE（Adaptive Query Execution，3.0+）：动态合并小分区（Coalesce）、动态切换 Join 策略（SMJ→BHJ）、动态处理倾斜（Skew Join 拆分） | 开关 AQE 对比一条倾斜 SQL 耗时 | 三种 AQE 能力各验证一次 |
| D4 | Join 策略全景 | Broadcast Hash Join（广播哈希）/Shuffle Hash Join/Shuffle Sort Merge Join（SMJ 排序归并）/Cartesian；Join 策略选择与阈值（spark.sql.autoBroadcastJoinThreshold） | 四种 Join 各构造场景并观察计划 | 能按表大小自动推演策略 |
| D5 | Spark 数据倾斜治理 | 治理手段：过滤异常 Key、加盐两阶段聚合、广播小表、AQE Skew Join、提高并行度；与 Hive 治理手段对照 | 治理 3 个倾斜 SQL 并记录 | 治理前后耗时对比 ≥ 3 倍 |
| D6 | Spark 与 Hive 整合 | Spark on Hive（借用 Metastore）、Hive on Spark vs Spark on Hive、数据格式兼容（ORC/Parquet）、小文件合并写入（repartition/coalesce 写出） | 用 Spark 重写月10 的 DWS 任务 | 与 Hive 任务结果一致且更快 |
| D7 | 周复盘 | Spark SQL 串讲 | 博客：《Spark AQE：一个参数解决数据倾斜的原理与实践》发布 | 博客含实测数据 |

### 第 4 周：批处理项目实战 + Structured Streaming 预热

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 项目设计 | 日活分析项目：埋点数据（Kafka 模拟）→ 落 HDFS（分区目录）→ Spark SQL 清洗 → DWD/DWS → ADS 报表；指标定义（DAU/留存 Retention/转化漏斗 Funnel） | 完成项目架构文档 | 指标口径（口径 = Metric Definition 统计规则）明确 |
| D2 | 数据接入层 | Kafka→HDFS 落地方案（Flume/自研 Consumer/Structured Streaming sink）、小文件预防（滚动写入滚动间隔） | 完成接入层 | 数据按时分区落盘 |
| D3 | 计算层开发 | 清洗规则（去重/补全/格式校验）、维度关联（Broadcast Join 维表）、窗口聚合（窗口函数算留存） | 完成 DWD→DWS→ADS 三个任务 | 结果与手工 SQL 对拍一致 |
| D4 | 调度与质量 | DolphinScheduler 编排 + 依赖配置、DQC 校验节点（行数波动率/主键唯一）、失败告警 | 完成调度链路 | 断链自动重试 + 告警触达 |
| D5 | 性能压测与调优 | 制造 1 亿行数据（生成器）、定位瓶颈（Spark UI：Stage 耗时/Shuffle 量/GC）、至少 3 项优化 | 完成压测报告 | 有优化前后数据表 |
| D6 | Structured Streaming 预热 | 微批（Micro-Batch）模型、Trigger（触发器）、输出模式（Append/Update/Complete）、与 Flink 对比初识（为下月铺垫） | 写一个 Kafka→控制台的流式词频统计 | 理解微批与逐事件的差异 |
| D7 | 月度总复盘 | Kafka+Spark 串讲 | 月记 + 《Spark 生产调优手册 v1》 | 达成本月全部验收标准 |

---

## 本月产出清单

1. 《Kafka 生产治理清单》《Spark 作业调优 Checklist》《Spark 生产调优手册》
2. 数据倾斜 3 例治理记录（含 AQE 实测）
3. 日活分析项目（接入→计算→调度→质量全链路）
4. 博客 1 篇（AQE 实践）

## 本月术语表

| 英文 | 中文 |
|------|------|
| RDD (Resilient Distributed Dataset) | 弹性分布式数据集 |
| DAG (Directed Acyclic Graph) | 有向无环图（Job 的执行计划） |
| Narrow / Wide Dependency | 窄依赖 / 宽依赖（是否触发 Shuffle） |
| Tungsten | 钨丝计划（Spark 内存与 CPU 效率优化工程） |
| Catalyst | Catalyst 优化器（SQL 逻辑计划优化框架） |
| AQE (Adaptive Query Execution) | 自适应查询执行 |
| BHJ / SMJ | 广播哈希连接 / 排序归并连接 |
| Predicate Pushdown | 谓词下推（过滤提前减少数据量） |
| Micro-Batch | 微批（把流切成小批次处理） |
| Retention / Compaction | 保留策略 / 日志压实（Compact 保留每 Key 最新） |

> 下月：`../month12-Flink与实时数仓.md`——真正的流处理之王 Flink，完成实时数仓项目。
