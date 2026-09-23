# Day 01 · 并发全景与环境搭建

> **今日目标**：建立本月学习仓库；亲手复现并发两大经典 bug——可见性破坏与原子性破坏，用"痛感"开启本月。并发三大特性是全月的总纲，今天先混个脸熟。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：学习仓库 + 两个"翻车"演示程序的复现记录

## 1. 知识地图

```
为什么需要并发？（三个动机）
  吞吐：多核 CPU 不能闲着（8 核只跑 1 线程 = 浪费 87.5%）
  响应：一个慢任务不能拖死整个服务（异步化）
  成本：一个进程扛不住，线程比进程轻

并发的代价 = 三大特性被破坏（本月主线，先认脸）：
  ┌────────────────────────────────────────────────┐
  │ 原子性 Atomicity                                │
  │   一个操作不可分割。count++ 实际是 3 步：        │
  │   读内存 → 加 1 → 写回。两线程交叉 → 丢失更新    │
  ├────────────────────────────────────────────────┤
  │ 可见性 Visibility                               │
  │   线程改了共享变量，别的线程可能永远看不到       │
  │   （每个线程有工作缓存，month01 的 JMM 雏形）    │
  ├────────────────────────────────────────────────┤
  │ 有序性 Ordering                                 │
  │   编译器/CPU 会重排指令（day03 细讲）           │
  │   "先初始化再发布"可能变成"先发布再初始化"       │
  └────────────────────────────────────────────────┘
对应的三大武器（后续每天一个）：
  原子性 → 锁/CAS   可见性 → volatile/happens-before   有序性 → volatile/final

并发 Concurrency vs 并行 Parallelism（一句话区分）：
  并发 = 交替使用 CPU（单核也能并发）；并行 = 同时使用多核
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Concurrency / Parallelism | 并发/并行 | 交替执行 / 同时执行 |
| Atomicity | 原子性 | 操作不可分割，count++ 不是原子的 |
| Visibility | 可见性 | 一个线程的写入对另一个线程立即可见 |
| Ordering | 有序性 | 禁止指令重排（Reordering） |
| Race Condition | 竞态条件 | 结果取决于线程执行时序的缺陷 |
| Shared Variable | 共享变量 | 多线程都能访问的变量（static/堆对象） |

## 3. 动手实操

### 3.1 建仓库（10 分钟）

```powershell
mkdir D:\mywork\springbootai\learning\month02-concurrency; cd D:\mywork\springbootai\learning\month02-concurrency
git init
# 复制 month01 的 JMH jar 备用（day26 用）
mkdir day26 -Force
Copy-Item ..\month01-jvm\day23\jmh-*.jar day26\
Copy-Item ..\month01-jvm\day23\jopt-simple.jar day26; Copy-Item ..\month01-jvm\day23\commons-math3.jar day26
```

### 3.2 复现①：可见性破坏（今天的主角，能"卡住"的程序）

```java
// VisibilityDemo.java —— 不加 volatile，t1 可能永远看不到 flag 变化
public class VisibilityDemo {
    static boolean flag = true;                 // 共享变量，故意不加 volatile

    public static void main(String[] args) throws Exception {
        new Thread(() -> {                       // t1：自旋等待 flag 变 false
            while (flag) { /* 自旋 */ }
            System.out.println("t1: 我看到了 false，退出");
        }, "t1").start();

        Thread.sleep(1000);                      // 给 t1 时间进入自旋（JIT 编译）
        flag = false;
        System.out.println("main: flag 已改为 false，但 t1 可能永远看不到...");
    }
}
```

```powershell
javac VisibilityDemo.java
java VisibilityDemo
# 预期：t1 死循环停不下来（main 已改 flag）→ Ctrl+C 结束
# 对照组 1：加上 volatile 修饰 flag → 立即退出
# 对照组 2：java -Xint VisibilityDemo（纯解释执行）→ 大概率也退出
#          （解释器每次真读内存；JIT 才把 flag 缓存优化掉——这就是"JIT 参与了 bug"）
# 结论写进笔记：可见性问题的完整链条 = 工作缓存 + JIT 优化
```

### 3.3 复现②：原子性破坏（丢失更新）

```java
// AtomicityDemo.java —— 期望 20000，实际大概率更少
public class AtomicityDemo {
    static int count = 0;

    public static void main(String[] args) throws Exception {
        Runnable task = () -> {
            for (int i = 0; i < 10_000; i++) count++;   // 三步：读→加→写
        };
        Thread t1 = new Thread(task), t2 = new Thread(task);
        t1.start(); t2.start();
        t1.join(); t2.join();
        System.out.println("期望 20000，实际 " + count);
    }
}
```

```powershell
javac AtomicityDemo.java
java AtomicityDemo
java AtomicityDemo
java AtomicityDemo
# 跑三次记录三个结果（如 17842 / 19350 / 20000）→ "时序决定结果"就是竞态条件
```

### 3.4 观察点

1. VisibilityDemo 加 volatile 后为什么就好了？（day04 揭晓，今天先记现象）
2. AtomicityDemo 把 count++ 换成 `synchronized { count++; }` → 恒 20000（day05 细讲）
3. 这两个 bug 的"修复武器"对应表记进笔记（本月路线图）

## 4. 面试连接

**Q：并发和并行的区别？**
> 并发是"交替使用 CPU"（单核可并发，靠时间片轮转），并行是"同时使用多核"。Java 线程在多核上既能并发也能并行；说"并发关注结构、并行关注执行"是加分句。

**Q：并发三大特性是什么？各举一个被破坏的例子。**
> 原子性（count++ 两线程丢失更新）、可见性（无 volatile 标志位死循环）、有序性（DCL 单例半初始化对象逸出，day04 细讲）。每个例子都说"我亲手复现过"+ 一句现象，立刻和背书的候选人拉开差距。

## 5. 今日验收清单

- [ ] 学习仓库建好，JMH jar 就位
- [ ] VisibilityDemo 复现成功（含 -Xint 对照）
- [ ] AtomicityDemo 跑三次记录三个不同结果
- [ ] 三大特性"破坏例子 → 修复武器"对照表成文
- [ ] `git add . && git commit -m "day01: visibility & atomicity demos"`

---
[本月目录](README.md) | [Day 02 · 线程基础与生命周期 →](day02-线程基础与生命周期.md)
