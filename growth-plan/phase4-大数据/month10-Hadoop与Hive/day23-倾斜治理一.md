# Day 23 · 倾斜治理（一）：小表广播与空值打散

> **今日目标**：掌握倾斜治理前两件武器——**Map Join（小表广播：让 Join 根本不走 Shuffle，倾斜自然消失）**和**空值打散（给 null 加随机数，把"一队人"拆成"N 队人"）**。每件武器都做治理前后耗时对比。
> **时长**：Map Join 治 Join 倾斜 1.5h / 空值打散 1.5h / 对比实验 1h
> **今日产出**：两种治理的实测对比数字 + 武器选型决策表（一）

## 1. 知识地图

```
武器一：Map Join（小表广播）——"根本不排队，直接在自己桌上配对"
针对：大表 Join 小表/大表 Join 中表 的 Join 倾斜（day20 旋钮⑥的实战篇）
原理回顾：小表整个装进每个 Map 端的内存 → 大表每行在本地直接查小表
  → 没有按 key 分发的 Shuffle → 热点 key 也就没有"挤一个 Reduce"的问题
三种打开方式（按表大小递进）：
① 自动转换（默认）：hive.auto.convert.join=true，小表 <25MB 自动广播
  ——日常 SQL 什么都不用做，前提是统计信息别过期（ANALYZE TABLE）
② 显式 hint：SELECT /*+ MAPJOIN(small_t) */ ...（统计信息失灵时手动指定）
③ Bucket Map Join / SMB Join（两表都大时）：
  两张表按 Join 键分桶（day12 伏笔回收！），桶号对桶号本地配对——
  "大表拆成 64 份，1 号桶只和 1 号桶比"，Shuffle 变成"按桶读文件"
  set hive.optimize.bucketmapjoin=true; + set hive.optimize.bucketmapjoin.sortedmerge=true;
  前提：建表时 CLUSTERED BY 同一字段、桶数成倍数关系（day12 建表规范加桶的原因）

武器二：空值打散——"把 null 一队人拆成 N 队人"
针对：空值堆积倾斜（未登录 uid 全是 null，hash(null) 全挤一个 Reduce）
思路：null 与 null 本来就不该 Join 上（业务上空值无配对意义）——
  那就把它们随机打散，别让它们排队：
-- 治理前：全表 Join，null 全部挤一个 Reduce
SELECT a.*, b.uid AS b_uid FROM logs a LEFT JOIN users b ON a.uid = b.uid;
-- 治理后：null 加随机后缀（打散到 N 个 Reduce），反正也 Join 不上，分布开就行
SELECT a.*, b.uid AS b_uid FROM logs a LEFT JOIN users b
  ON COALESCE(CAST(a.uid AS STRING), CONCAT('null_', CAST(RAND()*100 AS INT)))
   = CAST(b.uid AS STRING);
  ——null 变成 'null_37' 这样的假键，hash 后均匀散开；
    真实 uid 不受影响；LEFT JOIN 的 null 配不上行，语义不变
简化版（只聚合不 Join 时）：GROUP BY COALESCE(CAST(uid AS STRING),'n/a')
  ——null 作为一个"正常分组值"处理，避免特判堆积
权衡点：随机后缀会失去"null 聚成一组"的语义——如果业务需要统计
  "null 一共有多少"，聚合场景用 COALESCE 统一值，Join 场景才用随机打散。

武器选型决策（一）——按"倾斜类型"对号入座：
| 倾斜类型 | 武器 | 一句话原理 |
|---------|------|-----------|
| Join 一边小 | Map Join | 广播免 Shuffle |
| 两边都大+可分桶 | Bucket/SMB Join | 桶对桶本地配对 |
| 空值堆积 | 空值打散/COALESCE | 随机散开或统一成组 |
| 热点 key 聚合/Join 热点 | （day24）加盐两阶段 | 拆了再合 |
| Count Distinct | （day24）两阶段改写 | 分桶去重再求和 |
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Map Join | 广播 Join（小表进内存，本地配对零 Shuffle） |
| Bucket Map Join / SMB | 桶对桶 Join（两边都大时按桶本地配） |
| 空值打散 | null 加随机后缀均匀散开（Join 场景） |
| COALESCE | 空值统一成一个组值（聚合场景） |
| Hint | SQL 提示（/*+ MAPJOIN(t) */ 手动指定广播） |

## 3. 动手实操：两种治理实测对比

```sql
-- ===== 实验①：Map Join 治理 Join 倾斜 =====
-- 造小表（3 个类目的字典表）：
CREATE TABLE shop.dim_cat (category STRING, cat_level INT)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE;
INSERT INTO shop.dim_cat VALUES ('book',1),('toy',1),('food',1);
-- 治理前：强制 Common Join（关掉自动转换）
SET hive.auto.convert.join=false;
SELECT o.uid, d.cat_level, COUNT(*) FROM shop.skew_orders o
  JOIN (SELECT 'x' category, 1 cat_level UNION ALL SELECT 'y',2) d ON 1=1
  GROUP BY o.uid, d.cat_level;   -- 记耗时（此处用简化 Join 演示流程，实际用 orders join dim_cat）
