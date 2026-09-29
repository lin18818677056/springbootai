# Day 11 · 内外部表：删表时数据跟不跟着走

> **今日目标**：搞懂 Hive 内部表（Managed）与外部表（External）的区别——一句话：**内部表删表连数据一起删（Hive 全权管理），外部表删表只删账本、数据原地不动（Hive 只管记账）**。以及数仓 ODS 层为什么强制用外部表。今天做实验亲眼验证。
> **时长**：概念与选型 1h / 实验 1.5h / 数据加载方式 1h
> **今日产出**：内外表对比实验记录 + ODS 外部表挂载真实日志目录

## 1. 知识地图

```
day10 的顿悟："表只是账本上的说法，数据就是 HDFS 文件"。
顺着推：那"删表"的时候，底下的文件删不删？——两种选择，就是内外表：

内部表 Managed Table（默认）——"Hive 全权管理"：
  建表时数据放 Hive 的仓库目录（/user/hive/warehouse/xxx.db/yyy/）
  删表 DROP：元数据和数据文件一起删（账本撕了，货也扔了）
  类比：公司自营仓库，货和台账都归公司，退货时台账货品一起清。
  适用：中间加工结果（DWD/DWS 的临时产物），数据可由上游重建，删了无所谓。

外部表 External Table（EXTERNAL 关键字）——"Hive 只管记账"：
  建表时用 LOCATION 指到任意 HDFS 目录（数据已经躺在那了，谁来都行）
  删表 DROP：只删元数据，文件原地不动（账本撕了，货还在货架上）
  类比：寄存仓库，仓库只记了"这批货在 3 号货架"，合同解除只是撕记账，
  货主的东西一分不少。
  适用：①ODS 原始层——数据是"进货"来的原始凭证，绝不能被一次 DROP 误删
       ②多工具共享——Spark/ Presto 也要读这份数据，Hive 只是"读者之一"
       ③数据生命周期由采集流程管理，不由分析工具管理。

三条铁律（数仓规范）：
① ODS 层一律外部表（原始数据神圣不可侵犯——上游重放的唯一凭据）
② 中间层可用内部表（可重建的加工产物，删了自动回收空间）
③ 外部表数据目录按"权限最小化"收紧（Hive 用户可读不可删——day05 权限实操复用）

数据怎么进表（两种思路的对比）：
① LOAD DATA：把文件"搬"到表目录（HDFS 内是移动，本地是上传）
  ——原始、简单，但没有格式转换（读时模式等着解析）
② INSERT INTO/OVERWRITE：跑作业生成数据写进表目录（可 SELECT 别的表来加工）
  ——加工的正道，支持分区动态写入；OVERWRITE 是"重写分区"（幂等重跑的关键！）
  ——day04 治小文件、day23 治倾斜都要靠 INSERT OVERWRITE 重写
③ LOCATION 挂载：数据根本不动，只是"指给表看"（外部表专用姿势）
  （类比：LOAD=进货入库，INSERT=现场加工生产，LOCATION=给已有库存登记上账）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Managed Table | 内部表（Hive 管账也管货，删表删库跑路） |
| External Table | 外部表（Hive 只管账，货是别人的） |
| LOCATION | 数据实际所在的 HDFS 目录 |
| LOAD DATA | 搬文件进表目录 |
| INSERT OVERWRITE | 重写表/分区（先清后写，幂等重跑的关键） |
| Warehouse Dir | 仓库目录（/user/hive/warehouse/，内部表的家） |

## 3. 动手实操：删表实验见真章

```powershell
# ===== 实验一：内部表删表，数据陪葬 =====
docker exec -it hive-server beeline -u "jdbc:hive2://localhost:10000" -n root
# beeline 里：
CREATE TABLE shop.inner_t (id INT, name STRING)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY ',';
INSERT INTO shop.inner_t VALUES (1,'a'),(2,'b');
DROP TABLE shop.inner_t;
# 另一终端验证：
docker exec hadoop-single hdfs dfs -ls /user/hive/warehouse/shop.db/
# inner_t 目录消失了——数据真没了（这就是"误删内部表=数据事故"的原因）
# exit 退出 beeline

