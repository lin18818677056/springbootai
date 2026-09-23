# Day 28 · 虚拟线程与 JDK 新特性（JVM 的近两年大变局）

> **今日目标**：虚拟线程（Virtual Threads）是 JDK21 最大的运行时变革。今天理解"挂载/卸载"模型，实测 1 万 IO 任务的对比，搞清 pinning 陷阱与版本演进——面试新贵知识点。
> **时长**：理论 1.5h / 实操 1.5h / 输出 0.5h
> **今日产出**：平台线程池 vs 虚拟线程对比数据 + 场景选型卡

## 1. 知识地图

```
虚拟线程 = JVM 管理的"轻量线程"，把"阻塞"变成"让出"

平台线程 Platform Thread        虚拟线程 Virtual Thread
──────────────────────        ──────────────────────────
1:1 映射 OS 线程                M:N 映射少量载体线程
栈 = 1MB（-Xss）               栈在堆上，按需增长（KB 级起步）
创建/切换 = OS 代价             创建/切换 = JVM 内部操作
几千个就到顶                    轻松百万级

挂载模型（Mount / Unmount）：
  载体线程 Carrier Thread（普通 OS 线程，默认 CPU 核数个）
     ┌─ 虚拟线程A ─ 遇到阻塞 IO → unmount（把栈存回堆）让出载体
     ├─ 虚拟线程B ← mount（从堆恢复栈）顶上继续跑
     └─ 虚拟线程C ...
  效果：阻塞不再占 OS 线程 → "写同步代码，得异步吞吐"

Pinning 钉住陷阱（版本演进，必考）：
  JDK21：synchronized 块内阻塞 → 虚拟线程被"钉"在载体上
         （无法 unmount，退化成平台线程行为，吞吐崩塌）
         诊断：-Djdk.tracePinnedThreads=full
  JDK24：JEP 491 修复！synchronized 不再 pinning（你的 JDK25 已免疫）
  仍会 pinning：native 方法/外部函数（JNI/FFM）内的阻塞
  迁移提示：老代码 Netty/连接池在 JDK21 的坑，升级 JDK24+ 自愈大半

配套新特性速览（JDK17→25 主线，和你直接相关）：
  JDK17 Records/Sealed 类           JDK21 虚拟线程/分代 ZGC(实验)/字符串模板(预览)
  JDK22~23 分代 ZGC 默认            JDK24 Stream Gatherers/移除非分代 ZGC
  JDK25 紧凑对象头(实验)/结构化并发稳定化
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Virtual Thread | 虚拟线程 | JDK21 正式（JEP 444），轻量并发单元 |
| Carrier Thread | 载体线程 | 承载虚拟线程的 OS 线程（ForkJoinPool） |
| Mount / Unmount | 挂载/卸载 | 阻塞时虚拟线程让出载体的机制 |
| Continuation | 续体 | 栈状态的保存/恢复原语（虚拟线程的底座） |
| Pinning | 钉住 | 无法卸载（JDK21 synchronized/native） |
| Structured Concurrency | 结构化并发 | 任务生命周期成组管理（JDK25 趋稳） |

## 3. 动手实操

### 3.1 实验①：1 万个 IO 任务，两种跑法（今天的重头戏）

```java
// ThreadCompare.java —— 同样的活，感受代差
public class ThreadCompare {
    static final int TASKS = 10_000;

