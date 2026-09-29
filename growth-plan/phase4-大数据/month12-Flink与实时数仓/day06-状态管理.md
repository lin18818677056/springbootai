# Day 06 · 状态管理：流计算的小本本，忘了清就 OOM

> **今日目标**：搞懂 Flink 的"记忆"——为什么流计算必须有状态；Keyed State 和算子状态的分工；状态后端（HashMap vs RocksDB）怎么选；状态 TTL 为什么是"一切状态都要问的问题"。实验：观察状态大小+亲手给状态定寿命。
> **时长**：状态原理 1.5h / 后端与 TTL 实验 2.5h / 输出 1h
> **今日产出**：状态分类图 + 《状态后端选型表》+ 《状态上线自检四问》

## 1. 知识地图（先讲人话：记账的小本本）

```
为什么流计算必须有状态？
  "每个用户的今日累计消费"——处理第 100 条数据时，
  你必须记得前 99 条里这个用户已经花了多少
  —— 这个"记得"就是状态（State）：算子本地的小本本
  —— 对照：Spark 每批都从源头重算（无状态思维）；
     Flink 常驻作业，中间结果记在本子上持续更新（有状态思维）
  —— 状态是双刃剑：功能全靠它（累计/去重/Join/CEP），
     事故也多在它（OOM/恢复慢/升级迁移）——生产 70% 的 Flink 故障跟状态有关

状态的分类（两张小本本）：
  ① Keyed State 键控状态（最常用）：
     按 key 隔离——"每个商品一个小本本"，同 key 的数据都找同一本
     种类：ValueState(单值)/ListState(列表)/MapState(映射)/ReducingState(聚合)
     —— 天然按 key 分片到各并行实例（keyBy 之后才能用）
  ② Operator State 算子状态：
     算子并行实例自己的本本，不按 key 分（如 Kafka Source 的 offset 账本）
     —— day03 说过：offset 存这里，Checkpoint 时一起快照

状态后端 State Backend（本本放哪）：
  HashMap 状态后端：JVM 堆内存——快，但状态大了 OOM（状态 <几 GB 时用）
  RocksDB 状态后端：本地磁盘（带堆外序列化）——状态可 TB 级、
    支持增量 Checkpoint，稍慢（多一层序列化/磁盘 IO）
  —— 选型一句话：小状态求快用 HashMap，大状态求稳用 RocksDB

状态 TTL（本本的"忘性"）：
  一切状态都要问：它什么时候可以忘？
  "用户 7 天内访问记录"→ TTL 7 天；"今日 UV"→ TTL 到次日零点
  —— 不设 TTL 的状态=无界增长=一个月后必 OOM（本月上线检查单第一问）
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| State | 状态 | 算子本地的"小本本"（中间结果持续记忆） |
| Keyed State | 键控状态 | 按 key 一本账（每商品/每用户的独立记录） |
| Operator State | 算子状态 | 并行实例自己的账（如 Source 的 offset） |
| State Backend | 状态后端 | 本本放哪：堆内存(快) vs RocksDB(大而稳) |
| State TTL | 状态生存时间 | 本本的"忘性"：多久可以擦掉 |
| Checkpoint | 检查点 | 小本本的定期存档（day08 详讲） |
| 增量快照 | Incremental CKPT | 只存档"变化的那几页"（RocksDB 专属能力） |

## 3. 动手实操：观察状态 + 设 TTL

```sql
-- ===== 实验①：跑一个"有状态"作业，UI 看状态大小 =====
-- 按 uid 去重计数（每用户首单数）——典型的键控状态场景
CREATE TABLE orders2 (
  uid INT, amt DOUBLE,
  et AS CURRENT_TIMESTAMP,
  WATERMARK FOR et AS et - INTERVAL '3' SECOND
) WITH (
  'connector' = 'datagen', 'rows-per-second' = '10',
  'fields.uid.kind' = 'random', 'fields.uid.min' = '1', 'fields.uid.max' = '200',
  'fields.amt.min' = '10', 'fields.amt.max' = '500'
);

CREATE TABLE print2 (uid INT, cnt BIGINT) WITH ('connector' = 'print');

-- 每 uid 计数（GROUP BY 常驻聚合 = 隐式键控状态）
INSERT INTO print2 SELECT uid, COUNT(*) FROM orders2 GROUP BY uid;
-- UI → 作业 → Checkpoints 页：看 State Size 列持续增长（截图①）
-- 观察：uid 上限 200，账本很快封顶——把 max 改 200 万重跑，状态继续涨
-- 结论记录：状态大小 ∝ key 基数 × 单条状态大小——控制 key 基数是第一杠杆

