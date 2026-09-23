# Day 22 · 读写锁与 StampedLock（读多写少的两代方案）

> **今日目标**：缓存类场景"读多写少"，一把互斥锁太浪费。今天先玩 ReentrantReadWriteLock（读写分离 + 锁降级），再上 StampedLock 的乐观读（无锁读 + 版本校验），最后压测三种方案拍板选型。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：RWCache + LockDowngrade + StampedDemo + 三方案压测数据

## 1. 知识地图

```
ReentrantReadWriteLock（读写分离，一个 AQS 拆成两半用）：

  state（一个 int 两个用途）：高 16 位 = 读锁计数   低 16 位 = 写锁计数
  ┌─────────────────────────────────────────────┐
  │ 规则矩阵：                                    │
  │   读-读  √ 共存（大家都能看）                  │
  │   读-写  × 互斥（写着别看，看着别写）           │
  │   写-写  × 互斥                               │
  └─────────────────────────────────────────────┘
  适合：读多写少（读写比 > 10:1 才明显赚）
  注意：同线程读到写要小心——"升级"不支持（死锁），"降级"支持且有标准姿势

  锁降级标准流程（写 → 读）：
    w.lock();  改数据;  r.lock();  ← 先拿读锁
    w.unlock();                    ← 再放写锁（此刻其他读可进，数据已被我改）
    用数据;  r.unlock();

StampedLock（JDK8+，三种模式，性能再上一档）：
  ① 写：long stamp = sl.writeLock(); ... sl.unlockWrite(stamp);
  ② 悲观读：long stamp = sl.readLock();（同读写锁的读）
  ③ 乐观读（灵魂）：不加锁！
       long stamp = sl.tryOptimisticRead();   ← 拿版本号（不阻塞）
       读数据 x, y;                            ← 裸读
       if (!sl.validate(stamp)) {              ← 校验期间有没有写发生
           stamp = sl.readLock();              ← 失败 → 退化为悲观读重读
           x, y 重读; sl.unlockRead(stamp);
       }
  原理一句话：版本号 + 校验（PBDA），读路径全程无 CAS 无挂起
  ⚠ StampedLock 不可重入、不支持 Condition；别在乐观读里调用会阻塞的方法
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| ReentrantReadWriteLock | 可重入读写锁 | 一把锁拆读写两种视图 |
| Lock Downgrade | 锁降级 | 写锁→读锁，保数据一致的标准姿势 |
| Optimistic Read | 乐观读 | 拿版本号裸读，validate 校验 |
| Stamp | 邮戳/版本号 | StampedLock 所有 API 的句柄 |
| PBDA | 乐观并发控制 | 无锁读的算法家族 |
| Read-mostly | 读多写少 | 读写锁/乐观读的适用前提 |

## 3. 动手实操

### 3.1 实验①：读写锁缓存（读读并存的直观感受）

```java
// RWCache.java —— 10 读线程 + 1 写线程，读写锁 vs synchronized
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

public class RWCache {
    private final Map<String, Integer> data = new HashMap<>();
    private final ReadWriteLock rw = new ReentrantReadWriteLock();
    private final Lock r = rw.readLock(), w = rw.writeLock();

    public Integer get(String k) {
        r.lock();                                    // 读锁：10 个读线程互不阻塞
        try { simulateRead(); return data.get(k); }
        finally { r.unlock(); }
    }
    public void put(String k, int v) {
        w.lock();                                    // 写锁：独占
        try { data.put(k, v); } finally { w.unlock(); }
    }
    static void simulateRead() {                     // 模拟"读处理"耗时
        try { Thread.sleep(1); } catch (Exception e) {}
    }

    public static void main(String[] args) throws Exception {
        RWCache cache = new RWCache();
        long begin = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(11);
        for (int i = 0; i < 1000; i++) pool.execute(() -> cache.get("k"));
        pool.execute(() -> {                          // 期间穿插写
            for (int i = 0; i < 10; i++) { cache.put("k", i); try { Thread.sleep(5); } catch (Exception e) {} }
        });
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);
        System.out.println("读写锁版耗时: " + (System.currentTimeMillis() - begin) + "ms");
        // 对照组：把 get/put 的 r/w 换成同一把 synchronized 锁再跑，对比耗时
    }
}
// 预期：读写锁明显快于全互斥（读读并行）；写越少差距越大
```

### 3.2 实验②：锁降级（为什么顺序不能反）

```java
// LockDowngrade.java —— 写完立刻保证自己读到的是"我写的值"且不阻塞其他读
import java.util.concurrent.locks.*;

public class LockDowngrade {
    static final ReentrantReadWriteLock RW = new ReentrantReadWriteLock();
    static int data = 0;

