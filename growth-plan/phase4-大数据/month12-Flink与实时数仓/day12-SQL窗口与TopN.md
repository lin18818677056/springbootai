# Day 12 · SQL 窗口与 TopN：实时 GMV 和热销榜的写法

> **今日目标**：把 day05 的窗口知识和 day11 的 SQL 能力合成两个"可直接上生产"的写法——窗口 TVF（Table-Valued Function）的滚动聚合，和 TopN（实时热销榜）的"先聚再排"套路。这两段 SQL 就是 day17/18 项目作业的原型。
> **时长**：窗口 TVF 1.5h / TopN 2h / 输出 1.5h
> **今日产出**：两个可复用 SQL 模板 + TopN 去重更新机制笔记 + 性能提示清单

## 1. 知识地图（先讲人话：两个最值钱的 SQL 模板）

```
模板① 窗口聚合（实时 GMV 的标准姿势）：
  TUMBLE 窗口 TVF：TABLE(TUMBLE(TABLE 源, DESCRIPTOR(时间列), INTERVAL '1' MINUTE))
  —— 1.18 推荐写法（窗口 TVF 比老式 GROUP BY TUMBLE(...) 更可读）
  语义：每分钟的订单汇总成一个窗口行——窗口关闭（水位线越过）才输出

模板② TopN（实时热销榜的标准姿势）：
  一步到位的写法是错的：直接对明细 ROW_NUMBER()——
    每条数据都全量重排，状态爆炸+算不动
  正确姿势=先聚再排（两段式）：
    内层：窗口内按商品聚合（count）——把明细压成"每窗每商品一行"
    外层：按窗口分区 ROW_NUMBER() 排序取前 N
  —— M11 Spark 的 TopN 也是同一思想（先聚合降基数再排序），跨月同构

TopN 的底层机制（面试深水区）：
  TopN 的结果不是"关窗出一次"，而是【持续更新】的排名榜：
  每来一条数据，名次可能变——输出是 changelog（+新排名 / -旧排名）
  —— 状态：每个 key 的"榜上队伍"要记着（谁在第几名），
     名次变动时撤回旧的插入新的（Retract 流，day11 概念在此落地）
  —— 这就是为什么 TopN 必须用支持更新的 sink（大屏一般直连 MySQL/Redis）

性能提示（写生产 SQL 前过一遍）：
  · TopN 外层记得带窗口时间分区（PARTITION BY window_start）——不然全局排
  · 聚合 key 尽量粗（商品 id 而不是 商品id+用户id）
  · N 取业务最小值（Top 10 就别排 100）
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| Window TVF | 窗口表函数 | 窗口的现代 SQL 写法（TABLE(TUMBLE(...))） |
| TopN | 实时排名 | 持续更新的前 N 名榜单 |
| ROW_NUMBER OVER | 排名窗口函数 | 给榜上队伍编号（先聚再排的外层） |
| Retract | 撤回 | 名次变了：撤旧排名插新排名 |
| Descriptor | 描述符 | 声明"按哪列的时间切窗" |
| Pre-aggregate | 预聚合 | 先把明细压小再排序（TopN 的命门） |

## 3. 动手实操：两个生产级模板跑通

```sql
-- 源表（带类目字段，为 TopN 准备）
CREATE TABLE shop_src (
  uid INT,
  item_id INT,
  amt DOUBLE,
  et AS CURRENT_TIMESTAMP,
  WATERMARK FOR et AS et - INTERVAL '5' SECOND
) WITH (
  'connector' = 'datagen', 'rows-per-second' = '10',
  'fields.uid.kind' = 'random', 'fields.uid.min' = '1', 'fields.uid.max' = '100',
  'fields.item_id.kind' = 'random', 'fields.item_id.min' = '1', 'fields.item_id.max' = '20',
  'fields.amt.min' = '10', 'fields.amt.max' = '500'
);

-- ===== 模板①：窗口 TVF——每分钟 GMV（day17 项目原型）=====
SELECT window_start, window_end,
       SUM(amt) AS gmv,
       COUNT(*) AS order_cnt
FROM TABLE(
  TUMBLE(TABLE shop_src, DESCRIPTOR(et), INTERVAL '1' MINUTE))
