# Day 17 · 项目二 · 实时 GMV：秒级大屏的数据从哪来

> **今日目标**：项目周第 3 天——消费 DWD 明细流，按 1 分钟滚动窗口聚合出实时 GMV，写入"ADS 表"（本地无 MySQL 环境用 print 模拟 upsert，生产即 JDBC sink）。跑通后观察三件事：出数节奏、窗口边界的账、恢复后的衔接。
> **时长**：作业开发 2h / 边界与恢复验证 2h / 输出 1h
> **今日产出**：实时 GMV 作业 + 出数节奏记录 + 窗口边界对账笔记

## 1. 项目设计（先讲人话：大屏的数字怎么冒出来的）

```
大屏上的"今日 GMV"每分钟跳一次，背后是这样一条链：
  DWD 明细流（day16 产出）
    → Flink 作业②：1 分钟滚动窗口，SUM(amount) + COUNT(*)
    → 每个窗口关闭（水位线越过终点）输出一行：
       {window_start, window_end, gmv, order_cnt}
    → ADS 落地：生产=MySQL 表 ads_gmv_realtime（按主键 upsert）
       本地演示=print sink（日志看结果）

三个设计决策（每个都有"为什么"）：
  ① 窗口选 1 分钟滚动：大屏刷新节奏=分钟级（业务约束定参数，不是拍脑袋）
     要"今日累计 GMV"再叠一层状态累加（DWS→ADS 的两级）
  ② 事件时间分窗（et 列+水位线 5 秒乱序度）：
     用处理时间会因上游积压导致分钟边界错乱（day04 的结论）
  ③ 输出用 Append 语义（窗口聚合=窗口关了才出，结果不更新）：
     —— 对照：TopN 是 Update 语义（day18 对比）
     —— 补一条 DWS 常驻聚合（无窗口 GROUP BY+upsert）可提供"实时累计值"

和 M11 离线 GMV 的口径对齐（day19 对拍的前提）：
  离线口径：dwd_order_detail_di 的 amount 合计（成功订单）
  实时口径：必须一样——action='pay' 的 amt 合计
  —— 口径不一致的对拍是鸡同鸭讲（对账第一步：先对口径）
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| Realtime GMV | 实时成交额 | 分钟级跳动的"今日卖了多少钱" |
| ADS | 应用层 | 大屏/报表直接读的结果表 |
| Upsert | 按主键更新 | 同一窗口重算就覆盖（幂等落地） |
| Window Boundary | 窗口边界 | 每分钟的账怎么切（事件时间切） |
| Metric Caliber | 指标口径 | "算什么"的定义（实时离线必须一字不差） |
| DWS 常驻聚合 | 无窗口聚合 | GROUP BY 直连状态（提供"累计值"视角） |

## 3. 动手实操：实时 GMV 作业

```sql
-- ===== 步骤 1：源（降级路径：datagen 模拟 DWD 流；正路径换 kafka connector）=====
CREATE TABLE dwd_stream (
  uid INT,
  item_id INT,
  action STRING,
  amt DOUBLE,
  et AS CURRENT_TIMESTAMP,
  WATERMARK FOR et AS et - INTERVAL '5' SECOND
) WITH (
  'connector' = 'datagen', 'rows-per-second' = '10',
  'fields.uid.kind' = 'random', 'fields.uid.min' = '1', 'fields.uid.max' = '100',
  'fields.item_id.kind' = 'random', 'fields.item_id.min' = '1', 'fields.item_id.max' = '30',
  'fields.amt.min' = '10', 'fields.amt.max' = '500',
  'fields.action.kind' = 'random', 'fields.action.chars' = 'payview',   -- 一半是 pay
  'fields.action.length' = '3'
);
-- 正路径（有 connector 时）：
-- CREATE TABLE dwd_stream (...) WITH ('connector'='kafka','topic'='dwd_order_rt',
--   'properties.bootstrap.servers'='kafka:9092','format'='json', ...)

-- ===== 步骤 2：ADS 落地表（print 模拟；生产=jdbc upsert）=====
CREATE TABLE ads_gmv_realtime (
  window_start TIMESTAMP(3),
  window_end TIMESTAMP(3),
  gmv DOUBLE,
  order_cnt BIGINT,
  PRIMARY KEY (window_start) NOT ENFORCED     -- 每分钟一行，重复算就覆盖
) WITH ('connector' = 'print');

-- ===== 步骤 3：聚合作业（窗口 TVF，只算 pay 单）=====
INSERT INTO ads_gmv_realtime
SELECT window_start, window_end,
       SUM(amt) AS gmv,
       COUNT(*) AS order_cnt
