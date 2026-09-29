# Day 13 · HQL 窗口函数：排名、环比不用自连接

> **今日目标**：掌握 HQL 窗口函数（ROW_NUMBER/RANK/LAG/SUM OVER）——一句话：**普通 GROUP BY 把行"捏成一行"，窗口函数"每行都保留，只是在旁边多算一列"**。今天用 10 道练习题把最常用的 5 个窗口函数练到条件反射。
> **时长**：窗口函数原理 1h / 10 题实战 3h（这是本周最值得花时间的实操）
> **今日产出**：10 题全过记录 + TopN/同比环比 SQL 模板（进速查卡）

## 1. 知识地图

```
为什么需要窗口函数？先看 GROUP BY 干不了的事：
"每个用户最近 3 笔订单"——GROUP BY uid 只能给每用户一行（比如 COUNT），
但你要的是"每个用户名下保留 3 行明细"。GROUP BY 的行会合并，
窗口函数的行不合并——这就是本质区别。

窗口函数心智模型："在结果集旁边加一列，这一列在'某个窗口范围内'计算"：
SELECT uid, amount,
  ROW_NUMBER() OVER (PARTITION BY uid ORDER BY amount DESC) AS rn
FROM orders;
读法：PARTITION BY uid = 按用户分窗口（每个用户一个小组）
     ORDER BY amount DESC = 组内按金额排序
     ROW_NUMBER() = 给组内每行编号 1,2,3...

五大常用函数（先记"什么时候用哪个"）：
① ROW_NUMBER()：唯一连续编号（1,2,3,4...）——取 TopN 用它（并列也想挤进 N 个时）
② RANK() / DENSE_RANK()：并列排名（并列第二则 1,2,2,4 / 1,2,2,3）——榜单并列语义
③ LAG(col, n) / LEAD(col, n)：上一行/下一行的值——环比、留存、连续登录用它
④ SUM(col) OVER (PARTITION BY ... ORDER BY ...)：累计求和——累计销售额曲线
⑤ COUNT(*) OVER (PARTITION BY ...)：组内总数——每行旁边标"本组共几单"

经典三板斧模板（面试和实战 80% 的窗口需求）：
模板一 TopN（每组前 N）：
  SELECT * FROM (
    SELECT *, ROW_NUMBER() OVER (PARTITION BY category ORDER BY sales DESC) rn
    FROM dws_category_sales WHERE dt='20260924') t
  WHERE rn <= 3;          -- 每个品类销售额前 3
模板二 环比（和昨天比）：
  SELECT dt, gmv,
    LAG(gmv, 1) OVER (ORDER BY dt) AS yestd,
    ROUND((gmv - LAG(gmv,1) OVER (ORDER BY dt)) / LAG(gmv,1) OVER (ORDER BY dt) * 100, 2) AS mom_pct
  FROM dws_gmv_daily WHERE dt >= '20260901';   -- 注意 LAG 依赖排序，先过滤好范围
模板三 连续 N 天（连续登录/连续打卡）：
  思路：日期减去行号 = 分组键（连续的天算出来同一组）——
  SELECT uid, MIN(dt) AS start_dt, COUNT(*) AS days FROM (
    SELECT uid, dt, DATE_SUB(dt, ROW_NUMBER() OVER (PARTITION BY uid ORDER BY dt)) AS grp
    FROM login_log) t GROUP BY uid, grp HAVING COUNT(*) >= 3;  -- 连续 3 天以上的段
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Window Function | 窗口函数（不合并行，窗口内算一列） |
| PARTITION BY | 分窗口（组内计算，类比 GROUP BY 但不并行） |
| ROW_NUMBER / RANK | 行号 / 并列排名（挤 TopN / 讲并列用哪个记牢） |
| LAG / LEAD | 前一行 / 后一行的值（环比与连续判断的主力） |
| Cumulative Sum | 累计和（SUM OVER 按排序累加） |
| TopN | 每组前 N 名（窗口函数第一应用场景） |

## 3. 动手实操：10 题通关（基于 day12 的 orders_pt 造数扩展）

```sql
-- 准备更丰富的数据（三品类多用户）
CREATE TABLE shop.sales (oid INT, uid INT, category STRING, amount DOUBLE, dt STRING)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE;
INSERT INTO shop.sales VALUES
 (1,101,'book',50,'20260920'),(2,101,'book',70,'20260921'),(3,102,'book',90,'20260922'),
 (4,102,'toy',120,'20260922'),(5,103,'toy',80,'20260923'),(6,101,'toy',60,'20260923'),
 (7,103,'food',30,'20260924'),(8,102,'food',45,'20260924'),(9,101,'food',25,'20260924'),
 (10,103,'book',66,'20260924');

