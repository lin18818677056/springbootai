# Day 19 · 内存与资源调优：统一内存模型这台"共享冰箱"

> **今日目标**：搞懂 Executor 的内存布局——统一内存模型（Execution 和 Storage 两格可以互相借空间）；学会 Executor OOM 的三步排查法；认识堆外开销（memoryOverhead）和动态分配。这是 Spark 稳定性调优的主战场（day12 说过：Spark 的"贵"就贵在内存）。
> **时长**：内存布局 1.5h / OOM 排查与参数 1.5h / 实验 2h
> **今日产出**：内存布局图 + OOM 三步排查卡 + 《Executor 资源配置模板》

## 1. 知识地图

```
Executor 内存布局（一个 JVM 进程里的地盘划分，先画图再解释）：
  Executor 总内存（--executor-memory）
  ├─ Reserved 300MB          ——雷打不动的保留地
  ├─ 统一内存区 Unified（× spark.memory.fraction，默认 0.6）——"共享冰箱"
  │    ├─ Execution 执行内存：Shuffle/Join/Sort/聚合 的临时工作台
  │    └─ Storage 存储内存：cache/广播变量 的货架
  │    ——两格【可以互相借】：Storage 空闲时 Execution 可以借来干活；
  │       反过来也行。但回收规则不对称：
  │       Storage 借出的可以随时"驱逐"（缓存掉了重算就是了）；
  │       Execution 借出后要等当前操作结束才能收（正在排序的内存不能说停就停）
  └─ User Memory 用户内存（0.4）：你自己的数据结构/UDF 对象

为什么这样设计？（对比老版静态分配）
  老版：Execution 和 Storage 各占固定比例——一边闲置一边爆
  统一模型：资源共享——cache 少的作业能把内存让给 Shuffle（灵活）
  ——思想同构：M2 线程池的核心/队列数权衡、M8 的资源隔离与借还

OOM 排查三步法（面试高频，直接背）：
  第一步看现场：UI Stage 页找爆掉的 Task——看它的 Shuffle Read Size /
    GC Time / 单条记录大小，判断是哪种 OOM
  第二步对号入座（三大常见根因）：
    ① 单条记录过大：一行几百 MB（如超大 JSON）→ 换算子/截断/解析流式化
    ② 单分区数据过大：Shuffle 后某分区 GB 级 → 调大 shuffle.partitions
       或根本是倾斜（个别 key 巨大，day24 治理）
    ③ 广播/缓存过大：Storage 格撑爆 → 检查 broadcast 大小/cache 级别降级
  第三步动参数（按根因对应）：调大 executor-memory / 加分区数摊薄 /
    memoryOverhead 补堆外 / 缩小广播

堆外开销 memoryOverhead（容易被忽略的隐藏账单）：
  JVM 堆外也要钱：Netty 的 direct memory/Shuffle 网络缓冲/线程栈……
  spark.executor.memoryOverhead 默认 = executor-memory × 0.1
  —— 容器/K8s 环境 Pod 被杀（OOMKilled）却没看到 Java OOM 异常？
     多半是堆外超了：调大 overhead，不是无脑加堆
动态分配 Dynamic Allocation：Executor 按需伸缩（闲时归还资源）
  前提：开 external shuffle service（不然 Executor 被收走时 Shuffle 数据跟着陪葬）
  适合多租户共享集群； SLA 敏感的定时大作业建议固定 Executor（避免伸缩抖动）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Unified Memory | 统一内存（Execution+Storage 共享冰箱，可互借） |
| Execution Memory | 执行内存（Shuffle/Join/Sort 的工作台） |
| Storage Memory | 存储内存（cache/广播的货架） |
| memoryOverhead | 堆外开销（Netty/线程栈等隐藏账单） |
| Dynamic Allocation | 动态分配（工人按需增减，需外部 Shuffle 服务） |
| OOMKilled | 被 OS 杀（堆外超限，无 Java 异常的"无声死亡"） |

## 3. 动手实操：内存实验

```scala
// ===== 实验①：把内存配小制造 OOM，练三步排查 =====
// 提交一个聚合作业，executor-memory 压到 512m：
// spark-submit --master "local[2]" ... 或 yarn 模式 --executor-memory 512m
// SQL：对大分区做 count(distinct)（Shuffle 密集）
// 预期：Executor OOM（或容器 OOMKilled）
// 三步走一遍：UI 找爆的 Stage → 看 Shuffle Read Size（单 Task 拉了多大）→
//   判断根因（分区过大）→ 对应解法（加 shuffle.partitions 分摊）
// ===== 实验②：观察统一内存互借（UI Executor 页）=====
// 跑一个"cache 大表+重聚合"作业：4040 → Executors 页
// 看 Storage Memory 列的变化：cache 占用后 Execution 可用空间下降（互相挤压）
// 记录：Storage Used / On Heap / Off Heap 各列数字
// ===== 实验③：overhead 不足的"无声死亡"模拟（记录思路）=====
// spark.executor.memoryOverhead 压到 256m + 大 shuffle 网络 IO
// YARN 模式观察：作业失败但日志无 OutOfMemoryError，YARN 界面显示内存超限被杀
// —— 记录结论：OOMKilled 排查方向是 overhead，不是堆
// ===== 参数卡落地：写《Executor 资源配置模板》=====
// 单 Executor：4 核 + 8g 堆 + 1g overhead（起步模板）
//   总并行度 = num-executors × 4 核；单 Task 目标处理 128MB~1GB
// 核对公式：num-executors × (memory + overhead) ≤ YARN 队列配额
```

## 4. 面试连接

**Q：讲讲 Spark 统一内存模型？（机制必考题）**
> Executor 的 JVM 堆分三块：300MB 保留、统一内存区（默认占 60%，由 spark.memory.fraction 控制）、用户内存（40%，放用户数据结构和 UDF 对象）。核心在统一内存区：它内部不设硬边界，Execution（Shuffle/Join/Sort 这些计算工作台）和 Storage（cache/广播这些货架）两块可以互相借用——这解决了老版静态分配"一边闲置一边 OOM"的浪费。借还规则不对称是面试的深水区：Storage 借出去的内存可以被随时驱逐（缓存分区挤掉就挤掉，丢了沿血缘重算，无伤大雅）；Execution 借出去的必须等操作完成才归还（排序排到一半的内存不能说收就收）。所以实际行为是：计算高峰时缓存被挤出、计算低谷时缓存占大头——这就是"共享冰箱"的含义。联系生产：cache 有效的作业突然变得反复重算，先查是不是 Execution 高峰把缓存挤掉了（UI Storage 页能看到缓存分区数下降）；反过来 cache 塞满导致 Shuffle 频繁 spill 落盘变慢，就要控制缓存级别（MEMORY_ONLY 换 SERIALIZED 或 DISK_ONLY）。这套"共享+不对称回收"的设计和我在 M2 做的线程池资源权衡是同一类问题：资源要共享以提利用率，但回收时机必须尊重使用中的资源。

**Q：Executor OOM 怎么排查？（高频排障题）**
> 我的三步法：第一步看现场——Spark UI 找失败的 Stage/Task，读三个关键指标：Shuffle Read Size（这个 Task 拉了多大）、GC Time（是不是在 GC 挣扎）、单条记录大小（Records 平均值）。第二步对号入座，三大根因各有特征：单条记录过大（平均记录几百 MB，如没拆的超大 JSON）——解法是流式解析或换算子；单分区数据过大（Shuffle Read 达 GB 级且分布不均）——要么是 shuffle.partitions 太少要加，要么本质是数据倾斜（个别 key 巨大，这时加分区也没用，得走 day24 的倾斜治理）；广播或缓存过大（Storage 格撑爆）——检查广播表大小、降 cache 级别。第三步动参数——对应根因调 executor-memory、加分区、调 overhead。特别要讲的是"无声死亡"：容器/K8s 环境 Pod 显示 OOMKilled，但 Java 日志里根本没有 OutOfMemoryError——这是堆外内存超限（Netty direct memory/Shuffle 缓冲这些不占堆的钱），解法是调大 spark.executor.memoryOverhead（默认只有堆的 10%），而不是无脑加堆。我实际排查过的案例：一个聚合作业反复失败，堆内存怎么调都不行，最后发现是 over head 不足——这类"日志里没有异常的失败"最考验对内存布局的理解。

## 5. 今日验收清单

- [ ] 内存布局图（三块+互借+不对称回收）能白板画
- [ ] OOM 三步排查法+三大根因特征能脱稿
- [ ] OOMKilled（堆外无声死亡）的原理和修法能讲
- [ ] 实验①②完成（OOM 复现+UI 内存页观察）
- [ ] 《Executor 资源配置模板》成文（起步值+核对公式）
- [ ] `git add . && git commit -m "day11-19: memory"`

---
[← Day 18](day18-SparkShuffle.md) | [本月目录](README.md) | [Day 20 · Spark SQL 与 Catalyst →](day20-SparkSQL与Catalyst.md)
