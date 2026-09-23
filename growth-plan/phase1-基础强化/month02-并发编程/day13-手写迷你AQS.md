# Day 13 · 手写迷你 AQS（100 行看透同步器本质）

> **今日目标**：读源码是"看懂"，手写是"掌握"。今天用 state + 等待队列 + park/unpark 三大件手写一个不可重入互斥锁（约 50 行），再加一个 10 行变体展示共享模式——通过 100 线程并发对拍测试。
> **时长**：理论 0.5h / 实操 2.5h / 输出 0.5h
> **今日产出**：MiniAqsLock（通过 100 线程对拍）+ 《一张图讲透 AQS》博客素材

## 1. 知识地图

```
还原 AQS 的"最小可行骨架"——三大件一个不少：

  ┌───────────────────────────────────────────────┐
  │ MiniAqsLock                                   │
  │                                               │
  │  state : AtomicInteger        ← 同步状态(0无锁/1持有) │
  │  waiters: ConcurrentLinkedQueue<Thread> ← 等待队列    │
  │  park/unpark                  ← 挂起与唤醒           │
  └───────────────────────────────────────────────┘

  lock() 流程（对照 AQS acquire）：
    ① 快速路径：CAS(0→1) 成功直接进（=非公平锁的插队）
    ② 失败 → 自己加入等待队列
    ③ 自旋：先再试一次 CAS；失败就 park 挂起（=acquireQueued）
  unlock() 流程（对照 AQS release）：
    ① state 置 0
    ② unpark 队首线程（=unparkSuccessor）

与真 AQS 的差距（诚实清单，也是加分项）：
  ① 真双向 CLH 队列 vs 我的 ConcurrentLinkedQueue
  ② 真 SIGNAL 唤醒契约（防丢失唤醒）vs 我简化为"唤醒队首"
  ③ 真可重入/共享传播/超时/中断支持
  → 面试话术："我手写过 50 行迷你版理解骨架，生产用真 AQS。"
    （比背 1000 行源码的更可信，比只会用 API 的深一层）

共享模式变体（10 行展示）：
  MiniSemaphore：acquire = while(!(cur>0 && CAS(cur,cur-1))) park
  → 与独占只差"判定条件"：独占 state==0 才进；共享 state>0 就进
  → 这就是 AQS 独占/共享两套 API 的本质差异
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Minimal AQS | 迷你 AQS | state+队列+park 三大件的最小实现 |
| Quick Path | 快速路径 | CAS 直接成功，免入队 |
| Missed Wakeup | 丢失唤醒 | 检查条件与 park 之间被唤醒（真 AQS 用 SIGNAL 防） |
| Ownership Test | 持有者校验 | 解锁前校验当前线程是否持有者 |
| Stress Test | 压力对拍 | 高并发下验证正确性（100 线程计数） |

## 3. 动手实操

### 3.1 主菜：MiniAqsLock（手写 + 对拍）

```java
// MiniAqsLock.java —— 三大件版不可重入互斥锁
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class MiniAqsLock {
    private final AtomicInteger state = new AtomicInteger(0);            // ① 同步状态
    private final ConcurrentLinkedQueue<Thread> waiters                  // ② 等待队列
        = new ConcurrentLinkedQueue<>();

    public void lock() {
        if (state.compareAndSet(0, 1)) return;                           // 快速路径
        Thread cur = Thread.currentThread();
        waiters.add(cur);                                                // 入队
        while (true) {
            if (state.compareAndSet(0, 1)) {                             // 醒来先重试
                waiters.remove(cur);
                return;
            }
            LockSupport.park(this);                                      // ③ 挂起
            // 被 unpark 或中断后回到 while 顶部重新竞争
        }
    }

    public void unlock() {
        if (Thread.currentThread() != stateOwnerGuess()) {              // 持有者校验（简化）
            throw new IllegalMonitorStateException("不是持有者别解锁");
        }
        state.set(0);                                                    // 释放
        Thread head = waiters.peek();                                    // 唤醒队首
        if (head != null) LockSupport.unpark(head);
    }
    private Thread owner = null;   // 简化持有者记录（真实实现用 AQS 的 exclusiveOwnerThread）
    private Thread stateOwnerGuess() { return owner; }

    public static void main(String[] args) throws Exception {
        // 修正版：owner 由 lock 成功时设置（见 3.2 完善版），此处先跑对拍
    }
}
```

### 3.2 完善版（把 owner 记对 + 完整对拍测试）

```java
// MiniAqsLockV2.java —— 最终交付版（建议以这个为准提交 git）
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class MiniAqsLockV2 {
    private final AtomicInteger state = new AtomicInteger(0);
    private final ConcurrentLinkedQueue<Thread> waiters = new ConcurrentLinkedQueue<>();
    private volatile Thread owner;

    public void lock() {
        Thread cur = Thread.currentThread();
        if (state.compareAndSet(0, 1)) { owner = cur; return; }
        waiters.add(cur);
        while (true) {
            if (state.compareAndSet(0, 1)) { owner = cur; waiters.remove(cur); return; }
            LockSupport.park(this);
        }
    }
    public void unlock() {
        if (owner != Thread.currentThread())
            throw new IllegalMonitorStateException("当前线程未持有锁");
        owner = null;
        state.set(0);
        Thread head = waiters.peek();
        if (head != null) LockSupport.unpark(head);
    }

    public static void main(String[] args) throws Exception {   // ★100 线程对拍
        MiniAqsLockV2 lock = new MiniAqsLockV2();
        int[] count = {0};
        Runnable task = () -> {
            for (int i = 0; i < 1000; i++) {
                lock.lock();
                try {
                    int t = count[0];                // 读
                    count[0] = t + 1;                // 写（中间故意不加原子性，靠锁）
                } finally { lock.unlock(); }
            }
        };
        Thread[] threads = new Thread[100];
        for (int i = 0; i < 100; i++) threads[i] = new Thread(task);
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        System.out.println("期望 100000，实际 " + count[0]);   // 必须 100000
    }
}
```

### 3.3 对拍实验（错误版对照，加深理解）

```powershell
cd D:\mywork\springbootai\learning\month02-concurrency
javac MiniAqsLockV2.java; java MiniAqsLockV2
# 预期：恒 100000

