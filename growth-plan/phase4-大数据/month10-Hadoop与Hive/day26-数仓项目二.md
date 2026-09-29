# Day 26 · 数仓项目（二）：DWS → ADS，把菜装盘、对好账、上大屏

> **今日目标**：完成流水线后半段——DWS 备出"半成品宽表"（用户日汇总/类目日汇总），ADS 摆出"大屏专用菜"（GMV 指标），最后做**全链路对账**：从 ODS 到 ADS 四个数字全部对上，大屏数字可复现。
> **时长**：DWS 宽表 2h / ADS 大屏 1h / 全链路对账 2h
> **今日产出**：电商数仓四层全链路 + 《GMV 对账报告》（四个数字+核对结论）

## 1. 知识地图

```
后半段两站，仍是厨房类比：
  DWS 备餐区 = 把"一单单的菜"预做成"半成品"：按用户汇总一份、按类目汇总一份
    ——大屏、报表、分析师都要用这些半成品，做一次处处用（复用）
  ADS 出餐口 = 只做摆盘：从半成品里拿现成的，简单组合就上桌
    ——大屏刷新要快，绝不能现炒（DWD 全表扫）

为什么中间必须有 DWS？（今天实验用数字回答，面试高频）
  ① 性能：100 万行明细 → 汇总成 1 万行宽表，ADS 从宽表算快 100 倍
  ② 复用：GMV 大屏、用户画像报表、运营周报——三张下游共用同一张 DWS，
     口径只算一处（改口径只改 DWS 一张表，不会出现三个大屏三个数）
  ③ 兜底：一切倾斜的终极解是预聚合（day24 手册最后一条）——DWS 本身就是预聚合

全链路对账图（今天的核心产出，四个数字必须相等）：
  ODS 行数（原始 14）== DWD 行数（清洗后 12）
     == SUM(DWS.user 每人订单数)（12）== ADS 订单数字段（12）
  对不上的每一段，就是流水线漏数据/多数据的那一站——这就是"分段断点定位"
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| DWS 宽表 | 汇总半成品表（一行=一个用户/类目某天的累计值） |
| ADS 大屏表 | 最终指标表（一行=一天全站，字段即大屏上的数字） |
| 客单价 (AOV) | GMV ÷ 订单数（比率类指标，禁止跨天直接相加再除——day17 可加性） |
| 全链路对账 | ODS→DWD→DWS→ADS 四段行数/金额逐段核对 |
| 分段断点定位 | 对账对不上时，先找第一段不相等的地方（上游找因，不查下游） |

## 3. 动手实操：后半段施工+对账

```sql
-- ===== 步骤 1：DWS 用户日宽表（备餐：按人汇总）=====
CREATE TABLE IF NOT EXISTS shop.dws_user_order_1d (
  uid        STRING,
  order_cnt  BIGINT,          -- 当日订单数
  gmv        DECIMAL(12,2),   -- 当日 GMV（元）
  last_time  STRING,          -- 当日最晚下单时间
  dt         STRING
)
PARTITIONED BY (dt STRING) STORED AS ORC
TBLPROPERTIES ('orc.compress'='SNAPPY');
INSERT OVERWRITE TABLE shop.dws_user_order_1d PARTITION (dt='2026-09-25')
SELECT uid, COUNT(*), SUM(amount), MAX(order_time)
FROM shop.dwd_order_detail_di
WHERE dt='2026-09-25' GROUP BY uid;

-- ===== 步骤 2：DWS 类目日宽表（备餐：按类目汇总）=====
CREATE TABLE IF NOT EXISTS shop.dws_cat_order_1d (
  category STRING, order_cnt BIGINT, gmv DECIMAL(12,2), dt STRING
) PARTITIONED BY (dt STRING) STORED AS ORC;
INSERT OVERWRITE TABLE shop.dws_cat_order_1d PARTITION (dt='2026-09-25')
SELECT category, COUNT(*), SUM(amount)
FROM shop.dwd_order_detail_di WHERE dt='2026-09-25' GROUP BY category;

-- ===== 步骤 3：ADS 大屏表（摆盘：全站指标一行）=====
CREATE TABLE IF NOT EXISTS shop.ads_gmv_daily (
  dt STRING, gmv DECIMAL(12,2), order_cnt BIGINT,
  aov DECIMAL(10,2),                       -- 客单价 = gmv/order_cnt
  book_gmv DECIMAL(12,2), toy_gmv DECIMAL(12,2)
) STORED AS ORC;
INSERT OVERWRITE TABLE shop.ads_gmv_daily
SELECT '2026-09-25', SUM(gmv), SUM(order_cnt),
       SUM(gmv)/SUM(order_cnt),           -- ①先加总再相除（可加性正确姿势）
       SUM(CASE WHEN category='book' THEN gmv END),
       SUM(CASE WHEN category='toy' THEN gmv END)
