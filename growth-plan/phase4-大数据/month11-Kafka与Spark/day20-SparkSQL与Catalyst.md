# Day 20 · Spark SQL 与 Catalyst：SQL 进厂先过优化流水线

> **今日目标**：搞懂一条 SQL 在 Spark 里的完整旅程——解析成逻辑计划、过 Catalyst 优化流水线（谓词下推/列裁剪/常量折叠）、生成物理计划再执行；用 EXPLAIN 亲眼验证三大优化。同时把 M10 的 Hive 表直接拿给 Spark 用（元数据共享），为项目周铺路。
> **时长**：执行流程 1.5h / 三大优化验证 2h / 接 Hive 元数据 1.5h
> **今日产出**：EXPLAIN 三优化证据截图 + Spark 读 Hive 表跑通记录

## 1. 知识地图

```
DataFrame 是什么？——"带表头的 RDD"
  RDD 知道每一行是字符串，DataFrame 知道每一列叫什么、什么类型（Schema）
  —— 引擎因此能"看懂"你的意图，优化才有下手处（这就是 SQL 快于裸 RDD 的原因）
  Dataset：编译期类型安全版（Java 工程师友好），内部还是 DataFrame 引擎

一条 SQL 的旅程（五站流水线）：
  SQL 文本 → 解析 Parser（查语法）→ 未解析逻辑计划（Unresolved：列还没对号）
  → 分析 Analyzer（对号入座：对 Schema 绑定列）→ 优化逻辑计划（Catalyst RBO 改写）
  → 物理计划（Physical：选具体算子）→ 执行（RDD 上跑）
  Catalyst 优化器 = 规则引擎：拿着一堆"改写规则"反复重写逻辑计划，直到无法更优

三大经典优化（每个都要有 EXPLAIN 证据）：
  ① 谓词下推 Predicate Pushdown：过滤条件往数据源推——
    "WHERE dt='2026-09-25' AND amount>100" 推到读文件阶段，
    ORC/Parquet 按行组统计直接跳过整段（M10 day19 谓词下推在此接上！）
    ——Hive 的下推发生在"存储格式读段"层，Spark 的下推发生在"计划改写"层，
      两层协作，殊途同归：让不必要的数据根本不进内存
  ② 列裁剪 Column Pruning：SELECT amount 只要一列 → 只读那一列的文件段
    （列存的"按需读列"在引擎层的配合——day19 M10 列存三好处的第二好处）
  ③ 常量折叠 Constant Folding：1+2*3 → 7（编译期算掉，别在 1 亿行上重复算）
  （进阶：CBO 基于成本的优化——靠统计信息选 Join 顺序；AQE 运行时再优化——day22）

接 Hive 元数据（跨月实战的关键一步）：
  Spark 配置 hive metastore 地址（spark.sql.hive.metastore 尿性：enableHiveSupport）
  → 直接 SELECT M10 建的 shop.dwd_order_detail_di ——表结构/分区/权限全继承
  —— "SQL 语法几乎无缝迁移"的原因找到了：两家共享同一本账本（Metastore）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| DataFrame | 带表头的 RDD（有 Schema，引擎看得懂） |
| Catalyst | 优化器（规则改写引擎，SQL 的"改卷老师"） |
| Logical Plan | 逻辑计划（"要做什么"的描述，与物理无关） |
| Physical Plan | 物理计划（"具体怎么干"的执行方案） |
| Predicate Pushdown | 谓词下推（过滤提前，数据源少吐数据） |
| Column Pruning | 列裁剪（要哪列读哪列） |
| Constant Folding | 常量折叠（编译期算死的常量） |

## 3. 动手实操：EXPLAIN 验证三优化

```scala
// ===== 步骤 1：接上 Hive 元数据（spark-shell 开 Hive 支持）=====
// bitnami 镜像已带 hive 支持；指定 metastore（M10 的 hive-mysql + hive-server 环境）：
// docker exec -it spark spark-shell --conf spark.hadoop.hive.metastore.uris=thrift://hive-server:9083
spark.sql("SHOW DATABASES").show()          // 能看到 M10 的 shop —— 账本打通
spark.sql("SELECT * FROM shop.dwd_order_detail_di WHERE dt='2026-09-25'").show(5)
// —— M10 的 DWD 表在 Spark 里直接可查（跨月大合流时刻，截图留痕）

