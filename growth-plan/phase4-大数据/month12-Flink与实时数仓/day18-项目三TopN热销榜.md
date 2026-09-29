# Day 18 · 项目三 · TopN 热销榜：一直在变的排行榜

> **今日目标**：项目周第 4 天——从 DWD 明细流产出"每 5 分钟销量 Top3 商品榜"，重点是体感 Update 流的"持续变化"（名次此起彼伏），并验证窗口+TopN 组合的状态开销；与 day17 的 Append 型 GMV 做输出语义对照。
> **时长**：作业开发 1.5h / changelog 观察 1.5h / 对照与输出 1.5h
> **今日产出**：TopN 大屏作业 + changelog 更新记录 + 《Append vs Update 输出对照表》

## 1. 项目设计（先讲人话：排行榜为什么一直在变）

```
电商大屏的"热销榜 Top3"和 GMV 有个本质区别：
  GMV：每分钟一锤定音（窗口关了，这一分钟的数就定了）——Append 语义
  TopN：窗口内的名次随时变——新订单一来，名次可能重排——Update 语义
  —— 所以下游 sink 必须支持更新（MySQL upsert/前端直接收 changelog）

作业结构（day12 模板②的实战版）：
  内层：5 分钟滚动窗口 × 商品 聚合（count）——明细压成"每窗每商品一行"
  外层：按窗口分区 ROW_NUMBER 排序，取 cnt 前 3
  —— 状态账单预估：窗口长 5min，商品 30 个 →
     内层状态 ≈ 30 行×滚动接力；外层状态 ≈ 每窗 30 行排名队伍
     —— 基数小所以轻松；若商品百万级就必须"先粗聚"（类目级 TopN）
  —— day12 讲过的命门再验一遍：不先聚直接排明细，状态爆炸

输出形态（changelog）：
  名次变动时输出：-U(旧行) +U(新行) 或 -D(掉榜) +I(新上榜)
  —— print sink 日志里能直接看到 U/D/I 标记（今天的观察重点）
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| TopN | 前 N 名 | 持续更新的排名榜 |
| Changelog | 变更日志 | +I 插入 / -U 撤旧 / +U 更新 / -D 删除 的流 |
| Row Number Ranking | 排名窗口 | ROW_NUMBER/RANK/DENSE_RANK 三兄弟 |
| State per Window | 每窗状态 | 排名队伍在窗口存续期的"驻留内存" |
| Append vs Update | 追加 vs 更新 | 一锤定音 vs 随时重排（两种输出语义） |

## 3. 动手实操：TopN 作业 + changelog 观察

```sql
-- ===== 源：复用 day17 的 dwd_stream（datagen 或真 Kafka 均可）=====
CREATE TABLE dwd_stream2 (
  uid INT, item_id INT, action STRING, amt DOUBLE,
  et AS CURRENT_TIMESTAMP,
  WATERMARK FOR et AS et - INTERVAL '5' SECOND
) WITH (
  'connector' = 'datagen', 'rows-per-second' = '8',
  'fields.uid.kind' = 'random', 'fields.uid.min' = '1', 'fields.uid.max' = '100',
  'fields.item_id.kind' = 'random', 'fields.item_id.min' = '1', 'fields.item_id.max' = '10',
  'fields.amt.min' = '10', 'fields.amt.max' = '500',
  'fields.action.kind' = 'random', 'fields.action.chars' = 'payview',
  'fields.action.length' = '3'
);

-- ===== TopN 作业（5 分钟窗 × 销量 Top3）=====
CREATE TABLE item_topn_realtime (
  window_start TIMESTAMP(3),
  item_id INT,
  cnt BIGINT,
  rn BIGINT,
  PRIMARY KEY (window_start, rn) NOT ENFORCED   -- 每窗每名次一行（upsert 键）
) WITH ('connector' = 'print');                 -- 生产=MySQL，前端直接渲染榜单

