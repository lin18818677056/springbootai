# Day 10 · AQS 总体设计（JUC 的骨架）

> **今日目标**：AQS（AbstractQueuedSynchronizer，抽象队列同步器）是 ReentrantLock/Semaphore/CountDownLatch/线程池同步器的共同骨架。今天读懂它的三大件（state/CLH 队列/模板方法），用 IDEA 断点亲眼看 3 个线程入队的全过程。
> **时长**：理论 1.5h / 实操 1.5h / 输出 0.5h
> **今日产出**：3 线程抢锁的队列变化图 + AQS 骨架笔记

## 1. 知识地图

```
AQS 三大件（读懂这一个类 = 读懂半个 JUC）：

  ① state（volatile int 同步状态）——语义由子类定义
     ReentrantLock:  0=无锁, 1=持有1次, 2=重入2次...
     Semaphore:      剩余许可数
     CountDownLatch: 还剩几个 count
     FutureTask:     任务状态机

  ② CLH 变体队列（FIFO 双向队列）——排队等锁的线程
     head → [dummy] ⇄ [t1] ⇄ [t2] ⇄ [t3] ← tail
     原版 CLH：自旋等前驱（烧 CPU）
     AQS 变体：park 挂起（省 CPU），前驱释放时 unpark 唤醒后继
     waitStatus：SIGNAL(-1) 我挂了，释放时请唤醒我 / CANCELLED(1) 取消

  ③ 模板方法模式——子类只管"一次尝试"的语义，排队/挂起/唤醒全是 AQS 管
     独占模式：tryAcquire()/tryRelease()      （ReentrantLock）
     共享模式：tryAcquireShared()/tryReleaseShared()（Semaphore/CountDownLatch）

独占获取 acquire(1) 主流程（背下来，断点就是沿着它走的）：
  tryAcquire(arg) 失败？
    → addWaiter(Node.EXCLUSIVE) 入队尾部
    → acquireQueued(node)：
         自旋：前驱是 head？→ 再试 tryAcquire
              不是 head 或又失败 → shouldParkAfterFailedAcquire（设 SIGNAL）
              → parkAndCheckInterrupt() 挂起，等前驱 unpark
  release(1)：tryRelease 成功 → unparkSuccessor(head) 唤醒队首后继

公平 vs 非公平（一句话本质）：
  非公平：lock() 上来先 CAS 抢一把（插队！），失败才排队——吞吐高
  公平：  先看队列有没有人在等，有就老实排队——无饥饿
  默认都是非公平（插队失败概率低，省了排队开销，整体吞吐更高）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| AQS (AbstractQueuedSynchronizer) | 抽象队列同步器 | JUC 锁与同步器的公共骨架 |
| state | 同步状态 | volatile int，语义由子类赋予 |
| CLH Queue | CLH 变体队列 | FIFO 双向等待队列，park 版 |
| Exclusive / Shared | 独占/共享 | 一次一个通行 / 一次多个通行 |
| Template Method | 模板方法模式 | 骨架固定，"尝试一次"由子类实现 |
| waitStatus | 等待状态 | SIGNAL/-1 等，控制唤醒契约 |

## 3. 动手实操

### 3.1 实验①：断点亲历 3 线程入队（今天的主角）

```java
// AqsDebug.java —— IDEA 断点调试 AQS 入队
import java.util.concurrent.locks.ReentrantLock;

public class AqsDebug {
    static final ReentrantLock LOCK = new ReentrantLock();

