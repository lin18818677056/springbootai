# Day 19 · 查询优化器与 Join 算法（NLJ/BNL/Hash Join）

> **今日目标**：回答"优化器凭什么这么选"——成本模型与统计信息；然后啃 join 三算法（NLJ/BNL/Hash Join）+"小表驱动大表"的真正含义。全部用 EXPLAIN ANALYZE 看真实执行数据，不只是计划。
> **时长**：理论 1.5h / 实操 1.5h / 输出 0.5h
> **今日产出**：Join 三算法对照图 + EXPLAIN ANALYZE 实验记录 + 驱动表选择规则卡

## 1. 知识地图

```
优化器的工作：对每条 SQL 生成候选计划 → 估算成本 → 选最便宜的
成本 = IO 成本（读多少页）+ CPU 成本（比较/排序多少行）
依据 = 统计信息（页数/行数/区分度）：
  SELECT * FROM mysql.innodb_table_stats WHERE table_name='t_index';
  统计过期 → 计划翻车 → ANALYZE TABLE 重算（慢 SQL 排查第 3 步）

Join 三算法（从原始到现代）：
① NLJ 嵌套循环（Simple）：
   for (驱动表每行 r) { for (被驱动表全表扫找匹配) {...} }
   复杂度 O(M×N) —— 太蠢，InnoDB 不会真用它
② INL 索引嵌套循环（被驱动表 join 列有索引）：
   for (驱动表每行 r) { 用 r.key 查被驱动表索引 }
   O(M×索引查找) —— 驱动 1 万行 = 1 万次索引查找，可接受
③ BNL 块嵌套循环（被驱动表无索引的历史方案，8.0.20 移除）：
   驱动表分块装 join buffer → 被驱动表全表扫一遍比对一块
   Extra: Using join buffer(Block Nested Loop) ← 化验单坏点
④ Hash Join（8.0.18+，★现在的主流）：
   小表建哈希表(build) → 大表逐行探测(probe)
   被驱动表没索引也能高效 join —— BNL 的替代者

驱动表选择（面试高频）：
  INL：小表驱动大表（驱动次数=小表行数，每次数被驱动表索引）
  Hash Join：小表做 build 侧（哈希表小=内存友好）
  "小表"的真义：过滤【后】行数少的表，不是行数定义上小的表
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Cost Model | 成本模型 | IO+CPU 加权估算 |
| Nested Loop Join | 嵌套循环 | 双层循环的朴素 join |
| Join Buffer | 连接缓冲 | BNL/Hash Join 的内存块 |
| Hash Join | 哈希连接 | build+probe 两阶段 |
| Driving Table | 驱动表 | 外层循环表（小表优先） |
| EXPLAIN ANALYZE | 实际执行分析 | 8.0.18+ 真跑并统计各步耗时 |

## 3. 动手实操

### 3.1 主菜：三种 Join 现场（同一对表，三种命运）

```sql
USE learn;
-- 准备：t_user_ext(100 行，有主键) join t_index(100 万行)

-- ① INL（被驱动表 join 列有主键）→ 最优
EXPLAIN ANALYZE
SELECT * FROM t_user_ext e JOIN t_index t ON e.id = t.id;
--   看输出树：t 表 access(type=eq_ref) 每行是索引精准查找
--   记录 actual time 与 rows —— 8.0 的 ANALYZE 给真实数字（不只是估算）

-- ② 无索引 join 列 → 触发 Hash Join（8.0）
CREATE TABLE t_tag (name VARCHAR(32), tag VARCHAR(8)) ENGINE=InnoDB;
INSERT INTO t_tag SELECT name, CONCAT('g-', id % 5) FROM t_index WHERE id <= 1000;
--   t_tag.name 无索引
EXPLAIN ANALYZE
SELECT t.id, g.tag FROM t_tag g JOIN t_index t ON t.name = g.name;
--   计划树里看 "Inner hash join"：t_tag 做 build，t_index 全扫 probe
--   对照 5.7 时代：这种写法是 BNL 全表扫 N 次 → 8.0 的巨大进步

-- ③ 强制关 hash join 对照（感受没有它的痛苦）
SET optimizer_switch='block_nested_loop=off,hash_join=off';   -- 实验完记得开回
EXPLAIN SELECT t.id, g.tag FROM t_tag g JOIN t_index t ON t.name = g.name;
SET optimizer_switch='block_nested_loop=on,hash_join=on';
--   观察 Extra 的变化 → 截图对比
```

### 3.2 统计信息与成本验证

```sql
-- 看统计信息
SELECT * FROM mysql.innodb_table_stats WHERE table_name = 't_index';
SELECT * FROM mysql.innodb_index_stats
WHERE table_name='t_index' AND stat_name IN ('size','n_diff_pfx01');
--   n_diff_pfx01 = 首列区分度估算（idx_status_name 的 status 只有 10 种）

