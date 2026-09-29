# Day 12 · Spark 全景：MR 的三宗罪与 Spark 的三张牌

> **今日目标**：正式兑现 M10 思考题①——"同样读 HDFS 上的同一批文件，Spark 为什么常常比 MR 快 3~10 倍？快在哪、又贵在哪"。先复习 MR 慢的三宗罪，再拆 Spark 的三张牌（内存迭代/DAG 优化/线程模型），最后把容器里的 Spark 跑起来。
> **时长**：三宗罪复习 1h / 三张牌精讲 2h / Spark 容器与首跑 2h
> **今日产出**：M10 思考题①完整作答 + Spark 容器跑通 WordCount + 三张牌白板图

## 1. 知识地图

```
先复习 MR 的三宗罪（M10 day06 的伏笔今天全额兑现）：
  罪① 步步落盘：Map 结果写磁盘 → Shuffle 落盘 → Reduce 结果写 HDFS →
       下一个 Job 再从头读——复杂任务被切成多个 Job，中间结果全部过磁盘
       （类比：流水线每道工序都把半成品入库，下一道工序再领出来）
  罪② 启动笨重：每个 Task 是一个独立 JVM 进程——JVM 启动就要几百毫秒，
       小任务全耗在开机仪式上（百万个 task = 百万次 JVM 启动）
  罪③ 没有全局图：MR 只提供 map/reduce 两个算子，多步逻辑手工拆 Job，
       引擎不知道你要干嘛，无从优化（材料自己搬，没人管路线）

Spark 的三张牌（逐条对症）：
  牌① 内存迭代（省罪①）：算子链上前一步结果放内存直接喂下一步
       ——只有 Shuffle（宽依赖）才落盘，其他工序半成品不进仓库
       （类比：一条流水线上工序之间直接手递手）
  牌② DAG 全局优化（省罪③）：先把你写的代码翻译成完整的执行图（DAG），
       引擎看着全图排兵布阵——哪些工序能拼在一起流水线化、哪些必须切 Stage
       （类比：先看全线路图再派车，而不是走一站问一站）
  牌③ 线程模型（省罪②）：每个 Executor 是一个 JVM 进程，里面的 Task 是线程
       ——线程启动毫秒级 vs 进程启动百毫秒级；同一个 Executor 的常驻内存也省

贵在哪？（面试必答的另一面）
  内存吃紧：中间结果堆内存 → OOM 风险/资源紧张时反而不如 MR 稳
  ——所以 Spark 调优的主战场就是内存（day19 统一内存模型）
  结论句：Spark 用"内存"这个贵资源，换掉了"磁盘 IO+进程启动"这两个更贵开销
  ——资源有价，架构即交换（M10 day21 主线的又一次复现）

定位（先立框架）：
  Spark = 批处理主力 + SQL（Spark SQL）+ 流（微批 Structured Streaming）+ ML 库
  部署复用 M10 家底：Spark on YARN，HDFS 数据不用搬
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| DAG | 有向无环图（整个任务的完整施工图，引擎照图优化） |
| RDD | 弹性分布式数据集（分布式"会记仇的账本"，day15 详讲） |
| Executor / Task | 干活的进程 / 进程里的线程（vs MR 的进程级 Task） |
| In-memory Iteration | 内存迭代（工序间手递手，不进仓库） |
| Spark on YARN | Spark 跑在 M10 的 YARN 上（资源复用） |

## 3. 动手实操：Spark 容器与第一个作业

```powershell
# ===== 步骤 1：起 Spark 容器（bitnami 镜像，交互式用 spark-shell）=====
docker run -d --name spark --network bigdata -p 4040:4040 `
  bitnami/spark:3.5 bash -c "sleep infinity"
# ===== 步骤 2：跑第一个 Scala 交互式作业（local 模式）=====
docker exec -it spark spark-shell --master "local[2]" 
# 进入 scala> 提示符后逐行输入：
# val text = spark.read.textFile("hdfs://hadoop:9000/data/ods/order_detail/dt=2026-09-25")
# text.show(3)                          —— 看到 M10 造的订单数据（跨月复用！）
# val wc = text.flatMap(_.split(",")).groupBy("value").count()
# wc.orderBy($"count".desc).show(5)     —— 最常见"词"Top5（逗号最多）
# :quit
# ===== 步骤 3：跑官方示例验证集群模式（spark-submit，可跳过）=====
docker exec spark spark-submit --master "local[2]" `
  --class org.apache.spark.examples.SparkPi `
  /opt/bitnami/spark/examples/jars/spark-examples_2.12-3.5.0.jar 10
# 输出最后看到 Pi is roughly 3.14xxxx —— 环境就绪
# ===== 步骤 4：Web UI 观察（下个月份的主战场，先认门）=====
# 浏览器开 http://localhost:4040（作业运行期间有效）：DAG 图/Stage 列表/Task 耗时
# —— 上面第 2 步的作业跑时打开，找到"一行 flatMap+groupBy+count"的 DAG 形状
```

