# Day 16 · 项目一 · 明细流：Kafka 进，洗完回 Kafka

> **今日目标**：项目周第 2 天——搭实时数仓的第一段管道：消费 app_event 埋点流 → Flink 清洗（过滤/去重/补字段）→ 写入 DWD 明细主题。这是 M11 day25 离线清洗的"实时版"：同一套清洗逻辑，从批姿势换成流姿势。
> **时长**：清洗逻辑设计 1h / SQL 作业开发 2.5h / 验证 1.5h
> **今日产出**：dwd_order_rt 明细流跑通 + 清洗规则验证记录 + 双姿势对照笔记

## 1. 项目设计（先讲人话：这一段管道干什么）

```
输入：Kafka 主题 app_event（M11 建过，埋点 JSON 消息）
  {"ts":"2026-09-27 10:23:01","uid":"1001","action":"click","page":"home","item_id":"9001"}

输出：Kafka 主题 dwd_order_rt（清洗后的 DWD 明细流）
  {"uid":"1001","action":"click","page":"home","item_id":"9001",
   "click_time":"2026-09-27 10:23:01","is_new":"0"}

清洗规则（对照 M11 day25 离线清洗四件事，全部平移到流上）：
  ① 过滤脏行：缺 uid/缺 ts 的直接丢（流的姿势：WHERE）
  ② 去重：重试导致的消息重复——
     流上没法像批那样"全量 ROW_NUMBER"（数据永远不齐）！
     流的姿势：按消息 ID 记状态去重（幂等思想）——
     简化版：当日 uid+action+item_id+秒级时间 已见过则丢
     （这个"见过"的状态要设 TTL——day06 的教训直接用上）
  ③ 格式补全：字符串时间转 timestamp、补 is_new 标记
  ④ 输出定型：字段顺序和类型固定（下游好接）

环境说明（重要，两种路径都走通）：
  正路径：有 kafka connector → 真 Kafka 进出
  降级路径：无 connector → datagen 模拟"埋点流"，
    清洗逻辑完全一致，day19 对拍时说明数据源差异即可
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| Dedup in Stream | 流上去重 | 不能等"全量到齐"，按状态记"见过没有" |
| Deduplication Row-time | 去重（按行时间） | SQL 内置：按时间+key 保留第一条/最后一条 |
| Event-time Dedup | 事件时间去重 | 用水位线范围内的历史做判重依据 |
| UDF-free Parsing | 免 UDF 解析 | JSON 字段用内置函数提取（不用写 Java） |
| DWD Stream | 明细流 | 清洗定型后的明细（下游聚合的统一入口） |

## 3. 动手实操：两条路径跑通明细流

```sql
-- ===== 正路径：真 Kafka 进出（有 connector 时）=====
CREATE TABLE ods_event (
  raw STRING,
  et AS CURRENT_TIMESTAMP,
  WATERMARK FOR et AS et - INTERVAL '5' SECOND
) WITH (
  'connector' = 'kafka',
  'topic' = 'app_event',
  'properties.bootstrap.servers' = 'kafka:9092',
  'properties.group.id' = 'flink_dwd',
  'scan.startup.mode' = 'latest',
  'format' = 'raw'                      -- 原样一行文本（JSON 解析下一步做）
);

CREATE TABLE dwd_order_rt (
  uid STRING, action STRING, page STRING,
  item_id STRING, click_time TIMESTAMP(3)
) WITH (
  'connector' = 'kafka',
  'topic' = 'dwd_order_rt',
  'properties.bootstrap.servers' = 'kafka:9092',
  'format' = 'json'
);

-- 清洗作业：解析+过滤+补全（去重简化为规则注释，见下）
INSERT INTO dwd_order_rt
SELECT
  get_json_string(raw, '$.uid'),
  get_json_string(raw, '$.action'),
  get_json_string(raw, '$.page'),
  get_json_string(raw, '$.item_id'),
  CAST(get_json_string(raw, '$.ts') AS TIMESTAMP(3))
FROM ods_event
WHERE get_json_string(raw, '$.uid') IS NOT NULL     -- ① 过滤脏行
  AND get_json_string(raw, '$.ts') IS NOT NULL;
