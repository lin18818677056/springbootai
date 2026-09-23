# Month02 · 并发编程（Concurrency Programming）30 天精细化计划

> **本月一句话目标**：并发是 7 年经验者面试被拷打最狠的领域。这 30 天从 JMM 地基到手写 AQS/线程池/限流器/连接池，全部用"图解 + 可复现代码"打通——面试官问到任何一层，你都有亲手做过的实验和代码接住。

## 0. 环境准备（开工前 30 分钟）

| 工具 | 用途 | 说明 |
|------|------|------|
| JDK 25 | 全部实验 | 已有（month01 环境沿用） |
| IntelliJ IDEA | 调试 AQS/线程池源码 | 断点跟 `ReentrantLock.lock()` 入队全流程（day10） |
| Arthas | day26 并发排查 | month01 day17 已装，直接复用 `arthas-boot.jar` |
| JMH（4 个 jar） | day26 伪共享实验 | month01 day23 已下载，`Copy-Item` 过来即可 |
| Docker Desktop | 本月可选 | 并发实验主要跑本机；day26 想在 Linux 复核数据时可用 |

```powershell
# 建立本月学习仓库（与 month01 同套路：每日目录 + git 提交）
mkdir D:\mywork\springbootai\learning\month02-concurrency -Force
cd D:\mywork\springbootai\learning\month02-concurrency
git init
```

## 1. 每日文件五段模板（与 month01 相同）

每个 day 文件固定五段：**① 知识地图（ASCII 图解）→ ② 核心概念（中英对照）→ ③ 动手实操（PowerShell 命令 + 可运行 Java 代码）→ ④ 面试连接 → ⑤ 验收清单**，文末带前后导航。学习节奏：理论 1h / 实操 1.5h / 输出 0.5h，周日（day07/14/21）为复盘输出日。

## 2. 30 天课程地图

| 周 | 主题 | 天 | 内容 |
|----|------|-----|------|
| **Week1** | 并发地基与 JMM | day01 | 并发全景与环境搭建（三大特性两个破坏性复现） |
| | | day02 | 线程基础与六状态生命周期、interrupt 协作式中断 |
| | | day03 | JMM 与 happens-before 八大规则、重排序观测 |
| | | day04 | volatile 深度：可见性/内存屏障/DCL 单例 |
| | | day05 | synchronized（上）：字节码 monitorenter 与 monitor |
| | | day06 | synchronized（下）：锁升级演进与锁粗化/消除 |
| | | day07 | 第一周复盘输出（默写 JMM 图 + 10 题自测 + 博客） |
| **Week2** | CAS、AQS 与锁体系 | day08 | wait/notify 与 LockSupport 两套线程通信 |
| | | day09 | CAS 与原子类：AtomicLong/LongAdder/ABA |
| | | day10 | AQS 总体设计：state/CLH 队列/独占与共享 |
| | | day11 | ReentrantLock 与 Condition（手写有界阻塞队列） |
| | | day12 | 并发工具三剑客：CountDownLatch/Semaphore/CyclicBarrier |
| | | day13 | 手写迷你 AQS：100 行不可重入锁 + 并发对拍 |
| | | day14 | 第二周复盘输出（《一张图讲透 AQS》博客素材） |
| **Week3** | 线程池与并发容器 | day15 | ThreadPoolExecutor 七参数与源码级执行流程 |
| | | day16 | 参数怎么定：CPU/IO 密集公式陷阱与动态线程池 |
| | | day17 | 手写迷你线程池：100 行看透 execute 全流程 |
| | | day18 | CompletableFuture：串行接口 → 并行聚合改造 |
| | | day19 | ConcurrentHashMap 深度：putVal/get/扩容协助 |
| | | day20 | ThreadLocal 与线程封闭：泄漏复现与 TTL 传递 |
| | | day21 | 第三周复盘输出（发布《手写迷你线程池》博客） |
| **Week4-5** | 高级主题与综合实战 | day22 | 读写锁与 StampedLock：乐观读校验机制 |
| | | day23 | 并发设计模式：不可变对象/两阶段终止/Future 模式 |
| | | day24 | 虚拟线程工程化：IO 接口改造 + 压测报告 |
| | | day25 | 并发 bug 复现场：竞态/发布逸出/构造器 this 逸出 |
| | | day26 | 并发性能排查：锁竞争热点/伪共享 @Contended 实测 |
| | | day27 | 综合实战一：手写令牌桶限流器（原子类 + 压测精度） |
| | | day28 | 综合实战二：手写连接池（超时/健康检查/无泄漏） |
| | | day29 | 综合演练：高并发聚合接口改造全流程 + 并发 50 问启动 |
| | | day30 | 月度复盘与 M2 验收（输出物盘点 + month03 预习） |

## 3. 本月验收标准（M2，day30 逐项核对）

- [ ] 能用 happens-before 判断任意一段代码的可见性安全性（day03）
- [ ] 手写迷你 AQS 通过 100 线程并发计数测试（day13）
- [ ] 手写迷你线程池 ≤100 行且行为与 ThreadPoolExecutor 对拍一致（day17）
- [ ] 完成"串行 300ms → 并行 100ms"聚合接口改造并留压测数据（day18/29）
- [ ] 能画出 CHM put 分支流程 + 讲清 ThreadLocal 泄漏链（day19/20）
- [ ] 手写限流器压测精度误差 <5%、连接池无死锁无泄漏（day27/28）
- [ ] 博客 2 篇：《一张图讲透 AQS》《手写迷你线程池》

## 4. 与 month01 的衔接

- day19 的 ThreadLocal 泄漏 → month01 day25 场景②的展开
- day26 的锁竞争排查 → month01 day17-20 的 Arthas/JFR 直接复用
- day24 的虚拟线程 → month01 day28 入门课的工程化落地

---
[Month01 · JVM 深度](../month01-JVM深度/README.md) | **本月目录** | [Day 01 · 并发全景与环境搭建 →](day01-并发全景与环境搭建.md)
