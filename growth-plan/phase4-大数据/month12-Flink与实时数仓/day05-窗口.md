# Day 05 · 窗口：班车固定间隔发车，坐满就出发

> **今日目标**：掌握流计算的"出结果节奏"——滚动窗口（每 N 分钟一班车）、滑动窗口（多班车重叠）、会话窗口（坐满没人来才发车）；SQL 语法三种全跑一遍；理解"窗口+水位线"是搭档不是竞争者。
> **时长**：三窗口原理 1.5h / SQL 实验三连 2.5h / 输出 1h
> **今日产出**：三窗口对照实验记录 + 《窗口选型卡》+ 增量聚合原理笔记

## 1. 知识地图（先讲人话：三种班车）

```
窗口 = "把无限流切成有限的段，一段一段出结果"——班车 metaphor：

① 滚动窗口 TUMBLE（定班车，固定间隔发车）
   10:00-10:10 一班，10:10-10:20 一班，互不重叠
   —— "每 10 分钟的 GMV"：分钟大屏、小时报表的标准姿势
   —— 数据只属于一个窗口（班车不重复载客）

② 滑动窗口 HOP（区间车，多班车同时在路上）
   窗口 1 小时、滑动 5 分钟 = 每 5 分钟发一班"近 1 小时统计"
   —— "近 1 小时热销榜"：一条数据会被 12 个窗口同时载走
   —— 代价：结果量=窗口长/滑动步长倍，状态也放大（留意）

③ 会话窗口 SESSION（包车，没人上车了才发车）
   活动间隙超 N 分钟就切窗（用户行为会话分析）
   —— 窗口边界不是固定的，由数据"自己长出来"
   —— 水位线对它尤其关键：没有水位线宣告，会话窗口永远不知道该不该关

窗口和水位线是搭档（day04 的延续，面试易混点）：
  窗口决定"按什么范围分桶"；水位线决定"什么时候封桶出结果"
  —— 窗口切班次，水位线是发车铃：铃响（水位线过终点）才发车

增量聚合（为什么窗口聚合不吃内存）：
  窗口内不是攒 1 万条原始数据最后一起算——
  而是每来一条就往"小账本"上累加（SUM/COUNT 的中间值），
  窗口关闭时只输出账本终值
  —— 内存里只有聚合结果（O(key×窗口数)），不是 O(数据量)——能扛大流量的关键
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| Tumbling Window | 滚动窗口 | 定班车：固定间隔，不重叠 |
| Sliding Window | 滑动窗口 | 区间车：班车重叠，一步一发 |
| Session Window | 会话窗口 | 包车：数据停了才发车 |
| Window Trigger | 触发器 | 发车铃：何时出结果（水位线驱动） |
| Incremental Agg | 增量聚合 | 小账本边收边记，关门只交终值 |
| Group Window | 分组窗口 | 窗口+分组：每班车按 key 分车厢各自算 |

## 3. 动手实操：三窗口 SQL 三连（datagen 全程支撑）

```sql
-- 通用源表（10 秒窗太碎看不清，用 1 分钟级实验更接近真实大屏）
CREATE TABLE orders (
  uid INT,
  item_id INT,
  amt DOUBLE,
  et AS TIMESTAMPADD(SECOND, -CAST(RAND()*5 AS INT), CURRENT_TIMESTAMP),
  -- 事件时间列：当前时间往前拨 0~5 秒（造轻微乱序，day04 手法）
  WATERMARK FOR et AS et - INTERVAL '5' SECOND
) WITH (
  'connector' = 'datagen',
  'rows-per-second' = '4',
  'fields.uid.kind' = 'random', 'fields.uid.min' = '1', 'fields.uid.max' = '50',
  'fields.item_id.kind' = 'random', 'fields.item_id.min' = '1', 'fields.item_id.max' = '100',
  'fields.amt.min' = '10', 'fields.amt.max' = '500'
);

-- ===== 实验①：滚动窗口——每 1 分钟 GMV（大屏核心姿势）=====
SELECT window_start, window_end,
       SUM(amt) AS gmv, COUNT(*) AS order_cnt
FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(et), INTERVAL '1' MINUTE))
GROUP BY window_start, window_end;
-- 观察：整分钟边界+5 秒（水位线）后出上一窗口结果；持续刷新（截图①）

