# Day 13 · 维表关联与 CDC：查字典不把字典书翻烂

> **今日目标**：解决实时链路的经典难题——流数据要补全维度信息（订单→商品名），但维表在 MySQL 里：怎么查不把数据库打死？三种方案（Lookup Join 带缓存/预广播/CDC 同步）各自的适用场景；顺便认识 CDC（Change Data Capture，变更数据捕获）。
> **时长**：三方案原理 2h / Lookup Join 实验 2h / 输出 1h
> **今日产出**：《维表关联选型卡》+ Lookup Join 参数实验 + CDC 概念笔记

## 1. 知识地图（先讲人话：字典的三种查法）

```
场景：订单流（每秒几千条）要补商品名——商品维表在 MySQL（几万行）
  直接每条订单查一次库 = 每秒几千次查询 → MySQL 立刻被打死
  —— 这是实时链路最常见的"好想法坏实现"

三种查字典姿势（按维表大小和更新频率选）：

方案① Lookup Join + 缓存（按需查，带记忆）
  流上每条数据按 key 查维表，但查过的结果缓存 N 行/T 秒
  —— 参数：lookup.cache.max-rows / lookup.cache.ttl
  适合：维表大（百万级，广播不动）+ 更新不频繁（TTL 内可容忍旧值）
  代价：仍有回源查询（缓存 miss 时）；缓存越久数据越旧

方案② 预广播（全量发小本本）
  维表小（几万行以内）→ 启动时全量拉取广播到每个并行实例，本地查
  —— 零回源查询，最快；代价：维表更新要重新广播（低频维表才适合）
  —— 对照：M11 Spark 广播 Join 的直系同款（跨月连线）

方案③ CDC 同步（把字典书复印一份贴墙上，还自动更新）
  CDC = Change Data Capture 变更数据捕获：
  用工具（Canal/Debezium/Flink CDC）读 MySQL 的 binlog（数据库的流水账），
  把每次增删改实时变成消息流 → 维表副本常驻 Kafka/状态里，自动跟着变
  —— 适合：维表更新频繁 + 要求准实时一致（价格/库存类）
  —— 这也是"实时数仓 ODS 层"的建法：业务库变更实时进 Kafka
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| Dimension Table | 维表 | 字典书：商品/用户/类目等描述性数据 |
| Lookup Join | 维表查询 | 流上按 key 现查字典（可带缓存） |
| Broadcast State | 广播状态 | 字典全量发到每个工人手里 |
| CDC | 变更数据捕获 | 监听 binlog，把数据库变更变成实时流 |
| Binlog | 二进制日志 | MySQL 的"操作流水账"（CDC 的数据源） |
| Temporal Join | 时态关联 | FOR SYSTEM_TIME AS OF——按"当时有效"的版本关联 |
| 缓存 TTL | 缓存生存时间 | 查过的字典条目记多久（新鲜度旋钮） |

## 3. 动手实操：Lookup Join 实验三连

```sql
-- ===== 步骤 1：用 datagen 模拟订单流 + "MySQL 维表" =====
-- 无 MySQL 维表环境时，用 datagen 造一张"维表模拟表"演示语法：
CREATE TABLE orders_stream (
  item_id INT,
  amt DOUBLE,
  proc AS PROCTIME()          -- Lookup Join 用处理时间
) WITH (
  'connector' = 'datagen', 'rows-per-second' = '5',
  'fields.item_id.kind' = 'random',
  'fields.item_id.min' = '1', 'fields.item_id.max' = '10',
  'fields.amt.min' = '10', 'fields.amt.max' = '500'
);

CREATE TABLE dim_item (       -- "维表"：datagen 模拟（生产=jdbc connector）
  id INT,
  item_name STRING
) WITH (
  'connector' = 'datagen',
  'fields.id.kind' = 'sequence', 'fields.id.start' = '1', 'fields.id.end' = '10',
  'fields.item_name.kind' = 'random', 'fields.item_name.length' = '8'
);

-- ===== 实验①：Lookup Join 基本姿势 =====
SELECT o.item_id, d.item_name, o.amt
FROM orders_stream AS o
JOIN dim_item FOR SYSTEM_TIME AS OF o.proc AS d
  ON o.item_id = d.id;