-- 10 题（先自己写，再看答案模板对）：
-- Q1 每个品类销售额 Top2
SELECT * FROM (SELECT *, ROW_NUMBER() OVER (PARTITION BY category ORDER BY amount DESC) rn
  FROM shop.sales) t WHERE rn<=2;
-- Q2 全表金额排名（并列同名次）：RANK 与 DENSE_RANK 差异（找并列数据验证）
SELECT oid, amount, RANK() OVER (ORDER BY amount DESC) r1, DENSE_RANK() OVER (ORDER BY amount DESC) r2 FROM shop.sales;
-- Q3 每个用户各订单旁标注"该用户累计消费"
SELECT *, SUM(amount) OVER (PARTITION BY uid ORDER BY dt) cum FROM shop.sales;
-- Q4 每个用户每笔订单占其总消费比例
SELECT oid, uid, amount, amount / SUM(amount) OVER (PARTITION BY uid) ratio FROM shop.sales;
-- Q5 每日 GMV 及环比（LAG 模板二）
-- Q6 每品类每天 GMV 及日环比
-- Q7 找出每个品类金额最高的订单（RANK=1，注意并列）
-- Q8 每个用户下单间隔天数（LAG(dt) 与 DATEDIFF）
-- Q9 每品类金额 Top1 的 uid 列表（窗口+去重组合）
-- Q10 累计 GMV 曲线（全表 SUM OVER ORDER BY dt）
-- 每题记录：SQL + 结果行数 + 卡住的知识点（10 题记录贴进 day13 笔记）
```

```text
10 题易错点自查（做完对一遍）：
① TopN 忘了嵌套子查询——WHERE rn<=3 不能直接写在带窗口的 SELECT 里（执行顺序：窗口函数在 WHERE 之后）
② LAG 的 PARTITION BY 漏了——环比串台到别的品类
③ RANK vs ROW_NUMBER 选错——并列时 TopN 会"多放人"或"少放人"，要想清楚业务语义
④ 连续登录题不会——记住"日期-行号=分组键"这个技巧就够了（面试高频）
```

## 4. 面试连接

**Q：窗口函数和 GROUP BY 的区别？什么时候必须用窗口函数？（SQL 面试第一梯队题）**
> 本质区别一句话：GROUP BY 聚合后行数变少（每组一行），窗口函数聚合后行数不变（每行多一列）。所以"既要明细又要组内统计"的需求必须用窗口：典型三类——TopN（每个品类前 3 的完整订单明细）、占比（每笔订单占该用户总消费的比例）、时序对比（每天的 GMV 旁边放昨天的 GMV 算环比）。这四类用 GROUP BY 都做不到，或者要写很绕的自连接（MySQL 8 之前大家真的靠自连接写 TopN，窗口函数一出基本绝迹了）。我练过 10 道题里最有意思的是连续登录：用"日期减去 ROW_NUMBER"当分组键，连续的天会算出相同的键——这个技巧把"连续性"问题转成了"分组"问题，面试常考。性能上补一句：窗口函数可能阻止谓词下推的优化，所以外层先过滤分区、内层再开窗口，是我的固定写法习惯。

**Q：写一个 SQL：每个品类销售额最高的商品。（当场手写题）**
> 我会先问一句"并列怎么办"——这决定用 ROW_NUMBER 还是 RANK：业务要"严格取一个"用 ROW_NUMBER（并列也随机挤掉一个），要"并列都算"用 RANK。然后标准三段式写法：内层先限定分区（WHERE dt 条件裁剪，day12 的习惯），中层开窗口编号，外层过滤 rn=1——因为窗口函数不能直接写在 WHERE 里（SQL 执行顺序里窗口在 WHERE 之后算），必须嵌套子查询或 CTE。写完主动说优化点：如果这张表品类基数很小（比如 20 个品类），数据量不大时其实 GROUP BY + MAX 也能做，但拿不到"哪一笔"的明细，窗口方案的信息量更完整；如果品类很多且表很大，考虑先在 DWS 层聚合品类日销售（day15 的分层思想），窗口只扫汇总表——查询成本从亿级降到万级。这个"先问语义、再写标准式、后说优化"的顺序是我在 10 题练习里磨出来的答题节奏。

## 5. 今日验收清单

- [ ] 五大窗口函数"什么时候用哪个"能脱口而出
- [ ] 10 题全部通过（记录进笔记，错题标注知识点）
- [ ] TopN/环比/连续 N 天三板斧模板入速查卡
- [ ] "窗口函数不能直接进 WHERE"的执行顺序原理能讲
- [ ] `git add . && git commit -m "day10-13: window fn"`

---
[← Day 12](day12-分区与分桶.md) | [本月目录](README.md) | [Day 14 · 第二周复盘 →](day14-第二周复盘.md)
