# Day 04 · volatile 深度剖析（可见性 + 禁止重排 + DCL 单例）

> **今日目标**：volatile 是最常被误解的关键字。今天吃透它的两层语义（可见性 + 有序性）、看清它"不保证原子性"的边界，并用 DCL（Double-Checked Locking，双重检查锁）单例把 volatile 的不可替代性讲透。
> **时长**：理论 1.5h / 实操 1.5h / 输出 0.5h
> **今日产出**：DCL 正确版 + 错误版对比记录

## 1. 知识地图

```
volatile 两层语义：
  ① 可见性：写 volatile 变量 → 立即刷主内存；读 → 从主内存拉最新
            （JMM 语义；硬件实现 = 缓存一致性协议 MESI + 失效广播）
  ② 有序性：编译器不得把 volatile 读写与前后的操作重排（插内存屏障）

内存屏障 Memory Barrier（volatile 的实现手段，四类）：
  StoreStore │ 普通写 │ StoreLoad │ volatile写 │ StoreLoad │ 普通写
  规则口诀：
    volatile 写前面  → StoreStore（普通写不许越过它）
    volatile 写后面  → StoreLoad （写不许与后续读重排，最贵的屏障）
    volatile 读后面  → LoadLoad + LoadStore（后续读写不许越过它）
  ★ volatile 读的开销接近普通读；写要插 StoreLoad（相对贵）——"读廉价写较贵"

volatile 明确不管的事（边界感，面试必考）：
  ✗ 不保证原子性：volatile int count; count++ 依然是 3 步，照样丢更新
    （day01 的 AtomicityDemo 加 volatile 修不好——只有 锁 或 AtomicInteger 能修）
  适用场景：一写多读的状态标志、低竞争开关；不适用：计数器/累加器

DCL 单例：volatile 不可替代性的经典证明
  instance = new Singleton() 的三步：
    ① 分配内存（alloc memory）
    ② 调构造器初始化（init object）
    ③ instance 指向内存（assign reference）
  无 volatile：② 和 ③ 可能重排成 ①③②
    → 线程 B 在第一次检查时读到"非 null 但未初始化完"的对象 → 用到半成品！
  有 volatile：③ 后的引用发布保证 ② 对所有读者可见（规则③+⑧）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| volatile | 易失的/易变的 | 可见性+有序性关键字，不保证原子性 |
| MESI | 缓存一致性协议 | CPU 缓存间"写失效/同步"的硬件机制 |
| DCL (Double-Checked Locking) | 双重检查锁 | 单例模式经典写法，必须配 volatile |
| Half-initialized | 半初始化对象 | 重排导致"引用已发布但构造未完成" |
| Read/Write Barrier | 读/写屏障 | volatile 前后插入的同步指令 |

## 3. 动手实操

### 3.1 实验①：给 day01 的 VisibilityDemo 加 volatile（闭环）

```powershell
cd D:\mywork\springbootai\learning\month02-concurrency
# 把 day01 的 VisibilityDemo 里 flag 加上 volatile（改名 VisibilityFixed.java）
javac VisibilityFixed.java; java VisibilityFixed
# 预期：1 秒后 t1 立即退出 → 可见性闭环
# 但把 AtomicityDemo 的 count 加 volatile → 结果照样 <20000（原子性救不了）
```

### 3.2 实验②：DCL 单例正确版 vs 错误版

```java
// SingletonDCL.java —— 正确版（volatile 是灵魂）
public class SingletonDCL {
    private static volatile SingletonDCL instance;      // ★ 去掉 volatile 就是错的

    private SingletonDCL() {
        System.out.println("构造一次，当前线程: " + Thread.currentThread().getName());
        try { Thread.sleep(50); } catch (Exception e) {} // 放大构造耗时，让半初始化更易现形
    }

    public static SingletonDCL getInstance() {
        if (instance == null) {                          // 第一次检查：无锁快速路径
            synchronized (SingletonDCL.class) {
                if (instance == null) {                  // 第二次检查：防重复创建
                    instance = new SingletonDCL();       // 三步，可能 ①③② 重排
                }
            }
        }
        return instance;
    }
    public int value;   // 用来观察半初始化
}
```

```powershell
javac SingletonDCL.java
# 写一个 100 线程并发 getInstance 的测试类，观察"构造一次"是否只打印一次
# 思考题：第一次检查为什么必要？（没有它，每次 getInstance 都进同步块，性能差）
#        第二次检查为什么必要？（等锁期间别人可能已创建）
```

### 3.3 实验③：volatile 读写的开销感知（简易版）

```java
public class VolatileCost {
    static volatile long v;
    static long p;
    public static void main(String[] args) {
        long s = System.nanoTime();
        for (long i = 0; i < 100_000_000L; i++) v = i;    // volatile 写
        System.out.println("volatile write: " + (System.nanoTime() - s) / 1_000_000 + "ms");
        s = System.nanoTime();
        for (long i = 0; i < 100_000_000L; i++) p = i;    // 普通写（会被 JIT 消除，仅感受）
        System.out.println("plain write    : " + (System.nanoTime() - s) / 1_000_000 + "ms");
    }
}
// 严格测速用 JMH（day26 做），今天先建立"volatile 写更贵"的直觉即可
```

### 3.4 观察点

1. volatile 修复可见性的同时"顺手"修了 day03 ReorderDemo 的重排（规则③）
2. DCL 两次检查各自存在的理由（性能 + 防重）
3. volatile 的"能管/不能管"清单表成文

## 4. 面试连接

**Q：volatile 能替代锁吗？**
> 不能一概而论：它只保证可见性+有序性，不保证原子性。状态标志/一写多读场景够用（比锁轻）；计数器/复合操作必须锁或原子类。答"看操作是否原子"比答"能/不能"高一个层次。

**Q：DCL 单例为什么要加 volatile？**
> new 的三步（分配/初始化/赋引用）中间两步可能重排，另一线程可能拿到半初始化对象。volatile 写通过 StoreStore + StoreLoad 屏障禁止这种发布重排。追问"为什么第一次检查不加锁"：无锁快速路径，绝大多数命中时直接返回。

**Q：volatile 底层怎么实现的？**
> 字节码层看不出差别，class 文件里只有 ACC_VOLATILE 标志；JVM 层在读写前后插内存屏障（StoreStore/StoreLoad/LoadLoad/LoadStore）；硬件层靠缓存一致性协议（MESI）+ 屏障指令。三层说全就是满分。

## 5. 今日验收清单

- [ ] VisibilityFixed 闭环 + "volatile 修不了原子性"实验
- [ ] DCL 正确版跑通（构造只打印一次）
- [ ] 能画 new 三步重排图并解释半初始化
- [ ] volatile "能管/不能管"清单成文
- [ ] `git add . && git commit -m "day04: volatile & dcl"`

---
[← Day 03](day03-JMM与happens-before.md) | [本月目录](README.md) | [Day 05 · synchronized 上 →](day05-synchronized上-字节码与monitor.md)
