# Day 02 · 线程基础与生命周期（六状态 + 协作式中断）

> **今日目标**：把线程六状态图刻进脑子，吃透 interrupt 的"协作式中断"本质（90% 的人答不对），搞清 start/run、守护线程三个高频陷阱。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：六状态迁移观测记录 + 中断正确姿势代码

## 1. 知识地图

```
线程六状态（Thread.State，面试必画）：

                 ┌──────────────┐
       new ────► │     NEW      │  new Thread() 未 start
                 └──────┬───────┘
                        │ start()
                 ┌──────▼───────┐
       运行/就绪► │   RUNNABLE   │◄──────────────┐
                 └──┬───┬───┬───┘               │
        等锁        │   │   │  主动等待           │
   ┌───────────────┘   │   └───────────┐        │
┌──▼────────┐  ┌───────▼────────┐ ┌────▼───────────────┐
│  BLOCKED  │  │    WAITING     │ │   TIMED_WAITING    │
│ synchronized│ │ wait()/join() │ │ sleep(n)/wait(n)/  │
│  没抢到锁   │ │  /park() 无限期│ │ join(n)/parkNanos  │
└──────┬────┘  └───────┬────────┘ └────────┬───────────┘
       │ 抢到锁          │ notify/uninterrupt │ 超时/被唤醒
       └────────────────┴────────┬───────────┘
                          ┌──────▼───────┐
                          │  TERMINATED  │  run() 执行完
                          └──────────────┘

中断 Interrupt（协作式，不是强杀！）：
  t.interrupt()      = 只是给线程打个"请求停止"的标志位
  isInterrupted()    = 查标志位（不清除）
  Thread.interrupted() = 查并清除（静态方法，容易踩坑）
  阻塞方法（sleep/wait/park）被中断 → 抛 InterruptedException 并清除标志
  → 所以正确姿势：catch 后要么恢复标志，要么直接退出

三个高频陷阱：
  ① 调 run() 不调 start() → 普通方法调用，没有新线程！
  ② 守护线程 setDaemon 必须在 start 前 → 否则 IllegalThreadStateException
  ③ 吞掉 InterruptedException（catch 里什么都不做）→ 中断信号丢失
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Thread State | 线程状态 | 六态：NEW/RUNNABLE/BLOCKED/WAITING/TIMED_WAITING/TERMINATED |
| Daemon Thread | 守护线程 | 所有非守护线程结束则 JVM 退出（如 GC 线程） |
| Cooperative Interruption | 协作式中断 | interrupt 只设标志，响应与否由线程自己决定 |
| join() | 等待结束 | 调用方阻塞直到目标线程 TERMINATED |
| yield() | 让步 | 提示调度器"可以让出"，只是提示（几乎不用） |
| ThreadGroup / Executor | 线程组/执行器 | 前者过时；线程统一交线程池管（day15） |

## 3. 动手实操

### 3.1 实验①：亲眼观测状态迁移

```java
// StateWatch.java
public class StateWatch {
    public static void main(String[] args) throws Exception {
        Thread t = new Thread(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException e) {}
        });
        System.out.println("创建后   : " + t.getState());   // NEW
        t.start();
        Thread.sleep(100);
        System.out.println("运行中   : " + t.getState());   // TIMED_WAITING
        t.join();
        System.out.println("结束后  : " + t.getState());   // TERMINATED
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month02-concurrency
javac StateWatch.java; java StateWatch
# 再写一个 BLOCKED 版：两个线程抢同一把 synchronized 锁，第二个打印 BLOCKED
```

### 3.2 实验②：interrupt 的正确与错误姿势

```java
// InterruptDemo.java —— 协作式中断标准模板
public class InterruptDemo {
    public static void main(String[] args) throws Exception {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {   // ① 循环条件查标志
                try {
                    Thread.sleep(500);                          // ② 阻塞点会抛异常
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();         // ③ 恢复标志位！
                    break;                                      // ④ 主动退出
                }
            }
            System.out.println("t: 收到中断，优雅退出");
        });
        t.start();
        Thread.sleep(1200);
        t.interrupt();          // 只是"请求"，不是"强杀"
        t.join();
        System.out.println("main: 结束");
    }
}
// 错误姿势对照组：catch (InterruptedException e) {} 空捕获 → 中断被吞，线程永生
```

### 3.3 实验③：守护线程（Daemon）

```java
public class DaemonDemo {
    public static void main(String[] args) throws Exception {
        Thread t = new Thread(() -> {
            while (true) { try { Thread.sleep(100); } catch (Exception e) {} }
        });
        t.setDaemon(true);      // 必须在 start() 之前！
        t.start();
        Thread.sleep(500);
        System.out.println("main 结束 → 守护线程跟着陪葬，JVM 退出");
    }
}
// 思考：把 setDaemon(true) 挪到 start() 之后会发生什么？（异常，动手验证）
```

### 3.4 观察点

1. 用 month01 的 jstack 观察 StateWatch：t 处于什么状态？栈顶是什么？
2. interrupt 后如果线程在 `synchronized` 排队（BLOCKED），会被中断唤醒吗？（不会！BLOCKED 不响应中断——这是 day11 ReentrantLock.lockInterruptibly() 存在的理由，先埋伏笔）

## 4. 面试连接

**Q：start() 和 run() 的区别？**
> start() 创建新线程并由 JVM 调度执行 run()；直接调 run() 只是当前线程的普通方法调用。判断依据：打印 `Thread.currentThread().getName()` 看线程名。

**Q：interrupt() 能强制停止线程吗？**
> 不能，它是协作式中断——只设置标志位。sleep/wait/park 中的线程会收到 InterruptedException，运行中的线程需要自己检查 isInterrupted() 决定退出。Java 没有"强杀线程"的安全机制（stop() 已废弃，会破坏锁的不变量）。

**Q：画出线程六状态图，BLOCKED 和 WAITING 的区别？**
> BLOCKED = 被动等锁（synchronized 进不去）；WAITING = 主动等待（wait/join/park，等被唤醒）；TIMED_WAITING = 带时限的主动等待。画图时标出每条迁移边的触发条件。

## 5. 今日验收清单

- [ ] 六状态图默写（每条边标触发条件）
- [ ] StateWatch / InterruptDemo / DaemonDemo 三程序跑通
- [ ] 能说出 interrupt 三个关键点（标志位/阻塞抛异常/恢复标志）
- [ ] 记录"BLOCKED 不响应中断"伏笔
- [ ] `git add . && git commit -m "day02: thread states & interruption"`

---
[← Day 01](day01-并发全景与环境搭建.md) | [本月目录](README.md) | [Day 03 · JMM 与 happens-before →](day03-JMM与happens-before.md)
