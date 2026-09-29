# Day 03 · 第一个流作业：把"常开的水管"接到 Kafka 上

> **今日目标**：跑通本月第一个"真链路"——Kafka 主题里的消息进 Flink、加工后从 Print Sink 出来。DataStream API 的核心抽象（Source/Transformation/Sink）和 SQL 双路径都走一遍，为项目周铺路。
> **时长**：API 概念 1h / SQL 路径跑通 1.5h / DataStream 路径 2h
> **今日产出**：Kafka→Flink 链路跑通记录 + 《Source/Transformation/Sink 速查卡》

## 1. 知识地图（先讲人话：流作业的三段式）

```
任何流作业都是三段式（和 Spark 的 读→算→写 同构）：

Source（水源）：数据从哪来
  · Kafka Source（生产主用）：消费主题，分区→并行度对齐
  · datagen（内置造数器）：测试用，昨天已用
  · socket（nc 端口）：调试用
  —— Source 有状态：Kafka 的 offset 存在状态里（day09 精确一次的伏笔）

Transformation（水管上的加工站）：数据怎么变
  · map（一件加工）/ filter（筛掉）/ flatMap（拆开）
  · keyBy（按 key 分流——宽依赖，跨网络）→ 聚合
  · window（窗口，day05）/ process（自定义逻辑）
  —— 和 Spark RDD 算子几乎是同一套词汇，迁移成本极低

Sink（下水口）：结果去哪
  · print（打印，调试利器）
  · Kafka Sink（回写主题，实时数仓分层的关键）
  · JDBC/ES/ClickHouse（喂下游系统）
  —— Sink 的幂等性决定端到端语义（day09 的核心伏笔）

两条写法路径（同一引擎，两种表达）：
  DataStream API（Java/Scala）：表达力强，复杂逻辑（CEP/自定义状态）用它
  Flink SQL：90% 的实时数仓需求用它——本月主线（day11 起）
  —— 先 SQL 跑通链路（今天的实验），day07 后补 DataStream 细节
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| Source | 数据源 | 水从哪来（Kafka/datagen/socket） |
| Sink | 输出 | 水往哪去（print/Kafka/JDBC） |
| Transformation | 转换 | 管道上的加工站（map/filter/keyBy） |
| keyBy | 按 key 分流 | 宽依赖：同 key 进同一子任务（跨网络） |
| Flink SQL | 流式 SQL | 用 SQL 写流作业（实时数仓主战场） |
| Dynamic Table | 动态表 | SQL 眼里的流：一张不断 INSERT 的新表 |

## 3. 动手实操：Kafka→Flink→Print 链路

```powershell
# ===== 前置：确认 Kafka 连接器（README 的可选 jar）=====
# 已下载 flink-sql-connector-kafka jar 并 docker cp 进 lib 则继续；
# 没下载：跳到【降级路径】用 datagen，结论不受影响（本文档两条路都给）

# ===== 步骤 1：造一个测试主题+发几条消息 =====
docker exec kafka kafka-topics.sh --bootstrap-server kafka:9092 `
  --create --topic flink_test --partitions 3 --replication-factor 1
docker exec -it kafka kafka-console-producer.sh --bootstrap-server kafka:9092 --topic flink_test
# 手敲几条 JSON：
# {"uid":"1001","amt":99.5}
# {"uid":"1002","amt":199.0}
```

