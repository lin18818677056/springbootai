# 第 10 月：Hadoop（HDFS + YARN）与 Hive 数据仓库

> **本月一句话目标**：以 Java 工程师视角切入大数据：搞懂分布式存储（HDFS）与资源调度（YARN）原理，掌握 Hive（基于 Hadoop 的数据仓库工具）建模与调优，完成数仓分层实战。**详细教程请配合阅读 `big-data/01~03` 三篇文档。**

## 本月目标与验收标准

- [ ] 讲清 HDFS 架构（NameNode/DataNode、Block 块、副本策略、机架感知 Rack Awareness）与读写流程
- [ ] 讲清 YARN（Yet Another Resource Negotiator）架构与调度器对比（FIFO/Capacity/Fair）
- [ ] 掌握 Hive：内外部表、分区（Partition）分桶（Bucket）、HQL 转 MapReduce/Tez 流程
- [ ] 数仓建模：数仓分层（ODS/DWD/DWS/ADS）、维度建模（星型/雪花模型）
- [ ] 独立排查并解决 3 种数据倾斜（Data Skew）场景
- [ ] 完成电商行为数仓 ODS→DWD→DWS→ADS 全链路建设（Docker 单机版）

---

## 每日计划

### 第 1 周：Hadoop 生态总览与 HDFS

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 大数据全景 | 为什么后端要懂大数据（数据驱动业务/大表治理/实时风控）；技术版图：存储（HDFS/对象存储）、计算（MR/Spark/Flink）、资源（YARN/K8s）、分析（Hive/Presto/ClickHouse）、同步（DataX/Flink CDC） | 画出大数据技术全景图 | 能讲清每层 2 个代表组件 |
| D2 | HDFS 架构 | NameNode（元数据：FsImage + EditLog）、DataNode（数据节点）、SecondaryNameNode（检查点 Checkpoint）、Block（默认 128MB）与副本（Replication=3） | 阅读 `big-data/02` 并搭建 Hadoop 伪分布式（Docker） | HDFS 上传下载文件成功 |
| D3 | HDFS 读写流程 | 写流程（客户端→DN 管道传输 Pipeline、ack 确认）、读流程（就近读取 Locality）、机架感知（Rack Awareness：同机架/跨机架副本策略） | 画出读写流程时序图 | 能讲清副本放置策略（本机→同机架→跨机架） |
| D4 | HDFS 高可用与 Federation | HA（High Availability 高可用：NameNode 主备 + JournalNode 共享编辑日志 + ZKFC 选主）、联邦 Federation（多命名空间扩容）、小文件问题（Small Files Problem：内存元数据爆炸）与治理（CombineFileInputFormat/Har/合并任务） | 搭 HA 伪集群并模拟 NameNode 切换 | 小文件危害与治理方案能脱稿 |
| D5 | HDFS 实操 | hdfs shell 常用命令、权限（HDFS Permission 与 Linux 区别）、配额（Quota）、快照（Snapshot）、Trash 回收站 | 完成一份 HDFS 运维速查卡 | 常用命令 20 条无需查文档 |
| D6 | MapReduce 原理 | Map→Shuffle（洗牌：分区 Partition/排序 Sort/溢写 Spill/合并 Merge）→Reduce；Shuffle 是性能关键；MR 为什么慢（磁盘 IO 多次、启动 JVM 重） | 手写 WordCount 并观察 Shuffle 过程日志 | 能画出完整 Shuffle 数据流图 |
| D7 | 周复盘 | Hadoop 串讲 | 周记 + 《HDFS 小文件治理方案》 | 达成周验收 |

### 第 2 周：YARN 与 Hive 基础

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | YARN 架构 | ResourceManager（RM 资源管理器）、NodeManager（NM 节点管理器）、ApplicationMaster（AM 应用管家）、Container（容器：内存+CPU 资源单位）；作业提交全流程 | 画出 YARN 作业提交流程图 | 能讲清 AM 的作用与失败重试 |
| D2 | 调度器与资源隔离 | 三种调度器：FIFO（先进先出）/ Capacity（容量调度器，队列保证+弹性借用）/ Fair（公平调度器，缺额抢占）；资源隔离（内存/CPU，cgroups）；队列设计实践（生产/离线队列隔离） | 配置两级队列并提交任务验证 | 能为一家公司设计队列方案 |
| D3 | Hive 入门 | Hive 架构（HQL→解析器→执行引擎 MR/Tez/Spark→HDFS）、Metastore（元数据服务：Derby/MySQL）、与传统 DB 的区别（读时模式 Schema on Read） | Docker 部署 Hive + MySQL 元数据 | 建库建表查数据全流程通 |
| D4 | Hive 表类型 | 内部表（Managed Table：删表删数据）vs 外部表（External Table：删表留数据）、LOAD DATA vs INSERT、 LOCATION 指定路径 | 建 ODS 层外部表挂载日志目录 | 能讲清内外表选型（ODS 外部表的原因） |
| D5 | 分区与分桶 | 静态分区 vs 动态分区（Dynamic Partition）、分区裁剪（Partition Pruning）、分桶（Bucket：hash 分文件利于采样与 Join）、分区与分桶的组合使用 | 建分区分桶表并加载数据 | EXPLAIN 可见分区裁剪生效 |
| D6 | HQL 核心语法 | 窗口函数（Window Function：ROW_NUMBER/RANK/LAG/LEAD/SUM OVER）、GROUPING SETS、WITH 子句；HQL 与 SQL 方言差异 | 用窗口函数完成 TopN/同比环比练习题 10 道 | 10 题全部通过 |
| D7 | 周复盘 | YARN+Hive 串讲 | 周记 + 《Hive 建表规范 v1》（分区/命名/存储格式） | 达成周验收 |

