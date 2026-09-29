# Day 10 · 反压与积压排查：下游堵车，一路堵到上游

> **今日目标**：实时作业的第一故障——反压（Backpressure）：什么是反压、怎么在 UI 上认出它、五步排查法（发现→定位→归因→处置→验证）。这是面试"生产事故"类问题的最佳素材，也是项目周上线前必须会的手艺。
> **时长**：反压原理 1h / 五步法+演练 3h / 输出 1h
> **今日产出**：反压演练记录（人为制造+定位）+ 《五步排查卡》+ 反压与 Kafka Lag 的联动监控笔记

## 1. 知识地图（先讲人话：堵车的传导）

```
什么是反压？
  下游算子处理不过来（ Sink 写库慢/窗口大状态/算子逻辑重），
  它的输入缓冲区堆满 → 告诉上游"别发了" → 上游停手 →
  压力沿数据链一路传导回 Source → Source 停止拉取
  —— 类比：高速公路收费口（Sink）收费慢 → 车流堵回去 → 入口（Source）限流
  —— 好的一面：这是 Flink 的自我保护（不丢数据，硬背压）
  —— 坏的一面：消费速度 < 生产速度 → Kafka 积压（Lag 上涨）→ 大屏延迟飙

先分清两个词（面试常混）：
  反压 Backpressure：Flink 作业内部的"憋流"现象
  积压 Lag：Kafka 消费跟不上生产的消息堆积（M11 day04 讲过 Lag）
  —— 因果链：反压（果在 Flink）→ 消费变慢 → Lag 上涨（果在 Kafka）
     监控 Lag 是发现反压的第一信号（Kafka 侧），UI busy% 是定位手段（Flink 侧）

五步排查法（背下来，day24 上线检查单也用它）：
  ① 发现：Kafka Lag 持续上涨 / Flink UI 作业状态红 / 大屏延迟告警
  ② 定位：UI 的 Backpressure 标签页 → 看 busy%（忙度）——
     busy% 最高的算子就是瓶颈（或它的下游慢把它憋忙了）
     辅助：busyTime/backPressuredTime 两列，后者高=它在被憋
  ③ 归因（三大常见根因）：
     a) 窗口/状态太重：大窗口+高基数 key+HashMap 后端 → 状态膨胀
     b) Sink 写入慢：逐条写库/目标库抖动 → 批量化/提升并行度
     c) 热点 key：某 key 的流量碾压（同 M11 倾斜，加盐/局部聚合）
  ④ 处置：提并行度（≤源分区数上限）/ 状态换 RocksDB / Sink 批量化 /
     热点局部聚合
  ⑤ 验证：Lag 回落到 0 附近且平稳、Checkpoint 时长恢复正常、
     大屏延迟回到 SLA 内
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| Backpressure | 反压 | 下游慢导致上游被憋（堵车传导） |
| Lag | 积压 | Kafka 消费落后生产的消息数（M11 老朋友） |
| busy% | 忙度 | 算子真正干活的时间占比（越高越可能是瓶颈） |
| backPressured | 被反压 | 算子在"等下游"的时间占比（高=它无辜，下游慢） |
| Credit-Based Flow Control | 信用流控 | 下游发"信用额度"，上游按额度发数据 |
| Sink Throughput | 输出吞吐 | 下水口的排水能力（常是堵的根源） |

## 3. 动手实操：人为制造反压并五步定位

```sql
-- ===== 步骤 1：造一个"故意很慢的 Sink"制造反压 =====
CREATE TABLE bp_src (
  uid INT, amt DOUBLE,
  et AS CURRENT_TIMESTAMP,
  WATERMARK FOR et AS et - INTERVAL '3' SECOND
) WITH (
  'connector' = 'datagen', 'rows-per-second' = '200',   -- 高速进水
  'fields.uid.kind' = 'random', 'fields.uid.min' = '1', 'fields.uid.max' = '500',
  'fields.amt.min' = '10', 'fields.amt.max' = '500'
);

