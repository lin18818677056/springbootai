# Day 12 · 分区与分桶：先分抽屉再分格

> **今日目标**：搞懂 Hive 两大组织数据的手法——**分区（Partition，按目录分抽屉，查询时整个抽屉跳过）**和**分桶（Bucket，抽屉内再按 hash 分格，为了采样和高效 Join）**。重点：分区裁剪为什么能把查询提速几十倍（EXPLAIN 亲眼看）。
> **时长**：分区 1.5h / 分桶 1h / 裁剪验证与动态分区 1.5h
> **今日产出**：分区分桶表实操 + EXPLAIN 裁剪对比数据 + 建表规范初稿

## 1. 知识地图

```
问题引入：订单表 3 年 10 亿行，查"2026-09-24 这一天"的订单——
没有分区 = 全表扫描 10 亿行（哪怕你只要一天的）；有按天分区 = 只扫 1/1095。
这就是分区：把表按某个字段（通常是日期）拆成 HDFS 的子目录。

分区 Partition——"按日期分抽屉"：
  /warehouse/shop.db/orders/dt=20260923/part-0000
  /warehouse/shop.db/orders/dt=20260924/part-0000   ← 查这天只开这个抽屉
  建表：PARTITIONED BY (dt STRING)——分区字段不存数据文件里，体现在目录名上
  查询：WHERE dt='20260924' → 优化器直接定位目录（Partition Pruning 分区裁剪）
  效果：扫的数据量直接少 1095 倍——这是 Hive 唯一接近"索引效果"的机制
  （day10 说过 Hive 基本没有索引：分区就是它的"粗粒度索引"）

静态分区 vs 动态分区：
  静态：INSERT ... PARTITION (dt='20260924')——一天一天写死（补数场景）
  动态：INSERT ... PARTITION (dt)——从数据里自动取值，一次灌 N 天（初始化场景）
  生产推荐"半动态"：PARTITION (dt='20260924', city)——天写死防跑飞，
  城市动态（day23 倾斜治理会用到这个细节）
  ⚠ 动态分区跑飞事故：字段拿错了，一次生成 10 万个分区 → NameNode 元数据爆炸
    （day04 小文件问题的亲戚）→ 用 hive.exec.max.dynamic.partitions 上限保护

分桶 Bucket——"抽屉内再按 hash 分格"：
  CLUSTERED BY (user_id) SORTED BY (amount) INTO 32 BUCKETS
  规则：hash(user_id) % 32 = 桶号，同一用户永远进同一桶
  两个用途：
  ① 高效 Join（Bucket Join）：两张表按同字段分桶，Join 时小桶对小桶，
    可以跳过 Shuffle（甚至 Map 端完成）——day23 治理 Join 倾斜的武器之一
  ② 高效采样：TABLESAMPLE (BUCKET 1 OUT OF 32)——精确取 1/32 的数据
    （每桶取一个格就是精确的 1/32，随机采样做不到这么均匀）
  分区 vs 分桶一句话区分：分区是"目录级"（人眼可见的文件夹，裁剪靠目录名），
  分桶是"文件级"（同目录下分文件，靠 hash 规则，人眼看不出哪行在哪个桶）。

建表规范 v1（day14 正式稿的底稿）：
① 一律按天分区（dt STRING），粒度按查询需求（小时级任务才用 dt+hour）
② 分区字段过滤必须写（建任务时把 dt 条件当"入口检查"）
③ 大表（亿级+）且常按 user_id/merchant_id Join 的，加分桶 32/64
④ 存储格式 ORC + 压缩（day19 实测后写进规范）
⑤ 表名分层前缀：ods_/dwd_/dws_/ads_（血缘一眼可辨）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Partition | 分区（按字段拆子目录，查询跳过无关目录） |
| Partition Pruning | 分区裁剪（WHERE 带分区条件→只扫对应目录） |
| Static / Dynamic Partition | 静态分区（写死值）/ 动态分区（从数据取值） |
| Bucket | 分桶（按 hash 分文件，桶号=hash%N） |
| Bucket Join / Map Join | 桶对桶 Join / 小表广播 Join（倾斜治理武器，day23） |
| TABLESAMPLE | 采样（按桶取样，精确比例） |

## 3. 动手实操：分区分桶表全流程

```sql
-- beeline 里执行
-- ① 建分区表（按天）并造 3 天数据
CREATE TABLE shop.orders_pt (oid BIGINT, uid BIGINT, amount DOUBLE)
  PARTITIONED BY (dt STRING)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE;
INSERT INTO shop.orders_pt PARTITION (dt='20260922') VALUES (1,101,99.9),(2,102,59.0);
INSERT INTO shop.orders_pt PARTITION (dt='20260923') VALUES (3,101,199.0);
INSERT INTO shop.orders_pt PARTITION (dt='20260924') VALUES (4,103,29.9),(5,101,999.0);