-- ② 去重（流式姿势，生产写法）：外面包一层 ROW_NUMBER() OVER
--    (PARTITION BY uid,action,item_id,秒级ts ORDER BY et) 取 rn=1
--    —— 并配状态 TTL（当日有效），day06+day11 知识的组合运用
```

```sql
-- ===== 降级路径：datagen 模拟（无 connector 时，逻辑等价）=====
CREATE TABLE ods_event_fake (
  uid STRING, action STRING, page STRING, item_id STRING, ts_str STRING,
  et AS CURRENT_TIMESTAMP,
  WATERMARK FOR et AS et - INTERVAL '5' SECOND
) WITH (
  'connector' = 'datagen', 'rows-per-second' = '5',
  'fields.uid.kind' = 'random', 'fields.uid.length' = '4',
  'fields.action.kind' = 'random',
  'fields.action.chars' = 'clickviewpay',   -- 模拟动作字符
  'fields.action.length' = '5',
  'fields.page.kind' = 'random', 'fields.page.length' = '4',
  'fields.item_id.kind' = 'random',
  'fields.item_id.min' = '1', 'fields.item_id.max' = '50',
  'fields.ts_str.kind' = 'random', 'fields.ts_str.length' = '10'
);
CREATE TABLE dwd_rt_print (
  uid STRING, action STRING, page STRING, item_id STRING, click_time TIMESTAMP(3)
) WITH ('connector' = 'print');
INSERT INTO dwd_rt_print
SELECT uid, action, page, item_id,
       CAST(ts_str AS TIMESTAMP(3))
FROM ods_event_fake
WHERE uid IS NOT NULL AND action IS NOT NULL;
-- TaskManager 日志看清洗后的输出（截图）
```

```powershell
# ===== 验证（正路径）=====
# 1) 发一条带脏数据的消息到 app_event：
docker exec -it kafka kafka-console-producer.sh --bootstrap-server kafka:9092 --topic app_event
#   发：{"action":"click"}      ← 缺 uid：预期被过滤不出现
#   发：{"ts":"...","uid":"2001","action":"pay","page":"pay","item_id":"9002"}  ← 正常：出现
# 2) 消费 DWD 主题验证输出：
docker exec kafka kafka-console-consumer.sh --bootstrap-server kafka:9092 `
  --topic dwd_order_rt --from-beginning --max-messages 5
# 记录：脏行过滤 N 条/正常透传 M 条（截图+数字）
```

## 4. 面试连接

**Q：实时清洗和离线清洗的做法有什么不同？去重怎么处理？（项目核心题）**
> 清洗规则一样（过滤脏行/去重/格式补全/字段定型），最大的不同是"数据视角"：离线是全量视角——数据到齐了，可以全量 ROW_NUMBER 去重、全量统计脏行数；流是增量视角——数据永远不齐，去重不能"回头看全量"，只能靠状态记"这个 key 我见过没有"。我的流上去重写法：按"业务键+秒级时间"分区做 ROW_NUMBER 取第一条（Flink SQL 内置的去重语义），并给去重状态配当日 TTL——既保证幂等又防止状态无界。脏行处理也不同：离线有"隔离区表"（M10 day25 的做法），流上脏行直接过滤+打监控计数（脏行率突增要告警，说明上游埋点挂了），如果要留存就写死信主题。最后是验证方式：离线跑完对账（源 12 条→清洗后 10 条，M11 做过），实时看的是"清洗率曲线"平稳+抽样比对——实时没有"跑完"的概念，验证是持续的。

**Q：为什么 DWD 层要回写 Kafka 而不是直接写 MySQL？（架构理解题）**
> 因为 Kafka 是实时数仓的"分层总线"：DWD 明细回写 Kafka 后，下游可以有 N 个消费者各取所需——GMV 作业、TopN 作业、风控作业、（未来的）推荐特征作业都从同一条明细流读，互不影响；如果直接写 MySQL，一是 MySQL 扛不住明细级写入（几千 QPS 的高频小事务），二是明细被"单点消费"——第二个需求来了就得改第一个作业。层次原则：明细流进总线（Kafka），只有聚合结果才落存储（MySQL/ClickHouse 喂应用）。这和 M10 离线数仓"明细进表、结果进表"的分层思想完全同构——分层的本质是解耦复用，跟存储引擎无关。

## 5. 今日验收清单

- [ ] 明细流跑通（正/降级两条路径至少一条留截图）
- [ ] 清洗四件事的"流版姿势"能讲（重点：去重靠状态+TTL）
- [ ] 脏行过滤验证记录（发脏消息→确认被滤）
- [ ] "DWD 为什么回 Kafka"能讲（分层总线）
- [ ] 双姿势对照笔记（离线全量 vs 流增量）
- [ ] `git add . && git commit -m "day12-16: proj1-dwd"`

---
[← Day 15](day15-实时数仓架构.md) | [本月目录](README.md) | [Day 17 · 项目二 · 实时 GMV →](day17-项目二实时GMV.md)
