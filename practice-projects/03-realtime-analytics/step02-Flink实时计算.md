# Step 2：Flink 实时计算（窗口聚合 / UV 去重 / TopN / Checkpoint）

> **本步目标**：用 Flink SQL 把 Kafka 里的 DWD 明细算成 DWS 汇总：分钟级 GMV、实时 UV、热销 TopN；搞懂 Watermark（水位线）、Checkpoint（检查点）、Exactly-Once（精确一次）三大核心机制，并完成一次"kill 进程恢复"演练。

## 前置术语

| 英文 | 中文 |
|------|------|
| Event Time / Processing Time | 事件时间（数据自带）/ 处理时间（机器时钟） |
| Watermark | 水位线（事件时间进度标记，宣告"之前的数据基本到齐"） |
| Tumbling Window | 滚动窗口（首尾相接不重叠，如每分钟一个） |
| Sliding Window | 滑动窗口（窗口重叠，如每 10 秒算最近 1 分钟） |
| State / State Backend | 状态 / 状态后端（Flink 存中间结果的地方） |
| Checkpoint / Snapshot | 检查点 / 快照（定期把状态持久化，用于故障恢复） |
| Exactly-Once | 精确一次语义（不丢不重） |
| Backpressure | 反压（下游处理不过来，压力向上游传导） |
| Two-Phase Commit (2PC) | 两阶段提交（预提交 + 确认提交） |
| HyperLogLog (HLL) | 超对数（近似去重算法，误差 ~1%，内存极小） |
| Late Element / Side Output | 迟到数据 / 侧输出流（窗口关了才到的数据去的地方） |

## 1. 环境准备：Flink 集群与连接器

在 docker-compose.yml 中追加（Kafka/MySQL 沿用 step01）：

```yaml
  jobmanager:
    image: flink:1.18-java11
    ports: ["8081:8081"]
    command: jobmanager
    environment:
      - |
        FLINK_PROPERTIES=
        jobmanager.rpc.address: jobmanager
  taskmanager:
    image: flink:1.18-java11
    command: taskmanager
    depends_on: [jobmanager]
    environment:
      - |
        FLINK_PROPERTIES=
        jobmanager.rpc.address: jobmanager
        taskmanager.numberOfTaskSlots: 4
```

把连接器 jar 放进镜像或挂载到 `/opt/flink/lib/` 后重启（**版本必须与 Flink 匹配**）：
- `flink-sql-connector-kafka-3.x-1.18.jar`（Kafka 连接器）
- `flink-connector-mysql-cdc-3.x.jar`（CDC，step01 已用）
- `flink-connector-jdbc-3.x.jar` + `mysql-connector-j`（维表 Lookup 用，step03 写 ClickHouse 也用）

启动 SQL 客户端：`docker exec -it <jobmanager> ./bin/sql-client.sh`，执行 `SET 'execution.checkpointing.interval' = '10s';` 后每条作业都会带 Checkpoint。

## 2. 源表与 Watermark：事件时间的关键一步

```sql
CREATE TABLE dwd_events_source (
  event_id   STRING,
  event_type STRING,
  user_id    STRING,
  device_id  STRING,
  page       STRING,
  properties MAP<STRING, STRING>,
  ts         TIMESTAMP(3),
  WATERMARK FOR ts AS ts - INTERVAL '10' SECOND   -- 允许 10 秒乱序
) WITH (
  'connector' = 'kafka',
  'topic' = 'dwd_events',
  'properties.bootstrap.servers' = 'kafka:9092',
  'properties.group.id' = 'flink-dws',
  'scan.startup.mode' = 'latest-offset',
  'format' = 'json',
  'json.timestamp-format.standard' = 'ISO-8601'
);
```

**Watermark 面试级理解（必须吃透）**：
1. 作用：解决"网络延迟导致乱序"下，窗口**何时触发**的问题。水位线 = 当前最大事件时间 − 允许乱序度。
2. 水位线到达 `窗口结束时间` 才触发窗口计算 → 之前 10 秒内乱序到达的数据都能算进去。
3. 水位线之后到的数据 = 迟到数据 → 默认**丢弃**，可用侧输出回收（见第 6 节）。
4. 乱序度是权衡：设大 → 数据全但结果延迟；设小 → 结果快但丢数据多。埋点场景 10~30 秒是常见取值。

