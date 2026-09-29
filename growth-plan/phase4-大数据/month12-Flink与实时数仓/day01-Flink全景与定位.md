# Day 01 · Flink 全景与定位：真"流水线"和"一桶一桶倒"

> **今日目标**：回答 M11 交接卡思考题①——Flink 凭什么做"真"流处理？搞清真流（来一条处理一条）和微批（攒一桶倒一桶）的本质差异；用现有环境做一次"流 vs 批"的对照体感实验。
> **时长**：定位与对比 1.5h / 容器部署+对照实验 2.5h
> **今日产出**：Flink 容器集群跑通 + 流/批延迟对照记录 + 《三大流引擎定位卡》

## 1. 知识地图（先讲人话：M11 思考题①的答案）

```
上个月你留下的悬念（M11 day30 交接卡）：
  "M11 落地作业按天落 HDFS，T+1 才有数——实时数仓要的是秒级"
  —— 先复盘离线链路的时间账：
     埋点 → Kafka（实时）→ 落地 HDFS（分钟级）→ T+1 凌晨 Spark 批算
     —— 链路前半段是实时的，后半段"攒一天算一次"，所以大屏永远是昨天的数

三种"攒数据"的姿势（流引擎三代进化史）：
  ① 一天攒一次（Spark 离线/MR）：睡一觉才有结果——延迟小时级
  ② 攒一桶倒一桶（Spark Structured Streaming 微批）：
     每隔 5 秒/1 分钟触发一次小批计算——像自来水分桶接，延迟秒~分钟级
     —— 微批的死穴：窗口要等"这一桶"攒完才算，桶越大延迟越高；
        桶调小又全是调度开销（每桶都要起一轮任务）
  ③ 常开的水管（Flink 真流）：数据进来一条就处理一条，
     作业提交后常驻运行、7×24 不停——延迟毫秒级
  —— M11 思考题①正式作答：Flink 凭的不是"算得快"，而是
     【常驻 + 纯流式数据结构】：数据不落盘攒批、在算子间直接流过，
     所以能做"低延迟"，还能做微批做不了的"事件时间+状态+Watermark"

代价要会说（没有银弹）：
  · 常驻作业要养：进程活着/资源常占/升级要 Savepoint（day24）
  · 状态要管：流计算天生带"小本本"（累计值），忘了清就 OOM（day06）
  · 乱序要治：水管里的水不排队，靠 Watermark 定规矩（day04）
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| Streaming | 流处理 | 常开的水管：数据一条条流过算子，边流边算 |
| Micro-batch | 微批 | 一桶一桶接水攒批算（Structured Streaming 的路线） |
| Latency | 延迟 | 一条数据从进来到出结果多久（流：毫秒；微批：秒起） |
| Throughput | 吞吐 | 每秒能处理多少条（批的吞吐通常更高，但延迟大） |
| Unbounded Data | 无界数据 | 永远流不完的数据（Kafka 主题就是典型） |
| Dataflow | 数据流图 | 作业的"管道施工图"：source→算子→sink |
| Event-driven | 事件驱动 | 不是"定时算"，是"来了才算"（反过来的调度逻辑） |

## 3. 动手实操：部署 + 流批延迟对照体感

```powershell
# ===== 步骤 1：拉起 Flink mini 集群（README 同款命令）=====
docker run -d --name flink-jm --network bigdata -p 8081:8081 `
  -e FLINK_PROPERTIES="jobmanager.rpc.address: flink-jm" flink:1.18 jobmanager
docker run -d --name flink-tm --network bigdata `
  -e FLINK_PROPERTIES="jobmanager.rpc.address: flink-jm`ntaskmanager.numberOfTaskSlots: 4" flink:1.18 taskmanager
# 验证：http://localhost:8081 —— Available Task Slots: 4 就是通了（截图①）

# ===== 步骤 2：跑一个内置示例（不写代码先见效果）=====
docker exec flink-jm ./bin/flink run /opt/flink/examples/streaming/WordCount.jar
# UI 上看到作业 RUNNING——注意：跑完它还显示"已完成"，
# 但流作业一般【永不结束】（下面 SQL 实验你会看到 RUNNING 不退的作业）