// ===== 实验①：谓词下推证据 =====
val df = spark.table("shop.dwd_order_detail_di")
val filtered = df.filter($"amount" > 30).select("uid", "amount")
filtered.explain(true)
// 看 Optimized Logical Plan：Filter 出现在 Relation 节点内（PushedFilters: [GreaterThan(amount,30)]）
// —— 过滤被推进了数据源扫描（读文件时就丢掉不满足的行组）
// ===== 实验②：列裁剪证据 =====
df.select("uid").filter($"amount" > 30).explain(true)
// 对比上面：Relation 处只声明了 uid,amount 两列（ReadSchema 只有所需列）
// 全列查询对照：df.select("*")... → ReadSchema 列出全部——一对比就懂
// ===== 实验③：常量折叠证据 =====
spark.sql("SELECT uid FROM shop.dwd_order_detail_di WHERE dt='2026-09-25' AND 1+2*3=7").explain(true)
// Optimized Plan 里 1+2*3=7 已变成 true（或被合并进 Filter 常量条件）——编译期算掉了
// ===== 实验④：物理计划选型预览（给 day22/23 埋点）=====
val j = df.join(spark.table("shop.dws_cat_order_1d"), "category")
j.explain()   // 看 Physical Plan：SortMergeJoin（默认）还是 BroadcastHashJoin
// 把小表阈值调大：spark.conf.set("spark.sql.autoBroadcastJoinThreshold", 50*1024*1024)
// 再 explain —— 变成 BroadcastHashJoin（day23 Join 策略的预告现场）
```

## 4. 面试连接

**Q：讲讲一条 SQL 在 Spark 里的执行流程？Catalyst 都优化了什么？（必考题）**
> 五站流水线：SQL 文本先经 Parser 解析成语法树，再经 Analyzer 对照 Schema 把"列名"绑定到具体表列（未解析逻辑计划→解析后逻辑计划），然后进入 Catalyst 优化器——它是一套规则引擎，用一批"改写规则"反复重写逻辑计划直到无法更优，最后把优化后的逻辑计划翻译成物理计划（选定具体算子实现，如 SortMergeJoin 还是 BroadcastHashJoin）交给 RDD 引擎执行。三大经典优化各有白话解释：谓词下推是把过滤条件往数据源推——配合 ORC/Parquet 的行组统计，不满足条件的整段数据直接不读（我验证过 EXPLAIN 里 PushedFilters 字段）；列裁剪是只读 SELECT 到的列——列存格式下这一招直接把扫描量砍掉大半；常量折叠是把编译期能算死的表达式提前算掉，不让它在 1 亿行上重复计算。加分项：为什么 Spark SQL 比裸 RDD 快——因为 DataFrame 带 Schema，引擎"看得懂"你的业务意图，优化才有下手处；裸 RDD 是黑盒函数链，引擎只能机械执行。再往深一步是 CBO（基于统计信息做成本决策）和 AQE（运行时看真实数据量再调优，day22 详讲）——静态优化看不到数据分布，动态优化补上这一环。

**Q：谓词下推在 Hive 和 Spark 里是一回事吗？（跨月融会贯通题）**
> 本质是同一个思想——"过滤越早，浪费越少"——但发生层次不同。Hive 场景里我讲的谓词下推发生在存储格式层：ORC 的每个 Stripe 有 min/max 统计，查询"amount>30"时按段统计直接跳过不可能包含结果的段——这是"数据源自己会筛"（M10 day19 的跳段能力）。Spark 场景里谓词下推首先发生在计划改写层：Catalyst 把 Filter 算子从计划树上层挪到 Relation 扫描节点下面，并且把过滤条件编码进 Parquet/ORC 的读取参数（EXPLAIN 里 PushedFilters 字段可见）——这是"引擎指挥数据源筛"。两层是协作关系而不是二选一：Spark 把谓词推给 ORC reader，ORC reader 再用段统计跳过物理数据——计划层决定"推什么"，存储层决定"怎么跳"。我实验的对照证据：同一条过滤 SQL，EXPLAIN 的 PushedFilters 有值且扫描的行组数下降（Spark UI 的 input records 变小）——两个层次的效果同时可见。这道题的价值在于展示"知识网络"：M10 学存储格式、M11 学执行引擎，谓词下推恰好是两者的接缝——面试官问这种跨月问题，答得出层次协作关系的候选人屈指可数。

## 5. 今日验收清单

- [ ] SQL 五站旅程图（Parser→Analyzer→Optimizer→Physical→执行）能白板画
- [ ] 三大优化各带 EXPLAIN 证据（截图三张）
- [ ] "DataFrame 为什么比 RDD 可优化"能讲（Schema 让引擎看懂意图）
- [ ] Spark 直连 M10 Hive 元数据跑通（SHOW DATABASES 见 shop）
- [ ] Hive 下推 vs Spark 下推的层次协作能展开
- [ ] `git add . && git commit -m "day11-20: catalyst"`

---
[← Day 19](day19-内存与资源.md) | [本月目录](README.md) | [Day 21 · 第三周复盘 →](day21-第三周复盘.md)