-- ===== 实验②：状态 TTL（SQL 方式，给"忘性"设表）=====
SET 'table.exec.state.ttl' = '10 s';   -- 全局 TTL：10 秒没更新就擦
-- 重跑一个 GROUP BY 作业，观察 UI State Size：
-- TTL 生效后状态大小会在峰值附近波动（擦了旧账）而不是无限涨（截图②）
-- 生产写法（按表声明，不是全局 SET）：
-- CREATE TABLE ... WITH ('format'='json') 后在作业级配置：
-- table.exec.state.ttl = 86400 s （今日 UV 场景给一天）

-- ===== 实验③：RocksDB 后端切换（配置体验）=====
-- 停掉当前作业；改 TM 配置声明 RocksDB：
docker exec flink-tm bash -c "grep -n 'state.backend' /opt/flink/conf/flink-conf.yaml"
-- 1.18 默认已是 hashmap（实际配置键：state.backend: hashmap / rocksdb）
-- 切换：FLINK_PROPERTIES 加 state.backend: rocksdb 重启 TM 容器
docker stop flink-tm; docker rm flink-tm
docker run -d --name flink-tm --network bigdata `
  -e FLINK_PROPERTIES="jobmanager.rpc.address: flink-jm`ntaskmanager.numberOfTaskSlots: 4`nstate.backend: rocksdb" flink:1.18 taskmanager
-- 重跑实验①：作业照常跑、Checkpoints 页 State Size 依然可见（截图③）
-- 体感差异：本实验数据量小看不出快慢——记住选型表结论即可
```

## 4. 面试连接

**Q：讲讲 Flink 的状态管理？Keyed State 和 Operator State 区别？（必考基础题）**
> 先讲为什么要有状态：流计算是常驻的、逐条处理的，"累计值/去重/Join 缓存"这些功能都必须"记得"历史——这个记忆就是状态，它是 Flink 功能的根基，也是生产事故的重灾区。分类上：Keyed State 按 key 隔离，每个 key 一本独立账（ValueState/ListState/MapState/ReducingState 四种形态），是最常用的（每商品累计销量、每用户会话计数）；Operator State 是算子并行实例自己持有，不按 key 分，典型如 Kafka Source 的 offset 账本——offset 和处理进度绑在同一份快照里，这是精确一次的基础（day09）。存储上，状态后端两选一：HashMap 后端放 JVM 堆，快但受堆大小限制，适合几 GB 内的小状态；RocksDB 后端落本地磁盘，支持 TB 级状态和增量 Checkpoint，代价是多一层序列化，大状态作业的首选。最后一定要讲 TTL：一切状态都要问"什么时候可以忘"——不设 TTL 的状态是无界的，线上见过按 uid 存访问记录不设 TTL，三天后 OOM 的案例；TTL 按业务寿命设（今日指标到零点、7 天留存 7 天），这是我上线自检的第一条。

**Q：状态太大怎么办？（生产调优高频，接 day22）**
> 三板斧：第一板斧降基数——检查 key 设计，能不能把"明细级 key"改成"聚合级 key"（存每个 uid 的记录 vs 只存聚合值，状态差几个量级）；第二板斧设 TTL——无界状态变有界，配合"冷数据归档"（超时状态落外部存储按需回查）；第三板斧换后端——HashMap 撑不住就 RocksDB，它的增量 Checkpoint 还能把快照成本也降下来（day22 详讲，包括反压下 Checkpoint 超时的对策）。我的原则：先问"这个状态可以忘吗"，再问"这个状态必须这么细吗"，最后才动后端——顺序反了就是把架构问题当参数问题调。

## 5. 今日验收清单

- [ ] "为什么要有状态"+两类状态+四种 Keyed State 形态能讲
- [ ] 状态后端选型表（HashMap vs RocksDB 三行对比）能白板画
- [ ] 实验①②③完成（状态增长观察/TTL 生效/后端切换）
- [ ] 《状态上线自检四问》成文（可忘吗/多细/多大/TTL 几何）
- [ ] "状态太大三板斧"的顺序能讲（先业务后参数）
- [ ] `git add . && git commit -m "day12-06: state"`

---
[← Day 05](day05-窗口.md) | [本月目录](README.md) | [Day 07 · 第一周复盘 →](day07-第一周复盘.md)
