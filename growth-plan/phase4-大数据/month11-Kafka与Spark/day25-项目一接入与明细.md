# Day 25 · 项目一 · 接入与明细：Kafka 埋点进 HDFS，Spark 洗出 DWD

> **今日目标**：项目周第 1 天——把本月两大主角串成一条真实链路：Kafka 收埋点消息 → 消费落 HDFS → Spark 清洗写入 Hive 的 DWD 表（复用 M10 的 shop 库和元数据）。这是"消息+计算"双引擎的第一次合体。
> **时长**：链路设计 1h / 落数 1.5h / 清洗入 DWD 2.5h
> **今日产出**：端到端链路跑通 + `shop.dwd_click_detail_di` 新表 + 链路图

## 1. 项目设计（先讲人话：我们要造一条什么流水线）

```
业务场景：电商埋点分析——用户每一次点击（曝光/点击/加购）都发一条消息
  到 Kafka，数仓侧要洗出"每日用户点击明细表"供下游分析。

链路设计（对照 M10 离线链路，只换了两个环节）：
  M10 链路：人工 CSV → HDFS → ODS → Hive 清洗 → DWD
  M11 链路：业务埋点 → Kafka（day01~11 的所有知识）→ 消费落 HDFS
            → ODS → Spark 清洗（day15~24 的所有知识）→ DWD
  —— 数据入口从"人工文件"升级为"消息队列"（生产真实形态）
  —— 清洗引擎从 Hive MR 升级为 Spark（更快，且共享同一本元数据账本）

四张站牌：
  ① 生产者：kafka-topics 建主题，控制台生产者模拟埋点消息
  ② 落地者：消费组拉消息 → 按 dt 分目录追加写 HDFS（模拟"采集 Worker"）
  ③ 清洗者：spark-shell 读 ODS → 去重/过滤/解析 → 写 Hive 表
  ④ 验收者：Hive/Spark 两边各查一遍，数字必须一致（元数据共享的好处）

表结构（埋点消息一行一个 JSON）：
  {"ts":"2026-09-27 10:23:01","uid":"1001","action":"click","page":"home","item_id":"9001"}
  DWD 目标表：dwd_click_detail_di（uid, action, page, item_id, click_time, dt 分区）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Event Tracking | 埋点（业务代码里打的"动作日志"） |
| Landing Job | 落地作业（把 Kafka 消息搬到 HDFS 的搬运工） |
| ODS → DWD | 原始层 → 明细层（照单全收 → 洗干净） |
| Dedupe | 去重（重试导致的消息重复，day08 埋的坑在此兑现） |
| External Table | 外部表（M10 的挂载思想，ODS 依旧外部表） |
| Lineage-free Check | 对账（源侧和目标侧数字互相印证，M8/M10 思想） |

## 3. 动手实操：四站牌逐个打通

```powershell
# ===== 站牌①：建主题+造埋点消息（Kafka 容器）=====
docker exec kafka kafka-topics.sh --bootstrap-server kafka:9092 `
  --create --topic app_event --partitions 3 --replication-factor 1
docker exec -it kafka kafka-console-producer.sh --bootstrap-server kafka:9092 --topic app_event
# 手敲 12 条消息（故意混入：1 条重复、1 条缺 uid 的脏行）：
# {"ts":"2026-09-27 10:23:01","uid":"1001","action":"click","page":"home","item_id":"9001"}
# （复制上一条再发一次 → 制造重复；发一条 {"ts":"...","action":"click","page":"home"} → 缺 uid）

# ===== 站牌②：落地（消费 → 写 HDFS，模拟采集 Worker）=====
docker exec kafka bash -c "kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic app_event --group lander --from-beginning > /tmp/events.json"
docker cp kafka:/tmp/events.json ./
docker cp ./events.json hadoop-single:/tmp/events.json
docker exec hadoop-single bash -c "hdfs dfs -mkdir -p /data/ods/app_event/dt=2026-09-27 && hdfs dfs -put -f /tmp/events.json /data/ods/app_event/dt=2026-09-27/"
docker exec hadoop-single bash -c "hdfs dfs -cat /data/ods/app_event/dt=2026-09-27/events.json | head -3"
```

```sql
-- ===== 站牌③前半：建 ODS 外部表（Hive，复用 M10 环境思路）=====
CREATE EXTERNAL TABLE IF NOT EXISTS shop.ods_app_event (raw string)
PARTITIONED BY (dt string)
ROW FORMAT DELIMITED LINES TERMINATED BY '\n'
LOCATION '/data/ods/app_event';
ALTER TABLE shop.ods_app_event ADD IF NOT EXISTS PARTITION (dt='2026-09-27');
```