FROM shop.dws_cat_order_1d WHERE dt='2026-09-25';

-- ===== 步骤 4：全链路对账四连（核心实验：四个数字抄进报告）=====
SELECT 'ods_raw' seg, COUNT(*) val FROM shop.ods_order_detail WHERE dt='2026-09-25'
UNION ALL SELECT 'dwd_clean', COUNT(*) FROM shop.dwd_order_detail_di WHERE dt='2026-09-25'
UNION ALL SELECT 'dws_sum', SUM(order_cnt) FROM shop.dws_user_order_1d WHERE dt='2026-09-25'
UNION ALL SELECT 'ads_cnt', order_cnt FROM shop.ads_gmv_daily WHERE dt='2026-09-25';
-- 预期：14 → 12 → 12 → 12（ODS 多出的 2 = 1 脏金额 + 1 重复，隔离区/去重各对得上）
SELECT * FROM shop.dwd_order_bad_di WHERE dt='2026-09-25';   -- 隔离区 1 行对上差额

-- ===== 步骤 5：人工对账基准（装不会 SQL 的业务方，用最土的 shell 数一遍）=====
-- docker exec hadoop-single bash -c "grep -c ',' /tmp/ods_order_2026-09-25.csv"  → 14
-- 再数 amount>0 且去重后的行数 → 12 —— 和 Hive 结果一致，报告闭环
```

## 4. 面试连接

**Q：大屏上的 GMV，你怎么保证它是对的？（项目收尾必考）**
> 四道防线，层层递进。第一道是口径先行：GMV 的定义（含不含退款、含不含未支付）在口径登记表里白纸黑字，大屏和报表引用同一个定义——三个大屏三个数的事故，九成是口径没收口。第二道是链路设计：GMV 只在 DWS 算一次（SUM(amount)），ADS 和报表都引用它，不存在两处独立计算。第三道是全链路对账：ODS 行数→DWD 行数→DWS 汇总和→ADS 数值，四段逐段核对——对不上时用"分段断点定位"，第一段出现分叉的地方就是病灶，上游找因而不是下游找补。第四道是过程保障：写入全部 INSERT OVERWRITE 幂等（重跑不翻倍）、DQC 强规则主键唯一+行数波动检测、脏数据进隔离区有据可查。我项目里实际对过账：ODS 14 行，清洗后 12 行，差额 2 行（1 条负金额在隔离区、1 条重复被去重），四段数字全部对上——能报出这串具体数字，比背十条理论都有说服力。

**Q：为什么要有 DWS 层？直接 DWD 算 ADS 不行吗？（架构题）**
> 能跑，但跑不好。用我的项目数字说话：DWD 明细 12 行还看不出差距，真实业务里是亿级行；ADS 大屏每 5 分钟刷新一次，每次都从 DWD 全表扫+聚合，一遍遍烧集群资源。中间加 DWS 后，明细先汇总成万级宽表，ADS 从半成品摆盘，计算量差两个数量级。第二是复用：GMV 大屏、用户报表、运营周报三张下游都基于同一张 `dws_user_order_1d`——口径只算一处，改口径只改一张表；没有 DWS 的话三处各写各的 SUM，迟早出"三个大屏三个数"。第三是它还是倾斜治理的兜底：day24 手册的最后一条——让参与 Join/聚合的数据先变小。一句话总结：DWS 是数仓里的"预聚合缓存层"，和我在 M8 做的计数缓存（先在内存加，最后落库）是同一个思想——贵的计算算一次，便宜地读 N 次。

## 5. 今日验收清单

- [ ] DWS 两张宽表+ADS 大屏表建成（INSERT OVERWRITE 全幂等）
- [ ] 全链路对账四连跑通：14→12→12→12，差额能在隔离区找到
- [ ] 《GMV 对账报告》成文（四数字+人工 shell 复核结论）
- [ ] 客单价先加总再相除（可加性）能讲为什么
- [ ] "为什么要有 DWS"三个理由各带一个项目实例
- [ ] `git add . && git commit -m "day10-26: dw project dws-ads"`

---
[← Day 25](day25-数仓项目一.md) | [本月目录](README.md) | [Day 27 · 调度与运维 →](day27-调度与运维.md)