## 3. 分钟级 GMV：滚动窗口聚合

```sql
INSERT INTO dws_gmv_1min
SELECT
  DATE_FORMAT(window_start, 'yyyy-MM-dd HH:mm:00') AS stat_minute,
  SUM(amount)   AS gmv,
  COUNT(*)      AS order_cnt,
  PROCTIME()    AS calc_time
FROM TABLE(
  TUMBLE(TABLE dwd_orders_source, DESCRIPTOR(created_at), INTERVAL '1' MINUTE)
)
WHERE status = 1          -- 只算已支付
GROUP BY window_start, window_end;
```

输出表 `dws_gmv_1min` 用 `upsert-kafka`（以 `stat_minute` 为主键）→ 后续消费端天然幂等，窗口重算也不会翻倍——**这就是无状态 DBSink 之外最省心的 Exactly-Once 写法**。

## 4. 实时 UV：精确去重 vs 近似去重（架构权衡必考）

```sql
-- 方案 A：精确去重（结果进状态，UV 千万级内存爆炸，仅小数据量用）
SELECT window_start, COUNT(DISTINCT device_id) AS uv
FROM TABLE(TUMBLE(TABLE dwd_events_source, DESCRIPTOR(ts), INTERVAL '1' MINUTE))
GROUP BY window_start;

-- 方案 B：近似去重（HLL，误差 ~1%，内存 KB 级，大屏首选）
SELECT window_start, HLL_COUNT_DISTINCT(device_id) AS uv_approx ...
```

**选型话术**："大屏展示用 HLL 近似值（误差 1% 谁也看不出来），计费/风控用精确去重且必须收敛分片（先按 hash 分组局部去重再全局合并，即两阶段聚合），避免单 Key 状态热点。"

## 5. 热销商品 TopN（Flink 最经典的几种写法）

```sql
-- 每 5 分钟算一次"近 1 小时销量 Top10"（滑动窗口 + OVER 排名）
INSERT INTO ads_realtime_topn
SELECT *
FROM (
  SELECT *,
         ROW_NUMBER() OVER (PARTITION BY window_start ORDER BY pay_cnt DESC) AS rn
  FROM (
    SELECT window_start, window_end, goods_id,
           COUNT(*) AS pay_cnt
    FROM TABLE(
      SLIDE(TABLE dwd_orders_source, DESCRIPTOR(created_at), INTERVAL '5' MINUTE, INTERVAL '1' HOUR)
    )
    WHERE status = 1
    GROUP BY window_start, window_end, goods_id
  )
) WHERE rn <= 10;
```

要点：先 GROUP BY 聚合出"每个商品的近 1 小时销量"，再用 `ROW_NUMBER()`（行号函数）排名取前 10。**直接对明细 OVER 排名是新手大坑**（状态全存明细，跑半天就 OOM，即内存溢出）。

## 6. 迟到数据与侧输出（保证"账能对上"）

DataStream 侧（SQL 无法表达时用）：

```java
SingleOutputStreamOperator<Event> result = stream
    .keyBy(Event::getPage)
    .window(TumblingEventTimeWindows.of(Time.minutes(1)))
    .sideOutputLateData(lateTag)              // 迟到数据送侧输出
    .aggregate(new UvAggregate(), new UvWindowFunction());

result.getSideOutput(lateTag)                 // 迟到数据：写入延迟主题，离线补偿
      .sinkTo(lateKafkaSink);                 // 再由离线任务回补结果
```

认知模型："实时层保新鲜（丢迟到），离线层保精确（全量重算），两者定时核对"——这就是 **Kappa 架构**下的"实离一致"实践。

## 7. Checkpoint 与 Exactly-Once：核心中的核心

```yaml
# flink-conf.yaml 或 SQL 客户端 SET
execution.checkpointing.interval: 10s          # 每 10 秒一次
execution.checkpointing.mode: EXACTLY_ONCE
execution.checkpointing.timeout: 1min
state.backend: rocksdb                          # 大状态用 RocksDB（增量快照）
state.checkpoints.dir: file:///opt/flink/checkpoints   # 生产用 HDFS/S3
execution.checkpointing.externalized-checkpoint-retention: RETAIN_ON_CANCELLATION
```

