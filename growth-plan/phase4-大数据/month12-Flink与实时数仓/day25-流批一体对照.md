# Day 25 · 流批一体对照：同一套 SQL，白天黑夜都能跑

> **今日目标**：理解"流批一体"到底一体在哪——同一套 SQL 既能流着跑也能批着跑；和 Spark 做一次正面对照；想清楚它和"实离对拍"（day19）的关系。
> **时长**：概念 1h / 实操 2h / 输出 0.5h
> **今日产出**：同一段聚合 SQL 的流/批双跑记录 + 《流批差异对照表》

## 1. 知识地图（先讲人话）

```
先复习旧世界（M10/M11 的分工）：
  白天实时看：Flink 算分钟级 GMV（数据一条条来，窗口滚动算）
  隔天精确算：Spark 跑 T+1 全量重算（数据攒成一坨，一口气算完）
  —— 两套引擎、两套代码 → 口径要对齐（day19 对拍就是给这个擦屁股）

流批一体的野心：
  同一套 Flink SQL，只换一个开关：
    SET 'execution.runtime-mode' = 'streaming';  -- 白天：数据源源不断，窗口滚动出数
    SET 'execution.runtime-mode' = 'batch';      -- 隔夜：数据有界，一次算完出最终结果
  —— 代码只有一份 → 口径天然一致 → 对拍的需求从根上消失一半
  —— 这就是 Lambda 架构（day15）到 Kappa 架构的演进逻辑

批模式下的三个变化（数据有界带来的"特权"）：
  ① 不需要水位线：数据全到齐了才开算，没有"等不等迟到数据"的问题
  ② 窗口一次性结算：每个窗口算一次出终值（流模式是滚动出中间值）
  ③ 支持全局排序/JOIN 优化：看得见全量数据，可以像传统数据库那样做计划

诚实的前提（演示环境的边界）：
  流批一体的代价：批模式当前优化器仍弱于 Spark 沉淀十年的 Tungsten
  —— 生产常见姿势：实时链路 Flink、超大历史回刷仍交 Spark，"一体"是渐进过程
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| Runtime Mode | 运行模式 | streaming=水龙头长开；batch=桶接满再算 |
| Bounded Stream | 有界流 | 数据有尽头（一个文件/一张表）——批就是"有头的流" |
| Unbounded Stream | 无界流 | 数据没尽头（Kafka 常驻）——真流处理 |
| Final Result | 终值输出 | 批模式每个窗口只出一次最终值（流模式会持续更新） |
| Lambda→Kappa | 架构演进 | 双链路对拍 → 一套流代码通吃（批=流的特例） |
| Unified API | 统一 API | 一套 SQL 两种跑法，口径只有一份 |

## 3. 动手实操

### 3.1 准备：一张"有头"的源表

```sql
-- datagen 可以造有界数据：加 number-of-rows 参数（造够 1000 条就停）
CREATE TABLE orders_bounded (
  uid INT, amt DOUBLE, action STRING,
  et AS CURRENT_TIMESTAMP,
  WATERMARK FOR et AS et - INTERVAL '3' SECOND
) WITH (
  'connector' = 'datagen',
  'rows-per-second' = '100',
  'number-of-rows' = '1000',          -- 关键：造 1000 条自动结束（有界！）
  'fields.uid.min' = '1', 'fields.uid.max' = '50',
  'fields.amt.min' = '10', 'fields.amt.max' = '500',
  'fields.action.kind' = 'random',
  'fields.action.chars' = 'payav'
);
```

### 3.2 实验①：同一聚合 SQL 的两种跑法

```sql
-- 流模式（白天）：滚动输出，每个窗口出值后还可能有更新
SET 'execution.runtime-mode' = 'streaming';
SET 'parallelism.default' = '1';
SELECT
  WINDOW_START, WINDOW_END,
  COUNT(*) AS order_cnt, SUM(amt) AS gmv
FROM TABLE(
  TUMBLE(TABLE orders_bounded, DESCRIPTOR(et), INTERVAL '10' SECOND))