GROUP BY window_start, window_end;
-- 观察：每分钟边界+5s（水位线）出一行；结果稳定不重复（Append 语义输出）

-- ===== 模板②：TopN——每分钟销量前 3 的商品（day18 项目原型）=====
SELECT * FROM (
  SELECT *,
         ROW_NUMBER() OVER (
           PARTITION BY window_start
           ORDER BY cnt DESC) AS rn
  FROM (
    SELECT window_start, window_end, item_id, COUNT(*) AS cnt
    FROM TABLE(
      TUMBLE(TABLE shop_src, DESCRIPTOR(et), INTERVAL '1' MINUTE))
    GROUP BY window_start, window_end, item_id      -- 内层：先聚合降基数
  )
) WHERE rn <= 3;                                     -- 外层：再排名取前 3
-- 观察：结果持续刷新；某商品名次上升时会看到行的"更新"（Update 流）
-- 记录：内层输出行数=窗口数×商品数（20 个商品），外层才排序——
--      如果不先聚（直接对明细 ROW_NUMBER），每条数据都要全量重排，状态爆炸
-- 对照实验（可选）：把内层聚合去掉直接排明细，UI 看状态大小对比（截图）
```

```
《SQL 模板卡》（day17/18 直接抄）：
  GMV 大屏   = TUMBLE TVF + SUM/COUNT            （Append 输出）
  热销榜     = TUMBLE TVF 聚合 → ROW_NUMBER ≤ N   （Update 输出，需 upsert sink）
  趋势曲线   = HOP TVF（窗长/步长按业务刷新频率定）
  会话分析   = SESSION 老式 GROUP BY 写法（TVF 暂不支持）
```

## 4. 面试连接

**Q：写一个实时 TopN，注意什么？（手写 SQL 题高频）**
> 核心是"先聚再排"两段式。直接对明细做 ROW_NUMBER 是新手陷阱：每条数据到达都触发全量重排，状态里要维护全部明细，几万 QPS 下去必反压。正确写法内层先按窗口+商品做聚合（TUMBLE TVF + GROUP BY，把明细压成每窗每商品一行，基数从亿级降到商品数级），外层再按窗口分区做 ROW_NUMBER 排序取前 N。三个生产注意点：外层 PARTITION BY 必须带 window_start（否则是全时间维度的榜，不是每期榜单）；输出是 Update 流，sink 要支持 upsert（MySQL/Redis/大屏后端），同时消费端要按"撤旧插新"处理 changelog；N 和聚合 key 的基数要控制（Top 10 就别排 100，商品级 key 别掺用户 id）。这个思路和我在 Spark 里做 TopN 完全同构——先聚合降基数再排序，M11 的经验直接平移。

**Q：窗口 TVF 和老式 GROUP BY 窗口写法有什么区别？（考版本跟进）**
> 功能上老式写法（GROUP BY TUMBLE(time_col, INTERVAL '1' MINUTE)）也能用，但 1.13+ 推荐窗口 TVF（FROM TABLE(TUMBLE(TABLE t, DESCRIPTOR(col), INTERVAL ...))）：一是可读性——窗口声明独立成 FROM 子句，时间列显式传参；二是组合性——TVF 结果就是一张表，窗口内可以继续 JOIN/再套窗口，老写法不行；三是未来窗口新特性都在 TVF 上演进。我的实践：新作业一律 TVF，唯一例外是会话窗口（TVF 暂不支持，仍用 GROUP BY SESSION 老写法）——这个"例外清单"本身就是面试里的加分细节，说明真的写过而不是背文档。

## 5. 今日验收清单

- [ ] 两个模板 SQL 能默写（GMV/TopN）
- [ ] "先聚再排"的原理和不先聚的后果能讲
- [ ] TopN 持续更新（changelog）机制能讲
- [ ] 对照实验（有无预聚合的状态对比）完成
- [ ] 《SQL 模板卡》成文
- [ ] `git add . && git commit -m "day12-12: topn"`

---
[← Day 11](day11-FlinkSQL入门.md) | [本月目录](README.md) | [Day 13 · 维表关联与 CDC →](day13-维表关联与CDC.md)
