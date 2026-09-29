# Day 09 · Exactly-Once 端到端：三段拼图，一段都不能少

> **今日目标**：搞懂"端到端精确一次"的完整拼图——Source 可重放+状态一致+输出幂等/事务，并与 M11 的 Kafka 消费语义三段拼图对上号；理解为什么只有 Flink Checkpoint 还不够。
> **时长**：语义分层 1.5h / 三段拼图精讲 2h / 对照输出 1.5h
> **今日产出**：端到端语义对照卡（Flink 版 vs Kafka 版）+ 《Sink 幂等选型表》

## 1. 知识地图（先讲人话：快递全链路不丢不重）

```
先纠正一个常见误解：
  "Flink 开了 Checkpoint 就是 Exactly-Once"——错！
  Checkpoint 只保证【Flink 内部】的状态一致（计算这一段）。
  数据从 Kafka 进、到外部系统出，整条链路叫"端到端"——
  每一段的语义都要对，才能claim 端到端精确一次。

完整拼图（三段，缺一不可）：
  ┌─────────┐   ┌──────────┐   ┌─────────┐
  │ ① Source │ → │ ② 计算+状态 │ → │ ③ Sink  │
  │ 可重放    │   │ Checkpoint │   │ 幂等/事务 │
  └─────────┘   └──────────┘   └─────────┘

  ① Source 可重放：挂了能"倒带"
     Kafka Source：offset 存进快照（day03/08 已讲），恢复时从 offset 重放 ✓
     —— 对照 M11：这就是消费端"至少一次"的底座（可重放才可能重）
  ② 计算一致：Checkpoint（day08）状态快照 ✓
  ③ 输出不重复：两选一
     a) 幂等写：重放写 100 次，结果和写 1 次一样
        —— upsert（按主键覆盖）是万能钥匙：MySQL ON DUPLICATE KEY /
           ClickHouse ReplacingMergeTree / ES doc _id / Redis SET
     b) 事务写：两阶段提交（2PC）
        —— Kafka Sink：Checkpoint 前预提交（数据写到事务里但不提交），
           快照成功后正式提交；快照失败则回滚
        —— 像"先填快递单不发货，确认无误再发货"
     对比：幂等简单但有约束（要有主键）；事务通用但延迟大
          （结果要等 Checkpoint 完成才对外可见）

对照 M11 的 Kafka 语义拼图（跨月连线，面试大杀器）：
  M11：生产端幂等(PID+序列号) + 服务端副本 + 消费端幂等三件套
       —— "精确一次"靠各段自己兜
  M12：Flink 把三段统一编排——Source offset 进快照 + 状态快照 +
       Sink 事务/幂等，由 Checkpoint 统一协调
       —— "精确一次"成了平台能力，业务少操心
  —— 一句话总结：Kafka 的 EO 是"组装货"，Flink 的 EO 是"整机"
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| At-Most-Once | 至多一次 | 可能丢，绝不重（快递可能丢件不重复送） |
| At-Least-Once | 至少一次 | 绝不丢，可能重（快递保送但可能送两次） |
| Exactly-Once | 精确一次 | 不丢不重（端到端意义上） |
| Idempotent Sink | 幂等输出 | 重放写 N 次=写 1 次（按主键覆盖） |
| 2PC / Two-Phase Commit | 两阶段提交 | 先预提交再正式提交（填单确认再发货） |
| Replay | 重放 | 倒带：从 offset 重新读一段 |

## 3. 动手实操：三种 Sink 语义体感

```sql
-- 场景：同一个聚合，分别写三种 Sink，观察恢复后的重复情况

-- ===== 准备：源+聚合（沿用 day08 作业结构）=====
CREATE TABLE e2e_src (
  uid INT, amt DOUBLE,
  et AS CURRENT_TIMESTAMP,
  WATERMARK FOR et AS et - INTERVAL '3' SECOND
) WITH (
  'connector' = 'datagen', 'rows-per-second' = '10',
  'fields.uid.kind' = 'random', 'fields.uid.min' = '1', 'fields.uid.max' = '20',
  'fields.amt.min' = '10', 'fields.amt.max' = '500'
);
SET 'execution.checkpointing.interval' = '3s';

-- ===== 实验①：Append Sink（追加写=注定重复）=====
CREATE TABLE append_sink (uid INT, amt DOUBLE)
WITH ('connector' = 'print');
INSERT INTO append_sink SELECT uid, amt FROM e2e_src;
-- kill TM → 恢复后：print 日志里 datagen 之外，若真接 Kafka 源
-- 重放的那段数据会被【再次输出】——append 语义没有"已经写过"的记忆
-- 结论：append sink 只能 At-Least-Once（配下游去重）

