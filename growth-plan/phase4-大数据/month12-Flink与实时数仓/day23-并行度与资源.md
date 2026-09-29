# Day 23 · 并行度与资源：给作业配几个工人、多少内存

> **今日目标**：搞懂 Flink 的"人力系统"——并行度（几个工人）、Slot（几个工位）、内存（多大车间）；把 M2 的"吞吐倒推法"搬到 Flink 上，给作业算出并行度并用 UI 验证。
> **时长**：概念 1h / 实操 2h / 输出 0.5h
> **今日产出**：三组调参实验记录 + 《并行度倒推卡》

## 1. 知识地图（先讲人话）

```
一个 Flink 作业 = 一条流水线，每个工位（算子）可以安排多名工人：

  source ──→ map ──→ keyBy ──→ 聚合 ──→ sink
   ×2         ×2        ×2        ×2        ×2   ← 并行度=2：每个算子 2 份同时干活

三个词别搞混（面试爱挖）：
  并行度 Parallelism：每个算子复制几份并行干活（工人人数）
  Slot：TaskManager 里的"工位"，一个 TM 配几就有几个工位
  算子链 Operator Chain：并行度相同的相邻算子绑成一组，数据串着传不落网
  —— 相当于"相邻工位合并、一个工人顺手全干"（Spark 的 pipeline 是远房表亲）

约束关系（一句话版，背下来）：
  作业需要的 slot 数 = 最大算子并行度（不是各算子求和！）
  并行度相同的相邻算子才可能链在一起；并行度变化点 = 数据要"搬家"（网络传输点）

吞吐倒推法（M2 老方法，跨月第 N 次上场）：
  目标吞吐 10000 条/s ÷ 单并行实测 2000 条/s ≈ 5 并行
  → 留 50% 余量 → 8 并行（永远别顶满，顶满的下一秒就是反压）

keyBy 的隐藏天花板（今天的反直觉点）：
  keyBy 把数据按 key 搬给对应工人；key 只有 3 个却开 10 并行
  → 7 个工人永远空手站着（资源花了，活一点没多）
  → 并行度上限要查 key 基数：keyBy 后的算子，并行度 ≤ key 基数
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| Parallelism | 并行度 | 每个算子开几份同时干（工人人数） |
| Slot | 槽位 | TM 里的工位，任务必须坐进工位才能干活 |
| Operator Chain | 算子链 | 并行度相同的相邻算子绑组，省中间搬运（day02 老朋友） |
| Slot Sharing | 槽位共享 | 一个 slot 轮流服务一条链上的多个算子（默认开） |
| busyTimeMsPerSecond | 忙碌占比 | 工人忙的时间比例（>80% 是加人信号） |
| maxParallelism | 并行度上限 | keyBy 分组的最大格子数（默认 128×并行度） |

## 3. 动手实操

### 3.1 实验①：看见并行度（UI 上的"格子"）

```sql
-- sql-client 里先改全局并行度再提交
SET 'parallelism.default' = '2';
INSERT INTO print_sink
SELECT uid, COUNT(*) FROM datagen_src GROUP BY uid;
-- 打开 localhost:8081 → 该作业 → 看 vertex 图：
-- 每个 box 里的数字=并行度；source→聚合 并行度相同会被框成一个 chain
```

### 3.2 实验②：吞吐倒推法实测（今天的重头戏）

```sql
-- 第 1 步：单并行测极限（并行度=1，rps 从 500 逐步加）
SET 'parallelism.default' = '1';
-- datagen 表 'rows-per-second' = '2000' 跑一次 → 看 busy%
-- 再改 '5000' 跑一次 → 看 busy%
-- UI → 作业 → 该算子 → Metrics → busyTimeMsPerSecond（0~100%）
-- 预期：rps=2000 时 busy≈40~60%；rps=5000 时 busy 顶到 100%，下游出现反压（day10 现象）
-- 结论记进表格：单并行能扛 ≈3000 条/s
```

```sql
-- 第 2 步：倒推并验证：目标 1 万条/s → 4 并行
SET 'parallelism.default' = '4';
-- 同一张 datagen 表 rps=10000 重跑
-- 预期：busy 回落到 60~70%，无反压 —— 倒推法生效
-- 记录：并行度 / rps / busy% 三列对照表
```

### 3.3 实验③：key 基数天花板（看"空转工人"）

```sql
-- 把 datagen 的 uid 范围改成 1~3（只有 3 个 key），并行度开 4
CREATE TABLE few_keys_src (
  uid INT, amt DOUBLE,
  et AS CURRENT_TIMESTAMP,
  WATERMARK FOR et AS et - INTERVAL '3' SECOND
) WITH (
  'connector' = 'datagen', 'rows-per-second' = '100',
  'fields.uid.kind' = 'random',
  'fields.uid.min' = '1', 'fields.uid.max' = '3',
  'fields.amt.min' = '10', 'fields.amt.max' = '500'
);
-- 并行度 4 跑 GROUP BY uid 聚合：
-- UI → 聚合算子 → 4 个 subtask 的 Records Received：
--   只有 3 个格子有数，1 个永远是 0 —— 那就是空转的工人
```

```
《并行度倒推卡》（贴墙）：
  ① 定目标吞吐：上游峰值 QPS（别按平均值算，按峰值！）
  ② 单并行压测：一人能扛多少条/s（busy≤70% 时的读数）
  ③ 倒推人数：目标 ÷ 单人 + 50% 余量
  ④ 两个天花板检查：keyBy 后并行度 ≤ key 基数；总并行 ≤ 集群 slot 数
  ⑤ 上线后校准：busy 长期 >80% 加人；长期 <20% 减人省钱
```

## 4. 面试连接

**Q：Flink 作业的并行度怎么定？（调优第一问）**
> 我用吞吐倒推法：先拿目标吞吐（上游峰值 QPS），再单并行压测出"一个工人能扛多少"，相除得基础人数，加 50% 余量。然后查两个天花板：keyBy 之后并行度不能超过 key 基数，否则有工人空转；总并行不能超过集群 slot 数，否则作业排队。上线后盯 busy% 持续校准——长期高于 80% 加并行，长期低于 20% 缩容。我实验里单并行扛 3000 条/s，目标 1 万条/s 用 4 并行，busy 稳在 60% 左右，这套数字都是 UI 的 busyTimeMsPerSecond 实测的。

**Q：Slot 数量和并行度什么关系？slot 里跑的是什么？（概念辨析）**
> 作业需要的 slot 数等于最大算子并行度，不是各算子求和——因为默认开 slot sharing，一个 slot 会轮流服务同一条算子链上的不同算子（一个工人兼管相邻几个工位）。slot 是资源隔离单位：TM 内存按 slot 均分，slot 之间互不挤占，slot 内部的任务共享这份内存。所以"1 个 TM 开 4 slot"和"4 个 TM 各 1 slot"总工位数一样，但故障隔离性完全不同——后者一台挂了只影响四分之一的任务，生产上倾向多 TM 少 slot。

## 5. 今日验收清单

- [ ] 并行度/Slot/算子链三者关系能画图讲清
- [ ] 实验①②③完成（rps/busy% 对照表 + 空转工人截图）
- [ ] "作业需要 slot 数=最大并行度"能解释为什么
- [ ] 《并行度倒推卡》五步成文
- [ ] `git add . && git commit -m "day12-23: parallelism"`
- [ ] 笔记：画"流水线+工人+工位"对照图

---
[← Day 22](day22-状态与Checkpoint调优.md) | [本月目录](README.md) | [Day 24 · 生产运维与升级 →](day24-生产运维与升级.md)