# 错误实验①：把 unlock 里的 unpark 删掉 → 程序挂死（丢失唤醒）→ Ctrl+C
# 错误实验②：unlock 不校验 owner → 随便谁都能解锁（互斥被破坏）
# 每个错误实验记录"现象 + 原因"，这两段就是面试讲源码的谈资
```

### 3.4 输出：《一张图讲透 AQS》博客素材整理

```text
结构建议（直接可写）：
  1. 一张总图：state + 双向队列 + park/unpark + 模板方法钩子
  2. 三处语义对照表：ReentrantLock(计数) / Semaphore(许可) / CountDownLatch(倒计时)
  3. 我的手写 50 行（贴 MiniAqsLockV2）+ 两个错误实验
  4. 差距清单：真 AQS 多了什么（SIGNAL 契约/双向队列/重入/共享传播）
  5. 一句收尾："同步器的本质 = 一个状态 + 一条队列 + 一套挂起唤醒协议"
```

## 4. 面试连接

**Q：让你实现一个锁，思路是什么？**
> 三大件：① 一个 volatile 状态（CAS 抢占）② 一个等待队列（失败排队）③ park/unpark 挂起唤醒协议（+防丢失唤醒的 SIGNAL 语义）。然后贴 50 行迷你版——"我写过一个最小实现"是降维打击的回答。

**Q：你的迷你版和真 AQS 差距在哪？**
> 主动列四点：真双向 CLH 队列（O(1) 取消/超时处理）、SIGNAL 唤醒契约（检查条件与 park 之间设标志防丢失唤醒）、重入与公平性支持、共享模式传播唤醒。能讲"差距"说明真读过，能讲"为什么简化"说明真理解。

**Q：怎么验证你写的锁是对的？**
> 三层：功能对拍（100 线程计数恒等于预期）→ 状态验证（jstack 看 park 中的线程）→ 故意破坏（删 unpark 看挂死，验证唤醒路径确实在工作）。

## 5. 今日验收清单

- [ ] MiniAqsLockV2 通过 100 线程对拍（恒 100000）
- [ ] 两个错误实验（删 unpark / 删 owner 校验）各复现一次
- [ ] 《一张图讲透 AQS》博客素材成文
- [ ] 能脱稿讲三大件 + 与真 AQS 的四点差距
- [ ] `git add . && git commit -m "day13: mini aqs lock"`

---
[← Day 12](day12-并发工具三剑客.md) | [本月目录](README.md) | [Day 14 · 第二周复盘 →](day14-第二周复盘输出.md)
