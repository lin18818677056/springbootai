# Day 24 · 虚拟线程工程化（IO 接口改造 + 压测报告）

> **今日目标**：month01 day28 学了虚拟线程入门，今天做工程化落地：IO 密集接口改造压测（虚拟线程 vs 平台线程池定量对比）、pinning 排查（含 JDK24 JEP 491 演进）、Spring Boot 一键开关。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：VTCompare 压测报告 + PinningDemo 记录 + Spring Boot 配置片段

## 1. 知识地图

```
平台线程 vs 虚拟线程（一年前学的，现在用工程视角再看一遍）：

  平台线程 Platform Thread        虚拟线程 Virtual Thread (JDK21+ 正式)
  ─────────────────────         ─────────────────────────────────────
  1:1 映射 OS 线程                M:N 调度到少量载体线程（carrier）
  栈 1MB 起，OS 调度              栈在堆上按需增长（几百字节起步）
  创建成本高 → 必须"池化复用"       创建成本极低 → 用完即弃（一请求一线程）
  阻塞 = 占住 OS 线程              阻塞 = 挂起自身，让出载体线程

  ★心智模型："虚拟线程不是更快的线程，而是更便宜的线程"
   → 它不提升 CPU 算力，提升的是"同时挂起多少个等待中的 IO 任务"

什么时候用/不用：
  ✔ IO 密集：RPC 聚合、DB 慢查询、文件/网络、爬虫、网关转发
  ✘ CPU 密集：算力不变，切换开销反而浪费（继续用线程池，池大小=核数）

工程三件事：
  ① 改造：Executors.newVirtualThreadPerTaskExecutor() 平替业务池
  ② 排查 pinning（钉住）：JDK21-23 里 synchronized 块内阻塞会钉住载体线程
     JDK24 JEP 491：synchronized 不再 pinning（但要会讲演进，面试常问旧版坑）
  ③ Spring Boot 3.2+：spring.threads.virtual.enabled=true 一键全量切换
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Carrier Thread | 载体线程 | 真正跑虚拟线程的少量平台线程（默认=核数） |
| Continuation | 续体 | 挂起/恢复执行栈的机制（栈帧搬到堆） |
| Pinning | 钉住 | 虚拟线程阻塞时无法让出载体线程 |
| Mount / Unmount | 挂载/卸载 | 虚拟线程上下载体的动作 |
| Structured Concurrency | 结构化并发 | 作用域管理子任务生命周期（JDK 25 仍预览） |
| Throughput vs Latency | 吞吐/延迟 | 虚拟线程提升吞吐，不降低单个延迟 |

## 3. 动手实操

### 3.1 实验①：两种创建方式（30 秒上手）

```java
// VTBasic.java —— 工厂两种姿势 + 与平台线程对照
import java.util.concurrent.*;

public class VTBasic {
    public static void main(String[] args) throws Exception {
        // 姿势①：直接创建
        Thread vt = Thread.ofVirtual().name("vt-", 0).start(() -> {
            System.out.println("我是 " + Thread.currentThread()   // VirtualThread[#..,vt-0]
                    + " isVirtual=" + Thread.currentThread().isVirtual());
        });
        vt.join();

        // 姿势②：执行器（改造现有线程池代码的最小改动点）
        try (ExecutorService vtp = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 5; i++) {
                int id = i;
                vtp.submit(() -> {
                    Thread.sleep(100);                            // IO 等待：挂起让出载体
                    System.out.println("任务" + id + " 在 " + Thread.currentThread());
                    return null;
                });
            }
        }                                                          // try-with 自动等任务结束
    }
}
```

### 3.2 主菜：IO 接口改造压测（200 平台线程 vs 虚拟线程）

```java
// VTCompare.java —— 5000 个"100ms IO 任务"，两种方案总耗时/资源对比
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class VTCompare {
    public static void main(String[] args) throws Exception {
        int tasks = 5000;                                  // 模拟 5000 个并发请求
        System.out.println("== 方案A：平台线程池（200 固定） ==");
        bench(newFixedPool(tasks), tasks);

        System.out.println("== 方案B：虚拟线程（每任务一线程） ==");
        bench(Executors.newVirtualThreadPerTaskExecutor(), tasks);
    }
    static ExecutorService newFixedPool(int n) {
        return new ThreadPoolExecutor(200, 200, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10000), r -> new Thread(r, "plat-" + n));
    }
    static void bench(ExecutorService pool, int tasks) throws Exception {
        LongAdder peak = new LongAdder();
        LongAdder current = new LongAdder();
        CountDownLatch latch = new CountDownLatch(tasks);
        long begin = System.currentTimeMillis();
        for (int i = 0; i < tasks; i++) {
            pool.submit(() -> {
                current.increment();
                peak.accumulate(Math.max(peak.sum(), current.sum()));
                try { Thread.sleep(100); } catch (Exception e) {}   // 模拟 100ms IO
                current.decrement();
                latch.countDown();
            });
        }
        latch.await();
        long cost = System.currentTimeMillis() - begin;
        System.out.printf("  耗时=%dms 峰值并发=%d 吞吐=%.0f TPS%n",
                cost, peak.sum(), tasks * 1000.0 / cost);
        pool.shutdown();
    }
}
// 预期解读（记进压测报告）：
//   方案A：5000 任务 / 200 线程 ≈ 25 批 × 100ms ≈ 2500ms+，TPS 封顶 ≈ 2000
//   方案B：5000 个任务同时挂起，一批跑完 ≈ 100~300ms，TPS 上万
//   ★吞吐差 5-10 倍，且方案B不需要调参（没有池参数烦恼）
```

### 3.3 实验②：pinning 排查（JDK25 环境讲演进，旧版复现）

```java
// PinningDemo.java —— synchronized 块内 sleep（旧 JDK 的 pinning 高危写法）
public class PinningDemo {
    static final Object LOCK = new Object();

