# Day 09 · CAS 与原子类（无锁并发的基石）

> **今日目标**：CAS（Compare And Swap，比较并交换）是整个 JUC 的发动机。今天手写 CAS 自旋锁、复现 ABA 问题并解决、实测 LongAdder vs AtomicLong 的高竞争差距——无锁化不是玄学，是今天就能写出来的代码。
> **时长**：理论 1.5h / 实操 1.5h / 输出 0.5h
> **今日产出**：CAS 自旋锁 + ABA 复现/解决 + LongAdder 压测对比

## 1. 知识地图

```
CAS：无锁并发的心脏
  三个操作数：内存值 V、期望值 E、新值 N
  语义：if (V == E) { V = N; return true } else { return false }
  硬件层：x86 的 lock cmpxchg 指令（lock 前缀保证缓存行独占 + 禁止重排）
  Java 层：sun.misc.Unsafe.compareAndSwapXxx（JDK9 后 VarHandle.compareAndSet）
  特征：失败不挂起，自旋重试（乐观并发 vs 悲观锁）

原子类家族（java.util.concurrent.atomic）：
  AtomicInteger / AtomicLong      计数器（getAndIncrement 内部 CAS 自旋）
  AtomicBoolean / AtomicReference  标志位 / 对象引用 CAS
  AtomicStampedReference           带版本戳（解决 ABA）
  LongAdder                        高竞争计数器（热点分离）

ABA 问题：
  时序：线程1 读到 A → 线程2 把 A 改成 B 再改回 A → 线程1 CAS(A→C) 成功
  看似成功，其实"中间发生过什么"被抹掉了
  有害场景：无锁栈（链表）
    栈顶 A，线程1 准备 CAS(A→B)
    线程2 弹出 A、B，又压回 A、B（A 又回来了但链已经变了）
    线程1 CAS 成功 → 栈里出现"幽灵节点"，链表结构损坏
  解决：AtomicStampedReference（值+版本戳一起 CAS）/ AtomicMarkableReference

LongAdder 原理（Striped64，热点分离思想）：
  AtomicLong：所有线程 CAS 同一个 value → 高竞争下自旋风暴
  LongAdder：base + Cell[] 数组
    无竞争 → 直接改 base
    有竞争 → 各线程 hash 到不同 Cell（每格独立 CAS）→ 分散热点
    sum() = base + ΣCells（无锁快照，弱一致！）
  口诀：低并发 AtomicLong，高并发 LongAdder；要精确瞬时值 AtomicLong
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| CAS (Compare And Swap) | 比较并交换 | V/E/N 三元操作，硬件级原子性 |
| Optimistic / Pessimistic | 乐观/悲观并发 | CAS 自旋 vs 加锁挂起 |
| ABA Problem | ABA 问题 | 值 A→B→A，CAS 误判未变 |
| Stamped Reference | 带戳引用 | 版本号机制防 ABA |
| LongAdder / Striped64 | 长加法器 | base+Cell 分段热点分离 |
| Spinning | 自旋 | 失败循环重试，适合短临界区 |

## 3. 动手实操

### 3.1 实验①：手写 CAS 自旋锁（10 行核心）

```java
// SpinLock.java —— 用 AtomicReference 实现的不可重入自旋锁
import java.util.concurrent.atomic.AtomicReference;

public class SpinLock {
    private final AtomicReference<Thread> owner = new AtomicReference<>();

    public void lock() {
        Thread cur = Thread.currentThread();
        while (!owner.compareAndSet(null, cur)) {   // CAS 失败就自旋，不挂起
            // 自旋：适合临界区极短的场景；长临界区 = 烧 CPU
        }
    }
    public void unlock() {
        Thread cur = Thread.currentThread();
        owner.compareAndSet(cur, null);              // 只允许持有者解锁
    }

    public static void main(String[] args) throws Exception {
        SpinLock lock = new SpinLock();
        int[] count = {0};
        Runnable task = () -> {
            for (int i = 0; i < 100_000; i++) {
                lock.lock();
                try { count[0]++; } finally { lock.unlock(); }
            }
        };
        Thread t1 = new Thread(task), t2 = new Thread(task);
        t1.start(); t2.start(); t1.join(); t2.join();
        System.out.println("期望 200000，实际 " + count[0]);   // 200000 ✓
    }
}
```

### 3.2 实验②：ABA 复现与解决

```java
// ABADemo.java —— AtomicStampedReference 用版本戳防 ABA
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicStampedReference;

