# Day 16 · 线程池参数怎么定（公式陷阱与动态调参）

> **今日目标**："线程数设多少"是面试第二高频，标准答案不是背公式，而是"公式只是起点 + 压测定参数 + 运行时可调"。今天用两个压测实验亲手量出 CPU/IO 密集的合理线程数，再实现动态调参。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：两组压测数据 + DynamicPoolDemo（运行时改参数）+ 参数选型决策卡

## 1. 知识地图

```
经典公式（只是起点，别当圣经）：
  CPU 密集型：N = 核心数 + 1
      （+1 是为了缺页/分支预测失败等偶发暂停时补位）
  IO 密集型：  N = 核心数 × (1 + 等待时间/计算时间)
      例：单请求 10ms CPU + 90ms RPC 等待 → N = 8 × (1+9) = 80

公式为什么靠不住（面试加分点）：
  ① IO 任务的"等待时间"随下游抖动，不是常数
  ② 多个线程池共享 CPU，公式假设了独占
  ③ 内存/连接数/上下文切换都是隐性约束
  → 正确姿势：公式定初值 → 压测找拐点 → 上线后动态调 + 监控

队列选型三兄弟（决定池的"性格"）：
  SynchronousQueue      不存任务，直接交接 → 不设界就疯狂扩容（CachedPool）
  ArrayBlockingQueue    有界，满了走救急/拒绝 → 生产首选
  LinkedBlockingQueue   默认无界 OOM 风险 → 必须 new 时传容量

动态线程池（美团方案的核心一句话）：
  ThreadPoolExecutor 本来就提供 setter：
    setCorePoolSize / setMaximumPoolSize / setKeepAliveTime
  运行时改参数即时生效 → 外面包一层配置中心（Nacos/Apollo）+ 监控告警
  注意 setMaximumPoolSize 先于 setCorePoolSize 时的大小关系校验
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| CPU-bound / IO-bound | CPU 密集/IO 密集 | 任务时间花在算/等 |
| Amdahl's Law | 阿姆达尔定律 | 并行加速比受串行部分上限约束 |
| Back Pressure | 背压 | 拒绝策略/有界队列反压提交方 |
| Dynamic Tuning | 动态调参 | 运行时 setter + 配置中心 |
| Throughput / Latency | 吞吐/延迟 | 压测的两个观察维度 |
| Context Switch | 上下文切换 | 线程过多的隐性税 |

## 3. 动手实操

### 3.1 实验①：CPU 密集压测（亲手量出拐点）

```java
// CpuBench.java —— 纯计算任务，对比 4/8/16 线程总耗时
import java.util.concurrent.*;

public class CpuBench {
    public static void main(String[] args) {
        int cores = Runtime.getRuntime().availableProcessors();
        System.out.println("本机核心数: " + cores);
        for (int n : new int[]{4, cores, cores * 2, cores * 4}) {
            ThreadPoolExecutor pool = new ThreadPoolExecutor(n, n, 0, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(100000));
            long total = 200_000;                       // 20 万个计算任务
            long begin = System.currentTimeMillis();
            for (int i = 0; i < total; i++) {
                final int x = i;
                pool.execute(() -> {                    // 纯 CPU：无 IO 等待
                    long h = 0;
                    for (int j = 0; j < 1000; j++) h += (x * j) % 7;
                    if (h == 42) System.out.print("");
                });
            }
            pool.shutdown();
            try { pool.awaitTermination(5, TimeUnit.MINUTES); } catch (Exception e) {}
            System.out.println("线程数=" + n + " 耗时=" + (System.currentTimeMillis() - begin) + "ms");
        }
    }
}
// 预期规律：4→cores 耗时近似减半；cores→2x 基本无提升甚至变慢（上下文切换税）
// 把这组数据记下来——这就是你面试里"我压测过"的证据
```

### 3.2 实验②：IO 密集压测（线程少 = 吞吐被等待拖死）

```java
// IoBench.java —— 每任务 90ms 模拟 IO + 10ms 计算，对比 8/32/80/200 线程
import java.util.concurrent.*;

