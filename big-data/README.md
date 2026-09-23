# 大数据学习教程（Big Data Learning Path）

> **定位**：面向 Java 后端工程师的大数据转型/增强教程。不讲"从零学编程"，默认你已会 Java、SQL、Spring——正好大数据生态的编程接口大多是 Java/SQL，你有天然优势。
> **学习顺序**：01 → 07 顺序阅读，配合 `../practice-projects/03-realtime-analytics/` 实战项目食用。
> **对应成长计划**：`../growth-plan/phase4-大数据/`（第 10-12 个月）。

## 目录导航

| 文件 | 主题 | 核心收获 |
|------|------|---------|
| `01-大数据全景与数仓理论.md` | 全景图、数仓分层、Lambda/Kappa 架构 | 建立"数据思维"，知道每个组件在架构里的位置 |
| `02-Hadoop-HDFS-YARN.md` | 分布式存储与资源调度 | 理解一切大数据组件的地基：分片、副本、调度 |
| `03-Hive与数仓建模.md` | Hive SQL、维度建模、离线数仓 | 用 SQL 产出业务数仓表，掌握 ODS/DWD/DWS/ADS |
| `04-Kafka.md` | 分布式消息与流处理入口 | 吃透分区/副本/ISR/Exactly-Once，它是流式数据的大动脉 |
| `05-Spark.md` | 批处理引擎 | RDD/DAG/ shuffle 原理、调优与数据倾斜治理 |
| `06-Flink与实时计算.md` | 流处理引擎 | 事件时间/水印/状态/Checkpoint，实时数仓核心 |
| `07-面试与实战要点.md` | 高频面试题 + 生产经验清单 | 把学过的东西组织成面试语言和生产判断力 |

## Java 后端工程师的转型优势与补课重点

| 你已有的（直接复用） | 需要重点补的（新思维方式） |
|--------------------|------------------------|
| Java/Scala 语法（Flink/Spark API 几乎无缝） | 数据是"事件流"不是"当前状态" |
| SQL 能力（Hive/Spark/Flink SQL 全吃香） | 一致性靠 Checkpoint + 幂等，不靠事务 |
| JVM 调优经验（Executor 也是 JVM 进程） | 调优看吞吐/反压/积压，不看单次 RT |
| 分布式经验（副本/选举概念相通） | 分区(Partition)是一切并行的基本单位 |

## 学习方法建议

1. **先 SQL 后 API**：Flink SQL / Spark SQL 能解决 70% 需求，且最贴近你的 SQL 基础。
2. **每个组件问三个问题**：它解决什么问题？它如何容错？它的性能瓶颈在哪？
3. **源码不必通读**：挑一个组件（推荐 Flink 的 Checkpoint 或 Kafka 的副本同步）读透，其余组件读架构文档即可——面试官考的是"你懂不懂原理"，不是"你背没背源码"。
4. **所有实验在 Docker 里做**：`../practice-projects/03-realtime-analytics/step01` 的 docker-compose 就是你的实验室。

## 版本说明

教程以当前主流稳定版为准：Hadoop 3.3+、Hive 3.1/4.0、Kafka 3.x、Spark 3.5、Flink 1.18。**学原理为主，版本差异不影响理解**。
