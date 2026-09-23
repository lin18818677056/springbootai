# Step 3：存储与大屏（ClickHouse / Redis / Grafana / 演练复盘）

> **本步目标**：DWS 结果落地两条路——ClickHouse（存明细与历史，支持灵活查询）+ Redis（毫秒级读，大屏直连）；用 Grafana 搭实时大屏并配告警；最后完成故障演练与项目复盘。

## 前置术语

| 英文 | 中文 |
|------|------|
| ClickHouse | 列式 OLAP 数据库（分析型查询极快） |
| OLAP / OLTP | 联机分析处理（分析）/ 联机事务处理（业务交易） |
| MergeTree | ClickHouse 核心表引擎（合并树） |
| Partition Key | 分区键（数据按此裁剪扫描范围） |
| ORDER BY / Sort Key | 排序键（决定稀疏索引与压缩效率） |
| Materialized View | 物化视图（写入时自动同步聚合的表） |
| Grafana | 开源可视化平台（大屏/监控标配） |
| Dashboard / Panel | 仪表盘 / 面板（Grafana 的容器与图表单元） |
| Alert Rule / Notification | 告警规则 / 通知渠道 |
| Chaos Drilling | 故障演练（主动制造故障验证系统韧性） |

## 1. 为什么是"ClickHouse + Redis"双写？

| 组件 | 角色 | 理由 |
|------|------|------|
| Redis | 大屏实时读 | 大屏 5 秒刷一次，只需"当前分钟 + 当日累计"等少量 Key，毫秒返回 |
| ClickHouse | 历史明细与自助分析 | 运营要按城市/商品任意下钻、看曲线回放，OLAP 玩家 |

架构话术："**热数据 Redis 服务大屏，全量数据 ClickHouse 服务分析**；两条路都从 Flink 出，互不影响。"

## 2. docker-compose 追加 ClickHouse

```yaml
  clickhouse:
    image: clickhouse/clickhouse-server:24.8
    ports: ["8123:8123", "9000:9000"]   # 8123 HTTP（JDBC/Grafana 用），9000 Native
    ulimits: { nofile: 262144 }
    volumes:
      - clickhouse-data:/var/lib/clickhouse
```

## 3. ClickHouse 建表（MergeTree 三要素）

```sql
-- 明细层：订单事件明细（保 90 天）
CREATE TABLE dwd_order_detail
(
  id           UInt64,
  user_id      UInt64,
  goods_id     UInt64,
  city         LowCardinality(String),      -- 低基数字典编码，省内存提速
  amount       Decimal(10, 2),
  status       UInt8,
  created_at   DateTime,
  pay_at       DateTime
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(created_at)        -- 分区：按天（查询裁剪 + 生命周期管理）
ORDER BY (created_at, goods_id)            -- 排序键：时间+商品（最常用查询路径）
TTL toDateTime(created_at) + INTERVAL 90 DAY DELETE;   -- 90 天自动清理
```

```sql
-- 汇总层：分钟 GMV（Flink 直接写入）
CREATE TABLE dws_gmv_1min
(
  stat_minute DateTime,
  gmv         Decimal(12, 2),
  order_cnt   UInt64
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(stat_minute)
ORDER BY stat_minute;
```

**MergeTree 认知三句话**：写入先落"分区目录"后台异步合并；查询按分区键裁剪 + 稀疏索引定位；列存 + 排序键让"扫描其中几列"极快。**主键 ≠ 去重键**（与 MySQL 最大差异），upsert 语义要靠 ReplacingMergeTree + FINAL 或业务侧幂等。

## 4. Flink SQL 写入 ClickHouse

```sql
CREATE TABLE sink_dws_gmv (
  stat_minute TIMESTAMP(3),
  gmv         DECIMAL(12,2),
  order_cnt   BIGINT,
  PRIMARY KEY (stat_minute) NOT ENFORCED
) WITH (
  'connector' = 'jdbc',
  'url' = 'jdbc:clickhouse://clickhouse:8123/default',
  'table-name' = 'dws_gmv_1min',
  'username' = 'default',
  'sink.buffer-flush.max-rows' = '200',     -- 攒批写：ClickHouse 最忌单条 insert
  'sink.buffer-flush.interval' = '1s'
);
```

**ClickHouse 写入铁律**：单条 insert = 一次 merge 压力，QPS 高会把合并打爆（too many parts 报错）。必须 Flink 攒批 / Kafka 引擎表 + 物化视图消化的方式写入。

## 5. Redis 指标设计（大屏直连的 Key 规划）

| Key | 类型 | 内容 | 过期 |
|-----|------|------|------|
| `screen:gmv:total:{yyyyMMdd}` | String | 当日累计 GMV（INCRBYFLOAT） | 2 天 |
| `screen:gmv:1min` | ZSET | score=分钟戳, member=分钟 GMV（取 Top 120 出曲线） | 1 天 |
| `screen:uv:today` | PFADD | 当日 UV（HyperLogLog，12KB 定长） | 2 天 |
| `screen:topn:goods` | ZSET | 热销 TopN（score=销量） | 1 天 |
| `screen:funnel:{yyyyMMdd}` | Hash | 曝光/点击/下单/支付 四级漏斗计数 | 2 天 |

