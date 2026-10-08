# Day 10 · 虚拟线程入门：专车司机 vs 共享单车

> **今日目标**：搞懂虚拟线程为什么能开百万个；平台线程到底贵在哪；pinning（钉住）这个大坑的前世今生（含 JDK 24 的修复）。
> **时长**：理论 1.5h / 实操 1h / 输出 0.5h
> **今日产出**：千任务对比实验（平台线程池 vs 虚拟线程）+ pinning 笔记

## 1. 知识地图（先讲人话）

```
平台线程（Platform Thread，普通线程）为什么贵：
  ① 一个线程默认 1MB 栈内存（预留的，哪怕用不上）
  ② 由操作系统内核调度——创建/销毁/切换都要"惊动"内核，贵
  ③ 上下文切换：内核保存/恢复寄存器，微秒级，线程多了白烧 CPU
  结论：单机几千个就到头了 → 所以需要"池子"省着用

虚拟线程（Virtual Thread，JDK 21 正式）为什么便宜：
  ① 栈在堆上，用多少占多少（几十 KB 起步，像 lazy 分配）
  ② 由 JVM 自己调度（用户态），不惊动内核
  ③ 关键魔法：阻塞时自动"让座"（unmount）——
    线程在等 IO 时，把自己从载体线程上卸下来，让位给别的虚拟线程

类比一图流：
  平台线程 = 专车司机（一对一，司机等人时车和人都干耗着）
  载体线程 = 出租车公司就那么几辆车（≈ CPU 核数）
  虚拟线程 = 共享单车乘客（不骑了就下车，车接着服务别人）
  阻塞 = 乘客停下来等红绿灯 → 下车让车走 → 绿了再约下一辆

pinning（钉住）坑——必须讲清版本历史（面试加分点）：
  JDK 21~23：虚拟线程在 synchronized 块里阻塞时会"钉死"在
    载体线程上（不能让座），载体被占住 → 池化的载体耗尽 → 卡死
    对策：把 synchronized 换成 ReentrantLock
  JDK 24（JEP 491）：synchronized 钉住问题已修复！JDK 25 直接受益
    但要会讲这段历史——说明你不仅会用还知道演进
  依然存在的坑：native 方法（JNI）里阻塞仍会钉住（比如某些老驱动）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 大白话 |
|------|------|--------|
| Virtual Thread | 虚拟线程 | JVM 用户态轻量线程，百万级并发的主力 |
| Carrier Thread | 载体线程 | 真正跑在 CPU 上的"出租车"（≈ 核数个） |
| Mount / Unmount | 挂载 / 卸载 | 上车干活 / 等待时下车让座 |
| Pinning | 钉住 | 没法下车（synchronized 旧版/JNI），堵住车厢 |
| Structured Concurrency | 结构化并发 | 任务树统一管理取消和错误（JDK 25 预览，day12 呼应） |

## 3. 动手实操

### 3.1 千任务对比实验（今天的重头戏）

```java
import java.time.Duration;
import java.util.concurrent.*;

public class VirtualVsPool {
    // 模拟工具调用：睡 200ms（纯等待，IO 型任务的缩影）
    static Runnable tool() { return () -> { try { Thread.sleep(200); } catch (Exception ignored) {} }; }

    public static void main(String[] args) throws Exception {
        int n = 1000;
        // 方案 A：平台线程池 200 线程（夸张地开大池子）
        try (var p = Executors.newFixedThreadPool(200)) {
            long t = System.nanoTime();
            for (int i = 0; i < n; i++) p.submit(tool());
            p.shutdown(); p.awaitTermination(1, TimeUnit.MINUTES);
            System.out.printf("平台线程池200: %d ms%n", (System.nanoTime()-t)/1_000_000);
        }
        // 方案 B：虚拟线程，每任务一线程（1000 个全开）
        try (var v = Executors.newVirtualThreadPerTaskExecutor()) {
            long t = System.nanoTime();
            for (int i = 0; i < n; i++) v.submit(tool());
            v.shutdown(); v.awaitTermination(1, TimeUnit.MINUTES);
            System.out.printf("虚拟线程1000: %d ms%n", (System.nanoTime()-t)/1_000_000);
        }
    }
}
// 预期（8 核机器实测量级）：A ≈ 1000ms（200 线程分 5 批 × 200ms）
//                        B ≈ 200ms（1000 个"同时"等，一批完成）
// 内存对比另测：虚拟线程千个 ≈ 几十 MB；平台线程 200 个 ≈ 200MB 栈预留
```

### 3.2 pinning 历史小实验（JDK 25 上验证"已修复"）

```java
// JDK 21~23 上这段会钉住载体（可加 -Djdk.tracePinnedThreads=full 观察）
// JDK 25 上应正常让座——跑通并记录版本行为差异
Thread.startVirtual(() -> {
    synchronized (VirtualVsPool.class) {          // 换成 ReentrantLock 是老版本正解
        try { Thread.sleep(100); } catch (Exception ignored) {}
    }
});
```

### 3.3 什么时候别用虚拟线程（反直觉清单）

```
① CPU 密集任务：虚拟线程不省 CPU，只是省"等待的座位"
   算法再快也靠真核，虚拟线程只会增加切换开销
② 需要池化复用重资源的场景：虚拟线程"用完即弃"哲学下，
   连接池里的连接还是要池化（虚拟的是线程，不是数据库连接）
③ ThreadLocal 滥用：百万虚拟线程 × 大 ThreadLocal = 内存炸弹
```

### 3.4 思考题

为什么说"虚拟线程杀死了响应式编程的复杂度"？ CompletableFuture 的 thenCompose 链和 `virtualThread { io(); io2(); }` 直写风格，哪个更好维护？（提示：直写栈式代码可读性/调试性完胜回调链——但 CF 在"超时/组合"编排上仍有价值，day12 讲怎么搭配）

## 4. 面试连接

**Q：虚拟线程的原理？和平台线程的区别？**
> 平台线程 1:1 绑内核线程，1MB 栈、内核调度，几千个就到顶；虚拟线程是 JVM 用户态的，栈在堆上按需分配，阻塞时 unmount 让出载体线程（数量≈核数），所以百万个也没压力。我用 1000 个 200ms 的模拟工具调用实测：200 线程池要 1 秒，虚拟线程一批 200ms 完事。

**Q：synchronized 的 pinning 问题是什么？现在还存在吗？**
> JDK 21~23 里虚拟线程在 synchronized 块内阻塞会钉死在载体线程上，导致载体耗尽。JDK 24 的 JEP 491 修复了这个问题，我们 JDK 25 直接受益，但历史代码规范仍是 IO 场景优先 ReentrantLock——因为 JNI 阻塞仍会钉住。能讲出版本演进说明我真踩过/真读过。

## 5. 今日验收清单

- [ ] 千任务对比实验跑通，记录两个数字和内存对比
- [ ] pinning 历史实验完成（JDK 25 验证已修复）
- [ ] "什么时候别用虚拟线程"三条能举例
- [ ] `git add . && git commit -m "day16-10: virtual-threads"`
- [ ] 笔记：专车司机 vs 共享单车类比图（含 mount/unmount）

---
[← Day 09](day09-线程池调参实战.md) | [本月目录](README.md) | [Day 11 · 虚拟线程并行工具调用 →](day11-虚拟线程并行工具调用.md)
