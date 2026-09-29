# Day 10 · Hive 入门：给大数据套上 SQL 外壳

> **今日目标**：搞懂 Hive 是什么、不是什么——**它不是数据库，是"SQL 翻译官"**：你写 SQL，它翻译成 MapReduce 去跑，数据其实一直躺在 HDFS 文件里。今天部署 Hive（MySQL 存元数据），跑通建库建表查数据全流程，并理解"读时模式"这个大数据独有的设计。
> **时长**：Hive 定位与架构 1.5h / 部署实操 2h / 读时模式 1h
> **今日产出**：Hive 环境跑通 + 建表查询全流程 + 《Hive vs MySQL 对照表》

## 1. 知识地图

```
先回答 day01 埋的问题：MapReduce 写起来太痛苦了（WordCount 50 行），
但 SQL 人人会——能不能"写 SQL，后台自动翻译成 MapReduce"？
能。这就是 Hive：数据仓库工具 + SQL 翻译官。

Hive 的架构（一次查询的旅程）：
你写 HQL → Hive 的 Driver（解析器→优化器→执行计划生成）
→ 变成 MapReduce/Tez 作业提交到 YARN → 到 HDFS 读文件 → 结果返回
（类比：你跟前台说"我要统计上个月各品类销售额"（说人话/SQL），
 前台把需求翻译成 50 个搬运工+会计的具体工单（MapReduce），
 工人在仓库（HDFS）干活，最后把报表递给你。）

两个关键部件：
① Metastore（元数据服务）：存"表的结构、字段、数据在 HDFS 哪个目录、
   分区长什么样"——注意：只存"账本"，不存数据本身！
   （数据在 HDFS，元数据在 MySQL——所以 Metastore 的 MySQL 挂了，
     数据没丢但"表都找不到了"，这是真实事故常见款）
② 执行引擎：MR（最慢）/ Tez（快些）/ Spark（最快，M11 细讲）——可切换

"读时模式"（Schema on Read）——大数据独有的宽松设计：
  写入时：Hive 不校验数据格式！往表目录里扔文件就完事（很快，进数据无门槛）
  读取时：按表定义的字段格式去"解析"文件（脏数据这时才现形：字段错位/类型转换失败/NULL）
  （对照 MySQL：写时模式 Schema on Write——INSERT 时就校验类型约束，进库前就把关）
  权衡：写快了（无校验），但脏数据溜进来了——所以数仓的"清洗"环节
  （day15 DWD 层）本质是把"读时模式的债"在中间层还掉。
  这个对比是"两套系统的哲学差异"：交易库严进宽出，数仓宽进严出（加工时严）。

Hive vs MySQL 对照表（背熟这张表，定位题不慌）：
| 维度 | MySQL | Hive |
|------|-------|------|
| 定位 | 在线交易库（OLTP） | 离线分析仓（OLAP） |
| 延迟 | 毫秒级 | 秒~分钟级（起 MR 作业） |
| 增删改 | 行级 CRUD 随便 | 基本只读（不支持行级更新，ACID 有限） |
| 校验 | 写时校验 | 读时解析（脏数据晚现形） |
| 数据量 | 亿级吃力 | 百亿级常态 |
| 事务 | 完整 ACID | 无传统事务 |
| 索引 | B+ 树索引丰富 | 基本没有（靠分区裁剪+全扫并行） |
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Hive | SQL on Hadoop（写 SQL 翻译成分布式作业） |
| Metastore | 元数据服务（存表结构/位置/分区——账本，不是数据） |
| HQL | Hive SQL（方言版 SQL，大部分语法兼容） |
| Schema on Read | 读时模式（写入不校验，读的时候才解析） |
| Driver / Compiler | 驱动器/编译器（把 HQL 变成执行计划的流水线） |
| Tez / Spark Engine | 替代 MR 的更快执行引擎 |
| SerDe | 序列化反序列化器（规定文件怎么解析成行和列） |

## 3. 动手实操：部署 Hive 并跑通全流程

```powershell
# ① 起 Hive 容器（连 day01 的 hadoop-single 做 HDFS+YARN；MySQL 做元数据库）
docker network create bigdata 2>$null; docker network connect bigdata hadoop-single
docker run -d --name hive-mysql --network bigdata `
  -e MYSQL_ROOT_PASSWORD=root123 mysql:8.0
docker run -d --name hive-server --network bigdata `
  -e SERVICE_NAME=hiveserver2 `
  -e DB_DRIVER=mysql `
  -e "SERVICE_OPTS=-Xmx1G" `
  apache/hive:4.0.0
# 首次启动会自动 schematool 初始化元数据库（bin/schematool -dbType mysql -initSchema）

# ② 用 beeline 连上（HiveServer2 的 JDBC 客户端）
docker exec -it hive-server beeline -u "jdbc:hive2://localhost:10000" -n root
# ③ 全流程：建库→建表→加载数据→查询
CREATE DATABASE IF NOT EXISTS shop;
CREATE TABLE shop.users (id INT, name STRING, age INT)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE;
INSERT INTO shop.users VALUES (1,'zhang',28),(2,'li',35),(3,'wang',41);
SELECT age / 10 AS decade, COUNT(*) FROM shop.users GROUP BY age / 10;
# ④ 验证"数据其实在 HDFS"（今天最重要的顿悟时刻）：
# 在另一个终端：
docker exec hadoop-single hdfs dfs -cat /user/hive/warehouse/shop.db/users/* 
# 你会看到纯文本逗号分隔——表只是"账本上的说法"，数据就是文件！
# ⑤ 看翻译：EXPLAIN SELECT ... GROUP BY ... 能看到 STAGE 依赖图（Map→Reduce）
```

