# Day 15 · ThreadPoolExecutor 七参数与执行流程（源码级）

> **今日目标**：线程池是并发面试第一高频。今天把七参数逐个吃透，亲手用自定义参数建池验证 execute 的三步决策（核心线程→队列→救急线程→拒绝），并用 jstack 观察。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：SevenParamsDemo（四阶段观察）+ 四种拒绝策略对比记录

## 1. 知识地图

```
七参数全景（构造器顺序就是考点顺序）：

  new ThreadPoolExecutor(
    corePoolSize,      ① 核心线程数     —— 常驻部队，默认不回收
    maximumPoolSize,   ② 最大线程数     —— 常驻 + 救急的上限
    keepAliveTime,     ③ 存活时间       —— 救急线程空闲多久被回收
    unit,              ④ 时间单位       —— 配合③
    workQueue,         ⑤ 任务队列       —— 核心满了先排队（不是先扩容！）
    threadFactory,     ⑥ 线程工厂       —— 起名字！排查问题全靠它
    handler            ⑦ 拒绝策略       —— 队列满 + 线程也满 时的最后处理
  )

execute(task) 三步决策图（背下来，面试画这张图）：
  submit/execute
      │
      ▼
  线程数 < corePoolSize? ──是──→ new 核心线程执行
      │否
      ▼
  workQueue.offer 成功? ──是──→ 入队等待（核心线程空闲自取）
      │否（队列满）
      ▼
  线程数 < maximumPoolSize? ─是→ new 救急线程执行
      │否
      ▼
  reject(task) ──→ ④种拒绝策略之一

⚠ 最反直觉的点：是"先队列后扩容"——队列满了才建救急线程。
  （SynchronousQueue 除外：它不存任务，直接走扩容）

线程池状态机（生命周期）：
  RUNNING → SHUTDOWN(不再收新任务，跑完存量) → STOP(中断在跑的) 
  → TIDYING → TERMINATED
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| corePoolSize | 核心线程数 | 常驻，默认不回收（allowCoreThreadTimeOut 可改） |
| maximumPoolSize | 最大线程数 | 救急线程的上限 |
| keepAliveTime | 空闲存活时间 | 只作用于"超出核心数"的线程 |
| workQueue | 工作队列 | BlockingQueue：有界/无界/直接交接 |
| ThreadFactory | 线程工厂 | 命名 + 守护标志 + 异常处理器 |
| RejectedExecutionHandler | 拒绝策略 | Abort/CallerRuns/Discard/DiscardOldest |
| Worker | 工作线程 | 源码里的 Worker 类：线程 + 首任务 |

## 3. 动手实操

### 3.1 实验①：七参数四阶段观察（亲手验证三步决策）

```java
// SevenParamsDemo.java —— 核2 / 队列3 / 最大4，任务每2秒一个，看得清清楚楚
import java.util.concurrent.*;

public class SevenParamsDemo {
    public static void main(String[] args) throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(3),                       // 有界队列，容量3
                r -> new Thread(r, "my-pool-" + POOL_ID.getAndIncrement()),
                new ThreadPoolExecutor.AbortPolicy());
        System.out.println("== 阶段1：前2个任务 → 直接建核心线程 ==");
        for (int i = 1; i <= 2; i++) { pool.execute(task(i)); Thread.sleep(200); }
        show(pool);
        System.out.println("== 阶段2：再3个任务 → 入队（不建新线程！）==");
        for (int i = 3; i <= 5; i++) { pool.execute(task(i)); Thread.sleep(200); }
        show(pool);
        System.out.println("== 阶段3：再2个任务 → 队列已满 → 建救急线程3、4 ==");
        for (int i = 6; i <= 7; i++) { pool.execute(task(i)); Thread.sleep(200); }
        show(pool);
        System.out.println("== 阶段4：第9个任务 → 全满 → AbortPolicy 抛异常 ==");
        try { pool.execute(task(9)); } catch (RejectedExecutionException e) {
            System.out.println("第9个任务被拒绝: " + e.getMessage());
        }
        show(pool);
        Thread.sleep(12000);                                       // 等任务跑完
        pool.shutdown();
    }
    static java.util.concurrent.atomic.AtomicInteger POOL_ID = new java.util.concurrent.atomic.AtomicInteger(1);
    static Runnable task(int i) {
        return () -> {
            System.out.println(Thread.currentThread().getName() + " 执行任务" + i);
            try { Thread.sleep(2000); } catch (Exception e) {}
        };
    }
    static void show(ThreadPoolExecutor p) {
        System.out.printf("   [池状态] 线程数=%d 队列=%d 已完成=%d%n",
                p.getPoolSize(), p.getQueue().size(), p.getCompletedTaskCount());
    }
}
// 判分点：阶段2 线程数仍是2（入队不扩容）——90% 的人答错这个顺序
```

### 3.2 实验②：四种拒绝策略对比

```java
// RejectPolicyDemo.java —— 队列容量1 + 最大1，第3个任务必然触发拒绝
import java.util.concurrent.*;

