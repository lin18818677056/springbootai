# 05 · Spark：批处理引擎与调优实战

> **本篇目标**：掌握 Spark 的核心抽象 RDD/DAG、执行模型（Driver/Executor）、Shuffle 原理、内存管理与数据倾斜治理；会写 Spark SQL 完成离线数仓加工。学完能把"跑得慢的离线任务"调到"跑得快"，这是数据团队里最值钱的手艺之一。

## 术语表

| 英文 | 中文 |
|------|------|
| RDD (Resilient Distributed Dataset) | 弹性分布式数据集（不可变、可分区、有血缘的分布式集合） |
| Lineage / DAG | 血缘 / 有向无环图（算错了能沿血缘重算——Spark 的容错之道） |
| Transformation / Action | 转换算子（惰性，只记不跑：map/filter）/ 行动算子（触发执行：count/collect） |
| Job / Stage / Task | 作业 / 阶段（以 Shuffle 为界切分）/ 任务（分区上的执行单元） |
| Driver / Executor | 驱动进程（调度大脑）/ 执行进程（干活的工人 JVM） |
| Shuffle | 洗牌（按 key 重新分布数据，网络+磁盘开销大户） |
| Wide / Narrow Dependency | 宽依赖（父分区被多个子分区用，如 groupByKey）/ 窄依赖（一对一，如 map） |
| Catalyst / Tungsten | 优化器（逻辑计划优化）/ 执行引擎（堆外内存+全时代码生成） |
| AQE (Adaptive Query Execution) | 自适应查询执行（Spark 3+ 运行时动态优化） |
| Broadcast Join | 广播连接（小表全量广播到各 Executor，免 shuffle） |
| Speculative Execution | 推测执行（慢任务复制一份赛跑，取先完成者） |

## 1. 核心心智模型：惰性求值 + 血缘容错

```scala
val lines = sc.textFile("hdfs://...")            // 转换：不执行，只记录
val errors = lines.filter(_.contains("ERROR"))   // 转换：继续记录
val count  = errors.count()                      // 行动：触发 DAG 真正执行
```

**三个认知**：
1. **惰性求值**：Transformation（转换）只是"记账"，Action（行动）才"结算" → 优化器能看到全图做全局优化。
2. **血缘（Lineage）容错**：某分区数据丢了 → 沿血缘从源头重算该分区，不用全量重来（对比 HDFS 复制容错，Spark 是"计算重建"容错）。
3. **宽窄依赖决定 Stage 切分**：窄依赖（map/filter）算子可以流水线串在同一个 Stage；遇宽依赖（Shuffle）就切一刀 → **Shuffle 次数 = 性能的第一杠杆**。

## 2. 执行架构：Driver 与 Executor

```
Driver（驱动器）
  ├── 把程序编译成 DAG → 按 Shuffle 切 Stage → 生成 TaskSet（任务集）
  ├── 向 Cluster Manager（YARN/K8s/Standalone）申请资源
  └── 调度 Task 到 Executor 执行，回收结果
Executor（执行器，每个若干核）
  └── 线程池跑 Task；用 Block Manager 缓存数据（cache/persist）
```

**内存布局直觉**：Executor 内存 = 执行内存（Shuffle/Join/排序）+ 存储内存（cache）+ 用户内存 + 预留。执行和存储可互相借（统一内存管理）→ 报 "OOM" 先分清是哪块爆的（任务溢写磁盘 vs 缓存挤爆）。

## 3. Shuffle：Spark 的阿喀琉斯之踵

```
Shuffle Write：每个 Task 按 key hash 把数据写成 N 个分区文件（落盘）
Shuffle Read：下游 Task 跨节点拉取属于自己的分区数据
代价：磁盘 IO + 网络传输 + 序列化反序列化 + 排序 → 占整任务耗时的 50%+ 是常态
```

**减少 Shuffle 的三板斧**：
1. **广播小表**：`broadcast(dimTable)` → join 在本地完成，零 shuffle（维度表 < 100MB~1GB 都值得）。
2. **预聚合**：先局部 `reduceByKey` 再全局（`repartition` 前先聚）→ shuffle 数据量骤减。
3. **AQE 自动优化**（Spark 3+）：运行时把小分区自动合并（`coalescePartitions`）、自动把 join 转广播 → 打开就有效，别手写太多画蛇添足的调参。

