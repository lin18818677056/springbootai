# Day 17 · 手写迷你线程池（100 行看透 execute 全流程）

> **今日目标**：day15/16 是"用"，今天是"造"。手写一个 ≤100 行的迷你线程池（核心线程 + 有界队列 + 优雅关闭），并与 ThreadPoolExecutor 对拍验证行为一致——这是本周博客《手写迷你线程池》的核心素材。
> **时长**：理论 0.5h / 实操 2.5h / 输出 0.5h
> **今日产出**：MiniPool（≤100 行 + 对拍一致）+ 与真池的四点差距清单

## 1. 知识地图

```
迷你线程池的最小可行骨架——把 day15 的三步决策砍掉一半：

  ┌────────────────────────────────────────────────┐
  │ MiniPool                                       │
  │                                                │
  │  taskQueue : BlockingQueue<Runnable>  ← 任务队列 │
  │  workers   : List<Thread>             ← 工人名单 │
  │  shutdown  : volatile boolean         ← 关闸标志 │
  └────────────────────────────────────────────────┘

  execute(task)：
    ① 已关闸？抛异常（对应真池的 shutdown 后拒绝）
    ② 否则 taskQueue.put(task)——只排队，不建救急线程（简化）

  worker 循环（每个工人线程干的事）：
    while (running) {
        task = taskQueue.take()      ← 没任务就挂起（对应真池 getTask）
        task.run()                   ← ★在工人线程里执行，不是提交线程！
    }

  shutdown()：
    ① shutdown = true（关闸：execute 开始拒绝）
    ② 对每个工人 interrupt()——唤醒 take() 挂起的工人
    ③ 工人醒来发现 running=false → 退出循环 → 线程结束

  真池比迷你版多什么（诚实清单）：
    ① 救急线程扩容（maximumPoolSize 分层）
    ② keepAliveTime 空闲回收（poll 超时版 getTask）
    ③ 拒绝策略钩子 + 线程池状态机（RUNNING→TERMINATED）
    ④ Worker 继承 AQS（不可中断锁）+ completedTaskCount 统计
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Worker Loop | 工人循环 | 取任务→执行→再取，线程复用的本质 |
| Graceful Shutdown | 优雅关闭 | 先拒新任务，跑完存量再退 |
| Poison Pill | 毒丸 | 一种关闭手法：往队列塞特殊任务（本实现用 interrupt 代替） |
| Pair Test | 对拍测试 | 同一负载下验证手写与官方行为一致 |
| Task Submission | 任务提交 | execute 的线程只是"投递"，跑在工人线程 |

## 3. 动手实操

### 3.1 主菜：MiniPool（数一数，真的 ≤100 行）

```java
// MiniPool.java —— 手写迷你线程池（核心线程 + 有界队列 + 优雅关闭）
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class MiniPool {
    private final BlockingQueue<Runnable> taskQueue;      // ① 任务队列（有界）
    private final List<Thread> workers = new ArrayList<>(); // ② 工人名单
    private volatile boolean shutdown = false;            // ③ 关闸标志
    private final AtomicInteger seq = new AtomicInteger(1);

    public MiniPool(int coreSize, int queueCap) {
        this.taskQueue = new ArrayBlockingQueue<>(queueCap);
        for (int i = 0; i < coreSize; i++) {
            Thread w = new Thread(this::workerLoop, "mini-pool-" + seq.getAndIncrement());
            workers.add(w);
            w.start();                                    // 建池即启动核心线程
        }
    }

    public void execute(Runnable task) {
        if (shutdown) throw new IllegalStateException("池已关闭，拒绝新任务");
        try {
            taskQueue.put(task);                          // 队列满则提交方阻塞（背压）
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void workerLoop() {
        while (!shutdown || !taskQueue.isEmpty()) {       // 关闸后把存量跑完（优雅）
            try {
                Runnable task = taskQueue.take();         // 没任务就挂起
                task.run();                               // ★跑在工人线程，不是提交线程
            } catch (InterruptedException e) {
                // shutdown 时被 interrupt 唤醒：回 while 判断存量是否跑完
            } catch (Throwable t) {
                System.err.println(Thread.currentThread().getName() + " 任务异常: " + t);
            }
        }
    }

    public void shutdown() {
        shutdown = true;                                  // ① 先关闸
        for (Thread w : workers) w.interrupt();           // ② 唤醒挂起的工人
    }

    public int getQueueSize() { return taskQueue.size(); }

    public static void main(String[] args) throws Exception {
        MiniPool pool = new MiniPool(3, 10);              // 3 工人 + 队列 10
        AtomicInteger done = new AtomicInteger();
        for (int i = 1; i <= 100; i++) {
            final int id = i;
            pool.execute(() -> {
                System.out.println(Thread.currentThread().getName() + " 跑任务" + id);
                done.incrementAndGet();
            });
        }
        pool.shutdown();                                  // 优雅关闭：存量跑完
        Thread.sleep(1000);
        System.out.println("完成 " + done.get() + "/100"); // 必须 100/100
    }
}
```

### 3.2 对拍测试：与 ThreadPoolExecutor 同负载对比

```java
// MiniPoolPairTest.java —— 同样 3 核心/10 队列/100 任务，行为应一致
import java.util.concurrent.*;

public class MiniPoolPairTest {
    public static void main(String[] args) throws Exception {
        System.out.println("==== 手写 MiniPool ====");
        runMini();
        System.out.println("==== 官方 ThreadPoolExecutor ====");
        runOfficial();
    }
    static void runMini() throws Exception {
        MiniPool pool = new MiniPool(3, 10);
        Counter c = new Counter();
        for (int i = 0; i < 100; i++) pool.execute(c::bump);
        pool.shutdown();
        Thread.sleep(800);
        System.out.println("计数=" + c.v + "（期望 100）");
    }
    static void runOfficial() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(3, 3, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10));
        Counter c = new Counter();
        for (int i = 0; i < 100; i++) pool.execute(c::bump);
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("计数=" + c.v + "（期望 100）");
    }
    static class Counter {
        int v = 0;
        synchronized void bump() { v++; }
    }
}
// 验证点：两者都输出 100；且日志里线程名都是各自的池名前缀
```

### 3.3 两个错误实验（理解 shutdown 的必要性）

```powershell
cd D:\mywork\springbootai\learning\month02-concurrency

