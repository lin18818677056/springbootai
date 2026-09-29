# 第 11 月 · Kafka 与 Spark：消息底座与新世代计算引擎

> **本月一句话目标**：搞懂两件事——①Kafka 为什么能扛住"百万条消息每秒"（把它当大数据世界的传送带总枢纽）；②Spark 为什么比上个月学的 MapReduce 快 3~10 倍（它聪明在哪、又贵在哪）。月底交付一个"Kafka 进数据 → Spark 加工 → 出报表"的日活分析小项目。
> **写法承诺（和 M10 一样）**：每个概念先说大白话、再打比方、最后才上术语和命令。中下水平的 Java 工程师能直接读懂。
> **详细教程配合阅读**：`../big-data/`（04 Kafka、05 Spark 章节）

## 本月验收标准（月底逐条对账）

- [ ] 能讲清 Kafka 快的四个原因（顺序写/零拷贝/批量压缩/分区并行），带 M10 思考题的答案
- [ ] 能讲清分区/副本/消费组/Rebalance，并给出一份《Kafka 生产治理清单》
- [ ] 三种消费语义（至多/至少/精确一次）能结合 M8 幂等思想讲透
- [ ] 能讲清 Spark 快的三个原因（省落盘/DAG 优化/线程模型），画出 Stage 切分图
- [ ] Spark SQL：谓词下推/列裁剪/AQE 三能力，每个都有 EXPLAIN 实验证据
- [ ] Spark 倾斜治理三例（对照 M10 四武器，前后耗时对比）
- [ ] 日活分析项目跑通（Kafka 造数→落 HDFS→Spark 清洗汇总→ADS），并与 M10 的 Hive 结果对拍一致
- [ ] 博客 1 篇发布 + git tag month11-done

## 30 天课程地图

### W1 · Kafka 深度（day01-07）
| 日 | 主题 | 一句话说明白 |
|----|------|------------|
| D1 | Kafka 全景与定位 | 消息界的"中央传送带"：和 M5 的 RocketMQ 什么关系、为什么大数据离不开它 |
| D2 | 架构与消息模型 | Broker/Topic/Partition/Offset：仓库、货架、格口、进度条四个词入门 |
| D3 | Kafka 为什么快 | 兑现 M10 思考题：顺序写+零拷贝+批量压缩+页缓存（对照 M3 的 sendfile） |
| D4 | 分区与消费组 | 格口越多搬得越快？分区数规划和"一群人抢着搬"的消费组 |
| D5 | Rebalance 深度 | "换人接力"的代价：为什么换一个人全队停工，Cooperative 怎么救 |
| D6 | 副本与 ISR | 每个格口配复印员：Leader/Follower/ISR 名单/acks 三档的可靠性账 |
| D7 | 周复盘 | 六线串讲+12 题自测+《Kafka 生产治理清单 v1》 |

### W2 · Kafka 语义运维 + Spark 启程（day08-14）
| 日 | 主题 | 一句话说明白 |
|----|------|------------|
| D8 | 位移提交与消费语义 | 进度条什么时候拨？at-most/at-least/exactly-once 三种账（接 M8 幂等） |
| D9 | 端到端可靠性拼图 | 生产者 acks/幂等+broker 副本+消费者位移：三段拼成"不丢不重" |
| D10 | Kafka 运维实战 | 消息保留（Retention/Compact）、压缩算法、扩容搬格子（reassignment） |
| D11 | Kafka Streams 初识 | 不起集群也能流式计算的小引擎；和 Flink 的定位差异（M12 预告） |
| D12 | Spark 全景 | 兑现 M10 思考题：MR 慢三原因 → Spark 三解法（省落盘/DAG/线程模型） |
| D13 | 部署与第一个作业 | 容器起 Spark，跑通 WordCount，认识 Driver/Executor/UI 界面 |
| D14 | 周复盘 | 串讲+自测+治理清单 v2 定稿 |

### W3 · Spark Core 与 SQL（day15-21）
| 日 | 主题 | 一句话说明白 |
|----|------|------------|
| D15 | RDD 与两类算子 | 弹性分布式数据集=一张"会记仇的账本"（血缘）；转换懒、行动才开工 |
| D16 | 宽窄依赖与 Stage | 流水线 vs 分会场：什么时候必须"全体放下手头活重新集合" |
| D17 | 持久化与广播变量 | cache 是"把菜先做好放着"，广播是"把小名册人手一份发下去" |
| D18 | Shuffle 机制 | Spark 的 Shuffle 比 MR 聪明在哪？SortShuffle/Bypass 一张图 |
| D19 | 内存与资源调优 | 统一内存模型（执行和存储可以互相借）；Executor 参数怎么配 |
| D20 | Spark SQL 与 Catalyst | SQL 进来先过"优化流水线"：谓词下推/列裁剪/常量折叠，EXPLAIN 验证 |
| D21 | 周复盘 | 串讲+自测+《Spark 作业调优 Checklist v1》 |