    public static void main(String[] args) throws Exception {
        // A：传统平台线程池（200 线程）
        long start = System.currentTimeMillis();
        try (var exec = java.util.concurrent.Executors.newFixedThreadPool(200)) {
            for (int i = 0; i < TASKS; i++) {
                exec.submit(() -> { Thread.sleep(100); return null; });   // 模拟 IO
            }
        }   // close() 自动等待全部完成（JDK19+ ExecutorService 可自动关闭）
        System.out.println("A platform pool(200): " + (System.currentTimeMillis() - start) + " ms");

        // B：虚拟线程（每任务一线程）
        start = System.currentTimeMillis();
        try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < TASKS; i++) {
                exec.submit(() -> { Thread.sleep(100); return null; });
            }
        }
        System.out.println("B virtual(10k threads): " + (System.currentTimeMillis() - start) + " ms");
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month01-jvm; mkdir day28 -Force; cd day28
javac ThreadCompare.java
java ThreadCompare
# 预期：A ≈ 5000ms（10000/200 × 100ms 排队等待）
#       B ≈ 300~1000ms（阻塞即让出，载体线程数 = 你机器核数）
# 顺带算吞吐倍数记进笔记
```

### 3.2 实验②：亲眼看看"载体线程"

```java
// CarrierWatch.java —— 虚拟线程数 vs 底层 OS 线程数
public class CarrierWatch {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 100_000; i++) {                      // 10 万虚拟线程！
            Thread.ofVirtual().name("vt-", i).start(() -> {
                try { Thread.sleep(60_000); } catch (Exception e) {}
            });
        }
        System.out.println("Java 线程总数: " + Thread.getAllStackTraces().keySet().size());
        Thread.sleep(5000);   // 此刻去任务管理器看 java 进程的 OS 线程数 ≈ 核数+少许
    }
}
// 结论：Java 视角 10 万线程，OS 视角只有几十个载体 → M:N 的直观证明
```

### 3.3 实验③：pinning 检查（JDK25 上验证 JEP 491）

```java
// PinningCheck.java —— synchronized 块里阻塞
public class PinningCheck {
    static final Object LOCK = new Object();
    public static void main(String[] args) throws Exception {
        Thread.ofVirtual().start(() -> {
            synchronized (LOCK) {                    // JDK21：这里 pinning！
                try { Thread.sleep(200); } catch (Exception e) {}
            }
        }).join();
        System.out.println("done");
    }
}
```

```powershell
javac PinningCheck.java
java -Djdk.tracePinnedThreads=full PinningCheck
# JDK25 预期：无 pinning 输出（JEP 491 已修复 synchronized）
# 把这段代码语义讲给同事听：在 JDK21 上必须改 ReentrantLock，JDK24+ 不必
```

### 3.4 场景选型卡（记进笔记）

```text
适合虚拟线程：高并发 IO 密集（网关下游调用/爬虫/聊天服务/文件批量处理）
不适合：      CPU 密集（没有阻塞就没让出，纯开销）
             已有成熟响应式栈（WebFlux）且团队顺手
配套纪律：    连接池容量 ≠ 虚拟线程数（DB 连接是稀缺资源，需信号量限流）
             ThreadLocal 慎用（百万线程 × 大对象 = 新泄漏姿势）
```

## 4. 面试连接

**Q：虚拟线程的原理？为什么它能百万级？**
> M:N 调度：海量虚拟线程复用少量载体线程；阻塞时通过 Continuation 把栈存回堆并卸载载体，让其他虚拟线程顶上。栈在堆上按需分配（KB 级）而非 OS 预留 1MB，成本差两个数量级。

**Q：什么是 pinning？现在还要担心吗？**
> JDK21 中 synchronized 块内阻塞无法卸载，虚拟线程被钉死占住载体，吞吐退化；JDK24 的 JEP 491 修复了 synchronized，JDK25 环境只剩 native 调用内阻塞会 pinning。能讲出版本演进 = 跟进最新技术的硬证据。

**Q：虚拟线程能替代线程池吗？**
> 任务调度层面：虚拟线程场景用 newVirtualThreadPerTaskExecutor（每任务一线程，不池化——虚拟线程廉价，池化反而反模式）。但信号量限流/连接池上限仍然必要：下游资源不因线程廉价而变廉价。

## 5. 今日验收清单

- [ ] ThreadCompare 跑通并记录倍数
- [ ] CarrierWatch 用数字讲清 M:N
- [ ] PinningCheck 在 JDK25 验证通过并能讲 JEP 491 演进
- [ ] 场景选型卡 + 三条配套纪律成文
- [ ] `git add . && git commit -m "day28: virtual threads"`

---
[← Day 27](day27-G1调优实战.md) | [本月目录](README.md) | [Day 29 · 综合实战排查演练 →](day29-综合实战排查演练.md)