# ===== 步骤 3：体感"常驻流作业"（SQL 客户端 + 内置造数器）=====
docker exec -it flink-jm ./bin/sql-client.sh
```

```sql
-- 内置造数器：每秒自动生成一条假订单（不需要 Kafka 任何依赖）
CREATE TABLE orders (
  id INT,
  amt DOUBLE,
  proc AS PROCTIME()               -- 处理时间（day04 细讲）
) WITH (
  'connector' = 'datagen',
  'rows-per-second' = '1',
  'fields.id.kind' = 'random',
  'fields.id.min' = '1', 'fields.id.max' = '100',
  'fields.amt.min' = '1', 'fields.amt.max' = '500'
);

-- 提交这条查询后【注意看】——它不会结束，一直 RUNNING：
SELECT * FROM orders;
-- 对照体感（记录②）：这就是"常开的水管"——每秒吐一行，永不停止
-- 关掉查询：菜单里 cancel 掉即可
```

```powershell
# ===== 步骤 4：对照 M11 的离线批（延迟体感数字）=====
# 同样 10 条订单数据：
#   Spark 批路径：Kafka→落地 HDFS→凌晨跑批→出数   —— 最好情况 1 小时，常态 T+1
#   Flink 流路径：Kafka→Flink 常驻作业→秒级出数   —— 实测 ~100ms 级
# 把这组对比写进《三大流引擎定位卡》（day28 要用）
```

## 4. 面试连接

**Q：Flink 和 Spark Structured Streaming 的本质区别？（必考开场题）**
> 本质是"数据模型"的差别：Flink 是纯流模型——把批当成"流的有限子集"，作业常驻、数据一条条流过算子，延迟毫秒级；Structured Streaming 是微批模型——把流当成"一连串小批"，每隔 N 秒攒一桶触发一次计算，延迟受批间隔限制，秒到分钟级。这个差别带来三个实际影响：延迟上，真流天然低延迟，微批调小间隔会陷入"调度开销"的泥潭（每桶都要一轮任务调度）；功能上，事件时间/水位线/复杂状态这些流原生能力，Flink 是一等公民，微批是后来补的；运维上，微批复用 Spark 生态学起来快，Flink 要养常驻作业、管状态、做 Savepoint。我自己的选择标准：分钟级容忍度、团队熟 Spark → Structured Streaming 完全够；秒级大屏/实时风控/实时数仓主链路 → Flink。我们公司的实时链路就是 Flink SQL 主写（回答时结合自己项目）。

**Q：什么是"无界数据"？为什么它改变了计算模型？（考理解深度）**
> 无界数据就是永远流不完的数据——Kafka 主题、用户行为埋点、支付回调，只要业务活着它就不停。有界数据（离线批）可以"等数据全到齐再算"，所以能排序、能全局统计、能一次出结果；无界数据永远等不到"到齐"，计算模型必须回答三个新问题：什么时候出结果（窗口/Watermark，day04/05）、中间结果存哪（状态，day06）、算到一半挂了怎么办（Checkpoint，day08）。这三个问题就是本月的主线——Flink 整个设计都在回答它们，而批引擎几乎不用想这些。这也是为什么"流批一体"的提法里，流是更本质的抽象：批只是流的一个特例（数据到齐了的那段流）。

## 5. 今日验收清单

- [ ] Flink mini 集群跑通（UI 截图：4 Slot）
- [ ] 内置示例+datagen 流作业各跑一次（体感"永不结束"）
- [ ] M11 思考题①能正式作答（常驻+纯流式数据结构）
- [ ] 流/批延迟对照数字记录（T+1 vs 秒级）
- [ ] 《三大流引擎定位卡》开写（离线批/微批/真流三列）
- [ ] `git add . && git commit -m "day12-01: flink-overview"`

---
[← 本月目录](README.md) | [Day 02 · 运行时架构 →](day02-运行时架构.md)
