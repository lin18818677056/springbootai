# Day 11 · ReentrantLock 与 Condition（显式锁的三大超能力）

> **今日目标**：ReentrantLock 相比 synchronized 的三大超能力：可中断、可超时、多条件精准唤醒。今天用 Condition 重写 day08 的生产者-消费者，并验证"BLOCKED 不响应中断、lock 等待可以"的关键差异。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：Condition 版有界缓冲区 + 三大超能力验证记录

## 1. 知识地图

```
ReentrantLock 的结构（完全站在 AQS 肩上）：
  ReentrantLock
    └─ Sync (extends AQS)
         ├─ NonfairSync（默认）: lock() = 先 CAS 抢（state 0→1），失败才 acquire 排队
         └─ FairSync:           lock() = hasQueuedPredecessors()? 排队 : CAS

  可重入的实现（对应 AQS state 语义）：
    lock()：CAS(0→1) 成功 → exclusiveOwnerThread=当前线程
            owner==自己 → state+1（重入计数，tryAcquire 里判）
    unlock()：state-1；减到 0 → owner=null，unparkSuccessor

三大超能力（synchronized 都没有）：
  ① 可中断     lockInterruptibly()：等锁途中被 interrupt 可抛异常退出
               （day02 伏笔回收：BLOCKED 状态不响应中断！）
  ② 可超时     tryLock(timeout, unit)：等 N 秒拿不到就放弃（防雪崩/降级）
               tryLock()：立刻试一把，抢不到返回 false（可探测）
  ③ 多条件     newCondition() 可建多个等待队列，signalAll 精准唤醒"同类"

Condition（对标 wait/notify，但按队列精准分组）：
   await()      ≈ wait()    释放锁 + 进该条件的等待队列
   signal()     ≈ notify()  唤醒该条件队列一个
   signalAll()  ≈ notifyAll() 唤醒该条件队列全部
   生产者消费者双队列设计：
     notFull.await()   满了去"不满"队列睡
     notEmpty.signal() 上货后只叫消费者（不会叫醒生产者！）
   对比 day08 的 notifyAll 惊群 → 这里精准唤醒 = 性能与正确性双赢
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| ReentrantLock | 可重入锁 | 显式锁，AQS 独占模式的标准实现 |
| Condition | 条件 | 等待队列抽象，await/signal 对应 wait/notify |
| lockInterruptibly | 可中断加锁 | 等锁期间响应中断 |
| tryLock | 尝试加锁 | 非阻塞/限时两种形态 |
| exclusiveOwnerThread | 独占持有线程 | AQS 记录"锁在谁手里"的字段 |
| Fair / Nonfair Sync | 公平/非公平同步器 | 是否检查排队前驱 |

## 3. 动手实操

### 3.1 实验①：Condition 版生产者-消费者（对比 day08）

```java
// BoundedBufferC.java —— 双 Condition 精准唤醒版
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class BoundedBufferC {
    private final Queue<Integer> buf = new LinkedList<>();
    private final int cap = 3;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull  = lock.newCondition();   // 生产者的等待队列
    private final Condition notEmpty = lock.newCondition();   // 消费者的等待队列

    public void put(int v) throws InterruptedException {
        lock.lock();
        try {
            while (buf.size() == cap) notFull.await();        // 满了去生产者队列睡
            buf.offer(v);
            System.out.println("P -> " + v + "  [" + buf.size() + "]");
            notEmpty.signal();                                 // ★只叫消费者，不惊群
        } finally { lock.unlock(); }
    }
    public int take() throws InterruptedException {
        lock.lock();
        try {
            while (buf.isEmpty()) notEmpty.await();           // 空了去消费者队列睡
            int v = buf.poll();
            System.out.println("      C <- " + v + "  [" + buf.size() + "]");
            notFull.signal();                                  // ★只叫生产者
            return v;
        } finally { lock.unlock(); }
    }
    // main 同 day08 的 BoundedBuffer，替换类名即可跑
}
// 对比记录：day08 版必须 notifyAll 全叫醒再各自重新检查；这里一叫一个准
```

### 3.2 实验②：可中断锁 vs BLOCKED（day02 伏笔回收）

```java
// InterruptibleLock.java
import java.util.concurrent.locks.ReentrantLock;