```scala
// ===== 站牌③后半：Spark 清洗 → DWD（今天的重头戏）=====
// spark-shell --conf spark.hadoop.hive.metastore.uris=thrift://hive-server:9083
val raw = spark.table("shop.ods_app_event").where($"dt" === "2026-09-27")
// 用 Spark 内置 JSON 解析（get_json_object——和 MySQL/UDF 族的亲戚）：
import org.apache.spark.sql.functions._
val parsed = raw.select(
  get_json_object($"raw", "$.ts").as("ts"),
  get_json_object($"raw", "$.uid").as("uid"),
  get_json_object($"raw", "$.action").as("action"),
  get_json_object($"raw", "$.page").as("page"),
  get_json_object($"raw", "$.item_id").as("item_id"))
// 清洗四件事（M10 DWD 的同款动作，引擎换 Spark）：
val cleaned = parsed
  .filter($"uid".isNotNull && $"uid" =!= "")                       // ① 过滤脏行
  .withColumn("rn", row_number().over(
    Window.partitionBy("ts","uid","action","item_id").orderBy("ts"))) // ② 去重
  .filter($"rn" === 1).drop("rn")
  .withColumn("click_time", to_timestamp($"ts"))                   // ③ 类型规范化
spark.sql("CREATE TABLE IF NOT EXISTS shop.dwd_click_detail_di " +
  "(uid string, action string, page string, item_id string, click_time timestamp) " +
  "PARTITIONED BY (dt string) STORED AS PARQUET")
cleaned.write.mode("overwrite").partitionBy("dt")
  .saveAsTable("shop.dwd_click_detail_di")    // ④ 写 Hive 表（Parquet，元数据入账本）

// ===== 站牌④：对账（源 12 = 清洗后 10：1 重复 + 1 脏行被洗掉）=====
spark.sql("SELECT count(*) raw_cnt FROM shop.ods_app_event WHERE dt='2026-09-27'").show()     // 12
spark.sql("SELECT count(*) dwd_cnt FROM shop.dwd_click_detail_di WHERE dt='2026-09-27'").show() // 10
// 验收：Hive 侧 beeline 查同一张表，数字必须一致（元数据共享的证明）
```

## 4. 面试连接

**Q：讲讲你的埋点数仓链路？为什么数据入口要用 Kafka？（项目题，本月主答案）**
> 链路四段：业务埋点发 Kafka（app_event 主题，3 分区）；落地作业以消费组身份拉消息，按天落 HDFS 目录；ODS 外部表挂载该目录进 Hive 元数据；Spark（开 Hive 支持）读 ODS 做清洗——过滤缺 uid 的脏行、用 row_number 对"时间+用户+动作+物品"去重、时间字段规范化——写 DWD 明细表（Parquet 分区表）。为什么入口必须 Kafka：埋点是高频小消息，直写 HDFS 会产生海量小文件且 HDFS 不支持随机追加，Kafka 天然缓冲削峰（day03 的批量+顺序写）、可回放重算（day04 的 offset 语义，下游算错了从历史位点重消费即可）；HDFS 才是"已定稿的文件世界"，Kafka 是"流动中的缓冲带"，两者是流水线的上下游不是替代关系。对账数字：源 12 条、DWD 10 条，差值=1 条重复+1 条脏行，每一笔都对得上账——这是我在项目里坚持的纪律：数字说得出处，才算清洗完成。

**Q：为什么清洗用 Spark 不继续用 Hive？（考引擎选型判断）**
> 三个理由：性能——MR 步步落盘，Spark 内存迭代，同类清洗作业快数倍（day12 三张牌）；生态——Spark SQL 能直接用 DataFrame API 做 JSON 解析、窗口去重，表达力强；协同——Spark 共享 Hive metastore，DWD 表 Hive/Spark/BI 工具全都能读，数据资产不迁移。代价也要会说：Spark 吃内存（资源紧张时 Hive 更皮实）、运维复杂度高一点。我的判断标准：迭代快、逻辑复杂的清洗用 Spark；超大规模 T+1 简单跑批用 Hive 也完全够——选引擎看场景不看潮流。

## 5. 今日验收清单

- [ ] 四站牌全部跑通（截图：主题/ODS/DWD/对账四张）
- [ ] 对账数字 12→10 的两笔差额能说出出处
- [ ] "为什么入口要 Kafka"能脱稿讲（缓冲/回放/小文件）
- [ ] "清洗为什么换 Spark"三个理由+代价能讲
- [ ] 链路图手绘一遍（埋点→Kafka→HDFS→ODS→Spark→DWD）
- [ ] `git add . && git commit -m "day11-25: proj1-ingest"`

---
[← Day 24](day24-Spark倾斜治理.md) | [本月目录](README.md) | [Day 26 · 项目二 · 汇总与对拍 →](day26-项目二汇总与对拍.md)
