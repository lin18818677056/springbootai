# Day 19 · 存储格式与压缩：列存为什么省

> **今日目标**：搞懂行存与列存的本质区别（用 Excel 横竖着读的类比）、ORC/Parquet 主流列存格式的特点、压缩算法怎么选（Snappy 快 / Zlib 省）；亲手做一次"三种格式+两种压缩"的量化对比实验——**为 day14 建表规范补上"存储格式"条款的实测数字**。
> **时长**：行存列存原理 1.5h / 格式与压缩 1h / 对比实验 2h
> **今日产出**：《存储格式量化对比表》（大小/查询耗时双维度）+ 选型结论

## 1. 知识地图

```
行存 vs 列存——一张 Excel 表的两种读法：
行存（TEXTFILE/MySQL InnoDB）："一行一行读"——像 Excel 横着读一整行
  存储形态：[1,101,50,'book'][2,102,70,'toy'][3,103,90,'food']...
列存（ORC/Parquet）："一列一列读"——像 Excel 竖着读一整列
  存储形态：oid:[1,2,3] uid:[101,102,103] amount:[50,70,90] category:['book','toy','food']

列存为什么适合分析？三个理由（每个都能算账）：
① 只读需要的列：查"各品类总销售额"只需要 amount 和 category 两列
  ——行存也要把整行从磁盘读出来（哪怕只要 2/10 的字段）；
  列存只读 2 列的文件段，磁盘 IO 直接少 80%。
  （这就是分析场景"宽表少列用"的天然优势）
② 同列数据类型一致，压缩率极高：uid 列全是相似的大整数，
  排序后相邻值差距小 → 字典编码/游程编码能压到原大小的 10~20%
  （行存一行里混着整数浮点字符串，谁也帮不了谁）
③ 列存自带"迷你索引"：ORC 每个列段（strip）记录 min/max/sum 统计——
  查 amount>1000 时，统计说这段 max=500，整个段直接跳过（谓词下推的存储层实现）
  ——这是"分层结构里的分区裁剪"：分区跳目录，列存跳段

TEXTFILE（行存文本）为什么还留着？——ODS 保真：
  人眼可读、任何工具都能解析、和源文件格式一致（重放友好）——
  所以规范是：ODS 用 TEXTFILE（原样），中间层开始用 ORC（为性能）

ORC vs Parquet（两大列存，选型一句话）：
  ORC：Hive 生态原生，ACID 支持好，Hive 里默认选它
  Parquet：Spark 生态原生（Impala/Kudu 配它），跨引擎兼容性好
  都支持：列式存储+块统计+谓词下推+多种压缩。单机学习用 ORC 即可。

压缩算法两档（压缩率与速度的权衡——又是权衡题）：
  Snappy/ZSTD(L1)：快（压缩解压 CPU 消耗小），压缩率中等——查询型表标配
  Zlib/Gzip：省（压缩率高一倍），但压得慢解得慢——冷归档数据用
  生产配置：ORC + Snappy 是 Hive 表的默认选择；
  ZSTD 是新一代平衡选手（压缩率接近 Gzip、速度接近 Snappy，Hive 4/Spark 3 支持）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Row / Columnar Format | 行存 / 列存（横着存 / 竖着存） |
| ORC (Optimized Row Columnar) | Hive 生态主流列存格式 |
| Parquet | Spark 生态主流列存格式 |
| Predicate Pushdown | 谓词下推（过滤条件推到存储层，跳过整段数据） |
| Stripe / Row Group | 列存的存储段（每段带 min/max 统计，可整段跳过） |
| Snappy / Zlib / ZSTD | 压缩算法三档（快 / 省 / 新一代平衡） |
| Dictionary Encoding | 字典编码（重复字符串存编号，字符串列的压缩神器） |

## 3. 动手实操：三种格式量化对比

```sql
-- ① 先造一份大点的数据（把 orders 复制 5000 倍≈5 万行，让差异可测量）
CREATE TABLE shop.big_src (oid BIGINT, uid BIGINT, amount DOUBLE, category STRING, dt STRING)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE;
INSERT OVERWRITE TABLE shop.big_src
  SELECT oid, uid, amount, category, dt FROM shop.orders_pt;
INSERT INTO shop.big_src SELECT oid+100000, uid, amount, category, dt FROM shop.big_src;
INSERT INTO shop.big_src SELECT oid+200000, uid, amount, category, dt FROM shop.big_src;
-- 再重复插入几次放大到几万行（INSERT INTO ... SELECT 自增复制）