WHERE action = 'pay'
GROUP BY WINDOW_START, WINDOW_END;
-- 观察：结果边跑边出，10 秒一个窗口

-- 批模式（隔夜）：同样的 SQL，只改一个 SET
SET 'execution.runtime-mode' = 'batch';
-- 同样跑：数据攒够 1000 条后一次性出全量窗口结果，作业自动 FINISHED
-- 观察：作业状态从 RUNNING → FINISHED（流作业永远 RUNNING——这就是 day01 的"常驻"对照）
```

### 3.3 实验②：流批行为差异三连拍

```
拍① FINISHED：批作业跑完会结束；流作业永远 RUNNING（除非手动停）
拍② 无迟到数据问题：批模式下水位线形同虚设（全到齐了才算），
    把乱序度参数改成 0 也不影响批结果——但同样的表流跑就可能丢迟到数据
拍③ 对照 Spark：同一段"按窗口分组求和"，Spark SQL 是
    SELECT window.start, sum(amt) FROM t GROUP BY window(...)（M11 写法）
    —— 语法不同、引擎不同、但口径逻辑相同（这就是 M11 day26 对拍的痛苦根源）
```

```
《流批差异对照表》（贴墙）：
  维度        流模式              批模式
  数据边界    无界（Kafka 常驻）  有界（文件/表/分段）
  水位线      正确性命门          基本不需要
  窗口输出    滚动出中间值        一次出终值
  作业状态    RUNNING 到永远      跑完 FINISHED
  排序/全局   受限                支持全量优化
  生产分工    实时大屏/风控       历史回刷/对账/报表
```

## 4. 面试连接

**Q：怎么理解流批一体？和 Lambda/Kappa 架构什么关系？（架构判断题）**
> 流批一体的本质是"批是流的特例"——有界数据就是有头的流，所以同一套 API/SQL 可以两种模式跑。架构上对应从 Lambda 到 Kappa 的演进：Lambda 是实时链路+离线链路两套代码，好处是各自稳定，代价是口径要靠对拍维持（day19 的 SOP 就是给 Lambda 擦屁股）；Kappa 是只保留流链路，要重算历史就把流"从头重放"（Kafka 保留期拉长+从最早 offset 消费）。我的判断：一体是大方向，但渐进落地——我们生产上实时链路 Flink，超大历史回刷还是 Spark 跑得快，属于"两引擎并存、SQL 口径统一"的务实版。被追问就承认 trade-off：批模式下 Flink 优化器还追不上 Spark 十年沉淀，全量重算的性能差距是真实存在的。

**Q：Flink 批模式和 Spark 比怎么样？（开放对比题，别踩一捧一）**
> 分场景。小中规模批任务和流批一体的场景，Flink batch 一套 SQL 两种跑法，口径只有一份，运维成本最低；超大规模纯批（几百 TB 的历史回刷、复杂多层 JOIN），Spark 依然是更成熟的选择——Tungsten 执行引擎、AQE 自适应（M11 day22）这些优化沉淀更久。我的实操感受：同样聚合 SQL，批模式行为差异最大的三点是——批作业会 FINISHED、不需要水位线、窗口一次出终值。选型纪律：链路里已有 Flink 的增量场景就近流批一体；纯离线大仓库没必要为了"一体"强行迁 Flink。

## 5. 今日验收清单

- [ ] number-of-rows 有界源跑通流/批双模式
- [ ] 三个行为差异（FINISHED/无水位线/终值输出）亲眼见过并记录
- [ ] Lambda→Kappa 的演进逻辑能画图讲
- [ ] "为什么流批一体能消灭一半对拍工作"能讲
- [ ] 《流批差异对照表》成文
- [ ] `git add . && git commit -m "day12-25: stream-batch-unified"`

---
[← Day 24](day24-生产运维与升级.md) | [本月目录](README.md) | [Day 26 · 综合项目双链路 →](day26-综合项目双链路.md)