实现方式二选一：Flink SQL 的 Redis 连接器（社区版需自编译），或 DataStream 里 RichSinkFunction 攒批管道写入（教学推荐后者，代码直观）：

```java
public class RedisMetricSink extends RichSinkFunction<Metric> {
    private transient JedisPooled jedis;          // Jedis 连接池，open() 里初始化
    @Override public void invoke(Metric m, Context ctx) {
        if ("GMV".equals(m.type())) {
            jedis.zadd("screen:gmv:1min", m.minute(), String.valueOf(m.value()));
            jedis.incrByFloat("screen:gmv:total:" + m.date(), m.value());
        }
    }
}
```

## 6. Grafana 大屏搭建

1. 数据源：添加 **ClickHouse**（官方插件，HTTP 8123 端口）与 **Redis**（Redis 插件）。
2. 面板规划（一屏讲清"生意好不好"）：

| 面板 | 类型 | 查询 |
|------|------|------|
| 当日 GMV（大数字） | Stat | Redis `GET screen:gmv:total:{today}` |
| GMV 分钟曲线 | Time Series | ClickHouse 查 `dws_gmv_1min` 近 2 小时 |
| 实时 UV | Stat | Redis `PFCOUNT screen:uv:today` |
| 热销 Top10 | Bar Gauge | Redis ZSET `ZREVRANGE screen:topn:goods 0 9 WITHSCORES` |
| 转化漏斗 | Funnel/Pie | Redis Hash 四级计数 |

3. 全局设置：自动刷新 **5s**；时间范围"最近 2 小时"；变量 `$city` 支持下拉切换（查询带 `city = $city` 条件下钻）。

## 7. 告警规则（大屏不能只好看，还得会喊人）

- **GMV 跌零告警**：近 3 分钟 `SUM(gmv) = 0` 且去年同期非零 → 严重级 → 钉钉/企微机器人。
- **数据延迟告警**：`max(stat_minute) < now() - 2min` → 上游链路堵了。
- **消费积压告警**：Kafka lag > 10 万 → 提前扩容，别等大屏停更。

Grafana Alert → Contact Point（联系点，配 Webhook 机器人）→ Alert Rule（PromQL/SQL 条件 + 持续时长 + 分级）。

## 8. 故障演练清单（面试时的"我做过"弹药）

| 演练 | 注入方式 | 预期 |
|------|---------|------|
| TaskManager 崩溃 | `docker kill` taskmanager | 自动从 Checkpoint 恢复，GMV 连续 |
| Kafka 断连 | 停 kafka 容器 1 分钟 | 作业 restart 策略重试，恢复后自动补齐（Kafka 保留期内） |
| ClickHouse 不可写 | 停 clickhouse 容器 | Sink 重试排队，恢复后追平；期间 Redis 大屏不受影响 |
| 数据倾斜复现 | 造 1 个爆品 90% 流量 | UI 看到单 subtask 忙 → 改进方案：局部预聚合 + 盐值打散 |

每次演练产出"现象截图 + 根因 + 处置 + 耗时"四要素，就是简历上"稳定性建设经历"。

## 9. 项目复盘（写进简历的模板）

> 实时数据分析平台：Flink CDC 双源接入（业务库 binlog + 客户端埋点）经 Kafka 分层（DWD/DWS/ADS），Flink SQL 实现分钟级 GMV、HLL 近似 UV、滑动窗口 TopN 与转化漏斗；Checkpoint + upsert 幂等保障 Exactly-Once，经 kill 演练验证不丢不重；ClickHouse 存明细支撑下钻分析，Redis 指标驱动 Grafana 大屏 5 秒刷新，配 GMV 跌零/数据延迟/积压三级告警。

## 验收清单

- [ ] ClickHouse 明细表/汇总表建好，TTL（生存时间）生效策略写进文档
- [ ] Flink 攒批写入 ClickHouse，无 "too many parts" 报错，稳定运行 1 小时
- [ ] Redis 五类指标 Key 按上表规划落地，大屏四个面板全部出数
- [ ] Grafana 大屏 5 秒刷新，切换 $city 变量曲线联动
- [ ] 配置 1 条真实触发的告警（把 Kafka 停掉让延迟告警响一次）
- [ ] 四项故障演练全部完成并留存"现象/根因/处置/耗时"记录
- [ ] 项目复盘文档完成（含架构图与三个"如果重来"的改进点）

## 常见坑

1. **ClickHouse 被当 MySQL 用**：频繁 update/单条 insert → 合并风暴，库直接拒绝写入；牢记"批量写、少更新"。
2. **大屏直查 ClickHouse**：每次刷新全表聚合 → QPS 一高查询排队；热指标必须走 Redis。
3. **Redis 大 Key**：把明细塞进 Hash/ZSET → 删除/迁移阻塞；大屏 Key 只存指标，明细查 ClickHouse。
4. **Grafana 时间范围设成 24h + 1s 刷新**：查询压力大且没意义；近实时看板范围给 1~2 小时即可。
5. **演练没有记录**：做过但讲不清"根因与耗时"，面试官一律视为没做过；**每次演练当场写四要素**。

> 项目三完成。进入 `../04-rag-knowledge-base/README.md` 开始 AI 项目。