-- 治理后：开自动转换 + 确认统计信息
SET hive.auto.convert.join=true;
ANALYZE TABLE shop.dim_cat COMPUTE STATISTICS;
SELECT o.uid, d.cat_level, COUNT(*) FROM shop.orders_pt o
  JOIN (SELECT category, 1 AS cat_level FROM shop.dim_cat GROUP BY category) d
    ON o.category = d.category
  GROUP BY o.uid, d.cat_level;
-- EXPLAIN 确认 Map Join Operator；对比耗时（小表广播后 Join Stage 消失）
-- 再显式 hint 版（统计失灵时）：
SELECT /*+ MAPJOIN(d) */ COUNT(*) FROM shop.orders_pt o
  JOIN shop.dim_cat d ON o.category = d.category;

-- ===== 实验②：空值打散治理 =====
-- 治理前（day22 场景二复现过）：NULL 全挤一个 Reduce
SELECT uid, COUNT(*) FROM shop.skew_logs GROUP BY uid;
-- 治理后 A：聚合场景 COALESCE 统一组
SELECT COALESCE(CAST(uid AS STRING), 'guest') k, COUNT(*)
  FROM shop.skew_logs GROUP BY COALESCE(CAST(uid AS STRING), 'guest');
-- 治理后 B：Join 场景随机打散（造 users 表做 LEFT JOIN 演示）
CREATE TABLE shop.users_t (uid BIGINT, name STRING)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE;
INSERT INTO shop.users_t VALUES (1,'a'),(2,'b'),(3,'c');
SELECT a.page, b.name FROM shop.skew_logs a LEFT JOIN shop.users_t b
  ON COALESCE(CAST(a.uid AS STRING), CONCAT('null_', CAST(CAST(RAND()*100 AS INT) AS STRING)))
   = CAST(b.uid AS STRING);
-- 对比记录：治理前长尾 Reduce 独扛 N 万行 → 治理后各 Reduce 均匀
```

## 4. 面试连接

**Q：大表 Join 小表倾斜怎么治？（倾斜治理第一问）**
> 首选 Map Join：把小表整体广播到每个 Map 端内存，大表每行在本地直接配对——Join 根本不走 Shuffle，热点 key 自然没有"挤一个 Reduce"的问题。三档用法：日常靠自动转换（hive.auto.convert.join 默认开，小表 25MB 内自动广播，前提是 ANALYZE TABLE 统计信息别过期）；统计失灵时用 hint 手动指定 MAPJOIN；两张表都大就走 Bucket Map Join——建表时按 Join 键分桶（这就是为什么建表规范里"亿级大表加分桶"），桶号对桶号本地配对，Shuffle 变成按桶读文件。我实验对比过：同一个聚合 Join，Common Join 走 Shuffle 长尾明显，开 Map Join 后整个 Join Stage 从执行计划里消失，耗时降一个数量级。补充边界：小表的判断是"压缩前大小+键基数都能装进内存"，长字符串键的"小表"会撑爆 Map 端；如果两边都大且没法分桶，终极方案是让两边变小——在 DWS 预聚合后再 Join。核心思想一句话：与其治理排队的混乱，不如让队列消失。

**Q：null 值导致的倾斜怎么处理？为什么可以随便打散？**
> 先讲根因：hash(null) 对所有行都是同一个值，一亿条未登录日志全部挤进同一个 Reduce。处理分场景：Join 场景用随机打散——给 null 拼一个随机后缀（CONCAT('null_', RAND()*100)），hash 后均匀散到 N 个 Reduce；这么做的合法性在于 LEFT JOIN 语义没变——null 本来就匹配不上任何用户，散到哪里都匹配不上，只是"排队的队伍"从 1 条变 100 条，结果完全一致。聚合场景用 COALESCE 统一——把 null 变成'guest'这样的正常值参与 GROUP BY，既打散了特判压力又保留了"未登录用户一共多少"的统计语义。两种打法的区别就一句话：Join 场景 null 是"配不上的孤儿"可以随便安置；聚合场景 null 是"需要被统计的一组人"要保留成组。我踩过的坑是把两种场景搞反——聚合统计里用了随机后缀，结果"未登录用户数"被拆成 100 行对不上账，DQC 波动检测当场报警（day18 的规则救了一把）。这也说明倾斜治理不是纯技术题，改写前必须确认业务语义。

## 5. 今日验收清单

- [ ] Map Join 三档用法+每档适用条件能讲
- [ ] Map Join 治理实验完成（EXPLAIN 证据+耗时对比）
- [ ] 空值打散两种打法（随机打散 vs COALESCE）及语义区别能讲
- [ ] 打散翻车的坑（聚合语义被拆）能自述
- [ ] 武器选型决策表（一）成文
- [ ] `git add . && git commit -m "day10-23: skew fix1"`

---
[← Day 22](day22-数据倾斜定位.md) | [本月目录](README.md) | [Day 24 · 倾斜治理二 →](day24-倾斜治理二.md)