INSERT INTO item_topn_realtime
SELECT window_start, item_id, cnt, rn FROM (
  SELECT window_start, window_end, item_id, cnt,
         ROW_NUMBER() OVER (
           PARTITION BY window_start
           ORDER BY cnt DESC) AS rn
  FROM (
    SELECT window_start, window_end, item_id, COUNT(*) AS cnt
    FROM TABLE(
      TUMBLE(TABLE dwd_stream2, DESCRIPTOR(et), INTERVAL '5' MINUTE))
    WHERE action = 'pay'
    GROUP BY window_start, window_end, item_id    -- 内层先聚：30→10 个商品行
  )
) WHERE rn <= 3;
```

```
观察三件事（边跑边记录）：
① changelog 标记：print 日志里的行带 U/D/I 标记——
   名次上升=旧名次 -U + 新名次 +U；新商品上榜=+I；掉榜=-D
   （截图一张含 U/D 的日志——这就是"持续更新"的实物证据）
② 和 GMV 对照：day17 的窗口输出没有 U/D（Append，一锤定音）——
   两张截图并排贴进笔记（输出语义对照的实物证据）
③ 状态开销：UI → Checkpoints 页看 State Size——
   5min 窗×10 商品 ≈ 很小；改窗长 1 小时再看（状态涨）
   记录：窗长 × key 基数 × N 的三因子关系

对照实验（可选，验证"先聚再排"）：直接对外层明细做 ROW_NUMBER——
  SQL 支持但状态里要驻留全部明细；本例基数小看不出，
  把 item_id 基数开到 10 万再对比 State Size（数字说话）
```

```
《Append vs Update 输出对照表》：
  窗口聚合(GMV)   Append   一窗一行不更新   Kafka append/文件/推送
  TopN/常驻聚合   Update   持续 changelog   MySQL upsert/大屏直连
  判断口诀：结果会"反悔"吗？会→Update+可更新 sink；不会→Append
```

## 4. 面试连接

**Q：实时热销榜怎么实现？它和实时 GMV 的实现有什么本质不同？（项目对照题）**
> 结构上都是"DWD 明细流→窗口聚合→大屏表"，本质不同在输出语义。GMV 是 Append 语义：滚动窗口关闭时一次性输出、结果永不修改，落 Kafka append 主题都行；TopN 是 Update 语义：窗口存续期内每条新订单都可能改变名次，输出是持续的 changelog——名次上升撤旧插新、新商品上榜 +I、掉榜 -D，所以 sink 必须支持按主键 upsert（MySQL/前端直连 changelog）。实现上的共同命门是先聚再排：内层先按窗口+商品聚合把明细压成商品级行数，外层才做 ROW_NUMBER——我做过基数对比，10 万商品基数下不先聚的状态开销差几个数量级。状态开销三因子（窗长×key 基数×N）也要主动讲：窗长 5 分钟、商品 10 个时状态几乎忽略，改 1 小时窗状态明显上涨——参数是业务的（榜单刷新节奏）不是拍的。最后补一句工程判断：榜排名展示可以容忍秒级误差的，还有个轻量替代——Redis ZSet 计数（INCR+ZREVRANGE），Flink 只负责投递计数事件，复杂度低一个量级——方案题答出"更便宜的替代方案"是加分项。

**Q：ROW_NUMBER、RANK、DENSE_RANK 在榜单里怎么选？（小知识题）**
> 区别在并列怎么处理：ROW_NUMBER 强制唯一序号（并列也分先后，10,11,12）；RANK 并列同名次、跳号（10,10,12）；DENSE_RANK 并列同名次、不跳号（10,10,11）。热销榜一般用 RANK——同销量的商品并列更合理；ROW_NUMBER 适合"必须要唯一名次"的场景（发奖只发前三）。SQL 层面三者都是 OVER 窗口函数，性能差异可忽略，选择是业务语义问题。小知识点答得干脆，别展开过度。

## 5. 今日验收清单

- [ ] TopN 作业跑通，changelog 的 U/D 截图留档
- [ ] Append vs Update 对照表成文（GMV vs TopN 截图并排）
- [ ] 状态三因子（窗长×基数×N）实验记录
- [ ] "先聚再排"基数对比实验完成（或推演记录）
- [ ] Redis ZSet 轻量替代方案能讲
- [ ] `git add . && git commit -m "day12-18: proj3-topn"`

---
[← Day 17](day17-项目二实时GMV.md) | [本月目录](README.md) | [Day 19 · 实离对拍 →](day19-实离对拍.md)
