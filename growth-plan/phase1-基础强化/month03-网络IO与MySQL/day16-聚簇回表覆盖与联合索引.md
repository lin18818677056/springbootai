# Day 16 · 聚簇索引 / 回表 / 覆盖索引 / 联合最左前缀

> **今日目标**：昨天懂了 B+ 树的"形"，今天懂二级索引的"用"：聚簇 vs 二级索引叶子里各放什么、回表为什么慢、覆盖索引为什么快，以及最左前缀的完整判定矩阵——这是索引设计的核心四概念，全部用昨天 100 万行的表实证。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：回表/覆盖对比实验记录 + 最左前缀判定矩阵 + 索引设计三原则卡

## 1. 知识地图

```
一棵表 = 一棵聚簇索引 B+ 树（数据即叶子），N 个二级索引 = N 棵小 B+ 树

聚簇索引（PRIMARY）与二级索引（idx_status）的叶子差别：
  聚簇索引树（整表在这）：
    [根] → [内层: id 路标] → [叶子: id + 整行所有列]
  二级索引树 idx_status（迷你索引）：
    [根] → [叶子: status + id]      ← 只存索引列 + 主键，不存整行！

回表（回聚簇索引再取整行）：
  SELECT * FROM t_index WHERE status = 5;
    ① idx_status 树: 找到所有 status=5 的 (status, id) 对
    ② 逐个拿 id 回聚簇索引树查整行    ← "回表"，每行多一棵树的查找
    行数越多回表越痛 → 优化器可能直接弃用索引全表扫

覆盖索引（不回表）：
  SELECT id FROM t_index WHERE status = 5;   ← 要的 id 就在二级索引叶子里
    → 索引"覆盖"了查询所需全部列 → 零回表
  生产套路：把高频 SELECT 列塞进联合索引（空间换时间）

联合索引 (status, created)：排序规则像"字典先按首字母再按拼音"
  索引内部: (0,t1) (0,t5) (1,t3) (1,t9) (2,t2) ...  先 status 后 created
  最左前缀判定矩阵（★必背）：
    WHERE status=1                    ✅ 用上（最左列）
    WHERE status=1 AND created>'x'    ✅ 全用上（两列连续）
    WHERE created>'x'                 ❌ 用不上（跳过最左列）
    WHERE status=1 ORDER BY created   ✅ 排序也用上（免 filesort）
    WHERE status IN (1,2) AND ...     ✅ in 不破坏最左（跳扫描 skip scan）
  记忆法："从最左列开始连续命中才有效，中间断了后面全废"
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Clustered Index | 聚簇索引 | 叶子=整行，每表一棵 |
| Secondary Index | 二级索引 | 叶子=索引列+主键 |
| Back to Table | 回表 | 二级索引→主键→聚簇树取整行 |
| Covering Index | 覆盖索引 | 查询列全在索引里，零回表 |
| Leftmost Prefix | 最左前缀 | 联合索引从左连续匹配 |
| Index Condition Pushdown | 索引条件下推 ICP | 引擎层先过滤再回表（8.0 默认） |

## 3. 动手实操

### 3.1 回表 vs 覆盖索引：同数据两种写法对比

```sql
USE learn;
-- ① 回表版：要整行 → 二级索引命中 10 万行，每行回聚簇树
EXPLAIN SELECT * FROM t_index WHERE status = 5;
-- 看 Extra：可能没有"Using index"（=没覆盖），rows≈10 万

-- ② 覆盖版：只要 id → 二级索引叶子自带，零回表
EXPLAIN SELECT id FROM t_index WHERE status = 5;
-- 看 Extra：Using index ← 覆盖索引的官方标志（截图记进笔记）

-- ③ 性能实证（重点）：把两个 SELECT 各跑三遍看耗时
SELECT SQL_NO_CACHE *    FROM t_index WHERE status = 5;   -- 回表版
SELECT SQL_NO_CACHE id   FROM t_index WHERE status = 5;   -- 覆盖版（只传 id，网络也快）
-- 更狠的对照：故意关索引走全表
SELECT SQL_NO_CACHE * FROM t_index IGNORE INDEX(idx_status) WHERE status = 5;
-- 记录三条耗时 → 回表版明显慢于覆盖版，数据量大时优化器甚至会弃用回表方案

