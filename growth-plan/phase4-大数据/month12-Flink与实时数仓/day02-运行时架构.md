# Day 02 · 运行时架构：工头、工人和工位

> **今日目标**：搞懂 Flink 集群的三个角色——JobManager（工头）、TaskManager（工人）、Slot（工位），以及"算子链"这个省钱设计；对照 M11 的 Driver/Executor 建立映射，架构图能白板画。
> **时长**：架构原理 1.5h / UI 观察实验 1.5h / 图解输出 1h
> **今日产出**：Flink 运行时架构图（手绘）+ UI 观察记录 + 《Flink↔Spark 架构对照表》

## 1. 知识地图（先讲人话：工地上的三种角色）

```
Flink 集群 = 一个工地（对照 M11 Spark 的映射直接标在旁边）：

JobManager（工头，每个作业一个）
  · 接活：把你的代码/DataStream 图转成"执行图"，切分并行任务
  · 排班：决定哪个 SubTask 放到哪个 Slot 上跑
  · 救灾：定时组织 Checkpoint（day08），挂了组织从快照恢复
  —— 对应 Spark 的 Driver（总包工头）

TaskManager（工人，常驻干活的进程）
  · 真正执行任务的 JVM 进程，一台机器一个（容器里就是一个容器）
  · 提供数据交换：SubTask 之间把数据"递"给下游
  —— 对应 Spark 的 Executor

Slot（工位，工人手里的资源切片）
  · TaskManager 把自己切成 N 个 Slot（默认按 CPU 核数）
  · 一个 Slot 跑一个（或一串链起来的）SubTask
  · 铁律：同一作业的【不同 SubTask】不能挤同一 Slot，
    但"算子链"（见下）可以串成一串占一个 Slot
  —— Spark 没有精确对应物（Executor 是整块资源，靠线程池分），
     Slot 是 Flink 更粗但更简单的资源隔离单位

算子链 Operator Chain（省钱的串门设计）：
  相邻的"窄依赖"算子（map→filter 这类，无需跨网络洗牌）
  默认串成一个 SubTask——数据在【同一个线程内存里】直接传
  —— 好处：省序列化/省网络/省线程切换，快 2~3 倍
  —— 对照：Spark 的 Pipeline（窄依赖流水线）是同一思想
  —— 什么时候断开：宽依赖（keyBy/ rebalance）强制断，跨网络传输

一张图总结（面试白板版）：
  作业提交 → JobManager 切图排班 → SubTask 分配到各 TM 的 Slot
  → 窄算子串成链在 Slot 里连续跑 → 宽依赖处跨 Slot/跨 TM 走网络
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| JobManager | 作业管理器 | 工头：切图、排班、组织存档救灾 |
| TaskManager | 任务管理器 | 工人：常驻进程，真干活+传数据 |
| Slot | 槽位 | 工位：TM 的资源切片，跑一串 SubTask |
| SubTask | 并行子任务 | 作业算子的一份并行副本（并行度=N 就有 N 份） |
| Operator Chain | 算子链 | 窄算子串成一串省网络（同线程直传） |
| Dataflow Graph | 数据流图 | 作业施工图：source→map→keyBy→sink |
| Parallelism | 并行度 | 每个算子开几份副本（决定吃几个 Slot） |

## 3. 动手实操：UI 观察三个架构现象

```sql
-- 在 sql-client 里跑一个"能看穿架构"的作业（datagen 不需要外部依赖）：
CREATE TABLE src (
  id INT,
  amt DOUBLE,
  proc AS PROCTIME()
) WITH (
  'connector' = 'datagen',
  'rows-per-second' = '100',
  'fields.id.kind' = 'random',
  'fields.id.min' = '1', 'fields.id.max' = '10'
);

CREATE TABLE sink_sink (
  id INT,
  total DOUBLE
) WITH ('connector' = 'print');

