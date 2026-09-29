# Day 11 · Flink SQL 入门：用 SQL 写流，动态表是钥匙

> **今日目标**：理解 Flink SQL 的核心抽象——动态表（Dynamic Table）和流上的三种变更（Insert/Update/Delete）；掌握源表/汇表的 WITH 连接器写法；把 W1 学的窗口/水位线全部翻译成 SQL。这是实时数仓的主战场（day15 起的项目全部用 SQL 写）。
> **时长**：动态表模型 1.5h / 连接器与实操 2.5h / 输出 1h
> **今日产出**：动态表模型图 + 《连接器速查卡》+ append 流 vs upsert 流对照实验

## 1. 知识地图（先讲人话：SQL 眼里的流是什么）

```
SQL 是批世界的语言，怎么让它跑在流上？
  —— Flink 的答案：动态表 Dynamic Table
  把流想象成一张【永远在 INSERT 的新表】：
    每来一条数据 = 往表里 INSERT 一行
    你写的 SQL = 对这张"不断变化的表"的持续查询
    查询结果 = 也是一张动态表（不断变化的输出流）

关键分水岭：查询会不会产生"更新"（Update/Delete）？
  ① Append 流（只追加）：SELECT ... WHERE 简单过滤
     —— 每条输入产生一条输出，输出只增不改（像明细日志）
  ② Update 流（会更新）：GROUP BY 聚合
     —— "uid=1 的 SUM"每来一条同 uid 数据就变一次
        输出是"+1 改成 +2 再改成 +3"的变更流（changelog）
  ③ Retract 流（撤回重发）：复杂聚合的输出是"撤回旧行+插入新行"
     —— (-, 旧值) 然后 (+, 新值)
  —— Sink 的选择取决于流的类型：Kafka append sink 只吃 Append 流；
     有主键的 sink（JDBC/upsert-kafka）才能吃 Update 流
     —— day09 的"Sink 幂等选型"在 SQL 层的对应物

连接器 Connector（表的"插座"）：
  WITH ('connector' = 'kafka'/'datagen'/'print'/'jdbc'...)
  —— 一张"表"背后是 Kafka 主题/JDBC 表/打印控制台——SQL 统一抽象
  常用参数三件套：connector 类型 + topic/表名 + format(json/csv/debezium)
  —— format=debezium/canal 就是 CDC（day13 的伏笔：数据库变更实时流入）
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| Dynamic Table | 动态表 | SQL 眼里的流：一张不断 INSERT 的表 |
| Append Stream | 追加流 | 只增不改的输出（明细） |
| Changelog / Update Stream | 变更流 | 带更新语义的输出（聚合结果在变） |
| Retract | 撤回流 | 先撤旧值再发新值 |
| Connector | 连接器 | 表的插座：背后接 Kafka/JDBC/打印 |
| Format | 格式 | 消息的编码：json/csv/debezium（CDC） |
| Primary Key (NOT ENFORCED) | 主键声明 | 告诉引擎"按这个 key 更新"（流里不校验唯一） |

## 3. 动手实操：三种流对照 + 连接器串烧

```sql
-- 通用源（沿用 W1 的 datagen 姿势）
CREATE TABLE dsql_src (
  uid INT, amt DOUBLE,
  et AS CURRENT_TIMESTAMP,
  WATERMARK FOR et AS et - INTERVAL '3' SECOND
) WITH (
  'connector' = 'datagen', 'rows-per-second' = '5',
  'fields.uid.kind' = 'random', 'fields.uid.min' = '1', 'fields.uid.max' = '10',
  'fields.amt.min' = '10', 'fields.amt.max' = '500'
);

-- ===== 实验①：Append 流（过滤查询——只增不改）=====
SELECT uid, amt FROM dsql_src WHERE amt > 250;
-- 结果模式（tableau）下：只有新行不断出现，旧行不动——Append 体感（截图①）

-- ===== 实验②：Update 流（聚合查询——行会变）=====
SELECT uid, COUNT(*) AS cnt, SUM(amt) AS total FROM dsql_src GROUP BY uid;
-- 观察：同一 uid 的行【反复更新】（cnt 1→2→3...）——Update 体感（截图②）
-- 思考：这种流写进 Kafka append 主题会怎样？——报错！
--       需要 upsert-kafka 连接器+主键（下面实验③验证）