    public static void main(String[] args) throws Exception {
        Runnable task = () -> {
            synchronized (LOCK) {
                try { Thread.sleep(100); } catch (Exception e) {}   // ★块内阻塞
            }
        };
        for (int i = 0; i < 3; i++) {
            Thread vt = Thread.ofVirtual().start(task);
            vt.join();
        }
        System.out.println("跑完。JDK25：synchronized 已不再 pinning（JEP 491，JDK24 起）");
        System.out.println("JDK21-23 复现方法：java -Djdk.tracePinnedThreads=full PinningDemo");
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month02-concurrency
javac VTBasic.java;  java VTBasic
javac VTCompare.java; java VTCompare        # ★两组数据记进压测报告
javac PinningDemo.java; java PinningDemo

# 旧版对照（可选）：如果有 JDK21 的 JAVA_HOME，切过去跑 PinningDemo 看告警输出：
# java -Djdk.tracePinnedThreads=full PinningDemo
# 会打印：Thread[#..] PINNED ...（synchronized 阻塞导致）
# 面试讲法："JDK21-23 用 -Djdk.tracePinnedThreads 排查；JDK24 起 JEP 491 修复，
#           ReentrantLock 和 synchronized 的行为对齐了——我在 JDK25 环境，但知道旧版怎么查"
```

### 3.4 Spring Boot 3.2+ 一键开关（生产落地姿势）

```properties
# application.properties —— Tomcat 请求线程/ @Async / @Scheduled 全面虚拟线程化
spring.threads.virtual.enabled=true
# 前提：JDK21+；Spring Boot 3.2+；确认无 pinning 高危写法（旧 JDK）
# 注意事项：
#   ① ThreadLocal 大对象模式要重审（虚拟线程数量级暴涨，泄漏放大 N 倍——day20 铁律更重要）
#   ② CPU 密集任务不要跟着切（没有收益还添乱）
#   ③ 有锁的热点反而更堵（吞吐上去了，锁竞争变瓶颈——day26 排查登场）
```

## 4. 面试连接

**Q：虚拟线程的原理，你的理解？**
> 三层：① M:N 调度——海量虚拟线程挂载到默认核数的载体线程上；② 阻塞即让出——IO 阻塞时栈（continuation）从载体线程搬到堆，载体去跑别人；③ 恢复时再搬回来。核心心智模型："不是更快的线程，是更便宜的线程"，收益全部来自"等待中的任务不再占着昂贵资源"。

**Q：什么场景不该用虚拟线程？**
> CPU 密集（算力不变，白付调度成本）；重度依赖 ThreadLocal 缓存大对象的存量代码（线程数量级暴涨，内存与脏数据风险放大）；热点锁竞争严重的代码（吞吐瓶颈会转移到锁上，要用 day26 手段先解竞争）。这些"不该用"恰恰证明你真用过。

**Q：synchronized 会导致虚拟线程 pinning 吗？**
> 分版本答：JDK21-23 会——synchronized 块内阻塞时无法 unmount，载体被独占，吞吐反而退化，用 -Djdk.tracePinnedThreads=full 排查，解法是换 ReentrantLock；JDK24 起 JEP 491 让 synchronized 不再 pinning，我当前 JDK25 环境已无此问题，但存量服务的旧 JDK 仍要当心。

## 5. 今日验收清单

- [ ] VTCompare 压测报告成文（两方案耗时/TPS 数据）
- [ ] PinningDemo 跑通 + 能讲 JDK21→24 的演进
- [ ] Spring Boot 开关 + 三条注意事项记录
- [ ] 能脱稿讲"不是更快的线程，是更便宜的线程"
- [ ] `git add . && git commit -m "day24: virtual threads engineering"`

---
[← Day 23](day23-并发设计模式.md) | [本月目录](README.md) | [Day 25 · 并发 bug 复现场 →](day25-并发bug复现场.md)
