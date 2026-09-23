# 06 · Flink：流处理引擎与实时数仓核心

> **本篇目标**：吃透 Flink 的运行时架构、事件时间与 Watermark（水位线）、状态管理、Checkpoint（检查点）与 Exactly-Once（精确一次）机制；会用 Flink SQL 写实时数仓。Flink 是国内实时计算的绝对主流，也是你实战项目三的核心引擎——本篇是理论篇，实战请配合项目三。

## 术语表

| 英文 | 中文 |
|------|------|
| JobManager / TaskManager | 作业管理器（调度大脑）/ 任务管理器（干活的 Slot 容器） |
| Slot | 槽位（TaskManager 的资源切片，一个 Slot 跑一个并行子任务） |
| DataStream API | 数据流 API（低阶，Java/Scala，表达力最强） |
| Flink SQL / Table API | SQL 接口（高阶，90% 实时需求用它写） |
| Event Time / Processing Time | 事件时间（数据发生时刻）/ 处理时间（机器时钟） |
| Watermark | 水位线（事件时间进度标记，宣告窗口可以关闭了） |
| Window / Trigger / Evictor | 窗口 / 触发器（何时出结果）/ 清理器 |
| State / Keyed State / Operator State | 状态 / 键控状态（按 key 隔离）/ 算子状态 |
| State Backend | 状态后端（HashMap 内存型 / RocksDB 磁盘型） |
| Checkpoint / Barrier | 检查点 / 屏障（随流流动的对齐标记，快照的依据） |
| Savepoint | 保存点（手动触发的快照，用于升级/迁移） |
| Exactly-Once / At-Least-Once | 精确一次 / 至少一次（端到端语义） |
| Backpressure | 反压（下游处理不过来，压力沿数据链向上传导） |
| CEP (Complex Event Processing) | 复杂事件处理（模式匹配：连续登录失败 → 风控告警） |

## 1. 运行时架构

```
JobManager（每个作业一个）
  ├── 把作业图（Dataflow Graph）转成执行图，切分 Task
  ├── 协调 Checkpoint（注入 Barrier、收集确认）
  └── 失败时负责从快照恢复
TaskManager（常驻）
  ├── 提供 Slot（槽位）跑并行子任务（SubTask）
  └── SubTask 之间以网络/本地方式链式传输数据（算子链 Operator Chain 减少序列化）
```

与 Spark 的本质差异：**Flink 是"常驻连续计算"**——作业提交后一直跑，来一条处理一条（真流式）；Spark 是"每批数据触发一次计算"（微批）。

## 2. 事件时间与 Watermark：流计算最重要的一节课

**问题**：网络有延迟，数据会乱序。窗口按事件时间切，那窗口**什么时候关**？关早了丢数据，关晚了出结果慢。

**Watermark（水位线）解法**：
```
水位线 = 迄今见到的最大事件时间 − 允许乱序度(如 10 秒)
语义："事件时间 ≤ 水位线的数据，我认为基本到齐了" → 水位线越过窗口终点 → 触发窗口计算
```

| 设计点 | 权衡 |
|--------|------|
| 乱序度设大 | 数据全、结果准，但出数延迟大 |
| 乱序度设小 | 出数快，但迟到数据多 |
| 迟到数据处理 | 侧输出（Side Output）收走补偿；或窗口 `allowedLateness` 再等一会 |

**面试标准答案**："Watermark 解决的是'事件时间语义下，无界数据流中窗口何时关闭'的确定性问题。它是正确性与延迟之间的一个可调权衡，不是消灭乱序。"

## 3. 状态：Flink 的"内存"，也是性能与事故的中心

- **Keyed State（键控状态）**：按 key 隔离（如"每个商品的累计销量"），最常用。
- **Operator State（算子状态）**：算子并行实例自己持有（如 Kafka Source 的 offset 记录）。
- **State Backend（状态后端）选择**：

| 后端 | 状态存哪 | 适用 |
|------|---------|------|
| HashMap（JVM 堆内） | TaskManager 内存 | 小状态（< 几 GB），快 |
| RocksDB | 本地磁盘 + 堆外 | 大状态（TB 级），支持增量 Checkpoint |

**状态 TTL（Time To Live，生存时间）**：状态必须会"过期"！`state.ttl` 给"用户 7 天内访问记录"这类状态定寿命，否则无界状态 = 一个月后 OOM（内存溢出）。**一切状态都要问"它什么时候可以忘"**。

## 4. Checkpoint 与 Exactly-Once（Flink 的灵魂，面试必考）