## 4. Spark SQL 与 AQE（日常主战场）

```sql
-- 数据团队 90% 的工作是这条路径：Spark SQL 读数仓 → 加工 → 写回
INSERT OVERWRITE TABLE dws_user_order_1d PARTITION (dt = '2026-09-23')
SELECT u.city,
       COUNT(DISTINCT o.user_id)  AS uv,
       SUM(o.pay_amount)          AS gmv
FROM dwd_order_item_detail o
JOIN dim_user u ON o.user_id = u.user_id      -- 维度表小时 AQE 自动转广播
WHERE o.dt = '2026-09-23'
GROUP BY u.city;

-- 打开自适应执行（推荐全局配置）
SET spark.sql.adaptive.enabled = true;              -- 自适应查询执行
SET spark.sql.adaptive.skewJoin.enabled = true;     -- 倾斜 join 自动拆分
SET spark.sql.shuffle.partitions = 800;             -- shuffle 分区数（默认200，按数据量调）
```

## 5. 数据倾斜：定位 → 归因 → 治理（与 Hive 一脉相承）

**定位**：Spark UI 的 Stage 页 → 某几个 task 的 Shuffle Read Size / Duration 长尾（999 个 task 1 分钟，1 个 task 40 分钟）。

**归因**（拿 SQL 反推哪些 key 大）：null 值 / 爆品 / 头部大客户。

**治理**：

| 场景 | 手段 |
|------|------|
| null/异常 key | 过滤单独处理，或加盐 `concat(key, '_rand')` 打散再还原 |
| join 大 key | 打盐扩容：大表加随机前缀，小表全量复制 N 份带对应前缀（两阶段 join） |
| 聚合倾斜 | 两阶段聚合：局部 `groupBy(盐+key)` → 全局 `groupBy(key)` |
| AQE 能解决的 | 直接交给 `spark.sql.adaptive.skewJoin`（运行时把大分区拆给多个 task） |

## 6. 调优速查表（性能问题按这个顺序查）

1. **数据源头**：能不能早点过滤（谓词下推）？能不能少读列（Parquet 列裁剪）？
2. **Shuffle 次数**：join 能否广播？聚合能否预聚合？
3. **并行度**：`shuffle.partitions` 与数据量匹配（每分区 ~128MB 为宜）；task 数 = CPU 核数 × 2~3。
4. **资源配比**：Executor 数 × 核数 × 内存，别贪大（单 Executor > 32GB 时 GC 停顿恶化）。
5. **缓存滥用检查**：`cache()` 缓存了没人复用的大表 → 占着存储内存还拖累执行 → 用完 `unpersist()`。
6. **小文件**：结果目录几万个文件 → 下游读得慢 → 写前 `repartition` / 开启 AQE 自动合并。

## 7. Spark vs Flink（一句话定位）

| | Spark | Flink |
|--|-------|-------|
| 世界观 | 批处理（微批流处理：把流切成小批） | 流处理（批是流的特例） |
| 延迟 | 秒~分钟（微批） | 毫秒级（真流式） |
| 强项 | 生态成熟、SQL 完备、离线吞吐王 | 事件时间语义、状态管理、Exactly-Once |
| 选用 | 离线数仓/T-1 报表 | 实时大屏/实时风控/CEP |

## 验收自测

- [ ] 能解释惰性求值与血缘容错，并说出宽窄依赖如何切 Stage
- [ ] 能画出 Driver/Executor 架构与 Task 调度链路
- [ ] 能解释 Shuffle 的代价与三个减少 Shuffle 的手段
- [ ] 能用 Spark SQL + AQE 完成一次日汇总加工
- [ ] 能完整讲出数据倾斜"定位→归因→治理"并演示至少一种打盐方案
- [ ] 能按速查表顺序对一个慢任务做系统性排查

## 延伸阅读

- Spark 官方文档 Tuning Guide（英文，配合本文调优速查表读）
- 实战配套：`../practice-projects/03-realtime-analytics/`（Spark 离线重算层可基于同一套数据练手）
