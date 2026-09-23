# 第 2 月：并发编程（Concurrency Programming）深度

> **本月一句话目标**：吃透 JUC（java.util.concurrent，Java 并发包）源码主线，能手写线程池与锁，掌握 JDK 21+ 虚拟线程——并发是 7 年经验者面试被拷打最狠的领域，本月必须翻盘。

## 本月目标与验收标准

- [ ] 讲清 JMM（Java Memory Model，Java 内存模型）：可见性、原子性、有序性与 happens-before（先行发生原则）
- [ ] 手写简化版 AQS（AbstractQueuedSynchronizer，抽象队列同步器）与不可重入锁
- [ ] 讲透线程池 ThreadPoolExecutor 七参数与执行流程，能设计动态可调线程池
- [ ] 画图讲清 synchronized 锁升级（偏向锁→轻量级锁→重量级锁）与 ConcurrentHashMap 1.8 分段 CAS + synchronized
- [ ] 实操虚拟线程（Virtual Thread）与 CompletableFuture 异步编排，完成一个高并发聚合接口改造
- [ ] 产出 1 篇博客：《手写迷你版线程池：100 行看透 ThreadPoolExecutor》

## 学习资料

- 书籍：《Java 并发编程的艺术》《实战 Java 高并发程序设计》
- 源码：JDK 25 `java.util.concurrent` 包（重点 ReentrantLock、AQS、ThreadPoolExecutor、ConcurrentHashMap）
- 参考Doug Lea 的 AQS 论文（搜索 "The java.util.concurrent Synchronizer Framework"）

---

## 每日计划

### 第 1 周：JMM（Java Memory Model，Java 内存模型）与三大特性

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 并发三大特性 | 原子性（Atomicity）、可见性（Visibility）、有序性（Ordering）；指令重排（Instruction Reorder） | 写Demo复现可见性问题：主线程读不到子线程修改的标志位 | 能举出三大特性各自被破坏的例子 |
| D2 | JMM 与 happens-before | 主内存（Main Memory）与工作内存（Working Memory）；happens-before 八大规则；as-if-serial 语义 | 画出两个线程读写共享变量的 JMM 交互图 | 能用 happens-before 判断一段代码是否线程安全 |
| D3 | volatile 深度 | volatile 两层语义（可见性 + 禁止重排）；内存屏障（Memory Barrier：LoadLoad/StoreStore/LoadStore/StoreStore）；volatile 不保证原子性 | 用 JMH（Java Microbenchmark Harness，基准测试框架）对比 volatile 与 AtomicLong | 能讲清 volatile 底层如何插屏障 |
| D4 | synchronized 原理(上) | 对象头（Mark Word）结构、monitor（监视器锁/ObjectMonitor）、字节码层面 monitorenter/monitorexit | `javap -c` 反编译同步方法与同步块对比 | 能对应字节码讲锁的进入与退出 |
| D5 | synchronized 原理(下) | 锁升级：无锁→偏向锁（Biased Locking）→轻量级锁（CAS 自旋）→重量级锁；锁消除（Lock Elimination）与锁粗化（Lock Coarsening）；注意 JDK 15+ 偏向锁已废弃 | 用 `-XX:-UseBiasedLocking` 对比压测 | 能讲清不同竞争程度下的锁状态迁移 |
| D6 | CAS 与原子类 | CAS（Compare And Swap，比较并交换）；ABA 问题与 AtomicStampedReference（带版本号）；LongAdder 分段累加 vs AtomicLong | 实现 CAS 自旋锁；压测对比 LongAdder/AtomicLong | 能讲清 ABA 在什么场景真会出问题 |
| D7 | 周复盘 | JMM 知识串讲 | 周记 + 《volatile/synchronized/CAS 选型决策表》 | 面试官追问任意层面都能接住 |