public class InterruptibleLock {
    static final ReentrantLock LOCK = new ReentrantLock();

    public static void main(String[] args) throws Exception {
        LOCK.lock();                                    // 主线程先持有锁 5 秒
        Thread t = new Thread(() -> {
            try {
                LOCK.lockInterruptibly();               // 等锁中，可被中断
                try { System.out.println("t: 拿到锁"); } finally { LOCK.unlock(); }
            } catch (InterruptedException e) {
                System.out.println("t: 等锁时被中断，优雅退出");
            }
        });
        t.start();
        Thread.sleep(500);
        t.interrupt();                                  // 中断正在等锁的 t
        t.join();
        LOCK.unlock();
    }
}
// 对照组：换成 synchronized + BLOCKED 状态 → interrupt() 无效，线程卡到拿锁为止
// （可再用 jstack 确认两种状态：WAITING(parking) vs BLOCKED）
```

### 3.3 实验③：tryLock 超时的业务价值（防雪崩）

```java
// TryLockDemo.java —— 拿不到锁就快速失败，而不是无限排队
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class TryLockDemo {
    static final ReentrantLock LOCK = new ReentrantLock();

    public static void main(String[] args) throws Exception {
        LOCK.lock();
        Thread t = new Thread(() -> {
            try {
                if (LOCK.tryLock(300, TimeUnit.MILLISECONDS)) {   // 只等 300ms
                    try { System.out.println("t: 拿到锁"); } finally { LOCK.unlock(); }
                } else {
                    System.out.println("t: 300ms 拿不到 → 降级/快速失败");
                }
            } catch (InterruptedException e) { System.out.println("t: 被中断"); }
        });
        t.start();
        Thread.sleep(1000);
        LOCK.unlock();
    }
}
// 场景联想：大促降级、防止锁风暴级联（上游全在等一把锁 → 全线超时）
```

### 3.4 观察点

1. Condition 版把 notifyAll 改 signal 的收益：日志里消费者/生产者各归各醒
2. lockInterruptibly 的线程在 jstack 里是什么状态？（WAITING parking，不是 BLOCKED）
3. tryLock 的超时轮询怎么实现的？（AQS tryAcquireNanos：自旋 + parkNanos 限时）

## 4. 面试连接

**Q：synchronized 和 ReentrantLock 怎么选？**
> 默认 synchronized（JDK 级优化：锁升级/消除/粗化，且不怕忘 unlock）；需要三大超能力之一才上 ReentrantLock：可中断（取消排队任务）、可超时（防雪崩降级）、多 Condition（生产消费分组唤醒）。再加一条：需要公平语义或需要锁状态探测（isLocked/getQueuedThreads 监控）也选它。

**Q：Condition 相比 wait/notify 的优势？**
> ① 多条件队列：生产者/消费者各自等待，signalAll 精准唤醒不惊群（对比 day08 的坑）② 不依赖 synchronized，与 ReentrantLock 配套支持中断/超时语义。本质：wait/notify 只有一个"大杂院"，Condition 可以开多个"包间"。

**Q：可重入锁的"重入"怎么实现的？**
> AQS state 计数 + exclusiveOwnerThread 记录持有者：同线程再加锁 state+1；解锁 state-1，减到 0 才释放并唤醒后继。验证方法：lock() 两次 unlock() 一次 → 线程卡死（锁没放干净）。

## 5. 今日验收清单

- [ ] BoundedBufferC 跑通并与 day08 对比记录
- [ ] InterruptibleLock 验证成功（含 jstack 状态观察）
- [ ] TryLockDemo 跑通并说出一个业务场景
- [ ] 三大超能力 + 选型结论成文
- [ ] `git add . && git commit -m "day11: reentrantlock & condition"`

---
[← Day 10](day10-AQS总体设计.md) | [本月目录](README.md) | [Day 12 · 并发工具三剑客 →](day12-并发工具三剑客.md)
