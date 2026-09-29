# Day 08 · 限流算法全景：手写四种 RateLimiter

> **今日目标**：纯 Java 手写四种限流算法（固定窗口/滑动窗口/漏桶/令牌桶）并实验对比；讲清各自适用场景——为明天 Sentinel 的流控规则打底。
> **时长**：手写 3h / 实验 1.5h / 对比总结 0.5h
> **今日产出**：RateLimiterSuite（learning/month06-ms-stability，四种实现+对比实验）

## 1. 知识地图

```
限流解决什么问题：容量有限 → 超过容量的请求要"礼貌拒绝"而不是全拖死
  （拒绝一部分 = 保住全部；全接 = 全崩——韧性设计第一课）

四种算法（从粗糙到精细）：
  ① 固定窗口计数器（Fixed Window）
     每 1s 一个格子，计数器+1，超阈值拒绝；窗口切换清零
     缺陷：临界突刺——前 500ms 放 100、后 500ms 又放 100 → 瞬间 200 超容量
  ② 滑动窗口（Sliding Window）
     把 1s 切成 N 个小格（Sentinel 默认 2 格），统计"最近 1s"= 滚动求和
     格子越细越平滑，统计越准、内存越多——临界突刺缓解
  ③ 漏桶（Leaky Bucket）
     请求进桶，桶以恒定速率流出（处理），桶满拒绝——"整形流量"：绝对平滑
     缺陷：无法应对合法突发（即使系统有空闲容量也按固定速率放）
  ④ 令牌桶（Token Bucket）
     恒定速率往桶里放令牌，请求拿令牌才放行，桶有容量上限
     突发友好：桶里攒的令牌允许一波突发（Guava RateLimiter / Sentinel 默认思想）

选型速记：
  要绝对平滑（调第三方、保护弱下游）→ 漏桶
  要允许突发（本地接口、秒杀前置）→ 令牌桶
  监控/简单保护 → 固定窗口够用；高精度限流 → 滑动窗口
  ——Sentinel：滑动窗口（QPS 维度）+ 冷启动（令牌桶变体，day09 兑现 M4 day09 伏笔）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Rate Limiting | 限流 |
| Fixed Window Counter | 固定窗口计数器 |
| Sliding Window | 滑动窗口（统计最近 N 秒） |
| Leaky Bucket | 漏桶（恒定速率流出） |
| Token Bucket | 令牌桶（恒定速率补充令牌） |
| Burst Traffic | 突发流量 |
| Critical Burst | 临界突刺（窗口交界处双倍放行） |
| Traffic Shaping | 流量整形（漏桶的匀速化效果） |

## 3. 动手实操：RateLimiterSuite 手写与实验

```java
// learning/month06-ms-stability/src/RateLimiterSuite.java（javac 直接编译，无第三方依赖）
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** ① 固定窗口：最简单，先跑通"计数-判断-清零"心智模型 */
class FixedWindowLimiter {
    private final long threshold; private final long windowMillis;
    private volatile long windowStart = System.currentTimeMillis();
    private final AtomicLong counter = new AtomicLong();
    FixedWindowLimiter(long threshold, long windowMillis) { this.threshold = threshold; this.windowMillis = windowMillis; }
    public synchronized boolean tryAcquire() {
        long now = System.currentTimeMillis();
        if (now - windowStart >= windowMillis) { windowStart = now; counter.set(0); }  // 窗口滚动清零
        return counter.incrementAndGet() <= threshold;   // 超阈值拒绝
    }
}

/** ② 滑动窗口：N 个小格，统计最近一个整窗 = 各格求和 */
class SlidingWindowLimiter {
    private final long bucketMillis; private final AtomicInteger[] buckets;
    private final int n; private final long threshold;
    SlidingWindowLimiter(long threshold, int buckets, long windowMillis) {
        this.threshold = threshold; this.n = buckets;
        this.bucketMillis = windowMillis / buckets;
        this.buckets = new AtomicInteger[buckets];
        for (int i = 0; i < buckets; i++) this.buckets[i] = new AtomicInteger();
    }
    public boolean tryAcquire() {
        int idx = (int) ((System.currentTimeMillis() / bucketMillis) % n);
        AtomicInteger cur = buckets[idx];
        cur.set(0); // 简化实现：进入新格时清零旧格（严格版需按时间戳惰性过期）
        cur.incrementAndGet();
        long sum = 0; for (AtomicInteger b : buckets) sum += b.get();
        return sum <= threshold;
    }
}