```sql
-- ===== 步骤 2：Flink SQL 建 Kafka 源表（正路径）=====
docker exec -it flink-jm ./bin/sql-client.sh
SET 'sqlclient.execution.result-mode' = 'tableau';

CREATE TABLE kafka_src (
  uid STRING,
  amt DOUBLE,
  proc AS PROCTIME()
) WITH (
  'connector' = 'kafka',
  'topic' = 'flink_test',
  'properties.bootstrap.servers' = 'kafka:9092',
  'properties.group.id' = 'flink_demo',
  'scan.startup.mode' = 'latest',
  'format' = 'json'
);

-- 步骤 3：查它（表模式下你会看到流式刷新的数据）
SELECT * FROM kafka_src;
-- 去 Kafka 生产者再敲几条 → Flink 端 1 秒内出现——链路通了！（截图①）

-- 步骤 4：加工+输出（三段式齐活）
CREATE TABLE print_sink (uid STRING, amt DOUBLE, remark STRING)
WITH ('connector' = 'print');
INSERT INTO print_sink
SELECT uid, amt,
  CASE WHEN amt > 100 THEN 'big' ELSE 'normal' END
FROM kafka_src WHERE amt > 0;
-- UI 看到新作业 RUNNING；TaskManager 日志里看 print 输出：
docker exec flink-jm bash -c "ls /opt/flink/log | tail -3"
docker exec flink-jm tail -20 /opt/flink/log/flink-*-taskexecutor-*.log | Select-String "big|normal"
```

```sql
-- ===== 降级路径（无 Kafka connector 时用 datagen 模拟同样效果）=====
CREATE TABLE fake_kafka (uid STRING, amt DOUBLE, proc AS PROCTIME())
WITH (
  'connector' = 'datagen',
  'rows-per-second' = '2',
  'fields.uid.kind' = 'random',
  'fields.uid.length' = '4',
  'fields.amt.min' = '1', 'fields.amt.max' = '300'
);
-- 后续 SELECT/INSERT 完全一样——实验结论等价
```

## 4. 面试连接

**Q：讲讲 Flink 作业的三段式结构？写过一个什么流作业？（开场实操题）**
> 任何流作业都是 Source→Transformation→Sink 三段：Source 负责接入（生产上是 Kafka Source，消费主题并把 offset 记进状态）；Transformation 是加工（map/filter/keyBy/窗口聚合，词汇和 Spark 算子高度同构，keyBy 就是宽依赖）；Sink 负责输出（print 调试、Kafka 做数仓分层、JDBC 喂应用）。我最近跑通的一条链路：Kafka 埋点主题 → Flink SQL 建 Kafka 源表 → 过滤+打标 → print/下游主题，从生产者敲消息到 Flink 出结果实测 1 秒内——这条链路就是实时数仓的雏形，day16 起我把它扩展成完整的 DWD 明细流。加分点：主动提两条写法路径的选择——Flink SQL 写 90% 的常规逻辑（声明式、好维护），DataStream API 写复杂逻辑（自定义状态/CEP）；以及 Source 的并行度要对齐 Kafka 分区数（day02 讲过的铁律）。

**Q：Flink 消费 Kafka 的 offset 存在哪？为什么不是存在 ZooKeeper/Kafka 里？（埋向 day09 的钩子题）**
> 存在 Flink 的【算子状态】里，随 Checkpoint 一起快照到外部持久存储（HDFS/S3）。这是个精妙的设计：如果 offset 存 Kafka（消费者组提交）或 ZK，就会造成"数据处理进度"和"状态快照"两本账——挂了恢复时对不齐，做不到精确一次。存进自己的快照后，恢复时"状态到哪+offset 到哪"是同一时刻的一致性切片，重放多少数据是算出来的，不是猜的。这个设计的完整展开在 day09（端到端三段拼图），今天先记住结论：offset 的存放位置是流式语义的基石之一。

## 5. 今日验收清单

- [ ] Kafka→Flink→Print 链路跑通（截图：SQL 查询流式刷新）
- [ ] 三段式（Source/Transformation/Sink）能白板画
- [ ] 降级路径（datagen）也跑过一遍（环境适应性）
- [ ] "offset 存算子状态"的原因能讲（埋 day09 钩子）
- [ ] 《Source/Transformation/Sink 速查卡》开写
- [ ] `git add . && git commit -m "day12-03: first-job"`

---
[← Day 02](day02-运行时架构.md) | [本月目录](README.md) | [Day 04 · 时间语义与水位线 →](day04-时间语义与水位线.md)