### 第 2 周：AQS（AbstractQueuedSynchronizer）与显式锁

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | AQS 总体设计 | state（同步状态）、CLH 变体队列（FIFO 双向队列）、独占模式（Exclusive）与共享模式（Shared）；模板方法模式在 AQS 中的运用 | 通读 AQS 类注释（Doug Lea 原文）并翻译要点 | 能画出 AQS 核心数据结构 |
| D2 | 独占式获取与释放 | `acquire` 流程：tryAcquire → addWaiter → acquireQueued（自旋+挂起）；`release` 唤醒后继节点；cancelAcquire 处理取消节点 | 跟踪调试 ReentrantLock.lock() 的入队全过程 | 能画出 3 个线程抢锁的队列变化 |
| D3 | ReentrantLock 与 Condition | 可重入原理（state 计数 + Owner 线程）、公平 vs 非公平；Condition 队列（等待队列）与 await/signal 流程 | 手写一个用 ReentrantLock + Condition 实现的有界阻塞队列 | 能对比 synchronized+wait 的异同 |
| D4 | 共享模式与工具类 | 共享获取 `acquireShared`、传播唤醒；CountDownLatch（倒计时门闩）、Semaphore（信号量）、CyclicBarrier（循环栅栏）与 CountDownLatch 区别 | 三者各写一个真实业务场景 Demo（并行初始化/限流/分批同步） | 能按场景正确选型 |
| D5 | 手写 AQS | 手写迷你版 AQS：state + CLH 队列 + park/unpark；基于它实现不可重入互斥锁 | 100 行左右实现，与 ReentrantLock 对拍测试 | 手写版通过 100 线程并发计数测试 |
| D6 | 读写锁与 StampedLock | ReentrantReadWriteLock（读写分离、写饥饿）、锁降级；StampedLock（写锁/悲观读/乐观读 Optimistic Read） | 用 StampedLock 优化一个读多写少的缓存类 | 能讲清乐观读的校验机制 |
| D7 | 周复盘 | AQS 全景串讲 | 博客素材整理：《一张图讲透 AQS》 | 画出独占+共享+Condition 三队列关系图 |

### 第 3 周：线程池与并发容器

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | ThreadPoolExecutor 核心七参数 | corePoolSize（核心线程数）、maximumPoolSize（最大线程数）、keepAliveTime（存活时间）、workQueue（工作队列）、threadFactory（线程工厂）、handler（拒绝策略）；线程池状态机（RUNNING→SHUTDOWN→STOP→TIDYING→TERMINATED） | 手写线程池执行流程图 | 能复述"先核心、再队列、再扩容、最后拒绝"及为什么 |
| D2 | 参数怎么定 | CPU 密集型 vs IO 密集型线程数估算公式；队列选型（SynchronousQueue/LinkedBlockingQueue/ArrayBlockingQueue）；拒绝策略（AbortPolicy/CallerRunsPolicy/DiscardPolicy/DiscardOldestPolicy）与 CallerRuns 的反压（Backpressure）效果 | 为"接口调用下游"场景设计线程池参数并说明理由 | 能推翻"IO 密集 = 2N"的机械公式，讲清压测定参 |
| D3 | 动态线程池与监控 | setCorePoolSize 运行时调整原理、beforeExecute/afterExecute 埋点、线程池监控指标（活跃数/队列深度/拒绝数）；美团动态线程池思想 | 给 springbootai 写一个带监控端点的动态线程池 Bean | 能接入 Micrometer 并在 Grafana 看板展示 |
| D4 | Future 与 CompletableFuture | FutureTask 源码（state 状态机）、CompletableFuture 核心方法（supplyAsync/thenApply/thenCompose/thenCombine/allOf/exceptionally）与默认线程池陷阱（ForkJoinPool.commonPool） | 把一个串行调用 3 个下游的接口改造为并行聚合，RT 从 300ms 降到 100ms | 有改造前后压测数据 |
| D5 | 并发容器 | ConcurrentHashMap 1.8 实现（CAS + synchronized 锁桶头、sizeCtl、扩容协助 transfer）、size() 为何是弱一致；CopyOnWriteArrayList（写时复制）适用场景与缺陷 | 读 CHM 源码 putVal/get/transfer 主线 | 能画出 CHM put 的分支流程图 |
| D6 | ThreadLocal 与线程封闭 | ThreadLocal 结构（ThreadLocalMap、开放寻址、哈希冲突线性探测）、内存泄漏根因（key 弱引用 value 强引用）、InheritableThreadLocal 与 TransmittableThreadLocal（阿里开源，线程池传递） | 复现 ThreadLocal 泄漏并修复；在 @Async 场景传递用户上下文 | 能讲清"为什么 remove 要在 finally" |
| D7 | 周复盘 | 线程池+并发容器串讲 | 博客：《手写迷你版线程池：100 行看透 ThreadPoolExecutor》发布 | 博客含完整可运行代码与测试 |