-- ===== 实验③：有主键的 Sink 吃 Update 流 =====
CREATE TABLE agg_sink (
  uid INT,
  cnt BIGINT,
  total DOUBLE,
  PRIMARY KEY (uid) NOT ENFORCED
) WITH ('connector' = 'print');   -- 生产换成 upsert-kafka/jdbc
INSERT INTO agg_sink
SELECT uid, COUNT(*), SUM(amt) FROM dsql_src GROUP BY uid;
-- UI 提交成功——同样的 Update 流，有主键的 sink 就能接（对照实验②的思考）

-- ===== 实验④：连接器串烧（一张表=一个插座）=====
-- 把同一份 datagen 数据"写入"另一个主题再读回来（需要 kafka connector；
-- 无则跳过，参数背下来即可）：
-- CREATE TABLE kafka_out (uid INT, amt DOUBLE)
-- WITH ('connector'='kafka','topic'='flink_out',
--       'properties.bootstrap.servers'='kafka:9092',
--       'format'='json');
```

```
《连接器速查卡》：
  datagen   测试造数：rows-per-second + fields.*.kind/min/max
  print     调试输出：TaskManager 日志看结果
  kafka     源/汇：topic + properties.bootstrap.servers + format
  upsert-kafka  按主键更新到 Kafka（实时数仓 DWS 层的写法）
  jdbc      汇 MySQL/PG：url + table-name + 主键=upsert（大屏后端直连）
  debezium/canal(json)  CDC 格式：数据库 binlog 实时流入（day13 展开）
```

## 4. 面试连接

**Q：什么是 Flink 的动态表？Append 流和 Update 流有什么区别，各怎么落地？（SQL 必考题）**
> 动态表是 Flink SQL 的核心抽象：把无界流看成一张不断 INSERT 的表，SQL 查询是对这张表的持续查询，结果也是动态表。关键要按"输出有没有更新语义"分两类：Append 流——简单过滤/打标这类查询，每条输入产一条输出，只增不改，可以落 Kafka append 主题、写文件；Update 流——GROUP BY 聚合的输出会随数据不断变化（某 uid 的合计从 100 变 150 变 200），这种流必须用支持更新的 Sink 落地：有主键声明的 JDBC 表（upsert）、upsert-kafka 主题，或者 Print 这类展示型 sink。把流类型和 Sink 能力对齐是实时数仓开发的第一课——常见事故就是拿 append 的 Kafka sink 接聚合查询，提交直接报错。加分点：提 Retract 流（撤回旧值再发新值）和 changelog 的概念，以及 CDC（debezium 格式）本质就是把数据库的变更日志变成一张动态表——day13 会用它做维表实时同步。

**Q：为什么说 Flink SQL 是实时数仓的主战场？（考技术判断）**
> 三个理由：表达效率——窗口/聚合/Join 这些实时数仓 90% 的逻辑，SQL 十几行搞定，DataStream API 要写一堆样板代码；维护成本——SQL 是声明式的，换人接手、排查问题的门槛都低得多；生态一致——离线数仓的同学会 Hive SQL，Flink SQL 语法高度相近，"流批同构"的学习成本低。但边界也要清楚：复杂事件处理（CEP）、自定义状态逻辑、高性能要求的算子，还是 DataStream API 的领地。我的分工原则：数仓链路（清洗/聚合/大屏指标）全 SQL，特殊算子（风控规则引擎）才下 API——这是"90% 用 SQL，10% 用 API"的实践。

## 5. 今日验收清单

- [ ] 动态表模型能白板讲（流=不断 INSERT 的表）
- [ ] Append/Update/Retract 三种流能区分+各自的 Sink 落地
- [ ] 实验①②③完成（三种流体感截图）
- [ ] 《连接器速查卡》成文
- [ ] "90% SQL + 10% API"的分工观点能讲
- [ ] `git add . && git commit -m "day12-11: flink-sql"`

---
[← Day 10](day10-反压与积压排查.md) | [本月目录](README.md) | [Day 12 · SQL 窗口与 TopN →](day12-SQL窗口与TopN.md)