### 第 3 周：数仓建模（Data Warehouse Modeling）与存储优化

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 数仓分层 | ODS（Operational Data Store 原始层）→ DWD（Data Warehouse Detail 明细层，清洗+维度退化）→ DWS（Summary 汇总层）→ ADS（Application Data Service 应用层）；为什么要分层（解耦/复用/血缘 Blood Lineage 清晰） | 画出电商数仓分层图 | 每层职责一句话讲清 |
| D2 | 维度建模 | 维度建模（Dimensional Modeling，Kimberly 方法论）、事实表（Fact Table：可加/半可加/不可加度量）、维度表（Dimension Table）、星型模型（Star Schema）vs 雪花模型（Snowflake Schema）、缓慢变化维（SCD，Slowly Changing Dimension：Type1/2/3） | 为订单域设计星型模型（事实表+5 维度表） | 能讲清 SCD Type2 的拉链表实现 |
| D3 | 事实表类型 | 事务事实表、周期快照事实表（Periodic Snapshot：每日库存快照）、累积快照事实表（Accumulating Snapshot：订单全生命周期）、无事实事实表 | 为电商设计 3 类事实表各一张 | 判别依据可自洽 |
| D4 | 数据质量 | 数据质量六性（完整性/准确性/一致性/及时性/唯一性/有效性）、DQC（Data Quality Control）规则配置、数据血缘（Lineage）与影响分析 | 设计 10 条 DQC 规则（强规则阻断/弱规则告警） | 规则覆盖核心表 |
| D5 | 存储格式与压缩 | 行存 vs 列存（Columnar：分析场景只读少数列）、ORC（Optimized Row Columnar）/Parquet 对比、压缩（Snappy 速度/Zlib 比率）、表属性配置 | 同一份数据用三种格式+压缩对比（大小/查询耗时） | 有量化对比表 |
| D6 | Hive 调优(1) | Fetch 抓取（本地读不启 MR）、本地模式（Local Mode 小任务）、并行执行（Parallel Execution）、JVM 重用、推测执行（Speculative Execution） | 给数仓任务配置调优参数并对比 | 调参前后有耗时对比 |
| D7 | 周复盘 | 建模串讲 | 周记 + 《数仓建模 Checklist》 | 达成周验收 |

### 第 4 周：数据倾斜（Data Skew）实战 + 数仓项目

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 数据倾斜定位 | 现象（个别 Reduce 长尾/OOM）、定位（YARN UI 看各 Task 耗时、Hive 执行计划）；倾斜根因分类（Key 分布不均/空值/大表 Join 小表/Count Distinct） | 构造 3 种倾斜数据并复现 | 每种都能定位到根因 |
| D2 | 倾斜治理(1) | 空值随机打散（null 加随机数两阶段聚合）、Map Join（小表广播，hive.auto.convert.join）、Bucket Join（分桶对分桶） | 治理空值倾斜与大表 Join 小表 | 治理前后耗时对比 |
| D3 | 倾斜治理(2) | 两阶段聚合（加盐 Salting + 去盐）、Count Distinct 改写（先 group 再 count）、倾斜 Key 单独处理（Filter 拆分union） | 治理热点 Key 聚合倾斜 | 治理前后耗时对比 |
| D4 | 数仓项目(1) | 电商行为数仓：埋点日志 + 业务库数据接入 ODS（模拟生成数据）；DWD 明细层构建（清洗规则/维度退化） | 完成 ODS→DWD | 数据可查且质量校验通过 |
| D5 | 数仓项目(2) | DWS 汇总层（用户日活跃宽表/商品日销售宽表）、ADS 应用层（GMV（Gross Merchandise Volume 商品交易总额）大屏/漏斗分析） | 完成 DWS→ADS | ADS 指标 SQL 复现大屏数字 |
| D6 | 调度与运维 | 调度系统（DolphinScheduler/Airflow：DAG 有向无环图、依赖管理、失败重试、补数 Backfill）；任务基线与优先级 | 用 DolphinScheduler 编排数仓任务链 | 全链路自动跑通+失败重试生效 |
| D7 | 月度总复盘 | Hadoop+Hive 全域串讲 | 月记 + 博客：《我用一个月搞懂了数据仓库：写给 Java 后端的数仓入门》发布 | 达成本月全部验收标准 |

---

## 本月产出清单

1. 《HDFS 运维速查卡》《Hive 建表规范》《数仓建模 Checklist》
2. 数据倾斜 3 场景复现与治理记录（含耗时对比）
3. 电商行为数仓（ODS/DWD/DWS/ADS 四层 + 调度编排）
4. 博客 1 篇

## 本月术语表

| 英文 | 中文 |
|------|------|
| NameNode / DataNode | 名称节点（元数据）/ 数据节点（存数据） |
| Rack Awareness | 机架感知（副本跨机架放置策略） |
| Shuffle | 洗牌（Map 输出到 Reduce 输入的重分布过程） |
| Metastore | 元数据服务（存表结构与分区信息） |
| Schema on Read | 读时模式（写入不校验，读取时解析） |
| Partition Pruning | 分区裁剪（只扫描需要的分区） |
| Star / Snowflake Schema | 星型 / 雪花模型（维度是否规范化拆分） |
| SCD (Slowly Changing Dimension) | 缓慢变化维 |
| Data Skew | 数据倾斜（个别 Key 数据量过大导致长尾） |
| Salting | 加盐（给 Key 加随机前缀打散热点） |
| Blood Lineage | 数据血缘（数据来源与加工链路） |
| Backfill | 补数（回溯历史数据） |

> 下月：`../month11-Kafka与Spark.md`——批处理之王 Spark 与 Kafka 大数据视角。
