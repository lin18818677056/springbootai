# Step 1：埋点采集与 Kafka 接入（两路数据源）

> **本步目标**：设计埋点事件模型；用 Flink CDC 把 MySQL 业务变更实时同步进 Kafka；实现一个轻量埋点上报接口把行为日志打进 Kafka。完成后两路数据在 Kafka 汇合。

## 前置术语

| 英文 | 中文 |
|------|------|
| Event Tracking / Analytics | 埋点 / 行为分析 |
| CDC (Change Data Capture) | 变更数据捕获 |
| Snapshot + Binlog | 全量快照 + 增量日志（CDC 的两段式） |
| Topic / Partition | 主题 / 分区 |
| Schema Evolution | 模式演进（字段增删的兼容策略） |

## 1. 埋点事件模型设计（先定 schema，血的教训：埋点最怕改不动）

统一事件信封（所有埋点共用外层，业务属性放 properties）：

```json
{
  "event_id":  "uuid（客户端生成，服务端幂等去重用）",
  "event_type": "page_view | click | expose | order_submit",
  "user_id":   "u-1001（未登录用设备号）",
  "device_id": "d-abc123",
  "session_id":"s-xxx",
  "timestamp": 1727064000000,
  "page":      "goods_detail",
  "properties": { "goods_id": "g-2001", "city": "上海", "price": 19900 }
}
```

**埋点三原则**：事件命名过去式统一（`order_submit`）、必带 `event_id`（幂等）、时间用客户端事件时间（为 Flink Watermark 做准备，允许服务端补 `server_time`）。

## 2. Flink CDC：MySQL → Kafka

```sql
-- Flink SQL 客户端直接建 CDC 源表
CREATE TABLE orders_cdc (
  id BIGINT,
  user_id BIGINT,
  goods_id BIGINT,
  amount DECIMAL(10,2),
  status INT,
  created_at TIMESTAMP(3),
  PRIMARY KEY (id) NOT ENFORCED
) WITH (
  'connector' = 'mysql-cdc',
  'hostname' = '127.0.0.1',
  'port' = '3306',
  'username' = 'flinkuser',
  'password' = '***',
  'database-name' = 'mall',
  'table-name' = 'seckill_order',
  'scan.startup.mode' = 'initial',        -- 全量快照 + 增量 binlog 一体化
  'debezium.snapshot.locking.mode' = 'none' -- 无锁读取（低峰执行全量）
);

-- 写入 Kafka（明细层主题），upsert-kafka 保留更新语义
CREATE TABLE dwd_orders (
  id BIGINT, user_id BIGINT, goods_id BIGINT,
  amount DECIMAL(10,2), status INT,
  created_at TIMESTAMP(3),
  PRIMARY KEY (id) NOT ENFORCED
) WITH (
  'connector' = 'upsert-kafka',
  'topic' = 'dwd_orders',
  'properties.bootstrap.servers' = '127.0.0.1:9092',
  'key.format' = 'json',
  'value.format' = 'json'
);

INSERT INTO dwd_orders SELECT * FROM orders_cdc;
```

**验证**：Mysql 里 update 一条订单状态 → `kafka-console-consumer` 立刻看到变更消息（含 op: c/u/d）。

## 3. 埋点上报接口（Spring Boot 轻量实现）

```java
@RestController
@RequestMapping("/track")
public class TrackController {
    private final KafkaTemplate<String, String> kafka;

    @PostMapping("/batch")
    public Result<Void> batch(@RequestBody @Valid List<TrackEvent> events) {
        for (TrackEvent e : events) {
            // key=user_id 保证同用户事件有序（同一分区内有序）
            kafka.send("dwd_events", e.userId(), JSON.toJSONString(e));
        }
        return Result.ok(null);   // 上报端只管收，校验/清洗交给 Flink
    }
}
```

要点：
- **批量上报**：客户端攒 10 条或 5 秒一批，减少请求次数。
- **服务端快速返回**：只做格式校验 + 发 Kafka，不做重逻辑（上报接口必须轻）。
- **event_id 幂等**：Flink 消费侧去重（step02 状态去重）。

## 4. Kafka 主题规划（数仓命名规范）

| 主题 | 层 | 说明 |
|------|-----|------|
| `dwd_events` | DWD | 行为明细（原始 JSON） |
| `dwd_orders` | DWD | 订单明细（CDC 变更流） |
| `dws_gmv_1min` | DWS | 分钟级 GMV 汇总 |
| `dws_uv_1min` | DWS | 分钟级 UV |
| `ads_realtime_topn` | ADS | TopN 结果（供 Redis/大屏） |

分区数经验：`目标吞吐 ÷ 单分区吞吐（~10MB/s）× 2 余量`；本机 3 分区够用。

## 验收清单

- [ ] CDC：修改 MySQL 订单 → Kafka `dwd_orders` 实时出现变更消息（贴时间戳截图）
- [ ] 埋点接口压测 500 QPS：无丢失（对比上报数与 Kafka 消息数）
- [ ] 同一用户的埋点落同一分区（顺序性验证）
- [ ] 主题命名与分区规划文档完成

## 常见坑

1. **CDC 全量阶段锁表**：开发库无所谓，生产务必低峰 + 无锁模式，并评估 binlog 保留期。
2. **Kafka 消息时间戳**：默认用写入时间，Flink 事件时间要从 JSON 字段提取（step02 的 `watermark` 配置）。
3. **序列化字段演进**：提前约定"只增不改不删语义"，用 optional 字段，避免上下游反序列化爆炸。

> 完成后进入 `step02-Flink实时计算.md`。