# ===== 实验二：外部表删表，数据原地不动 =====
# 先造一份独立数据（模拟采集系统落盘的日志目录）
docker exec hadoop-single bash -c "hdfs dfs -mkdir -p /rawdata/logs; echo '1,x;2,y' | tr ';' '\n' | sed 's/,/ /g' > /tmp/l.txt"
docker exec hadoop-single bash -c "echo '3,z' >> /tmp/l.txt; hdfs dfs -put /tmp/l.txt /rawdata/logs/"
# beeline 里建外部表挂载：
CREATE EXTERNAL TABLE shop.ext_logs (id INT, name STRING)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY ' '
  LOCATION '/rawdata/logs';
SELECT * FROM shop.ext_logs;         -- 能查到 3 行
DROP TABLE shop.ext_logs;            -- 删表！
# 验证：
docker exec hadoop-single hdfs dfs -ls /rawdata/logs/
# 文件还在！——再建一次同样的外部表，SELECT 立刻又能查到（账本重建即恢复）
# 这就是"ODS 用外部表"的底气：误删账本 1 分钟自愈

# ===== 实验三：INSERT OVERWRITE 的幂等性（day25 数仓项目会天天用） =====
CREATE TABLE shop.t2 (id INT) ROW FORMAT DELIMITED FIELDS TERMINATED BY ',';
INSERT INTO shop.t2 VALUES (1);
INSERT INTO shop.t2 VALUES (2);
SELECT COUNT(*) FROM shop.t2;                          -- 2（INSERT 是追加）
INSERT OVERWRITE TABLE shop.t2 SELECT 99;              -- 全表重写
SELECT * FROM shop.t2;                                 -- 只有 99（先清后写=可重跑）
```

## 4. 面试连接

**Q：内部表和外部表的区别？ODS 层为什么必须用外部表？（数仓面试必考）**
> 区别一句话：删表时数据跟不跟着走。内部表由 Hive 全权管理，DROP 时元数据和 HDFS 数据一起删；外部表只是"登记上账"，LOCATION 指向已有目录，DROP 只删账本数据原地不动。ODS 层强制外部表三个理由：第一，ODS 是原始数据，是上游业务库同步或日志采集的唯一落点，一旦被误删无法从数仓内部重建（中间层可以由上游重算，ODS 没有上游），所以数据的"生死权"必须握在采集流程手里而不是分析工具手里；第二，原始数据通常多工具共享——Spark 跑加工、Presto 跑即席查询、Hive 做管理，谁都不该有"删除所有权"；第三，误删恢复成本：我做实验对比过，内部表误删就是数据事故，外部表误删只要重建同样的 DDL 立刻恢复，账本 1 分钟自愈。配套规范是中间层（DWD/DWS）用内部表——都是可重算的加工产物，删表自动清理空间，还省了手动管理的成本。

**Q：INSERT INTO 和 INSERT OVERWRITE 的区别？数仓里怎么选？**
> INTO 是追加，OVERWRITE 是"先清空目标（表或分区）再写入"。数仓里绝大多数场景选 OVERWRITE，核心是幂等性：天级任务失败重跑时，OVERWRITE 保证"跑一次和跑 N 次结果一致"——重跑直接覆盖昨天的分区，不会出现"重跑三次数据翻了三倍"的事故；INTO 是追加语义，重跑就重复，必须先手动 DELETE（Hive 删除又很笨重）。所以建表规范的写法是：加工类任务一律 INSERT OVERWRITE TABLE target PARTITION (dt='20260924')，按分区重写，粒度可控（只重写失败的这一天，不影响其他天）。INTO 的合理场景只剩"真正的流式追加"或"确定不会重跑的小修复"。这个幂等设计和 M8 写接口的"条件更新+幂等键"是同一个思想：批处理世界里，幂等是敢做自动重试的前提。

## 5. 今日验收清单

- [ ] 内外表删表行为差异能讲 + 两个删除实验都做过（亲眼见过）
- [ ] ODS 强制外部表的三个理由能脱稿
- [ ] INSERT OVERWRITE 幂等实验完成（重写前后 COUNT 对比）
- [ ] 三种数据加载方式（LOAD/INSERT/LOCATION）各一句话定位
- [ ] `git add . && git commit -m "day10-11: managed external"`

---
[← Day 10](day10-Hive入门与架构.md) | [本月目录](README.md) | [Day 12 · 分区与分桶 →](day12-分区与分桶.md)