-- 作业：keyBy（宽依赖）+ 聚合 → 提交后在 UI 看执行图
INSERT INTO sink_sink
SELECT id, SUM(amt) AS total FROM src GROUP BY id;
```

```
UI 观察点（边看边记录）：
① Jobs 页 → 点进作业 → 看 Job Graph：
   [Source: src] → [GroupAggregate] → [Sink: print]
   —— 注意有没有"串一起"的算子链（Chained 标记）
② 点每个算子看 Parallelism 和 Status：
   datagen 源并行度 1；聚合后被 keyBy 打宽（可能并行 1——默认 mini 集群）
③ Metrics 页：看 Records Received/ Sent——宽依赖处走网络的证据
④ TaskManager 页：看 Slot 分配——你的作业占了几个 Slot？
   （SQL 作业默认所有算子并行度 1 → 只占 1 个 Slot）
⑤ 停掉作业（Running Jobs → cancel），观察 Slot 释放
```

```powershell
# 进阶观察：把并行度调到 2 再跑一遍（对比 Slot 占用）
docker exec -it flink-jm ./bin/sql-client.sh
# SET 'parallelism.default' = '2';  然后重跑上面 INSERT
# UI：SubTask 数量翻倍、Slot 占用变 2 —— 并行度的直观感受（记录截图）
```

## 4. 面试连接

**Q：讲讲 Flink 的运行时架构？（必考，白板题）**
> 三个角色打比方：JobManager 是工头，TaskManager 是工人，Slot 是工位。提交作业后，JobManager 把数据流图转成执行图、按并行度切出 SubTask，分派到各 TaskManager 的 Slot 上执行；TaskManager 是常驻进程，负责真正计算和算子间的数据传输；Slot 是 TaskManager 的资源切片，一个 Slot 跑一串 SubTask。两个关键机制要主动讲：一是算子链——相邻的窄依赖算子（map/filter 这类不用跨网络重分配的）默认串成一个 SubTask，数据在同一个线程里内存直传，省掉序列化和网络开销，这是 Flink 低延迟的工程基础之一；二是 Slot 的隔离粒度比 Spark Executor 粗但简单——同一个作业的不同 SubTask 不共享 Slot，天然避免互相抢资源。和 Spark 对照着说更出彩：JobManager≈Driver，TaskManager≈Executor，算子链≈Spark 的窄依赖流水线，宽依赖（keyBy）两边都要走网络 Shuffle——架构同构，差别在 Flink 常驻、按 Slot 分，Spark 按批调度、按整块资源分。

**Q：并行度设多少合适？Slot 数和并行度什么关系？（考资源直觉）**
> 三个约束记清楚：① 最优并行度 ≈ 源的分区数——Kafka 主题 3 个分区，源并行度设 3，设 6 会有 3 个源 SubTask 分不到数据白占 Slot（这是和 Spark 最大的不同：Spark 随便加 Task，Flink 的 Source 并行度被上游分区数锁死）；② 全作业 Slot 需求 = 最大算子并行度 × 算子链条数——算子链串得越好，Slot 越省；③ 总 Slot 供给 = TM 数 × 每 TM 的 Slot 数。我的实践：先按源分区定源并行度，下游按吞吐压力调（day23 细讲倒推法），Slot 数按"峰值并行度×1.5"留余量。这个思路和 M2 线程池"核数×2"的倒推法是同一门手艺。

## 5. 今日验收清单

- [ ] 三角色+Slot+算子链能白板画（对照 Spark 版一起画）
- [ ] UI 五个观察点全部记录（含并行度=2 的对照）
- [ ] "Source 并行度被源分区锁死"这个坑能讲
- [ ] 《Flink↔Spark 架构对照表》成文（工头↔Driver 等六行）
- [ ] `git add . && git commit -m "day12-02: runtime-arch"`

---
[← Day 01](day01-Flink全景与定位.md) | [本月目录](README.md) | [Day 03 · 第一个流作业 →](day03-第一个流作业.md)