    public static void main(String[] args) {
        new Thread(() -> {
            RW.writeLock().lock();                    // ① 拿写锁
            data = 42;                                // ② 改数据
            RW.readLock().lock();                     // ③ ★先拿读锁（顺序关键！）
            System.out.println("降级线程: 改完并持有读锁, data=" + data);
            RW.writeLock().unlock();                  // ④ 放写锁（其他读线程放行）
            System.out.println("降级线程: 仍持有读锁, data=" + data);   // ⑤ 保证原子可见
            RW.readLock().unlock();
        }).start();
    }
}
// 反例（升级）：先 r.lock() 再 w.lock() → 永远等不到（自己持有读锁，写锁获取永不满足）
// → 直接死锁。这是读写锁最著名的坑，面试主动讲出来是亮点
```

### 3.3 实验③：StampedLock 乐观读（失败退化重读）

```java
// StampedDemo.java —— 二维坐标点，读远多于写
import java.util.concurrent.locks.StampedLock;

public class StampedDemo {
    private double x = 0, y = 0;
    private final StampedLock sl = new StampedLock();

    void move(double dx, double dy) {                 // 写
        long s = sl.writeLock();
        try { x += dx; y += dy; } finally { sl.unlockWrite(s); }
    }
    double distance() {                               // 乐观读（无锁）
        long stamp = sl.tryOptimisticRead();          // ① 拿版本号（不阻塞任何人）
        double cx = x, cy = y;                        // ② 裸读
        if (!sl.validate(stamp)) {                    // ③ 校验：读期间有写？重读
            stamp = sl.readLock();                    // ④ 退化悲观读
            try { cx = x; cy = y; } finally { sl.unlockRead(stamp); }
        }
        return Math.sqrt(cx * cx + cy * cy);
    }

    public static void main(String[] args) throws Exception {
        StampedDemo p = new StampedDemo();
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 1_000_000; i++) p.move(1, 1);
        });
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 1_000_000; i++) p.distance();    // 与写完全并发
        });
        writer.start(); reader.start();
        writer.join(); reader.join();
        System.out.println("最终 distance=" + p.distance() + "（无异常无脏读即正确）");
    }
}
// 注意红线：乐观读代码块里绝不能调用阻塞方法（IO/lock/park）——版本号会失效语义混乱
```

### 3.4 三方案压测与选型表

```powershell
cd D:\mywork\springbootai\learning\month02-concurrency
javac RWCache.java;     java RWCache      # 记录读写锁耗时
javac LockDowngrade.java; java LockDowngrade
javac StampedDemo.java; java StampedDemo
# 对照实验：把 RWCache 的读写锁整体换成 synchronized，跑同样负载对比
```

| 方案 | 读-读 | 读-写 | 写-写 | 适用 |
|------|-------|-------|-------|------|
| synchronized | 互斥 | 互斥 | 互斥 | 读写均衡、代码简单 |
| ReentrantReadWriteLock | 共存 | 互斥 | 互斥 | 读多写少（>10:1）、需 Condition/重入 |
| StampedLock | 乐观读近乎无锁 | 校验退化 | 互斥 | 读极多、追求极致吞吐、无可重入需求 |

## 4. 面试连接

**Q：读写锁适合什么场景？有什么坑？**
> 读多写少（读写比 10:1 以上）才有收益，如本地缓存元数据、配置热更。两个坑：① 不支持锁升级（读→写会死锁，因为自己持有读锁其他写进不来）；② 写饥饿在旧版本存在（公平模式下写等待读清空，非公平下读连环——可以提"写锁可插队 vs 读锁连环"）。坑之后给标准解法：锁降级流程（先读后放写）。

**Q：StampedLock 的乐观读为什么快？和 volatile 什么关系？**
> 读路径只有一次版本号读取和一次 validate（都是 volatile 读），没有 CAS、没有挂起唤醒；数据被读到本地栈后即使其他线程改了也不影响本次读的一致性（validate 失败就重读）。比 volatile 字段更强的点：可以原子读"多个字段的一致快照"（x、y 成对），volatile 做不到多字段原子性。

**Q：缓存怎么防击穿/雪崩？（读写锁落地的延伸）**
> 结合 day19 CacheDemo（FutureTask 只算一次防击穿）+ 本天读写锁（读读并行）+ 过期打散（雪崩）。三层答案配三段自己的代码，这就是"读过源码+写过实现"的完整证据链。

## 5. 今日验收清单

- [ ] RWCache 跑通并记录与 synchronized 对照数据
- [ ] LockDowngrade 跑通 + 说清"升级为什么死锁"
- [ ] StampedDemo 跑通（100 万读+100 万写无异常）
- [ ] 三方案选型表默写
- [ ] `git add . && git commit -m "day22: rwlock & stampedlock"`

---
[← Day 21](day21-第三周复盘输出.md) | [本月目录](README.md) | [Day 23 · 并发设计模式 →](day23-并发设计模式.md)