public class RejectPolicyDemo {
    public static void main(String[] args) throws Exception {
        String[] names = {"Abort(抛异常)", "CallerRuns(调用者跑)", "Discard(默默丢)", "DiscardOldest(丢最老)"};
        RejectedExecutionHandler[] handlers = {
                new ThreadPoolExecutor.AbortPolicy(),
                new ThreadPoolExecutor.CallerRunsPolicy(),
                new ThreadPoolExecutor.DiscardPolicy(),
                new ThreadPoolExecutor.DiscardOldestPolicy()
        };
        for (int k = 0; k < 4; k++) {
            ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1), r -> new Thread(r, "p" + k), handlers[k]);
            System.out.println("---- 策略: " + names[k] + " ----");
            try {
                pool.execute(() -> sleep(3000));    // 占住唯一线程
                pool.execute(() -> sleep(3000));    // 占满队列
                System.out.println("任务3 提交结果: " + trySubmit(pool));
            } finally { pool.shutdownNow(); }
        }
    }
    static boolean trySubmit(ThreadPoolExecutor pool) {
        try { pool.execute(() -> sleep(100)); return true; }
        catch (RejectedExecutionException e) { System.out.println("  抛出 RejectedExecutionException"); return false; }
    }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (Exception e) {} }
}
// 观察点：CallerRuns 的任务3由 main 线程执行（背压效果，拖慢提交方）
// DiscardOldest 会把队列里的任务2扔掉——丢的可能是"刚提交的重要任务"
```

### 3.3 实验③：ThreadFactory 的正确姿势（命名 + 异常兜底）

```java
// NamedFactory.java —— 生产级线程工厂模板（直接抄进项目）
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class NamedFactory {
    public static void main(String[] args) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "order-pool-" + seq.getAndIncrement());
                t.setDaemon(false);
                t.setUncaughtExceptionHandler((th, e) ->
                        System.err.println("线程 " + th.getName() + " 未捕获异常: " + e));
                return t;
            }
        };
        ExecutorService pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100), factory);
        pool.execute(() -> { throw new RuntimeException("模拟任务炸了"); });  // 有兜底日志
        pool.execute(() -> System.out.println("正常任务 by " + Thread.currentThread().getName()));
        pool.shutdown();
    }
}
// 生产教训：没命名的线程池在 jstack 里全是 pool-1-thread-N，排查等于盲人摸象
```

### 3.4 命令实操：jstack 观察线程池

```powershell
cd D:\mywork\springbootai\learning\month02-concurrency
javac SevenParamsDemo.java; java SevenParamsDemo
# 运行到"阶段3"时，另开一个终端：
jps -l                       # 找到 SevenParamsDemo 的 PID
jstack <PID> | Select-String "my-pool" -Context 0,2
# 观察：my-pool-1/2 RUNNABLE(sleep)，my-pool-3/4 新建中，队列里 0 个
# 面试杀手锏："我用 jstack 亲眼看过线程池扩容的那一刻"
```

## 4. 面试连接

**Q：讲一下线程池的工作流程？**
> execute 三步：① 线程数 < corePoolSize 直接建核心线程；② 否则入队；③ 队列满且线程数 < maximumPoolSize 建救急线程；④ 都满则触发拒绝策略。重点强调反直觉处："先入队后扩容"，并补一句 SynchronousQueue 是例外（不存任务直接扩容）——这是区分背过和用过的人的细节。

**Q：为什么不推荐 Executors 快捷方法？**
> 四宗罪对应四参数：newFixedThreadPool/newSingleThreadExecutor 用无界 LinkedBlockingQueue → OOM（任务堆积）；newCachedThreadPool 最大线程数 Integer.MAX_VALUE → 线程爆炸。所以阿里巴巴规约要求手动 new ThreadPoolExecutor 并显式给有界队列 + 命名工厂。

**Q：线程池怎么优雅关闭？**
> shutdown()（不再收新任务，跑完存量）与 shutdownNow()（中断在跑的，返回未执行任务）二选一或组合；标准姿势：先 shutdown + awaitTermination(超时)，超时再 shutdownNow 兜底。

## 5. 今日验收清单

- [ ] SevenParamsDemo 四阶段输出截图/记录（重点：阶段2 不扩容）
- [ ] 四种拒绝策略现象各记录一句（含 CallerRuns 背压）
- [ ] NamedFactory 模板已存为个人代码片段
- [ ] 三步决策图能脱稿画出（含 SynchronousQueue 例外）
- [ ] `git add . && git commit -m "day15: tpe 7-params & execute flow"`

---
[← Day 14](day14-第二周复盘输出.md) | [本月目录](README.md) | [Day 16 · 参数怎么定与动态线程池 →](day16-线程池参数设计与动态调参.md)