public class ABADemo {
    public static void main(String[] args) throws Exception {
        // 无戳版：ABA 现形
        AtomicReference<Integer> ref = new AtomicReference<>(100);
        Thread t1 = new Thread(() -> {
            Integer expect = ref.get();
            try { Thread.sleep(1000); } catch (Exception e) {}    // 给 t2 制造 ABA 的窗口
            System.out.println("t1 CAS(100→200): " + ref.compareAndSet(expect, 200));
        });
        Thread t2 = new Thread(() -> {
            ref.compareAndSet(100, 999);      // A → B
            ref.compareAndSet(999, 100);      // B → A（值回来了！）
            System.out.println("t2 完成一次 ABA");
        });
        t1.start(); t2.start(); t1.join();

        // 带戳版：ABA 被拦截
        AtomicStampedReference<Integer> stamped = new AtomicStampedReference<>(100, 0);
        int[] stampHolder = new int[1];
        Integer cur = stamped.get(stampHolder);
        stamped.compareAndSet(cur, 999, stampHolder[0], stampHolder[0] + 1);   // 版本 0→1
        stamped.compareAndSet(999, 100, stampHolder[0], stampHolder[0] + 1);   // 版本 1→2（值回来了，版本回不去）
        System.out.println("带戳 CAS(旧版本0) 会失败: " +
            stamped.compareAndSet(100, 200, 0, 1));       // false！版本对不上
    }
}
```

### 3.3 实验③：LongAdder vs AtomicLong 高竞争对比

```java
// AdderCompare.java —— 8 线程 × 1000 万次累加
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class AdderCompare {
    public static void main(String[] args) throws Exception {
        AtomicLong atomic = new AtomicLong();
        LongAdder adder = new LongAdder();
        int threads = 8, loops = 10_000_000;

        long s = System.nanoTime();
        run(threads, () -> { for (int i = 0; i < loops; i++) atomic.incrementAndGet(); });
        System.out.println("AtomicLong : " + (System.nanoTime() - s) / 1_000_000 + "ms  = " + atomic.get());

        s = System.nanoTime();
        run(threads, () -> { for (int i = 0; i < loops; i++) adder.increment(); });
        System.out.println("LongAdder  : " + (System.nanoTime() - s) / 1_000_000 + "ms  = " + adder.sum());
    }
    static void run(int n, Runnable task) throws InterruptedException {
        Thread[] ts = new Thread[n];
        for (int i = 0; i < n; i++) ts[i] = new Thread(task);
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();
    }
}
// 预期：高核数机器上 LongAdder 快 2~8 倍（竞争越激烈差距越大）
// 思考：单线程下谁快？（AtomicLong，Cell 分散有额外开销）
```

### 3.4 观察点

1. SpinLock 改成可重入版需要什么？（记录 owner 线程 + 计数器——试写一下，这就是 day13 迷你 AQS 的雏形）
2. LongAdder 的 sum() 为什么是弱一致的？（无锁快照，读时有人还在写 Cell）
3. CAS 自旋的适用边界：临界区短 + 竞争低；反之锁更优

## 4. 面试连接

**Q：CAS 的原理？有什么问题？**
> 三操作数 V/E/N，硬件级原子（lock cmpxchg）。三个问题：① ABA（值变回原值，用版本戳解决）② 自旋开销（高竞争烧 CPU，长临界区改用锁）③ 只能保证单个变量的原子性（复合变量用 AtomicReference 封装对象或加锁）。

**Q：LongAdder 为什么比 AtomicLong 快？**
> 热点分离：AtomicLong 所有线程 CAS 同一个值，高竞争下大量自旋重试；LongAdder 把写压力分散到 base+Cell[] 数组，各写各的格，求和时聚合。代价是 sum() 弱一致、内存占用更高。选型：统计类高并发写用 LongAdder，需要精确单值/低并发用 AtomicLong。

**Q：ABA 在什么业务里真的有害？**
> 依赖"中间状态不变"假设的无锁结构：无锁栈/队列（节点指针 ABA 导致链断裂）、资金系统（余额 A→B→A 但流水不同）。业务上"只要最终值对就行"的场景（计数器）ABA 无害。

## 5. 今日验收清单

- [ ] SpinLock 跑通并尝试改造可重入
- [ ] ABADemo 复现成功，讲清带戳版本为什么拦截
- [ ] AdderCompare 记录真实倍数
- [ ] CAS 三问题（ABA/自旋/单变量）能脱口而出
- [ ] `git add . && git commit -m "day09: cas & atomic & longadder"`

---
[← Day 08](day08-wait-notify与LockSupport.md) | [本月目录](README.md) | [Day 10 · AQS 总体设计 →](day10-AQS总体设计.md)