## 4. 面试连接

**Q：Spark 为什么比 MapReduce 快？（M10 思考题①正式作答，必背级）**
> 先答"MR 慢在哪"再答"Spark 省在哪"，逐条对症。MR 三宗罪：一，步步落盘——中间结果全写磁盘，复杂任务切多个 Job 串行，每个 Job 都从 HDFS 重读；二，Task 是独立 JVM 进程，百万个 Task 就是百万次 JVM 启动，小任务全耗在启动上；三，只有 map/reduce 两个算子，引擎看不到完整逻辑，无从优化。Spark 三张牌对症：一，内存迭代——算子链上前一步结果留在内存直接喂下一步，只有 Shuffle 边界才落盘，磁盘 IO 从"每步"降到"只在 Shuffle"；二，DAG 全局优化——代码先翻译成完整执行图，引擎看着全图把能合并的窄依赖拼成流水线、在 Shuffle 处切 Stage，还能继续做谓词下推这类优化；三，线程模型——Executor 进程常驻，Task 是线程，启动从百毫秒降到毫秒级。速度量级：官方口径迭代式任务 10~100 倍，普通批任务一般 3~10 倍。但必须答"贵在哪"：中间结果堆内存带来 OOM 风险，资源紧张时稳定性不如"笨但稳"的 MR——所以 Spark 生产调优的主战场就是内存管理。总结一句：Spark 拿内存这个贵资源，换掉了磁盘 IO 和进程启动这两个更贵的开销——这和我前面学的所有性能优化是同一条哲学：资源有价，架构即交换。

**Q：Spark 现在的地位和生态？我们为什么用它而不是只写 SQL？（定位题）**
> Spark 的现状是"大数据批处理的事实标准"，四大件：Core（RDD 引擎）、Spark SQL（结构化处理，现在的主流入口，写 DataFrame SQL 的人比写 RDD 的多得多）、Structured Streaming（微批流处理，M12 会和 Flink 对比）、MLlib（机器学习）。为什么后端工程师还要懂底层（RDD/Stage/Shuffle）？因为生产事故全发生在底层：SQL 慢了要能看懂 Spark UI 的 Stage 耗时和 Shuffle 量，OOM 了要知道是哪块内存爆的（day19 统一内存模型），倾斜了要有治理手段（day24）——只会写 SQL 的人在这些问题面前是瞎的。我自己的学习路线就是"SQL 上手、底层兜底"：日常用 Spark SQL 复用 M10 的 Hive 表（元数据共享，SQL 语法几乎无缝迁移），性能出问题再下潜到 Stage/Shuffle/内存层面解决。这个月的安排也是这个顺序：先 SQL 干活（day20-23），再底层排障（day15-19 打的地基）。

## 5. 今日验收清单

- [ ] "三宗罪 vs 三张牌"对照白板图（含贵在哪）
- [ ] M10 思考题①作答记录落档（含 3~10 倍的量级说明）
- [ ] Spark 容器跑通：读 M10 的 HDFS 数据完成聚合（跨月复用留痕）
- [ ] Spark UI 打开看过 DAG 形状
- [ ] `git add . && git commit -m "day11-12: spark overview"`

---
[← Day 11](day11-KafkaStreams初识.md) | [本月目录](README.md) | [Day 13 · 部署与第一个作业 →](day13-Spark部署与提交.md)