-- ② 三张表同一份数据：TEXTFILE / ORC(无压缩) / ORC+Snappy
CREATE TABLE shop.f_text LIKE shop.big_src STORED AS TEXTFILE;
CREATE TABLE shop.f_orc   LIKE shop.big_src STORED AS ORC;
CREATE TABLE shop.f_orc_snappy LIKE shop.big_src
  STORED AS ORC TBLPROPERTIES ('orc.compress'='SNAPPY');
INSERT OVERWRITE TABLE shop.f_text SELECT * FROM shop.big_src;
INSERT OVERWRITE TABLE shop.f_orc SELECT * FROM shop.big_src;
INSERT OVERWRITE TABLE shop.f_orc_snappy SELECT * FROM shop.big_src;

-- ③ 量化对比一：存储大小（HDFS du）
-- beeline 外执行：
-- docker exec hadoop-single bash -c "hdfs dfs -du -h /user/hive/warehouse/shop.db/f_text /user/hive/warehouse/shop.db/f_orc /user/hive/warehouse/shop.db/f_orc_snappy"
-- 典型结果（同数据）：TEXTFILE 100% → ORC 25~35% → ORC+Snappy 10~20%

-- ④ 量化对比二：查询耗时（聚合全表扫 + 过滤单列各测一次）
SELECT category, SUM(amount) FROM shop.f_text GROUP BY category;        -- 记时间
SELECT category, SUM(amount) FROM shop.f_orc GROUP BY category;         -- 记时间
SELECT category, SUM(amount) FROM shop.f_orc_snappy GROUP BY category;  -- 记时间
SELECT MAX(amount) FROM shop.f_orc WHERE amount > 99999;                -- 谓词下推实测
-- EXPLAIN 看 f_orc 的查询计划里出现 "filter predicate"——下推生效
-- 把四组数字记进对比表 → 建表规范 v1 的存储条款终于有实测背书
```

## 4. 面试连接

**Q：为什么数仓要用列式存储？和行存比好在哪？（存储必考）**
> 用读法类比切入：行存像 Excel 横着读，一行连着存；列存像竖着读，一列连着存。分析场景三个好处：第一，按需读列——查各品类销售额只要 amount 和 category 两列，列存只读这两段的磁盘块，行存必须整行读出，IO 能省七八成；第二，同列同型压缩率爆炸——一列里全是相似类型的数据，字典编码、游程编码都能用上，实测同一份数据 TEXTFILE 100MB，ORC 加 Snappy 只要十几 MB；第三，列存每段自带 min/max 统计，过滤条件在存储层就能整段跳过——谓词下推，相当于把"分区裁剪"从目录级细化到了数据段级。但行存没有被淘汰：OLTP 场景"取一行改一行"是行存的天下（MySQL InnoDB），而且 ODS 原始层我们坚持 TEXTFILE——人眼可读、工具无关、和源文件一致，重放保真。所以结论是分场景：交易行存、分析列存、原始层文本——和我 day15 讲的分层规范是同一张图。选型补一句：ORC 偏 Hive 生态、Parquet 偏 Spark 生态，都支持谓词下推和多种压缩，单引擎团队跟着生态走即可。

**Q：压缩算法怎么选？ZSTD 好在哪？（细节题）**
> 本质是"CPU 换 IO/存储"的权衡。Snappy 这类：压缩解压都快，CPU 花得少，压缩率中等——适合天天被查询的热表，快比省重要；Gzip/Zlib 这类：压缩率高近一倍，但压得慢解得也慢——适合写一次读得少的冷归档，省比快重要。ZSTD 是新一代平衡者：压缩率接近 Gzip、速度接近 Snappy，还支持多级调节（level 1~19，冷热可调），Hive 4 和 Spark 3 都已内置，新集群我会把它设为默认。三个实操细节：一是压缩要和格式配合——ORC 内部分段压缩，跳段时先看统计再决定解不解压，压缩和下推是乘法关系；二是别在小文件上追求压缩率，先把文件合到 128M+ 再谈压缩（day04 伏笔回收）；三是解压 CPU 也是集群资源——压缩率高意味着查询时解压 CPU 翻倍，YARN 队列的 CPU 配额要一起评估，这就是"省存储"和"费计算"之间的又一层权衡。

## 5. 今日验收清单

- [ ] 行存/列存读法类比+列存三好处能讲（含算账）
- [ ] 三格式两压缩对比实验完成（大小/耗时数字进对比表）
- [ ] 谓词下推 EXPLAIN 验证（filter predicate 出现）
- [ ] Snappy vs Zlib vs ZSTD 选型场景能说（热表/冷档/新默认）
- [ ] 建表规范存储条款更新（带实测数字）
- [ ] `git add . && git commit -m "day10-19: storage"`

---
[← Day 18](day18-数据质量与血缘.md) | [本月目录](README.md) | [Day 20 · Hive 调优 →](day20-Hive调优.md)