-- ④ 覆盖索引的生产化改造实验：加一列"刚好覆盖"联合索引
ALTER TABLE t_index ADD KEY idx_status_created (status, created);
EXPLAIN SELECT created FROM t_index WHERE status = 5;
-- Extra: Using index ← (status,created) 联合索引把 status 和 created 都带上 = 覆盖
```

### 3.2 最左前缀判定矩阵：9 连测（对着 §1 表格逐条验证）

```sql
-- 逐条 EXPLAIN，只看 key / key_len / rows / Extra 四列
EXPLAIN SELECT * FROM t_index WHERE status = 1;
--   key=idx_status_created ✅ 最左列命中，key_len 只算 status 一段
EXPLAIN SELECT * FROM t_index WHERE status = 1 AND created > '2026-01-01';
--   ✅ 两列连续命中，key_len 覆盖两段，Extra 出现 Using index condition（ICP）
EXPLAIN SELECT * FROM t_index WHERE created > '2026-01-01';
--   key=NULL ❌ 跳过最左列 → 不走索引（8.0 有 skip scan 的特例，讲面试加分）
EXPLAIN SELECT * FROM t_index WHERE status = 1 ORDER BY created;
--   ✅ Extra 无 filesort ← 排序直接顺索引序读，这个细节面试很值钱
EXPLAIN SELECT status, COUNT(*) FROM t_index GROUP BY status;
--   ✅ Using index：分组也走索引序（覆盖+免临时表）
```

### 3.3 ICP 现象观察（选做，理解"条件下推"）

```sql
-- idx_status_created 只有 (status, created)；追加一个不在索引里的过滤列做对照
EXPLAIN SELECT * FROM t_index
WHERE status = 5 AND created > '2026-01-01' AND name LIKE 'user-9%';
-- rows 很大但 Server 层还要过滤 name → ICP 的意义：
--   created 条件在 InnoDB 引擎层先用索引过滤，只有幸存行才回表
-- 面试一句话："ICP = 把 WHERE 里能用索引列判的条件压到引擎层，减少回表次数"
```

### 3.4 索引设计三原则卡（抄进笔记）

```text
原则① 最左前缀设计：把等值条件列放左边，范围列放最后
    例：(user_id, status, created) 支持 user_id= ? AND status=? AND created>
原则② 覆盖高频查询：联合索引顺手把 SELECT 列带上（空间换时间）
    例：列表页 SELECT id,status,created → (status,created) 就能覆盖
原则③ 别过度：每个索引都是一棵要维护的 B+ 树（写放大 + 空间）
    单表索引建议 ≤5 个；写多读少的表更要克制
反例自检：UUID 主键（随机插入页分裂）、冗余单列索引
    （有 (a,b) 就不需要单列 (a)——最左前缀已覆盖）
```

## 4. 面试连接

**Q：什么是回表？怎么避免？**
> 二级索引叶子只存索引列+主键，SELECT 的列不在索引里就要拿主键回聚簇索引再查一次整行，行数多时代价线性放大。避免三板斧：① 只查需要的列（别无脑 SELECT *）；② 设计覆盖索引把高频列纳入联合索引；③ 控制命中行数（加更严条件/分页）。加分句："我在 100 万行的表上实测过，回表版比覆盖版慢数倍，EXPLAIN 看 Extra 的 Using index 就是覆盖标志。"

**Q：联合索引 (a,b,c)，哪些查询能用上？**
> 答判定矩阵：a；a,b；a,b,c；a+c（c 虽不能用于定位但可做覆盖/ICP 过滤）；a ORDER BY b（免 filesort）。不能用：b、b,c、c（跳过最左列）。再补一句原理："联合索引排序规则是先 a 后 b 再 c，跳列就像字典跳过首字母查拼音——没有连续前缀就退化为全扫。"（8.0.13+ 有 Skip Scan 特例可提，注明是优化器彩蛋）

**Q：为什么推荐自增主键，UUID 有什么问题？**
> 自增主键顺序追加写，页写满就开新页，顺序 IO+无碎片；UUID 随机落位，频繁页分裂（50/50 分裂留下半空页）+ 碎片化 + 二级索引叶子全都存 UUID（索引肥大 16B vs 8B）。分布式要全局唯一可用雪花 ID（趋势递增）。这题答的是"索引结构与写入行为的联动"，能展开就是懂 InnoDB。

## 5. 今日验收清单

- [ ] 回表/覆盖两条 EXPLAIN 截图（Using index 标注）
- [ ] 三条 SELECT 耗时对比记录（回表/覆盖/全表）
- [ ] 最左前缀 9 连测全部跑过并记录 key 变化
- [ ] 索引设计三原则卡默写
- [ ] `git add . && git commit -m "day16: cluster/backtable/covering/composite"`

---
[← Day 15](day15-InnoDB页与B+树.md) | [本月目录](README.md) | [Day 17 · 索引高级特性与失效场景 →](day17-索引失效十场景.md)