## 4. 面试连接

**Q：Hive 和 MySQL 有什么区别？（考定位理解，不是考语法）**
> 一句话定位：MySQL 是在线交易库，Hive 是离线分析仓，解决两类完全不同的负载。展开五个维度：延迟——MySQL 毫秒级走索引，Hive 一条 SQL 起一个分布式作业，秒到分钟级，所以它天生不能给线上接口用；更新——MySQL 行级增删改随便来，Hive 基本只读，改数据靠"重写整个分区"；校验——MySQL 写时校验，类型不对插不进去，Hive 读时解析，什么都能先扔进去读的时候才报错，所以脏数据要靠数仓中间层清洗；规模——MySQL 亿级就吃力，Hive 百亿行常态，因为数据和计算都是水平的；事务——MySQL 完整 ACID，Hive 没有传统事务。这两个东西在架构里是上下游：业务数据在 MySQL，通过同步工具落到 HDFS，Hive 在上面做分析——day01 说的"OLTP 管今天这一单，OLAP 管过去三年每一单"就是这张表的浓缩版。

**Q：什么是读时模式？它带来什么问题和好处？**
> 写入时不校验数据格式，读取时才按表定义去解析——这是 Hive 和 MySQL 的根本哲学差异。好处：写入飞快且无门槛，日志、各种格式的原始数据直接往表目录扔，很适合数仓"先囤后理"的工作流。问题：脏数据溜进来了——字段错位、类型转不成、编码不对，都到查询那一刻才炸，而且炸在别人的查询里（跑夜批任务的人最懂这种痛）。所以数仓的标准做法是"宽进严出"：ODS 层随便进（原始层保持原貌），DWD 层统一清洗——去重、补默认值、类型归一、异常行隔离到脏数据目录——把读时模式的债在这一层还掉，下游所有层拿到的都是干净的。我在设计 DQC 规则时就按这个思路：ODS 不设强校验（保持原始数据完整性，方便重放），DWD 设强校验（失败阻断或隔离），各层各司其职。

## 5. 今日验收清单

- [ ] Hive 架构一次查询旅程能白板画（HQL→翻译→YARN→HDFS）
- [ ] "Hive 不是数据库"三个论据能讲（延迟/更新/事务）
- [ ] 部署跑通：建库建表插入查询全流程 + HDFS 里亲眼看到数据文件
- [ ] 读时模式 vs 写时模式权衡能展开（宽进严出的数仓哲学）
- [ ] Metastore 只存账本不存数据（及其挂掉的事故形态）能说
- [ ] `git add . && git commit -m "day10-10: hive intro"`

---
[← Day 09](day09-调度器与队列设计.md) | [本月目录](README.md) | [Day 11 · 内外部表 →](day11-Hive内外部表.md)
