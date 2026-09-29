# Day 17 · 持久化与广播变量：先做好菜放着，名册人手一份

> **今日目标**：学 Spark 的两件"省钱神器"——持久化（cache：把共享中间结果放内存，解决 day15 的重算坑；checkpoint：截断过长血缘）和广播变量（把只读数据发一份到每个工人，是 M10 Map Join 思想的 Spark 版）。顺手认识累加器及其重算陷阱。
> **时长**：cache/persist 1.5h / checkpoint 1h / 广播与累加器 2.5h
> **今日产出**：cache 前后 Job 数对比记录 + 广播 Join 实验数据 + 《cache/checkpoint/broadcast 选型卡》

## 1. 知识地图

```
神器① 持久化 cache/persist——"客人点了三次的菜，第一次做好就端在灶台上"
  问题回顾（day15 坑一）：一条转换链被两个 Action 触发 → 整链重算两遍
  解法：链的中间结果 .cache() ——第一个 Action 算完把分区留在内存，
       第二个 Action 直接读缓存（血缘仍在！缓存丢了照旧重算，安全网不撤）
  级别速查（按常用度）：
    MEMORY_ONLY        只放内存，放不下就重算（RDD.cache() 默认）
    MEMORY_AND_DISK    内存满了溢写磁盘（DataFrame.cache() 默认——更稳）
    DISK_ONLY / OFF_HEAP  全落盘 / 堆外（少用）
  纪律：cache 是有代价的（占内存）——只缓存"被多次使用"且"算得贵"的中间结果；
       用完 unpersist 释放；看 UI Storage 页确认缓存真的生效了（Cached 分区数）

神器② checkpoint——"账本记了 100 页，先把前 50 页复印存档"
  问题（day15 埋点）：血缘太长 → 单个分区丢失要重算整条链，代价失控
  解法：把当前 RDD 真正写到可靠存储（HDFS），之后血缘从这里重启
  cache vs checkpoint（高频对比）：
    cache：临时/内存/血缘保留/丢了重算/程序结束即清
    checkpoint：永久/HDFS/血缘截断/丢了从存档读/必须先设 checkpointDir
    —— cache 是"灶台上放一下"，checkpoint 是"存档室永久保存"

神器③ 广播变量 Broadcast——"名册不一人发一本，全车间贴一张"
  场景：Driver 上有个 100MB 的维表，1 万个 Task 都要用
  反面：默认闭包捕获 → 每个 Task 序列化一份发过去 → 100MB×10000=1TB 网络流量
  正解：sc.broadcast(table) → 每个【Executor】只发一份，Task 共享只读访问
  —— 这就是 Spark 版 Map Join 的底层（M10 day23 广播思想平移）：
     大表 join 小表 → 小表广播 → 免 Shuffle（day23 Join 策略的前置知识）

配角 累加器 Accumulator——"各车间的计件数汇总到总台"
  分布式只写变量：各 Task 里 val += 1，最后 driver 拿总和（LongAdder 思想同构）
  坑：Task 重算（推测执行/容错）会重复累加 → 计数类业务值不可靠；
     只用它做"监控指标"（处理了多少条），别做业务数据（账要靠事务性写入）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| cache / persist | 持久化（共享中间结果放内存/磁盘） |
| checkpoint | 检查点（血缘截断，结果存档到 HDFS） |
| Storage Level | 存储级别（内存/磁盘/堆外组合） |
| Broadcast Variable | 广播变量（每个 Executor 一份的只读数据） |
| Accumulator | 累加器（分布式计数，只作监控不作账本） |
| unpersist | 释放缓存（灶台清空） |

## 3. 动手实操：三件神器对比实验

```scala
// ===== 实验①：cache 省重算（Job 数对比）=====
val raw = spark.sparkContext.textFile("hdfs://hadoop:9000/data/ods/order_detail/dt=2026-09-25")
val cleaned = raw.flatMap(_.split(",")).filter(_.nonEmpty)
cleaned.count(); cleaned.take(3)          // 不缓存：UI 看到 2 个 job，各自全量重算
val cached = cleaned.cache()              // 加缓存
cached.count(); cached.take(3)            // UI：第 1 个 job 全算+写缓存；第 2 个 job
// 的 Stage 直接从 Storage 读（看 UI Jobs 页第二个 job 的 Stage 输入=MEMORY）
cached.unpersist()                        // 用完释放
// ===== 实验②：checkpoint 截断血缘 =====
spark.sparkContext.setCheckpointDir("hdfs://hadoop:9000/tmp/ckpt")
val long = raw.flatMap(_.split(",")).filter(_.nonEmpty).map((_,1)).reduceByKey(_+_)
long.checkpoint()                         // 标记检查点
long.count()                              // 触发时顺带把结果写 HDFS/tmp/ckpt
println(long.toDebugString)               // 观察血缘：checkpoint 之后链路重启
// 对比：去掉 checkpoint 的同链，toDebugString 更长（重算代价更大）
// ===== 实验③：广播 join vs 普通 join（为 day23 打底）=====
// 造小表（3 行类目字典）广播 + 大表（订单）join：
val dict = spark.sparkContext.parallelize(Seq(("book",1),("toy",1),("food",1)))
val bc = spark.sparkContext.broadcast(dict.collectAsMap())     // 广播成 Map
val orders = raw.map(l => l.split(",")).filter(_.length >= 4)
  .map(f => (f(3), f(1)))                                     // (category, uid)