FROM TABLE(
  TUMBLE(TABLE dwd_stream, DESCRIPTOR(et), INTERVAL '1' MINUTE))
WHERE action = 'pay'                            -- 口径：只算成交（对齐离线）
GROUP BY window_start, window_end;
-- 提交为作业，观察 1 分钟后开始出结果
```

```
三个验证（边跑边记录）：
① 出数节奏：窗口结束时间 +5s（水位线）左右出上一分钟结果——
   记录理论出数延迟（≤65s）vs 大屏 SLA（分钟级）→ 达标
② 边界对账：挑一个窗口 [10:31:00,10:32:00)，
   数一数 print 输出的 order_cnt 和 datagen 的节奏是否量级一致
   （datagen 10 行/秒×60s×约 1/2 是 pay → 预期 ~300 单/分钟）
③ 恢复衔接：开 3s Checkpoint → kill TM → 拉起 →
   窗口结果继续产出，没有"时间空洞"（记录恢复耗时，day08 实验的实战版）
```

```sql
-- 进阶：DWS 常驻聚合（"今日累计 GMV"的流式写法，无窗口）
CREATE TABLE dws_gmv_total (
  stat_date DATE, total_gmv DOUBLE, total_cnt BIGINT,
  PRIMARY KEY (stat_date) NOT ENFORCED
) WITH ('connector' = 'print');
SET 'table.exec.state.ttl' = '172800 s';   -- 状态保留 2 天（跨零点），day06 知识
INSERT INTO dws_gmv_total
SELECT CAST(et AS DATE), SUM(amt), COUNT(*)
FROM dwd_stream WHERE action = 'pay'
GROUP BY CAST(et AS DATE);
-- 观察：total 持续增长（Update 流），零点后按新日期重新累计
-- —— 累计指标的"状态视角" vs 分钟 GMV 的"窗口视角"，两种武器
```

## 4. 面试连接

**Q：实时 GMV 大屏怎么实现？说说链路和关键设计？（项目主答题）**
> 链路四段：埋点进 Kafka（ODS）→ Flink 清洗成 DWD 明细流（过滤脏行/流式去重）→ 1 分钟滚动窗口聚合成分钟 GMV（TUMBLE TVF + SUM/COUNT，只算 pay 单对齐离线口径）→ ADS 落 MySQL 按主键 upsert，大屏轮询读。四个关键设计我会主动讲：一是事件时间分窗+水位线 5 秒乱序度——上游积压时窗口边界不错乱；二是窗口关闭才输出、结果按 window_start 幂等覆盖——恢复重放不会算重；三是口径先行——实时只算成功订单，和离线 dwd 的口径逐字段对齐，否则 day19 的实离对拍没法做；四是叠加一条 DWS 常驻聚合（按日 GROUP BY+upsert+状态 TTL 两天）提供"今日累计值"——窗口视角给分钟明细，状态视角给累计值，两把工具配合。我实测的出数延迟：窗口结束+5 秒（水位线乱序度）内出数，分钟级大屏 SLA 达标；kill TaskManager 后从 Checkpoint 恢复继续产出无时间空洞。

**Q：大屏显示的实时 GMV 和财务结算对不上，可能是什么原因？（故障排查思路题，好素材）**
> 我按口径→边界→误差源三层排查。第一层口径：两侧"算什么"是否一致——实时是不是把未支付/退款也算进去了、金额字段是分还是元（M11 day25 就踩过分转元的坑）；第二层时间边界：窗口按事件时间还是处理时间切、跨零点的订单归哪天、水位线把迟到数据丢进了哪边；第三层固有误差：实时链路天生有乱序丢弃（水位线外）和重复（恢复重放），靠 upsert 吸收但 append 场景会真重。根因经常是第一层——口径文档缺失导致实时离线各算各的。所以我的防线是"口径先行+实离对拍"（day19 就是干这个的）：差异超 1% 告警，先查口径再查数据——把"对不上"从事故变成每日例行核对。

## 5. 今日验收清单

- [ ] 实时 GMV 作业跑通（print 输出截图）
- [ ] 出数节奏/边界对账/恢复衔接三个验证有记录
- [ ] DWS 常驻聚合（累计值）跑通，与窗口视角的分工能讲
- [ ] "口径先行"在对拍中的位置能讲
- [ ] `git add . && git commit -m "day12-17: proj2-gmv"`

---
[← Day 16](day16-项目一明细流.md) | [本月目录](README.md) | [Day 18 · 项目三 · TopN 热销榜 →](day18-项目三TopN热销榜.md)