-- ===== 实验②：Upsert Sink（幂等=重复无害）=====
-- 模拟按主键覆盖（真实场景：JDBC sink 的 ON DUPLICATE KEY UPDATE）
CREATE TABLE upsert_sink (
  uid INT,
  total DOUBLE,
  PRIMARY KEY (uid) NOT ENFORCED          -- 声明主键 → sink 按 key 覆盖
) WITH ('connector' = 'print');           -- print 也支持 upsert changelog
INSERT INTO upsert_sink
SELECT uid, SUM(amt) AS total FROM e2e_src GROUP BY uid;
-- 观察：同一 uid 反复输出更新值（changelog）——写多少次结果都收敛正确
-- kill TM → 恢复 → SUM 从快照续算，最终值依然正确（重复写被主键吸收）
-- 结论：Upsert 是"以结果幂等换过程简单"——实时大屏/汇总表首选

-- ===== 实验③：事务 Sink 的延迟代价（讲清原理即可）=====
-- Kafka Sink 开事务：'sink.transactional-id' + delivery-guarantee=exactly-once
-- （需要 kafka connector，有则试跑；没有则记录参数理解结论）
-- 关键观察：下游要等 Checkpoint 完成（实验设 3s）才能读到数据——
-- 事务语义把"可见性延迟"绑定到了 Checkpoint 间隔
-- 结论：事务=通用但慢；秒级大屏多用 upsert+短 CKPT 间隔
```

```
《Sink 幂等选型表》：
  MySQL        → ON DUPLICATE KEY UPDATE（upsert）
  ClickHouse   → ReplacingMergeTree / 去重键
  Elasticsearch→ 固定 _id（doc 覆盖）
  Redis        → SET/HSET（天然按 key 覆盖）
  Kafka        → 事务 2PC 或下游幂等消费（M11 day08 三件套）
```

## 4. 面试连接

**Q：Flink 的 Exactly-Once 是怎么实现的？开了 Checkpoint 就够了吗？（本月核心必考题）**
> 不够——这是本题的第一采分点。Checkpoint 只保证 Flink 内部状态一致，端到端精确一次需要三段拼图齐活：第一段 Source 可重放——Kafka 的 offset 存进状态快照，恢复时从快照 offset 重放；第二段计算一致——Checkpoint 保证算子状态在故障前后一致；第三段输出幂等或事务——Sink 侧要么按主键幂等覆盖（重放写多少次结果都收敛），要么两阶段提交（Checkpoint 前预提交、成功后正式提交，失败回滚）。我实际对比过两种 Sink 路线：append 追加写在恢复后必重复，只能配合下游去重；upsert 汇总表靠主键吸收重复，是实时大屏的首选；事务写通用但可见性延迟绑在 Checkpoint 间隔上。跨月对照我最想强调：M11 学 Kafka 时精确一次靠"生产幂等+副本机制+消费幂等"三段各自兜底，是组装货；Flink 把三段编排进 Checkpoint 协议里，端到端语义成了平台能力——这也解释了为什么实时数仓的主流选型是"Kafka 管流动+Flink 管加工"：流动端保可靠，加工端保语义，各司其职。

**Q：At-Least-Once 够用的场景还折腾 Exactly-Once 吗？（考成本意识）**
> 很多场景真的够——判断标准是"下游能不能吸收重复"：写 ClickHouse 用 ReplacingMergeTree、写 ES 固定 _id、写 MySQL upsert，重复天然被吸收，直接 At-Least-Once+幂等 Sink 就等效精确一次，还省掉事务的可见性延迟。真正必须 2PC 的是"下游没有幂等能力且不能重"的场景，比如把明细原样转写给无主键的存储、或者金融流水类的 append 场景。我的原则：先看 Sink 有没有主键/覆盖能力，有就幂等路线（简单快），没有才上事务——语义等级是成本，按需购买不盲目堆配。

## 5. 今日验收清单

- [ ] "Checkpoint≠端到端 Exactly-Once"能开门见山讲
- [ ] 三段拼图各段机制+对照 M11 Kafka 版能白板画
- [ ] 实验①②完成（append 重复 vs upsert 幂等体感）
- [ ] 《Sink 幂等选型表》成文
- [ ] "语义按需购买"的成本观点能讲
- [ ] `git add . && git commit -m "day12-09: exactly-once"`

---
[← Day 08](day08-Checkpoint与Barrier.md) | [本月目录](README.md) | [Day 10 · 反压与积压排查 →](day10-反压与积压排查.md)
