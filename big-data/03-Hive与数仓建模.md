# 03 · Hive 与离线数仓建模

> **本篇目标**：掌握 Hive（基于 Hadoop 的数据仓库工具：SQL 语句 → 分布式计算作业）的核心机制与传统数仓/互联网数仓差异、表类型、分区/分桶、维度建模实战。学完能独立完成"业务库 → ODS → DWD → DWS → ADS"的离线数仓搭建。

## 术语表

| 英文 | 中文 |
|------|------|
| Metastore | 元数据服务（Hive 的大脑：表结构存在哪、数据在哪） |
| HQL (Hive Query Language) | Hive 查询语言（类 SQL，编译为 MapReduce/Tez/Spark 作业） |
| Internal / External Table | 内部表（Hive 管生死）/ 外部表（删表不删数据——数仓首选） |
| Partition | 分区（按列值建目录，如 dt=2026-09-23，查询只扫当天） |
| Bucket | 分桶（按列 hash 再切文件，利于采样与 join） |
| ORC / Parquet | 列式存储格式（压缩高、只读需要的列，数仓标配） |
| Dynamic Partition | 动态分区（按数据自动建分区目录） |
| Skew / Data Skew | 数据倾斜（某几个 key 的数据量占绝对大头，任务被单点拖死） |
| Late-arriving Data | 迟到数据（今天才产生昨天的订单 → 数仓要支持重刷） |
| Backfill / 重刷 | 用历史数据重跑某分区，修正结果 |

## 1. Hive 是什么：SQL 编译器 + 元数据管家

```
你写 HQL → 解析器(ANTLR) → 逻辑计划 → 优化器(谓词下推/列裁剪) → 物理计划(MR/Tez/Spark) → YARN 执行
```

**认知**：Hive 本身不存数据也不算数据——元数据存 Metastore（通常是 MySQL），数据存 HDFS，计算交给引擎。它只是"SQL → 分布式作业"的编译器。

## 2. 内部表 vs 外部表（一句话定生死）

```sql
-- 外部表：数仓 99% 场景用它——删表数据还在，误删可救
CREATE EXTERNAL TABLE ods_order (
  id BIGINT, user_id BIGINT, amount DECIMAL(10,2), status INT
)
PARTITIONED BY (dt STRING)               -- 按天分区
STORED AS ORC                            -- 列式存储
LOCATION '/warehouse/mall/ods/ods_order';

-- 加载当天数据
ALTER TABLE ods_order ADD IF NOT EXISTS PARTITION (dt='2026-09-23');
```

| | 内部表 | 外部表 |
|--|--------|--------|
| DROP 后 | 元数据+数据全删 | 只删元数据 |
| 使用场景 | 临时中间表 | ODS 及一切共享数据 |

## 3. 分区与分桶（Hive 性能的两大支柱）

- **分区 = 目录裁剪**：`WHERE dt='2026-09-23'` → 只扫一个目录，1 年数据只碰 1/365 → **分区列必进 WHERE**，否则全表扫描（新手头号事故）。
- **分桶 = 文件级 hash**：`CLUSTERED BY (user_id) INTO 32 BUCKETS` → 同 user 必落同桶 → 两表按相同列分桶后 join 可走 **bucket map join**（免 shuffle）。
- 经验：分区粒度到"天"最常见；单分区文件数控制在几百以内（又是小文件问题！）。

## 4. 维度建模实战：给电商搭数仓

```sql
-- DWD：订单明细事实表（粒度：一行 = 一个订单商品项，先声明！）
CREATE EXTERNAL TABLE dwd_order_item_detail (
  order_item_id BIGINT COMMENT '订单明细ID',
  order_id      BIGINT,
  user_id       BIGINT,
  goods_id      BIGINT,
  pay_amount    DECIMAL(10,2) COMMENT '实付金额（退化维度：单号也放进来免 join）',
  dt            STRING
)
PARTITIONED BY (dt) STORED AS ORC;

-- DWS：商品粒度日汇总
INSERT OVERWRITE TABLE dws_goods_sales_1d PARTITION (dt='2026-09-23')
SELECT goods_id,
       COUNT(DISTINCT order_id) AS order_cnt,
       SUM(pay_amount)          AS gmv
FROM dwd_order_item_detail
WHERE dt = '2026-09-23'
GROUP BY goods_id;
```

**高频建模概念**：
- **退化维度**：订单号直接放事实表（不建维度表），免 join。
- **缓慢变化维（SCD，Slowly Changing Dimension）**：用户改了收货地址，历史订单该显示新地址还是下单时地址？→ 拉链表（见下）。
- **拉链表**：`start_date/end_date` 两列记录每条维度值的"有效期"，全量变更历史可查、存储只存增量——**数仓面试必考**。
- **全量快照表 vs 增量表**：状态类（账户余额）用每日全量快照；流水类（订单）用增量。

## 5. HQL 调优清单（面试+实战双用）

| 手段 | 原理 |
|------|------|
| 谓词下推（Predicate Pushdown） | 先过滤再扫描（ORC/Parquet 支持得更好） |
| 列裁剪 | 只读 SELECT 涉及的列，列式存储大杀器 |
| map join | 小表（<25MB~100MB）广播进内存，免 shuffle：`/*+ MAPJOIN(dim) */` |
| 数据倾斜治理 | 大 key 拆出单独处理 / `hive.groupby.skewindata=true` 两阶段聚合 /加盐打散 |
| COUNT(DISTINCT) 优化 | 大数据量改 `GROUP BY + COUNT`（两阶段，避免单 reducer 汇总） |
| 动态分区调整 | 分区多时调 `hive.exec.max.dynamic.partitions`，同时注意小文件 |

**数据倾斜标准话术**："先定位（看 task 长尾：99% 的 task 1 分钟完成，1 个跑 1 小时）→ 再归因（null 值、爆品、大用户）→ 后治理（null 加随机盐打散、大 key 单独 join、两阶段聚合）——倾斜本质是 hash 分区后的负载不均，任何按 key 分组的引擎都适用。"

## 6. 离线数仓的每日运转（调度视角）

```
每日 00:30 数据同步（DataX/Sqoop 抽 MySQL 全量+增量 → ODS）
每日 01:00 调度系统（DolphinScheduler/Airflow）按依赖串行跑：
   ODS → DWD（清洗）→ DWS（汇总）→ ADS（报表）
任务依赖：A 表产完 B 才能跑（DAG，有向无环图）→ 失败重试 + 告警 + 依赖树可视化
每日 06:00 实离核对：ADS 结果 vs 实时链路结果，差异超阈值告警
```

后端类比：调度系统 = 分布式任务编排（你写过的 XXL-Job 的高级版），核心新增概念是**数据就绪依赖**（不是时间到了就跑，是上游数据齐了才跑）。

## 验收自测

- [ ] 能解释 Hive 的架构（编译器/Metastore/HDFS/计算引擎各司其职）
- [ ] 能说明内部表/外部表选择理由与外部表防误删价值
- [ ] 能写出带分区的外部表 DDL 并解释"分区列必进 WHERE"
- [ ] 能用"订单明细 → 商品日汇总"例子讲清事实表/维度表/粒度声明
- [ ] 能解释拉链表解决什么问题、怎么设计
- [ ] 能完整说出数据倾斜"定位→归因→治理"三步与至少三种治理手段
- [ ] 能画出离线数仓每日调度依赖图

## 延伸阅读

- Hive 官方 Wiki：LanguageManual（英文，重点读 DDL 与 Select 语法）
- 《大数据之路：阿里巴巴大数据实践》第 2-4 章（数仓建模中文权威）