# 实验①：把 main 里的 pool.shutdown() 注释掉再跑
#   现象：程序不退出（3 个工人还在 take() 挂起，非守护线程拖住 JVM）
#   结论：线程池默认"常驻"，这就是服务不退出的原因，也是必须 shutdown 的原因

# 实验②：把 workerLoop 的 while 条件改成 while (!shutdown)（去掉存量判断）再跑
#   现象：完成数 < 100（关闸瞬间队列里的存量任务被抛弃）
#   结论：优雅关闭的关键就是"不再收新 + 跑完存量"这两条的组合
```

### 3.4 输出：《手写迷你线程池》博客素材（day21 发布）

```text
结构建议（day21 复盘日扩写成文）：
  1. 从 execute 三步决策图切入 → "最小可行版只需要队列+工人+关闸"
  2. 贴 MiniPool 全码（标注三处对应真池源码：put≈offer、take≈getTask、interrupt≈interruptIdleWorkers）
  3. 对拍结果 + 两个错误实验现象
  4. 四点差距清单（救急线程/keepAlive/拒绝钩子/状态机）
  5. 收尾："线程池 = 生产者-消费者模式的工业化版本"
```

## 4. 面试连接

**Q：让你手写一个线程池，思路？**
> 三大件：有界任务队列（put 背压）+ 固定工人线程跑 `take→run` 循环 + volatile 关闸标志做优雅关闭（先拒新、再 interrupt 唤醒、存量跑完退出）。然后主动说与真池差距（救急扩容/keepAlive/拒绝钩子/状态机），并给对拍结论——和 day13 手写 AQS 是同一套"迷你实现+差距清单"话术体系。

**Q：任务抛异常会怎样？execute 里怎么兜底？**
> 三种兜底层级：① worker 循环 catch Throwable 记日志（我的迷你版做法）；② ThreadFactory 设 UncaughtExceptionHandler（day15 模板）；③ 业务代码自己 try-catch + submit 用 Future.get 捕获。补充冷知识：execute 的任务抛异常线程会死掉重建，submit 的异常被吞进 Future——不 get 就永远看不见。

**Q：你的迷你池和 ThreadPoolExecutor 最大的行为差异？**
> 我没有救急线程——队列满只能背压阻塞提交方；真池会扩容到 max 再触发拒绝策略。另外没有 keepAliveTime（工人常驻）和状态机（无法区分 SHUTDOWN/STOP）。诚实承认差距 + 知道为什么砍掉（教学聚焦）是加分的。

## 5. 今日验收清单

- [ ] MiniPool ≤100 行（数一遍）且 main 输出 100/100
- [ ] 对拍测试两者计数一致
- [ ] 两个错误实验各复现一次并记录现象
- [ ] 博客素材成文（day21 正式发布）
- [ ] `git add . && git commit -m "day17: mini thread pool 100 lines"`

---
[← Day 16](day16-线程池参数设计与动态调参.md) | [本月目录](README.md) | [Day 18 · CompletableFuture 异步编排 →](day18-CompletableFuture异步编排.md)