-- ===== 实验②：滑动窗口——近 2 分钟 GMV，每 30 秒刷新 =====
SELECT window_start, window_end, SUM(amt) AS gmv
FROM TABLE(HOP(TABLE orders, DESCRIPTOR(et), INTERVAL '30' SECOND, INTERVAL '2' MINUTE))
GROUP BY window_start, window_end;
-- 观察：每 30 秒出一个新结果（步长驱动），同一订单贡献给多个窗口
-- 记录：结果产出频率 = 滑动步长；单条数据被算过的次数 = 窗口长/步长（截图②）

-- ===== 实验③：会话窗口——按用户切"活动会话" =====
SELECT uid, window_start, window_end, COUNT(*) AS click_cnt
FROM TABLE(GROUP BY ...) -- 会话窗口 SQL 用法（1.18 窗口 TVF 暂不支持会话，
-- 改用经典 GROUP BY 写法）：
SELECT uid,
       SESSION_START(et, INTERVAL '30' SECOND) AS ses_start,
       SESSION_END(et, INTERVAL '30' SECOND) AS ses_end,
       COUNT(*) AS click_cnt
FROM orders
GROUP BY uid, SESSION(et, INTERVAL '30' SECOND);
-- 观察：同一 uid 的连续点击聚在一个会话；间隔超 30 秒切成新会话（截图③）
```

```
窗口选型卡（贴墙）：
  分钟/小时大屏      → TUMBLE（简单、结果不重复）
  "近 N 分钟"曲线    → HOP（注意状态放大：窗口长/步长倍）
  用户会话/粘性分析  → SESSION（依赖水位线收尾，乱序度要给够）
  全局去重计数       → 别用窗口！用去重状态（day06 Keyed State/TTL）
```

## 4. 面试连接

**Q：三种窗口分别什么场景用？窗口和水位线什么关系？（高频组合题）**
> 三种窗口对应三种出结果节奏：滚动窗口固定间隔、不重叠，适合分钟/小时级的大屏指标（每 1 分钟 GMV）；滑动窗口按步长滚动、班车重叠，适合"近 1 小时"这类区间统计，但要留意状态和结果的放大倍数（窗口长/步长）；会话窗口的边界由数据自己长出来（活动间隔超阈值就切），适合用户行为会话分析，它最依赖水位线收尾。窗口和水位线的关系一句话：窗口管分桶，水位线管封桶——窗口把无限流切成段，水位线宣告"这段基本到齐"从而触发窗口出结果，两者是搭档不是替代。加分点：增量聚合——窗口内不是攒原始数据，而是每来一条就更新小账本（聚合中间值），窗口关闭只交终值，内存 O(key 数) 而不是 O(数据量)，这是窗口聚合能扛大流量的底层原因。再补一个实战坑：滑动窗口步长设太小（如 1 分钟长/1 秒步长）会导致每条数据被 60 个窗口持有，状态放大 60 倍——HOP 的步长要和业务刷新频率匹配，别拍脑袋。

**Q：不用窗口能做"累计 UV"这种全量统计吗？（考状态迁移，埋 day06 钩子）**
> 窗口适合"切段出结果"，但"今天的累计 UV"这种跨段统计不能靠窗口——窗口一关状态就交卷了。正确姿势是 Keyed State + TTL：按"用户+天"做 key，来一个新用户状态+1（或直接存 uid 集合），TTL 设到次日零点自动过期——day06 状态管理专门讲这个。这个问题其实是面试官在探你"知不知道窗口的边界"：窗口是无状态快照思维，累计指标是有状态思维，工具箱里得两把都有。

## 5. 今日验收清单

- [ ] 三窗口的比喻+选型能脱稿（定班车/区间车/包车）
- [ ] 实验①②③跑通，三张截图（重点：滑动窗口的放大倍数）
- [ ] "窗口管分桶、水位线管封桶"一句话能讲
- [ ] 增量聚合原理（小账本）能讲
- [ ] 《窗口选型卡》成文
- [ ] `git add . && git commit -m "day12-05: window"`

---
[← Day 04](day04-时间语义与水位线.md) | [本月目录](README.md) | [Day 06 · 状态管理 →](day06-状态管理.md)