val looked = orders.map { case (cat, uid) => (uid, bc.value.getOrElse(cat, -1)) }
looked.take(3)   // 广播路径：无 Shuffle（每个 Executor 查本地 Map）
// 对照：orders.join(dict) 会触发 Shuffle（UI 看到宽依赖）——记录两种 job 的 Stage 数
// ===== 实验④：累加器重算陷阱观察 =====
val acc = spark.sparkContext.longAccumulator("bad-lines")
val counted = raw.map { l => if (l.split(",").length < 4) acc.add(1); l }
counted.cache().count()   // 有缓存：acc 正常
// count()  // 再跑一次行动（若不缓存或缓存失效触发重算）→ acc 翻倍！
println(acc.value)        // 记录实验值：验证"重算会重复累加"
```

## 4. 面试连接

**Q：cache 和 checkpoint 有什么区别？分别什么时候用？（高频对比题）**
> 一句话定位：cache 是"灶台上放一下"，checkpoint 是"存档室永久保存"。五个维度展开：目的上，cache 解决"多 Action 重复计算"（性能优化），checkpoint 解决"血缘太长重算代价失控"（容错兜底）；位置上，cache 在 Executor 内存/本地磁盘，checkpoint 写到可靠存储（HDFS）；血缘上，cache 不截断血缘——缓存丢了照样沿血缘重算（这是设计使然，缓存永远可能有安全网兜底），checkpoint 截断血缘——存档之后重算从存档处重启；生命周期上，cache 程序结束或 unpersist 即清，checkpoint 文件永久保留需手动清；使用上，cache 一行代码即用，checkpoint 要先设 checkpointDir 且触发时机在下一个 Action。使用场景：迭代计算（机器学习多轮迭代、day15 的 take+count 场景）先 cache；超长血缘链（几十步转换）或高频重算的大作业，在关键中间点 checkpoint。工程纪律：cache 优先、checkpoint 兜底——cache 有代价（占内存）且失效会重算，所以先看 UI Storage 确认命中率；checkpoint 写 HDFS 本身有成本，不在关键点滥用。我在实验里验证过边界：缓存失效后 Action 会触发重算并让累加器翻倍——所以"监控计数靠累加器"要配合稳定缓存使用，业务账本永远不依赖这两者。

**Q：广播变量的原理？为什么能省网络？什么时候失效？（机制题）**
> 原理：默认情况下，Task 执行时用到的 driver 端变量通过"闭包捕获"序列化进每个 Task 的任务描述里——1 万个 Task 就是 1 万份拷贝，100MB 维表意味着 1TB 网络流量。广播变量把这条路改了：数据只从 driver 向每个 Executor 发一份（HTTP 分发，BitTorrent 式省流），Executor 内所有 Task 共享这个只读副本——流量从 O(Task数×大小) 降到 O(Executor数×大小)，通常省三个数量级。主要用途两类：一是大表 join 小表的广播 Join——把小表广播后 join 变成本地查找，彻底免 Shuffle（这就是 M10 Hive Map Join 的 Spark 对应物，day23 会系统讲策略选择）；二是分发查找字典/配置/黑名单。失效场景要会说：数据太大（超过 broadcast 阈值或 executor 内存）会广播失败退化回 Shuffle Join，甚至 OOM——广播的前提是"小到每个工人都装得下"；数据经常变化也不适合——广播是启动时快照，运行中不变，要更新得重新广播。这和我在 M10 day23 讲的边界完全一致：广播省的是 Shuffle，花的是每个节点的内存——评估"小表是否真的小"（压缩前大小+键基数）永远是第一步。

## 5. 今日验收清单

- [ ] cache/checkpoint 五维对比（目的/位置/血缘/生命周期/用法）能讲
- [ ] 实验①②完成：Job 数对比+血缘截断观察（UI/日志留痕）
- [ ] 广播原理（Task 闭包 vs Executor 共享）+两个失效场景能讲
- [ ] 实验③④完成：广播 join 免 Shuffle 证据+累加器翻倍陷阱
- [ ] 《选型卡》成文（何时 cache/何时 checkpoint/何时广播）
- [ ] `git add . && git commit -m "day11-17: persist"`

---
[← Day 16](day16-宽窄依赖与Stage.md) | [本月目录](README.md) | [Day 18 · Spark Shuffle →](day18-SparkShuffle.md)