public class IoBench {
    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        for (int n : new int[]{cores, cores * 4, cores * 10, cores * 25}) {
            ThreadPoolExecutor pool = new ThreadPoolExecutor(n, n, 0, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(100000));
            int total = 800;
            CountDownLatch latch = new CountDownLatch(total);
            long begin = System.currentTimeMillis();
            for (int i = 0; i < total; i++) {
                pool.execute(() -> {
                    try { Thread.sleep(90); } catch (Exception e) {}   // 模拟 RPC 等待
                    long h = 0;                                        // 模拟计算
                    for (int j = 0; j < 100_000; j++) h += j % 3;
                    if (h == -1) System.out.print("");
                    latch.countDown();
                });
            }
            latch.await();
            long cost = System.currentTimeMillis() - begin;
            System.out.printf("线程数=%-4d 吞吐=%.0f TPS%n", n, total * 1000.0 / cost);
            pool.shutdownNow();
        }
    }
}
// 预期：8 线程 ≈ 8×(1000/100)=80 TPS 封顶；80 线程才能吃满 IO 等待
// 这就是 IO 密集公式的直觉来源——但你压测过，就比背公式的可信十倍
```

### 3.3 实验③：动态调参（运行时改参数，即时生效）

```java
// DynamicPoolDemo.java —— 模拟"早高峰配置中心推送新参数"
import java.util.concurrent.*;

public class DynamicPoolDemo {
    public static void main(String[] args) throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 4, 10, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10), r -> new Thread(r, "dynamic-" + System.nanoTime() % 100));
        System.out.println("初始: core=2 max=4");
        // 灌入持续任务，让池保持忙碌
        for (int i = 0; i < 20; i++) {
            pool.execute(() -> { try { Thread.sleep(3000); } catch (Exception e) {} });
        }
        Thread.sleep(500);
        show(pool);
        System.out.println("== 推送新参数：core=6 max=8（模拟大促预案）==");
        pool.setMaximumPoolSize(8);                     // ★先改大的，避免校验冲突
        pool.setCorePoolSize(6);
        Thread.sleep(500);
        show(pool);                                     // 观察线程数立刻往上走
        System.out.println("== 回落：core=1 max=2（模拟低峰）==");
        pool.setCorePoolSize(1);                        // ★先改小的
        pool.setMaximumPoolSize(2);
        Thread.sleep(15000);                            // 等 keepAliveTime 生效
        show(pool);                                     // 空闲线程被回收
        pool.shutdown();
    }
    static void show(ThreadPoolExecutor p) {
        System.out.printf("   [实时] core=%d max=%d 线程数=%d 队列=%d%n",
                p.getCorePoolSize(), p.getMaximumPoolSize(), p.getPoolSize(), p.getQueue().size());
    }
}
// 生产落地：把两次 setXXX 包进 @NacosValueListener，配监控（活跃数/队列水位/拒绝数）
```

### 3.4 参数选型决策卡（抄进笔记）

| 场景 | core | max | 队列 | 拒绝策略 |
|------|------|-----|------|---------|
| CPU 密集（序列化/压缩） | 核数+1 | 同 core | 短有界 | CallerRuns |
| IO 密集（RPC/DB） | 压测拐点 | 同 core（不靠扩容） | 稍长有界 | CallerRuns/自定义告警 |
| 混合型 | 按业务拆两个池 | 同上 | 各自有界 | 自定义：记日志+降级 |
| 不确定 | 公式初值 | 同 core | 有界 | 先 AbortPolicy 暴露问题 |

## 4. 面试连接

**Q：线程数怎么定？**
> 三段式回答：① 公式给初值（CPU 密集 N+1，IO 密集 N×(1+W/C)）；② 公式的局限（等待时间抖动/多池共享/上下文切换）所以必须压测，并报出我自己的实验数据（CpuBench 拐点）；③ 生产上参数要可调——动态线程池 + 监控（活跃线程/队列水位/拒绝次数）。

**Q：动态线程池怎么实现？**
> JDK 原生 setter 就是入口（setCorePoolSize/setMaximumPoolSize），配配置中心监听推送 + 参数校验（max≥core，注意先后顺序）+ 变更审计日志。再补一刀："核心线程也可以回收，allowCoreThreadTimeOut(true)"——区分度立刻出来。

**Q：队列怎么选？**
> 一句话："无界是 OOM 隐患，Cached 是线程爆炸，生产默认有界 ArrayBlockingQueue + 合理拒绝策略"；再讲 CallerRuns 的背压价值（拖慢提交方保护自己）。

## 5. 今日验收清单

- [ ] CpuBench 数据记录（至少 4 组，找到拐点）
- [ ] IoBench 数据记录（验证低线程数吞吐封顶）
- [ ] DynamicPoolDemo 跑通（含顺序正确的大小调整）
- [ ] 参数选型决策卡默写
- [ ] `git add . && git commit -m "day16: pool sizing & dynamic tuning"`

---
[← Day 15](day15-线程池七参数与执行流程.md) | [本月目录](README.md) | [Day 17 · 手写迷你线程池 →](day17-手写迷你线程池.md)