-- 慢 Sink 模拟：用 datagen sink 加 sleep？1.18 SQL 无 sleep——
-- 改用"重状态"制造处理慢：高基数 GROUP BY（模拟状态重的算子）
CREATE TABLE bp_out (uid INT, total DOUBLE) WITH ('connector' = 'print');
SET 'execution.checkpointing.interval' = '10s';
-- 把 uid 基数开大（改 src 的 max=500000）+ print 逐条输出：
INSERT INTO bp_out SELECT uid, SUM(amt) FROM bp_src GROUP BY uid;
-- 200 行/秒 × 50 万基数 × print 逐条 —— mini 集群扛不住，反压就出现了
```

```
步骤 2：五步法实战走一遍（边做边记录）
① 发现：UI 作业页算子状态可能有 busy 红色标记；
   若接 Kafka，docker exec kafka 查 Lag：
   kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group <gid>
② 定位：UI → 作业 → 点算子 → Backpressure 标签：
   看 busy%/backpressured%/idle% 三列——
   预期：GROUP BY 算子 busy% 接近 100%（它就是瓶颈）；Source 被憋
③ 归因：本例=高基数状态+print 逐条写（两大根因叠加）
④ 处置演练（逐个试，观察 UI 变化）：
   a) 提并行度：SET 'parallelism.default' = '2' 重跑（注意 Slot 够不够）
   b) 降负载：rows-per-second 200→50（源头限流，验证瓶颈归因）
   c) 记录"正解"：生产上应该是 Sink 批量化+状态降基数
⑤ 验证：busy% 回落到 <70%、Checkpoint 时长恢复（记录前后数字）
```

```
《五步排查卡》（贴墙版）：
  发现：Lag 上涨/UI 红/延迟告警 → 定位：Backpressure 页 busy% 最高者
  → 归因三选一：状态重？Sink 慢？热点 key？ → 处置：并行度/批量化/加盐
  → 验证：Lag 归零+CKPT 时长正常+延迟回 SLA
```

## 4. 面试连接

**Q：线上 Flink 作业积压了怎么排查？（必考故障题，五步法是骨架）**
> 我按五步走。发现阶段看三个信号：Kafka 消费组 Lag 持续上涨、Flink UI 算子状态变红、大屏数据延迟告警——Lag 是最灵敏的先行指标。定位用 UI 的 Backpressure 标签：看每个算子的 busy% 和 backpressured%，busy% 接近 100% 的算子就是瓶颈；如果一个算子 backpressured% 很高，说明它是无辜的，压力在它的下游——顺藤摸瓜。归因按三大根因对号：状态重（大窗口+高基数 key，看 Checkpoint 的 State Size 和 Duration 是否一起变大）、Sink 慢（逐条写库/目标库抖动）、热点 key（个别 key 流量碾压，M11 的倾斜同款）。处置对应三招：提并行度（受源分区数上限约束）、Sink 批量化或换幂等批量写、热点做局部聚合+全局合并。验证必须闭环：Lag 回落到 0 附近、Checkpoint 时长恢复正常、大屏延迟回 SLA——三个都满足才算解决。我演练过完整过程：把 datagen 提到 200 行/秒配高基数聚合，人为复现反压，UI 定位到聚合算子 busy 100%，降负载+提并行度后 Lag 归零——这套手艺的底子是"先定位再归因后处置"，跟 M11 排查 Spark 倾斜是同一门诊断学。

**Q：为什么 Flink 选择"背压"而不是像 Spark 那样丢数据或缓冲到底？（考设计理解）**
> 因为流计算的第一约束是"不丢"。Spark 微批丢了可以在下批补（批有重跑语义），流作业的数据流过去就过去了，所以 Flink 用信用流控：下游给上游发"信用额度"，有多少额度发多少数据——源头限流，绝不压垮下游，也绝不丢数据。代价是"全局耦合"：一个 Sink 慢会传导到整个链路（这就是反压），所以反压治理的本质是找到并修好那个最慢的环节，而不是调大缓冲区硬扛——缓冲区调大只是把爆炸时间往后推，Lag 还在涨。

## 5. 今日验收清单

- [ ] 反压 vs Lag 的因果链能讲
- [ ] 五步法背熟+演练全流程（制造→定位→归因→处置→验证）
- [ ] busy% / backpressured% / idle% 三列的读法能教别人
- [ ] 《五步排查卡》成文贴墙
- [ ] "为什么选背压"的设计观能讲
- [ ] `git add . && git commit -m "day12-10: backpressure"`

---
[← Day 09](day09-ExactlyOnce端到端.md) | [本月目录](README.md) | [Day 11 · Flink SQL 入门 →](day11-FlinkSQL入门.md)