-- 看 SQL 的成本估算
EXPLAIN FORMAT=JSON SELECT id FROM t_index WHERE status = 5 LIMIT 10\G
--   找 "query_cost" 字段 —— 优化器眼里的价格签

-- 统计信息过期实验（理解为什么计划会突变）
--   生产事故模板：大表统计过期 → 优化器误判 rows → 计划翻转 → 突然全表
--   药方：ANALYZE TABLE t_index;   （耗时毫秒级，只采样）
```

### 3.3 驱动表选择实验：谁在外层

```sql
-- A：小表驱动（t_user_ext 100 行 → t_index 100 万行）
EXPLAIN SELECT * FROM t_user_ext e JOIN t_index t ON e.id = t.id;
--   id=1 两行：e 在前（驱动），t 是 eq_ref

-- B：写反了 join 顺序（MySQL 会自己换——验证优化器的功劳）
EXPLAIN SELECT * FROM t_index t JOIN t_user_ext e ON e.id = t.id;
--   计划里驱动表仍被优化为小的 e 表 ← "小表驱动"是优化器默认行为
--   什么时候必须人工干预？STRAIGHT_JOIN 强制顺序：
EXPLAIN SELECT STRAIGHT_JOIN * FROM t_index t JOIN t_user_ext e ON e.id = t.id;
--   强行让大表驱动 → rows 放大 → 体会"为什么默认小表驱动"
```

### 3.4 Join 优化规则卡（抄笔记）

```text
① join 列必建索引（被驱动表侧）—— 没索引就指望 hash join 或改写
② 小表驱动大表 —— "小" = 过滤后行数少（WHERE 先行）
③ 控制驱动量 —— 驱动表先 WHERE 筛小再 join（子查询/CTE 先物化）
④ 8.0 下 BNL 已死 —— 看到 Block Nested Loop 说明版本老旧或参数异常
⑤ 大结果 join 交给应用层拆查询 —— 跨库/跨实例 join 是微服务常态
   （id 列表 IN 查询两段拼装，month06 服务化会再遇到）
```

## 4. 面试连接

**Q：MySQL 的 join 是怎么执行的？**
> 分代答：被驱动表 join 列有索引 → INL 索引嵌套循环（驱动表每行走一次索引查找，eq_ref/ref）；无索引 → 8.0.18 前 BNL（分块+多次全表扫），8.0.18+ Hash Join（小表建哈希，大表探测一次全扫）。加分句："我用 EXPLAIN ANALYZE 对比过：1000 行无索引 join 100 万行，hash join 把 BNL 的多趟全表扫合成一趟探测。"收尾补驱动表选择与统计信息。

**Q：为什么建议小表驱动大表？"小"指什么？**
> INL 下驱动表每行触发一次被驱动表索引查找，循环次数=驱动表行数——行数少则查找次数少。Hash Join 下小表做 build 侧，哈希表小可全放内存。"小"的真义：经过 WHERE 过滤后的行数，不是物理行数——1000 万行的大表 WHERE 过滤后剩 100 行，它就是"小表"。所以优化器会基于统计信息自动换序，STRAIGHT_JOIN 只在统计失真时人工兜底。

**Q：统计信息为什么会让 SQL 突然变慢？怎么治？**
> 优化器按统计信息估算 rows 算成本；大表频繁写入时统计过期/采样偏差 → rows 误判 → 计划翻转（弃索引改全表）。治法：① ANALYZE TABLE 手动刷新；② 调 innodb_stats_persistent_sample_pages 提采样；③ 怀疑计划翻转时 EXPLAIN ANALYZE 对比估算与 actual rows。这题答的是"计划不稳定"的根因，中高级面试常用于区分背题党。

## 5. 今日验收清单

- [ ] INL / Hash Join 现场 EXPLAIN ANALYZE 记录（actual time）
- [ ] 关闭 hash join 的对照截图
- [ ] 驱动表自动换序 + STRAIGHT_JOIN 实验记录
- [ ] Join 优化规则卡默写
- [ ] `git add . && git commit -m "day19: optimizer & join algorithms"`

---
[← Day 18](day18-EXPLAIN执行计划.md) | [本月目录](README.md) | [Day 20 · 慢 SQL 治理实战 →](day20-慢SQL治理实战.md)