### W4 · AQE、倾斜与项目实战（day22-30）
| 日 | 主题 | 一句话说明白 |
|----|------|------------|
| D22 | AQE 自适应执行 | 运行时"看着办"：自动合并小分区/切换 Join/拆倾斜，三能力各验证一次 |
| D23 | Join 策略全景 | 四种 Join（广播/洗牌哈希/排序归并/笛卡尔）怎么按表大小自动选 |
| D24 | Spark 倾斜治理 | M10 四武器平移：加盐/广播/空值打散在 Spark 里怎么写+AQE Skew Join |
| D25 | 项目一：接入与明细 | Kafka 造埋点数据→落 HDFS 分区→Spark 清洗出 DWD（复用 M10 项目结构） |
| D26 | 项目二：汇总与对拍 | Spark 算 DWS/ADS，与 M10 Hive 的 GMV 结果对拍——数字必须一致 |
| D27 | 项目三：压测与调优 | 造 1 亿行数据（兑现 M10 未达③），Spark UI 定位瓶颈，3 项优化留数据 |
| D28 | 全月大串讲 | 《消息+计算双引擎地图》：从埋点点击到大屏数字的完整旅程 |
| D29 | M11 模拟验收 | 五轮 30 问+白板三件套+L1~L4 自评+薄弱区清单 |
| D30 | 月度复盘与博客 | 验收对账+诚实记录未达+博客发布+M12 交接卡+tag month11-done |

## 跨月伏笔清单（本月要兑现的"前债"）

| 伏笔 | 埋在哪 | 兑现在 |
|------|--------|--------|
| "Spark 为什么比 MR 快 3~10 倍？贵在哪？"（M10 思考题） | M10 day30 | day12 正式作答 |
| "Kafka 为什么能扛百万 TPS？"（M10 思考题） | M10 day30 | day03 正式作答 |
| MR 慢三原因（落盘多/启动重/无 DAG） | M10 day06 | day12 三解法逐条对 |
| RocketMQ 消息模型/事务消息 | M5 | day01/02/09 对照表 |
| sendfile 零拷贝、堆外内存 | M3 day06 | day03 零拷贝呼应 |
| 接口幂等/对账三铁律 | M8 | day08/09 语义与可靠性 |
| 数仓四层与 GMV 对账（14→12→12→12） | M10 day25/26 | day25/26 Spark 重算对拍 |
| 倾斜四武器+倾斜治理手册 | M10 day22-24 | day24 平移对照 |
| mini 调度器（状态机/补数） | M10 day27 | day27 压测复用 |

## 实验环境（本月新增容器）

```powershell
# Kafka（KRaft 单机模式：不需要 ZooKeeper，2026 年新项目默认姿势）
docker run -d --name kafka --network bigdata `
  -p 9092:9092 `
  -e KAFKA_CFG_NODE_ID=0 `
  -e KAFKA_CFG_PROCESS_ROLES=controller,broker `
  -e KAFKA_CFG_CONTROLLER_QUORUM_VOTERS=0@kafka:9093 `
  -e KAFKA_CFG_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 `
  -e KAFKA_CFG_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092 `
  -e KAFKA_CFG_CONTROLLER_LISTENER_NAMES=CONTROLLER `
  -e KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT `
  bitnami/kafka:3.7
# Spark（local 模式起步，跑批够用）
docker run -d --name spark --network bigdata -p 4040:4040 `
  bitnami/spark:3.5 bash -c "sleep infinity"
# 上月容器复用：hadoop-single / hive-server / hive-mysql（对拍要用）
```

## 导航

- [← 上月：M10 Hadoop 与 Hive](../month10-Hadoop与Hive/README.md)
- [本月目录](README.md) · day01 → day30 依次推进，每周 day07/14/21 复盘，day28-30 收尾
- [→ 下月：M12 Flink 与实时数仓](../month12-Flink与实时数仓/README.md)（day30 交接卡到位后转活）
