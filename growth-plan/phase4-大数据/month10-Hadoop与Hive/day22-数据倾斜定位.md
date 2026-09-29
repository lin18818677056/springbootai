# Day 22 · 数据倾斜定位：一个组员干了全组的活

> **今日目标**：搞懂数据倾斜（Data Skew）——一句话：**绝大部分数据被 hash 到了同一个 Reduce，一个人累死全组围观**（绝大多数任务 1 分钟完成，剩 1 个跑了 40 分钟）。今天学会定位倾斜（现象→YARN UI→执行计划），并亲手复现 3 种典型倾斜场景。
> **时长**：倾斜原理 1h / 定位方法 1h / 复现 3 场景 2.5h
> **今日产出**：3 种倾斜场景复现记录（含定位证据）+ 《倾斜定位 Checklist》

## 1. 知识地图

```
回顾 day06 的 Shuffle：Map 输出按 key 的 hash 分发到 Reduce——
hash(uid) % N 决定"这行数据归几号 Reduce 管"。
倾斜的本质：key 分布严重不均 → hash 后大量行挤进同一个桶 →
99 个 Reduce 1 分钟干完，1 个 Reduce 独扛 90% 数据跑 40 分钟。
（类比：全班分组打扫，按姓名首字母分组——结果全班 60% 的人姓王，
 "王字组"四十个人扫一个操场，"李字组"一个人扫一个厕所。）

倾斜的两个直观现象（先看现象再定位）：
① 任务时间极度长尾：99% task 几十秒完成，最后 1% 卡几十分钟（YARN UI 长尾明显）
② 某几个 Reduce 的数据量/耗时一骑绝尘（Container 日志里 shuffle bytes 巨大）
判别要点：不是"跑得慢"而是"极不均匀地慢"——平均提速解决不了长尾。

倾斜根因四大类（每类一个电商真实例子）：
① Key 天然分布不均：大商家/爆款——TOP1 商家订单占全站 40%，
   按商家聚合时它的 Reduce 独扛
② 空值/默认值堆积：日志里 user_id 大量为 null（未登录用户）——
   hash(null) 全是同一个值，全部挤一个 Reduce
③ 大表 Join 大表 + 热点键：订单 Join 点击日志，某爆款商品的点击行数百万级
④ Count Distinct：按 uid 去重计数——所有 uid 要 Shuffle 到一起比一比，
   单个 Reduce 内存和压力巨大（和①②常复合出现）

定位三板斧（从外到内，30 分钟定位法）：
① 看 YARN UI（8088）→ 找到作业 → Counters：Map/Reduce 各任务耗时分布，
   长尾 Reduce 的 shuffle bytes 是否远超其他——第一证据
② 看 Hive 执行日志：卡住的 Reduce 编号 + 它处理的表/Join 阶段
  （日志关键词：Reduce task ... running超时）
③ 看数据分布（SQL 定位到 key 层面）：
   SELECT uid, COUNT(*) cnt FROM t GROUP BY uid ORDER BY cnt DESC LIMIT 10;
   ——TOP 键占了多少比例一目了然（TOP1 占 40% = 石锤）
   JOIN 倾斜同理：分别查两张表 Join 键的 TOP 分布

《倾斜定位 Checklist》（遇到慢任务先走这张单）：
- [ ] YARN UI 看任务分布：是均匀慢还是长尾慢？（均匀慢=调优题 day20，长尾慢=倾斜题）
- [ ] 长尾 task 是 Map 还是 Reduce？（Map 长尾=读入不均/小文件，Reduce 长尾=倾斜主力）
- [ ] EXPLAIN 找到长尾 Stage 对应的算子（GroupBy？Join？Distinct？）
- [ ] 对嫌疑 key 跑 TOP-N 分布 SQL，量化 TOP1 占比（>10% 即高危）
- [ ] 判断根因类：天然热点/空值/Join 热点/Count Distinct
—— 定位清楚才轮到治理（day23/24 的四种武器按根因对号入座）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Data Skew | 数据倾斜（key 分布不均导致任务长尾） |
| Hot Key / 热点键 | 被挤爆的那个 key（TOP1 商家/null 值） |
| Task 长尾 | 少数任务拖到最后（整条链路被最慢者决定） |
| Shuffle Bytes | 任务搬动的数据量（长尾任务的此值一骑绝尘） |
| Count Distinct | 去重计数（倾斜惯犯：全量比对） |
| 谓词下推 | 与倾斜无关但常混淆——那是存储层跳段（day19） |

## 3. 动手实操：复现 3 种倾斜场景

```sql
-- 场景一：热点 key 聚合倾斜（大商家）
-- 造数：uid=888 占 90% 行（模拟 TOP1 商家）
CREATE TABLE shop.skew_orders (oid BIGINT, uid BIGINT, amount DOUBLE)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE;
INSERT INTO shop.skew_orders
  SELECT oid, 888, amount FROM shop.big_src;                     -- 90% 都给 888