**Checkpoint 原理（Chandy-Lamport 算法思想）**：
1. JobManager 定期向所有 Source 注入 **Checkpoint Barrier**（屏障，特殊标记）。
2. Barrier 随数据流向下游流动；收到 Barrier 的算子把当前状态**快照**到持久存储，然后继续转发 Barrier。
3. 所有算子快照完成 → 本次 Checkpoint 成功；超时/失败则丢弃重试。
4. 故障恢复：从最近成功快照恢复状态，**Source 回退到快照对应的 offset（偏移量）重新消费** → 状态不丢，数据靠"幂等写/事务写"不重。

**两阶段提交（KafkaSink EXACTLY_ONCE 模式）**：Checkpoint 期间先"预提交"（pre-commit，消息对消费者暂不可见），Checkpoint 成功后"确认提交"（notify）→ 事务消息才真正可见。代价：下游可见延迟 = Checkpoint 间隔。

## 8. 演练：kill 进程后恢复（简历上"不丢不重"的实证）

```bash
# 1. 记录当前 GMV 数字（比如 dws_gmv_1min 最新值为 125,800.00）
# 2. 直接 kill TaskManager 容器，模拟进程崩溃
docker kill <taskmanager容器>          # Docker 会按 restart 策略重启它
# 3. 作业从最近的 Checkpoint 自动恢复（UI 上能看到 restore 记录）
# 4. 对比恢复前后 GMV：数字连续，不重复累计
```

验证"不重"的依据：输出表按分钟主键 upsert，恢复后同一分钟的窗口重算只是**覆盖**，不是**累加**——设计输出主键时就要想到这一点。

## 9. 反压与积压排查（生产第一故障）

| 症状 | 定位 | 处置 |
|------|------|------|
| Web UI 作业卡红、吞吐骤降 | UI 的 Backpressure 标签页看哪个算子忙（busy%） | 忙的那个算子就是瓶颈 |
| 瓶颈在窗口聚合 | 单 Key 热点（如爆品） | `keyBy` 前先局部聚合 / 加盐（加随机前缀打散） |
| 瓶颈在 Sink | ClickHouse/Redis 写入 RT 高 | Sink 并行度 ≤ 目标端分片数；开批量写 |
| Kafka 消费 lag（滞后）持续增长 | 源头流量突增 | 提高作业并行度（受分区数限制，必要时扩分区） |

## 验收清单

- [ ] GMV 大屏 SQL：窗口每分钟出数，与手工 SQL 离线核对一致（误差为 0）
- [ ] 故意乱序造数（把 3 条数据时间戳倒着发）→ 10 秒乱序度内全部计入窗口
- [ ] UV：精确与 HLL 两种结果对比记录（误差 <2%），写清选型理由
- [ ] TopN：结果与离线 SQL 计算的 Top10 一致
- [ ] kill -9 演练：Checkpoint 恢复成功，GMV 数字不丢不重（截图 + 文字记录）
- [ ] 反压实验：把 Sink 并行度调成 1 制造积压 → 用 UI 定位到瓶颈算子

## 常见坑

1. **Watermark 字段用错**：埋点用客户端 `ts`（事件时间），不是 Kafka 写入时间；用错后窗口全乱。
2. **无界状态 OOM**：`COUNT(DISTINCT)` / 明细 OVER 不加窗口 → 状态无限增长，跑一天就挂；一切状态都要有"窗口/TTN"边界。
3. **Checkpoint 一直失败**：先看是否有算子没响应 barrier（如 Sink 阻塞），再把间隔从 10s 调大试；**Checkpoint 失败意味着故障后无法恢复**，比慢更危险。
4. **并行度 > Kafka 分区数**：空闲 Source 并行子任务吃资源不出数，保持两者相等或源略多。
5. **改了 SQL 直接覆盖旧作业**：状态兼容性问题会导致无法恢复 → 先停旧作业存 Savepoint（手动快照），或直接以新作业重放（Kafka 保留期内可回溯）。

> 完成后进入 `step03-存储与大屏.md`。