-- ② 分区裁剪实验：EXPLAIN 对比
EXPLAIN SELECT SUM(amount) FROM shop.orders_pt WHERE dt='20260924';
-- 看执行计划里的输入路径：只有 dt=20260924 一个目录！
EXPLAIN SELECT SUM(amount) FROM shop.orders_pt;
-- 不带条件：三个目录全扫——两次对比，裁剪效果一目了然

-- ③ 动态分区：一次灌多天（先开开关）
SET hive.exec.dynamic.partition=true;
SET hive.exec.dynamic.partition.mode=nonstrict;
CREATE TABLE shop.orders_src (oid BIGINT, uid BIGINT, amount DOUBLE, dt STRING)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY ',';
INSERT INTO shop.orders_src SELECT oid,uid,amount,dt FROM shop.orders_pt;
INSERT OVERWRITE TABLE shop.orders_pt PARTITION (dt)
  SELECT oid,uid,amount,dt FROM shop.orders_src;   -- 动态：自动按 dt 值分目录
SHOW PARTITIONS shop.orders_pt;

-- ④ 分桶表 + 采样
CREATE TABLE shop.orders_bk (oid BIGINT, uid BIGINT, amount DOUBLE)
  CLUSTERED BY (uid) INTO 4 BUCKETS
  ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE;
INSERT OVERWRITE TABLE shop.orders_bk SELECT oid,uid,amount FROM shop.orders_pt;
-- 查看物理文件：4 个桶 = 4 个文件（HDFS 里另一终端看目录）
SELECT COUNT(*) FROM shop.orders_bk TABLESAMPLE (BUCKET 1 OUT OF 4 ON uid);
-- 精确的 1/4 采样
```

## 4. 面试连接

**Q：分区和分桶的区别？分别解决什么问题？（高频概念题）**
> 一句话切开：分区是目录级拆分，分桶是文件级拆分。分区把表按字段值拆成 HDFS 子目录——比如按天——查询带上分区条件时优化器直接跳过无关目录，扫的数据量从"三年"变"一天"，这是 Hive 里最有效的性能手段，本质是粗粒度的"目录索引"；分桶是在一个分区内部按 hash 再拆成固定数量的文件——hash(uid)%32=桶号，同一用户的行永远在同一个文件里——它不减少扫描量，解决的是两件分区管不了的事：一是 Bucket Join，两张表按同字段分桶后 Join 可以桶对桶直接配对，省掉 Shuffle；二是精确采样，取 1/32 数据就是拿 1 个桶，均匀且有代表性。建表实践：几乎所有事实表都按天分区（这是规范），亿级且高频按用户维度 Join 的大表再加 32/64 桶（这是优化）。追加一个工程细节体现踩过坑：动态分区要设上限（hive.exec.max.dynamic.partitions），我见过字段选错导致一次灌出 9 万个分区、NameNode 元数据暴涨的事故——动态分区的便利是要用上限和半动态写法（天写死其余动态）来约束的。

**Q：一条 SQL 没带分区条件全表扫描，作为 reviewer 你会怎么处理？（工程化思维题）**
> 分三步。第一步先看是"该带没带"还是"真要全量"——60% 的情况是写 SQL 的人不知道有分区字段，加个 dt 条件立刻解决，review 时把分区字段过滤当硬性检查项；第二步如果是跨天分析（比如"近 90 天"），那不是错误而是代价问题，帮它估算扫描量（90/1095 的数据），确认队列和时长可接受，长期方案是给这类需求建 DWS 预聚合层（day15 分层的价值：让"近 90 天"变成"扫一张 90 行的汇总表"）；第三步治本——把规范固化：任务上线 Checklist 里加"分区条件检查"，再配一个 SQL 审计脚本扫描无分区条件的定时任务（读 Hive 历史元数据就能抓到）。这个处理顺序（先纠正认知、再权衡代价、最后固化规范）就是我理解的数据团队协作方式——不是 reviewer 说了算，是让规范替人说话。

## 5. 今日验收清单

- [ ] 分区/分桶一句话区分（目录级 vs 文件级）+ 各自解决的问题
- [ ] 分区裁剪 EXPLAIN 对比完成（两次执行计划的目录差异贴笔记）
- [ ] 动态分区实验跑通 + max.dynamic.partitions 上限保护能讲
- [ ] 分桶采样 TABLESAMPLE 验证（精确 1/4）
- [ ] 建表规范 v1 五条成文（day14 升级为正式稿）
- [ ] `git add . && git commit -m "day10-12: partition bucket"`

---
[← Day 11](day11-Hive内外部表.md) | [本月目录](README.md) | [Day 13 · HQL 窗口函数 →](day13-HQL窗口函数.md)