```
1. JobManager 周期性（如 10s）向 Source 注入 Barrier（屏障）
2. Barrier 随数据流经每个算子；算子收到后：
   - 先对齐（多输入时等齐所有输入的 Barrier）
   - 把当前状态快照写入持久存储（HDFS/S3）
   - 向下游转发 Barrier
3. 所有算子快照完成 → 本次 Checkpoint 成功；Source 记录当时的 offset
故障恢复 = 从最近成功快照恢复状态 + Source 回退 offset 重放
```

**端到端精确一次 = 三段拼图**（与 Kafka 一篇呼应）：
1. **Source 可重放**：Kafka offset 存进快照。
2. **状态一致**：Checkpoint 快照。
3. **Sink 幂等或事务**：upsert-kafka/ClickHouse 幂等写；Kafka Sink 用两阶段提交（预提交随 Checkpoint，成功后正式提交）。

**关键代价**：Barrier 对齐会让上游"憋流"；非对齐 Checkpoint（Unaligned Checkpoint，1.11+）可缓解反压下的 Checkpoint 超时。

## 5. Flink SQL 实时数仓（日常主战场）

```sql
-- DWS：分钟级 GMV（滚动窗口）
INSERT INTO dws_gmv_1min
SELECT window_start, SUM(amount) AS gmv, COUNT(*) AS cnt
FROM TABLE(TUMBLE(TABLE dwd_orders, DESCRIPTOR(pay_time), INTERVAL '1' MINUTE))
GROUP BY window_start, window_end;

-- TopN：近 1 小时热销（先聚再排，别对明细直接 ROW_NUMBER）
SELECT * FROM (
  SELECT *, ROW_NUMBER() OVER (PARTITION BY window_start ORDER BY cnt DESC) rn
  FROM (
    SELECT window_start, goods_id, COUNT(*) cnt
    FROM TABLE(SLIDE(TABLE dwd_orders, DESCRIPTOR(pay_time), INTERVAL '5' MINUTE, INTERVAL '1' HOUR))
    GROUP BY window_start, window_end, goods_id)
) WHERE rn <= 10;

-- 维表关联：Lookup Join（查 MySQL 维度表，带缓存防打爆）
SELECT o.*, d.goods_name
FROM dwd_orders AS o
JOIN dim_goods FOR SYSTEM_TIME AS OF o.proc_time AS d
  ON o.goods_id = d.goods_id;
-- 维表参数：'lookup.cache.max-rows'='10000', 'lookup.cache.ttl'='1h'
```

## 6. 反压与积压排查（实时作业的第一故障）

| 步骤 | 动作 |
|------|------|
| 1. 发现 | Kafka lag（积压量）持续增长 / Flink UI 状态红 |
| 2. 定位 | UI 的 Backpressure 标签 → 看 busy%（忙度）最高的算子，瓶颈就是它（或它的下游） |
| 3. 归因 | 窗口大状态（换 RocksDB/预聚合）？Sink 写入慢（批量/提并行）？热点 key（加盐/局部聚合）？ |
| 4. 处置 | 提作业并行度（受源分区数限制）/ 扩 Slot / 优化算子逻辑 |
| 5. 验证 | lag 回落至 0 且吞吐平稳，Checkpoint 恢复正常时长 |

## 7. 实时数仓建设要点（架构视角收尾）

1. **分层照旧**：Kafka 分区主题即分层（DWD 明细 / DWS 汇总 / ADS 结果），与离线数仓同名同义。
2. **实离一致核对**：实时结果 vs 离线结果每日核对，差异 > 阈值告警（实时保新鲜，离线保精确）。
3. **维表更新**：低频维表用 Lookup Join 缓存；高频变更维表走 CDC 广播状态（Broadcast State）。
4. **升级发布**：用 Savepoint（保存点）停旧起新；SQL 变更不兼容时评估"重放窗口"（Kafka 保留期内回溯重算）。

## 验收自测

- [ ] 能解释 JobManager/TaskManager/Slot 与算子链
- [ ] 能向完全不懂的人讲清 Watermark 是干什么的、乱序度怎么权衡
- [ ] 能说出键控状态/算子状态区别与状态 TTL 的必要性
- [ ] 能完整口述 Checkpoint 流程与端到端精确一次的三段拼图
- [ ] 能写出滚动窗口聚合 + TopN + Lookup Join 三类 Flink SQL
- [ ] 能按五步法排查一次积压故障
- [ ] 能阐述实时数仓分层与实离核对机制

## 延伸阅读

- Flink 官方文档：Streaming Concepts / Stateful Stream Processing（英文）
- 实战配套（强烈推荐边学边做）：`../practice-projects/03-realtime-analytics/step02-Flink实时计算.md`