/** ③ 漏桶：恒定速率"漏水"（处理），桶满拒绝 */
class LeakyBucketLimiter {
    private final long capacity, leakRatePerSec;
    private final AtomicLong water = new AtomicLong();
    private volatile long lastLeak = System.currentTimeMillis();
    LeakyBucketLimiter(long capacity, long leakRatePerSec) { this.capacity = capacity; this.leakRatePerSec = leakRatePerSec; }
    public synchronized boolean tryAcquire() {
        long now = System.currentTimeMillis();
        long leaked = (now - lastLeak) / 1000 * leakRatePerSec;   // 惰性漏水：按时间差算
        if (leaked > 0) { water.addAndGet(-Math.min(leaked, water.get())); lastLeak = now; }
        return water.incrementAndGet() <= capacity;
    }
}

/** ④ 令牌桶：恒定速率补令牌，拿令牌放行，桶容量允许突发 */
class TokenBucketLimiter {
    private final long capacity, refillPerSec;
    private final AtomicLong tokens; private volatile long lastRefill = System.currentTimeMillis();
    TokenBucketLimiter(long capacity, long refillPerSec) { this.capacity = capacity; this.refillPerSec = refillPerSec; this.tokens = new AtomicLong(capacity); }
    public synchronized boolean tryAcquire() {
        long now = System.currentTimeMillis();
        long added = (now - lastRefill) / 1000 * refillPerSec;
        if (added > 0) { tokens.set(Math.min(capacity, tokens.get() + added)); lastRefill = now; }
        return tokens.getAndDecrement() > 0;     // decrement 到 -1 也没关系，判断取的是旧值
    }
}

public class RateLimiterSuite {
    public static void main(String[] args) throws Exception {
        // 对比实验：每种限流器 10 线程并发打 2 秒，统计放行数曲线
        // 预期：固定窗口在窗口交界出现放行尖峰；漏桶放行曲线平；令牌桶开局一波突发后匀速
        bench("FixedWindow 100/s", new FixedWindowLimiter(100, 1000));
        bench("SlidingWindow 100/s", new SlidingWindowLimiter(100, 10, 1000));
        bench("LeakyBucket 100/s", new LeakyBucketLimiter(100, 100));
        bench("TokenBucket 100/s", new TokenBucketLimiter(100, 100));
    }
    static void bench(String name, Supplier<Boolean> limiter) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        AtomicLong pass = new AtomicLong(), reject = new AtomicLong();
        long end = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < end) {
            pool.submit(() -> { if (limiter.get()) pass.incrementAndGet(); else reject.incrementAndGet(); });
        }
        pool.shutdown(); pool.awaitTermination(3, TimeUnit.SECONDS);
        System.out.printf("%s pass=%d reject=%d%n", name, pass.get(), reject.get());
    }
}
interface Supplier<T> { T get(); }
```

```powershell
cd D:\mywork\springbootai\learning\month06-ms-stability
javac -encoding UTF-8 -d out src\RateLimiterSuite.java
java -cp out RateLimiterSuite
# 观察四个算法在同样压力下的 pass/reject 分布；调大 TokenBucket 容量看突发差异
# 进阶实验：把临界突刺"可视化"——固定窗口每 100ms 打印放行数 → 交界处尖峰肉眼可见
```

## 4. 面试连接

**Q：四种限流算法讲一下？各自优缺点和选型？**
> 固定窗口最简单但有临界突刺（窗口交界双倍放行）；滑动窗口把窗口切格子滚动统计，精度换内存；漏桶恒定速率流出做流量整形，但压制合法突发；令牌桶恒定速率补令牌、桶容量允许突发，Guava/Sentinel 都是它的变体。选型按"要不要突发"：保护弱下游选漏桶，本地接口保突发选令牌桶。加分句："四种我都在 learning 仓库手写过并做过对比压测——固定窗口的临界突刺在压测曲线上肉眼可见，这个实验让我彻底记住了它们的行为差异。"（手写+实验=超越 90% 背概念的人）

**Q：限流、熔断、降级三者什么关系？**
> 三个不同层面的自保手段：限流是"进门的安检"（控制进入量，事前防御）；熔断是"跳闸"（下游故障时快速失败停止请求，事中止损，day10）；降级是"备用方案"（拒绝精美服务保核心功能，是熔断/限流后的动作）。一句话串联：限流防"量大"，熔断防"下游坏"，降级保"核心体验"——三者组合才是完整韧性，明天开始用 Sentinel 把三件事做全。

## 5. 今日验收清单

- [ ] 四种算法手写完成并编译运行
- [ ] 对比实验数据记录（临界突刺可见）
- [ ] 三句话选型速记能脱口而出
- [ ] 限流/熔断/降级三者关系能画图讲清
- [ ] 实验截图或输出贴进学习笔记
- [ ] `git add . && git commit -m "day08: rate limiter suite"`

---
[← Day 07](day07-第一周复盘.md) | [本月目录](README.md) | [Day 09 · Sentinel流控规则 →](day09-Sentinel流控规则.md)