### 第 4 周：虚拟线程（Virtual Thread）与并发设计实战

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 线程模型演进 | 内核线程（Kernel Thread）与用户线程、1:1 / N:1 / M:N 模型；协程（Coroutine）概念；Go goroutine 与 Java 虚拟线程对比 | 画出线程模型对比图 | 能讲清虚拟线程解决什么问题（阻塞成本） |
| D2 | 虚拟线程原理 | 载体线程（Carrier Thread）、Continuation（续体）挂起/恢复、调度器（ForkJoinPool）、Pin（钉住：synchronized 与 native 阻塞导致无法卸载，JDK 24 起已大幅缓解） | 100 万虚拟线程 Demo；对比平台线程内存占用 | 能讲清 pinning 场景与规避方式（用 ReentrantLock） |
| D3 | 虚拟线程工程化 | `Executors.newVirtualThreadPerTaskExecutor()`、结构化并发（Structured Concurrency，JDK 预览特性）、信号量限流配合 | 把项目 IO 型接口改为虚拟线程执行并压测 | 记录吞吐前后对比数据 |
| D4 | 并发设计模式 | 生产者-消费者（BlockingQueue）、Master-Worker、Future 模式、两阶段终止、不可变对象（Immutable）与 final 语义、对象池思想 | 实现一个带优雅关闭的两阶段终止线程框架 | 每种模式能对应一个真实组件 |
| D5 | 综合实战：手写限流器 | 令牌桶（Token Bucket）与漏桶（Leaky Bucket）算法、滑动窗口（Sliding Window）；单机版手写 + 原子类保证线程安全 | 手写支持 QPS 限流的注解 + AOP（Aspect Oriented Programming，面向切面编程）组件 | 压测验证限流精度误差 < 5% |
| D6 | 综合实战：手写连接池 | 资源池设计：获取/归还/超时/健康检查/监控；对比 HikariCP 设计（ConcurrentBag） | 手写一个简易数据库/HTTP 连接池 | 并发压测下无死锁、无泄漏 |
| D7 | 月度总复盘 | 并发全域串讲 + 30 道面试题自测 | 整理《并发面试 50 问及标准答法》（每题绑定自己写过的代码） | 达成本月全部验收标准 |

---

## 本月产出清单

1. 博客 2 篇：《一张图讲透 AQS》《手写迷你版线程池》
2. 手写代码库：迷你 AQS、迷你线程池、限流器、连接池、有界阻塞队列
3. 《并发面试 50 问》自测文档（每题关联自己的实战案例）
4. 虚拟线程改造报告（吞吐对比数据）

## 本月术语表

| 英文 | 中文 |
|------|------|
| JUC (java.util.concurrent) | Java 并发包 |
| CAS (Compare And Swap) | 比较并交换 |
| ABA Problem | ABA 问题（值变回原值导致 CAS 误判） |
| Contention | 锁竞争 |
| Livelock / Deadlock | 活锁 / 死锁 |
| Fair / Unfair Lock | 公平锁 / 非公平锁 |
| Backpressure | 反压（下游限速上游的机制） |
| Structured Concurrency | 结构化并发 |
| Carrier Thread | 载体线程（运行虚拟线程的平台线程） |

> 下一月：`../month03-网络IO与MySQL.md`——网络协议 + IO 模型 + MySQL 内核，后端两大地基。
