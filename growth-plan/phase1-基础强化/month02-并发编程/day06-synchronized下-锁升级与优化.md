# Day 06 · synchronized（下）：锁升级演进与锁优化

> **今日目标**：讲清 synchronized 的锁状态演进（含偏向锁的兴与废），吃透锁粗化/锁消除两个 JIT 优化，并能回答"synchronized 和 ReentrantLock 怎么选"（day11 正式对比）。
> **时长**：理论 1.5h / 实操 1.5h / 输出 0.5h
> **今日产出**：锁演进笔记（含 JDK 版本差异）+ 锁优化实验记录

## 1. 知识地图

```
锁升级演进（Mark Word 的变身史）：

  教科书四阶段（JDK8 视角，面试八股版）：
   无锁(001) → 偏向锁(101) → 轻量级锁(00) → 重量级锁(10)
     │           │              │              │
   刚创建     只有一个线程访问    轻微竞争         激烈竞争
              （CAS 记线程 ID）  （CAS 自旋）    （OS 互斥量+挂起）

  ★ 现实修正（你的 JDK25 视角，加分版）：
   JDK15 JEP 374 废弃偏向锁（默认禁用）
   JDK18+ 代码路径移除 → 生产级 JDK 上锁只有三级：
   无锁 → 轻量级锁（CAS 自旋，栈上 Lock Record）→ 重量级锁（OS mutex）
   为什么废：偏向锁撤销要 STW、维护成本高、现代应用（网络服务）
   常多线程交替访问，收益为负——"为了 1% 的场景拖慢 99%"

两级锁的实际行为（JDK25）：
  轻量级：线程栈建 Lock Record → CAS 把 Mark Word 换成指向它的指针
          竞争失败 → 自旋（自适应自旋：上次成功就多转几圈）
  重量级：自旋也抢不到 → 升级 → EntryList 挂起排队（内核态，贵但不再烧 CPU）
  ★ 锁只升不降（对象终生）

JIT 层的锁优化（month01 day22 逃逸分析的延续）：
  锁消除 Lock Elimination
    局部对象上的 synchronized 被直接删掉（逃逸分析判定不共享）
    例：方法内 new StringBuffer().append(...) 的内部锁 = 免费的
  锁粗化 Lock Coarsening
    连续加解锁合并成一次大范围加锁
    例：循环里反复 lock/unlock → 合并成循环外一把
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Biased Locking | 偏向锁 | JDK15+ 废弃（JEP 374），八股要会、现实已无 |
| Lightweight Lock | 轻量级锁 | CAS + 栈上 Lock Record，不进内核 |
| Heavyweight Lock | 重量级锁 | OS 互斥量 + 线程挂起（内核态切换） |
| Adaptive Spinning | 自适应自旋 | 按历史成功率动态调整自旋次数 |
| Lock Coarsening | 锁粗化 | 多次加解锁合并为一次 |
| Lock Elimination | 锁消除 | 不逃逸对象的锁被 JIT 直接删除 |

## 3. 动手实操

### 3.1 实验①：锁消除观察（逃逸分析动手验证）

```java
// LockElim.java —— 拼接用局部 StringBuffer：内部 synchronized 全被消除
public class LockElim {
    static String concat(String a, String b) {
        StringBuffer sb = new StringBuffer();   // StringBuffer 方法全部 synchronized
        sb.append(a).append(b);                 // 理论上有 2 次加锁，实际 0 次
        return sb.toString();
    }
    public static void main(String[] args) {
        long s = System.nanoTime();
        for (int i = 0; i < 10_000_000; i++) concat("a", "b");
        System.out.println("cost=" + (System.nanoTime() - s) / 1_000_000 + "ms");
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month02-concurrency
javac LockElim.java
java -XX:+PrintCompilation LockElim 2> elim.log          # 看 JIT 事件
java -XX:-DoEscapeAnalysis LockElim                       # 关掉逃逸分析对照
# 预期：关闭后更慢（锁真的加了）→ 消除生效的证据
```

### 3.2 实验②：锁粗化（循环内外加锁的差距）

```java
// LockCoarsen.java —— 方法内加锁，调用 1000 万次
public class LockCoarsen {
    static final Object LOCK = new Object();
    static int count = 0;
    static void inc() { synchronized (LOCK) { count++; } }     // 每次进出锁

    public static void main(String[] args) {
        long s = System.nanoTime();
        for (int i = 0; i < 10_000_000; i++) inc();            // 1000 万次加解锁
        System.out.println("per-call lock: " + (System.nanoTime() - s) / 1_000_000 + "ms");

        s = System.nanoTime();
        synchronized (LOCK) {                                   // 粗化后：1 次加锁
            for (int i = 0; i < 10_000_000; i++) count++;
        }
        System.out.println("coarsened     : " + (System.nanoTime() - s) / 1_000_000 + "ms");
    }
}
// 预期：粗化版快数倍 → "锁的粒度设计"直接影响性能的证据
```

### 3.3 实验③：两种竞争程度的感受（为选型做铺垫）

```java
// ContentionLevel.java —— 单线程重复加锁 vs 双线程激烈竞争
public class ContentionLevel {
    static final Object LOCK = new Object();
    static long spin = 0;
    public static void main(String[] args) throws Exception {
        long s = System.nanoTime();
        for (long i = 0; i < 50_000_000L; i++) { synchronized (LOCK) { spin += i; } }
        System.out.println("无竞争: " + (System.nanoTime() - s) / 1_000_000 + "ms");

        Thread t = new Thread(() -> {
            for (long i = 0; i < 50_000_000L; i++) { synchronized (LOCK) { spin += i; } }
        });
        s = System.nanoTime();
        t.start();
        for (long i = 0; i < 50_000_000L; i++) { synchronized (LOCK) { spin -= i; } }
        t.join();
        System.out.println("激烈竞争: " + (System.nanoTime() - s) / 1_000_000 + "ms");
    }
}
// 记录两组数据：竞争让同一逻辑慢 N 倍 → "减少锁竞争"是性能优化的第一杠杆
// （拆分锁/无锁化/CAS——day09/22/27 全是这条线的延伸）
```

### 3.4 观察点

1. JDK25 跑实验时感受不到"偏向锁"阶段——因为已经没了（这是版本演进的活证据）
2. 把"教科书版 vs JDK25 现实版"两套演进图画在一起
3. 锁粗化实验中 per-call 版本慢多少？粗化的代价是什么？（持锁范围变大 → 并发度下降）

## 4. 面试连接

**Q：讲讲 synchronized 的锁升级。**
> 分层答：教科书版（无锁→偏向→轻量→重量 + 各自机制）→ 现实修正（JDK15 JEP 374 废弃偏向锁：撤销要 STW、多线程交替访问下收益为负；生产 JDK 只有无锁/轻量级 CAS 自旋/重量级 OS mutex 三级）→ 收尾（锁只升不降）。主动讲出版本演进 = 实战派信号。

**Q：什么是锁消除和锁粗化？**
> 都是 JIT 优化：锁消除基于逃逸分析，删掉不共享对象的锁（如局部 StringBuffer）；锁粗化把相邻加解锁合并（如循环内外移）。反例意识：粗化提高单次持锁范围，可能降低并发度，别为粗化而粗化。

**Q：JDK15 为什么要移除偏向锁？**
> 三个理由：撤销需要 STW（批量再偏向机制复杂）；现代服务端应用几乎都是多线程交替访问（无线程"偏向"红利）；维护成本拖累 HotSpot 演进。说得出 JEP 374 和"撤销成本"两个关键词即可封神。

## 5. 今日验收清单

- [ ] 两套锁演进图（教科书版 + JDK25 现实版）都画得出
- [ ] LockElim / LockCoarsen / ContentionLevel 三实验跑通
- [ ] 记录竞争前后耗时差（性能优化第一杠杆的证据）
- [ ] 能说出移除偏向锁的两个理由
- [ ] `git add . && git commit -m "day06: lock escalation & jit lock opts"`

---
[← Day 05](day05-synchronized上-字节码与monitor.md) | [本月目录](README.md) | [Day 07 · 第一周复盘 →](day07-第一周复盘输出.md)