-- 观察：订单流每条都补上了 item_name（截图①）
-- 语法要点：FOR SYSTEM_TIME AS OF o.proc = "查这一单发生时有效的维表版本"

-- ===== 实验②：缓存参数（生产必配）=====
-- WITH 里给维表加（datagen 模拟下参数被忽略但语法要会）：
--   'lookup.cache.max-rows' = '10000',   缓存 1 万条
--   'lookup.cache.ttl' = '10min'         缓存 10 分钟
-- 推演（记录到笔记）：10 分钟内同商品重复订单 → 全走缓存零回源；
--   缓存 miss 才查库。回源 QPS ≈ 流量 × (1 − 缓存命中率)
--   —— TTL 和库的更新频率匹配：商品名一天改一次，TTL 10 分钟完全够

-- ===== 实验③：三方案对比推演（填表）=====
-- 填《维表关联选型卡》：维表 1 万行/日更 1 次 → 广播；
-- 维表 500 万行/日更 → Lookup+缓存；维表 10 万行/分钟级更新 → CDC
```

```
《维表关联选型卡》（三行决策）：
  维表小(<10万)+低频更新  → 广播（零回源最快）
  维表大+低频更新        → Lookup Join + 缓存（max-rows+ttl）
  更新频繁+要求准实时     → CDC 同步维表（binlog→Kafka→关联）
  铁律：任何方案都要答"数据旧多久可以接受"——新鲜度是业务约束不是技术默认
```

## 4. 面试连接

**Q：实时链路里流和维表怎么关联？维表很大怎么办？（高频方案题）**
> 按维表大小和更新频率三选一。维表小且更新慢：启动时全量广播到每个并行实例，本地查，零回源——和 Spark 广播 Join 同构。维表大但更新慢：Lookup Join 带缓存——流上按 key 现查，查过的缓存起来（max-rows+ttl 两参数），缓存 miss 才回源，回源量取决于缓存命中率；TTL 按业务可容忍的陈旧度定，商品名这类一天改一次的给 10 分钟很安全。维表更新频繁且要求准实时：上 CDC——用 Flink CDC/Canal 读 MySQL binlog，把维表变更实时同步到 Kafka 或直接用 CDC 流做时态关联（FOR SYSTEM_TIME AS OF 按版本关联）。我做过参数推演：10 分钟 TTL 下回源 QPS ≈ 流量×(1−命中率)，命中率可以靠"同 key 局部性"优化。这道题的满分尾巴是反问业务："维表更新后，下游多久看到新值可以接受？"——新鲜度约束决定方案，这是把技术题答成方案题的关键。

**Q：什么是 CDC？为什么它对实时数仓这么重要？（概念题，M13+ 还会用）**
> CDC（Change Data Capture）是"捕获数据库的变更并实时外发"——典型实现读 MySQL 的 binlog（数据库天然记的流水账），把每条 INSERT/UPDATE/DELETE 变成消息。对实时数仓它解决三件事：一是 ODS 层实时化——业务库的变更实时进 Kafka，实时数仓的数据源不再只有埋点；二是维表同步——字典表的变更自动跟随，不用人工刷缓存；三是替代"定时全量抽取"——传统 Sqoop/JDBC 每小时全量拉一遍，CDC 是增量流，压力小新鲜度高。和 M13 之后的 AI 场景还有个连接：知识库的增量更新也会用类似思路。风险也要会讲：binlog 解析对库有侵入（要开 row 格式）、消息顺序要保（同 key 变更按序）、schema 变更（加字段）要有应对——CDC 不是免费午餐。

## 5. 今日验收清单

- [ ] 三方案（广播/Lookup+缓存/CDC）的选型逻辑能白板讲
- [ ] Lookup Join 语法（FOR SYSTEM_TIME AS OF）+ 缓存参数能写
- [ ] 实验①③完成（跑通+选型卡填写）
- [ ] CDC 概念（binlog→消息流）+ 三个价值能讲
- [ ] "新鲜度是业务约束"的观点能讲
- [ ] `git add . && git commit -m "day12-13: dim-join"`

---
[← Day 12](day12-SQL窗口与TopN.md) | [本月目录](README.md) | [Day 14 · 第二周复盘 →](day14-第二周复盘.md)