INSERT INTO shop.skew_orders
  SELECT oid, CAST(oid % 20 AS BIGINT), amount FROM shop.big_src; -- 其余分给 20 个普通 uid
-- 执行聚合并观察：
SELECT uid, SUM(amount) FROM shop.skew_orders GROUP BY uid;
-- YARN UI：某一个 Reduce 耗时远超其他 + shuffle bytes 巨大 → 复现成功

-- 场景二：空值堆积倾斜（未登录日志）
CREATE TABLE shop.skew_logs (uid BIGINT, page STRING)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE;
INSERT INTO shop.skew_logs SELECT NULL, page FROM shop.big_src;  -- 大量 NULL
INSERT INTO shop.skew_logs SELECT CAST(oid % 5 AS BIGINT), page FROM shop.big_src;
SELECT uid, COUNT(*) FROM shop.skew_logs GROUP BY uid;   -- NULL 全挤一个 Reduce
-- 看 TOP 分布（定位 SQL）：
SELECT CAST(uid AS STRING) k, COUNT(*) c FROM shop.skew_logs GROUP BY uid ORDER BY c DESC LIMIT 5;

-- 场景三：Count Distinct 压力
SELECT COUNT(DISTINCT uid) FROM shop.skew_orders;
-- EXPLAIN 观察执行计划：一个 Reduce 阶段做全局去重（对比先 GROUP 再 COUNT 的两阶段）
-- 对比实验（day24 的治理预告）：
SELECT SUM(c) FROM (SELECT uid, COUNT(*) c FROM shop.skew_orders GROUP BY uid) t;
-- 两阶段写法把"全局去重"拆成"分桶去重再求和"，压力摊开
```

## 4. 面试连接

**Q：什么是数据倾斜？怎么定位？（倾斜第一问，考方法）**
> 现象一句话：按 key 的 hash 分发后，大部分数据挤进少数几个 Reduce——99 个任务一分钟跑完，1 个跑四十分钟，整条任务被最慢的决定。和"均匀地慢"区分开是第一件事：均匀慢是资源不够或 SQL 差（day20 调优题），长尾慢才是倾斜。定位三板斧：先看 YARN UI 的任务耗时分布，长尾 Reduce 的 shuffle bytes 远超其他是第一证据；再用 EXPLAIN 把长尾 Stage 对应到具体算子——是 GroupBy 聚合还是 Join 还是 Count Distinct；最后用 SQL 量化嫌疑 key 的分布——GROUP BY 候选键 ORDER BY count DESC LIMIT 10，TOP1 占比超过 10% 基本石锤。根因分四类：天然热点（TOP 商家）、空值堆积（未登录 uid 全是 null）、大表 Join 热点键、Count Distinct 全量比对。我复现过全部四种——比如造一份 90% 行都是 uid=888 的数据跑聚合，YARN UI 上那个一骑绝尘的 Reduce 看得清清楚楚。定位清楚根因才谈治理，四类根因对应四种武器，空值打散、Map Join、加盐、两阶段改写——这是后两天的内容。

**Q：为什么 COUNT(DISTINCT) 容易出问题？原理上慢在哪？（细节题）**
> 原理在于它的 Shuffle 语义：COUNT DISTINCT 要保证"全局唯一"，同一个值的所有行必须汇到同一个地方才能判重——默认实现会把全表数据按去重键 Shuffle 到极少数 Reduce（极端是一个），单点内存和计算压力全在这。数据量小没事，亿级表一行都是负担。优化写法是两阶段：先 GROUP BY 去重键得到每组一行（这一步可以多 Reduce 并行摊开），外层再 COUNT 求和——把"全局比对"拆成"分桶比对再汇总"。两个补充：一是如果分组维度里本身有 uid 这类高基数维度（比如按天统计活跃用户数），GROUP BY 已经把 uid 分散了，COUNT DISTINCT 并不倾斜——它只在"全局唯一去重"或"低基数分组内去重"时才危险；二是 Spark 3.x 有扩展机制会自动两阶段化，但 Hive/MR 引擎要手写——这也是很多团队把重 SQL 从 Hive 迁 Spark 的原因之一。我实测过：5 万行看不出差别，放大到百万行以上两阶段写法的 Reduce 耗时就拉开一个数量级。

## 5. 今日验收清单

- [ ] 倾斜现象与"均匀慢"的区分能讲（长尾 vs 均匀）
- [ ] 四大根因各配一个电商例子
- [ ] 三种场景复现完成（YARN UI 证据+TOP 分布 SQL 都有记录）
- [ ] 《倾斜定位 Checklist》五步成文
- [ ] COUNT DISTINCT 两阶段对比实验完成
- [ ] `git add . && git commit -m "day10-22: skew locate"`

---
[← Day 21](day21-第三周复盘.md) | [本月目录](README.md) | [Day 23 · 倾斜治理一 →](day23-倾斜治理一.md)