    public static void main(String[] args) throws Exception {
        Runnable task = () -> {
            LOCK.lock();
            try {
                System.out.println(Thread.currentThread().getName() + " 拿到锁");
                try { Thread.sleep(3000); } catch (Exception e) {}   // 持锁 3 秒，制造排队
            } finally { LOCK.unlock(); }
        };
        new Thread(task, "T1").start();
        Thread.sleep(100);
        new Thread(task, "T2").start();
        Thread.sleep(100);
        new Thread(task, "T3").start();
    }
}
```

```text
IDEA 调试步骤（断点打在 AQS 源码）：
  ① 断点位置1：AbstractQueuedSynchronizer#addWaiter
  ② 断点位置2：acquireQueued 的 parkAndCheckInterrupt
  ③ 右键断点 → 设为 Thread 断点模式（每个线程分别停）
  ④ T2/T3 依次停在断点 → 看变量面板（Variables）：
      双击查看：this.state = 1（T1 持有）
      this.tail = T2 的 Node（T3 入队后 tail=T3）
      node.prev / node.next / node.waitStatus = -1(SIGNAL)
  ⑤ 3 秒后 T1 unlock → watch unparkSuccessor(head) → T2 醒来再抢
画出队列变化图（5 帧）：空 → [T1持锁] → dummy⇄T2 → dummy⇄T2⇄T3 → T2 醒来抢到
```

### 3.2 实验②：公平 vs 非公平的插队现场

```java
// FairVsUnfair.java —— 非公平锁的"插队"肉眼可见
import java.util.concurrent.locks.ReentrantLock;

public class FairVsUnfair {
    public static void main(String[] args) throws Exception {
        ReentrantLock lock = new ReentrantLock(false);   // 改 true 看公平版输出
        Runnable task = () -> {
            for (int i = 0; i < 3; i++) {
                lock.lock();
                try { System.out.println(Thread.currentThread().getName() + " 拿到"); }
                finally { lock.unlock(); }
                try { Thread.sleep(10); } catch (Exception e) {}
            }
        };
        for (int i = 0; i < 4; i++) { new Thread(task, "T" + i).start(); Thread.sleep(30); }
    }
}
// 非公平输出：可能 T0 连续拿两次（释放后立刻重新 CAS 抢回，没给排队者机会）
// 公平输出：  严格 T0→T1→T2→T3 轮转（FIFO）
```

### 3.3 观察点

1. state 的可见性靠什么保证？（volatile + CAS + 解锁时的写 hb 加锁的读，规则②③）
2. 非公平锁"插队"会不会导致排队线程永远抢不到（饥饿）？（理论上可能，实践概率低；强公平需求才用 fair=true）
3. 为什么 AQS 队列头是 dummy 节点？（简化边界：head 代表"当前持锁者"占位）

## 4. 面试连接

**Q：讲讲 AQS 的设计。**
> 三大件：volatile state（语义子类定）+ CLH 变体双向队列（park 版防 CPU 空转）+ 模板方法（独占/共享两组钩子）。获取失败入队挂起，释放时 unpark 后继。ReentrantLock/Semaphore/CountDownLatch 都是它的子类应用——用一个例子串起来（如 Semaphore 的 state=许可数）说明"一骨架多语义"。

**Q：公平锁和非公平锁的区别与取舍？**
> 本质：非公平 lock() 先直接 CAS 抢（可能插队），公平先检查队列。非公平吞吐高（减少线程挂起/唤醒），公平无饥饿但唤醒链路长。默认非公平；真实需求（严格按序处理，如审计）才用公平。

**Q：为什么 AQS 用 park 而不是自旋等待？**
> 原 CLH 是自旋（前驱状态轮询），CPU 空转；AQS 改成"设置 SIGNAL 后 park 挂起"，前驱释放时精确 unpark——把烧 CPU 换成挂起调度成本。竞争极短的场景两者都行，一般场景 park 更优。

## 5. 今日验收清单

- [ ] IDEA 断点走通 acquire 全流程，队列变化图 5 帧画完
- [ ] 能说出 state 在 Lock/Semaphore/CountDownLatch 三处的不同语义
- [ ] FairVsUnfair 两种输出都观察过
- [ ] 三大件 + acquire 流程能脱稿讲
- [ ] `git add . && git commit -m "day10: aqs design & debug"`

---
[← Day 09](day09-CAS与原子类.md) | [本月目录](README.md) | [Day 11 · ReentrantLock 与 Condition →](day11-ReentrantLock与Condition.md)
