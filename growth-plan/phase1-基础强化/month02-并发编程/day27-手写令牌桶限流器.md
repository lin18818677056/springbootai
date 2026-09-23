# Day 27 · 综合实战一：手写令牌桶限流器（原子类 + 压测精度）

> **今日目标**：本周实战第一弹。手写一个惰性补令牌（lazy refill）的令牌桶限流器（day09 CAS 知识落地），压测验证 100 TPS 目标误差 <5%（M2 验收项），再补一个注解 + AOP 的生产接入模板。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：TokenBucket（压测误差 <5% 数据）+ AOP 接入模板 + 四算法选型表

## 1. 知识地图

```
限流四算法对比（一张表定乾坤）：

  ┌──────────┬────────────────┬──────────────────┬──────────────┐
  │ 算法      │ 原理            │ 优点/缺点         │ 场景          │
  ├──────────┼────────────────┼──────────────────┼──────────────┤
  │ 固定窗口   │ 每秒计数器归零   │ 简单/窗口边界突刺  │ 粗粒度统计    │
  │ 滑动窗口   │ 循环数组滚动统计 │ 平滑/实现稍复杂    │ Sentinel     │
  │ 漏桶      │ 恒定速率出水     │ 绝对平滑/不能应对突发│ 对下游保护    │
  │ 令牌桶    │ 恒速发令牌，桶限 │ 允许突发/主流选择  │ 网关/接口限流 │
  └──────────┴────────────────┴──────────────────┴──────────────┘

令牌桶核心三要素：
  capacity  桶容量（允许的最大突发量）
  rate      每秒补令牌速率（目标 TPS）
  tokens    当前令牌数（≤capacity）

惰性补令牌（lazy refill，不用定时器！核心公式）：
  每次 tryAcquire 时现算：
    now = System.nanoTime()
    补充 = (now - lastRefill) × rate / 1e9
    tokens = min(capacity, tokens + 补充)
    lastRefill = now
  → 没有后台线程，天然分布式友好（同公式可搬 Redis+Lua）

实现要点（day09 CAS 的落地）：
  多线程并发补令牌：synchronized 简单可靠（临界区纳秒级）；
  进阶：CAS version+state 打包更新（可作选做）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Token Bucket | 令牌桶 | 恒速发牌 + 桶容量限突发 |
| Lazy Refill | 惰性补充 | 取牌时按时间差现算，免定时器 |
| Burst | 突发 | 短时允许超过平均速率 |
| Rate Limiting | 限流 | 保护自身的过载防线 |
| AOP | 面向切面 | 注解式限流的接入层 |
| Precision Test | 精度压测 | 实测通过速率 vs 目标速率 |

## 3. 动手实操

### 3.1 主菜：手写令牌桶（30 行核心 + 压测）

```java
// TokenBucket.java —— 惰性补令牌的线程安全令牌桶
public class TokenBucket {
    private final double capacity;          // 桶容量（突发上限）
    private final double ratePerSec;        // 补令牌速率（目标 TPS）
    private double tokens;                  // 当前令牌
    private long lastRefillNanos;           // 上次补令牌时间

    public TokenBucket(double ratePerSec, double capacity) {
        this.ratePerSec = ratePerSec;
        this.capacity = capacity;
        this.tokens = capacity;             // 启动时满桶（与 Guava 等行为一致）
        this.lastRefillNanos = System.nanoTime();
    }

    /** 非阻塞尝试拿 1 个令牌；拿到返回 true */
    public synchronized boolean tryAcquire() {
        refill();                           // ① 先按流逝时间补令牌
        if (tokens >= 1) {                  // ② 有牌就发
            tokens -= 1;
            return true;
        }
        return false;                       // ③ 没牌拒绝（不排队，快速失败）
    }
    private void refill() {
        long now = System.nanoTime();
        double added = (now - lastRefillNanos) / 1_000_000_000.0 * ratePerSec;
        if (added > 0) {
            tokens = Math.min(capacity, tokens + added);
            lastRefillNanos = now;
        }
    }
}

// RateLimitTest.java —— ★精度压测：目标 100 TPS，多线程打 10 秒
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

public class RateLimitTest {
    public static void main(String[] args) throws Exception {
        int targetTps = 100, seconds = 10;
        TokenBucket limiter = new TokenBucket(targetTps, targetTps);   // 速率100 桶100
        LongAdder passed = new LongAdder(), rejected = new LongAdder();
        ExecutorService pool = Executors.newFixedThreadPool(50);       // 50 并发使劲打
        CountDownLatch latch = new CountDownLatch(50);
        long begin = System.currentTimeMillis();
        for (int t = 0; t < 50; t++) {
            pool.execute(() -> {
                long deadline = System.currentTimeMillis() + seconds * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    if (limiter.tryAcquire()) passed.increment();      // 过了
                    else { rejected.increment(); }                     // 拒了（自旋打满CPU时sleep一下）
                    try { Thread.sleep(1); } catch (Exception e) {}
                }
                latch.countDown();
            });
        }
        latch.await();
        pool.shutdown();
        double actual = passed.sum() / (double) seconds;
        double error = Math.abs(actual - targetTps) / targetTps * 100;
        System.out.printf("目标 %d TPS | 实际 %.1f TPS | 通过 %d | 拒绝 %d | 误差 %.1f%%%n",
                targetTps, actual, passed.sum(), rejected.sum(), error);
        // ★M2 验收线：误差 < 5%（首秒有满桶突发，可把统计窗口改成"跳过首秒"再看）
    }
}
```

### 3.2 实验②：突发行为验证（令牌桶 vs 固定窗口的差异）

```java
// BurstTest.java —— 桶容量 100，速率 10/s：开局一波 100 个立刻全过（突发）
public class BurstTest {
    public static void main(String[] args) throws InterruptedException {
        TokenBucket limiter = new TokenBucket(10, 100);   // 10 TPS 但桶大
        int ok = 0;
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 150; i++) {                    // 瞬间打 150 个
            if (limiter.tryAcquire()) ok++;
        }
        System.out.println("瞬间通过 " + ok + "/150（前 100 个全过=突发能力）");
        Thread.sleep(1000);
        ok = 0;
        for (int i = 0; i < 20; i++) {                     // 1 秒后：只补了 10 个令牌
            if (limiter.tryAcquire()) ok++;
        }
        System.out.println("1 秒后再打 20 个，通过 " + ok + "（≈速率 10/s，平滑恢复）");
    }
}
// 对比固定窗口：开局 100 个会被"每秒 10 个"直接拒掉 90 个——这就是突发的价值
```

### 3.3 实验③：注解 + AOP 接入模板（生产姿势，Spring 项目用）

```java
// ① 注解定义
// RateLimit.java
import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    double tps();                                   // 每秒允许数
    String key() default "";                        // 限流维度（空=方法级）
}
```

```java
// ② 切面（Spring Boot 环境；demo 用 main 模拟调用即可）
// RateLimitAspect.java
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;

@Aspect
@Component
public class RateLimitAspect {
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        String key = rateLimit.key().isEmpty() ? pjp.getSignature().toLongString()
                                               : rateLimit.key();
        TokenBucket bucket = buckets.computeIfAbsent(key,           // day19 知识复用
                k -> new TokenBucket(rateLimit.tps(), rateLimit.tps()));
        if (!bucket.tryAcquire()) {
            throw new RuntimeException("限流了: " + key);            // 生产：抛自定义异常→429
        }
        return pjp.proceed();
    }
}
```

```java
// ③ 用法（一行注解完成限流）
// OrderService.java
// public class OrderService {
//     @RateLimit(tps = 200)
//     public String createOrder(String sku) { return "下单成功:" + sku; }
// }
```

### 3.4 四算法选型表（抄进笔记）

| 需求 | 选型 | 理由 |
|------|------|------|
| 接口保护，允许突发 | 令牌桶 | 桶容量吸收脉冲，主流网关选择 |
| 严格匀速保护下游 | 漏桶 | 出水速率恒定 |
| 大盘监控告警 | 固定窗口 | 实现零成本，边界突刺可容忍 |
| 精确滑动统计 | 滑动窗口 | Sentinel 的做法 |
| 分布式全局限流 | Redis+Lua 令牌桶 | 同一公式搬进 Lua 脚本 |

## 4. 面试连接

**Q：手写一个限流器，思路？**
> 三步：① 选算法（令牌桶：恒速发牌+桶容量限突发）；② 核心公式 lazy refill——不搞定时器，每次取牌按时间差现算 tokens=min(cap, tokens+elapsed×rate)；③ 并发安全 synchronized 纳秒级临界区，或 CAS 打包 state。然后报压测："目标 100 TPS 实测误差 <5%"，再补注解+AOP 接入。这是一套完整闭环回答。

**Q：令牌桶和漏桶怎么选？**
> 视角不同：令牌桶约束"自己调用别人"的速率且容忍突发（攒令牌）；漏桶约束"别人调用自己"的整形流量（恒定出水）。网关限流主流是令牌桶；保护脆弱下游用漏桶/队列。

**Q：单机限流升级分布式怎么办？**
> 同一公式搬 Redis+Lua（原子执行 refill+acquire），或用现成 Sentinel/Redisson RRateLimiter。关键讲清"为什么 Lua"：取牌+补牌+写回必须原子，Lua 保证脚本级原子性。单机版是分布式版的思维地基——这就是手写的意义。

## 5. 今日验收清单

- [ ] RateLimitTest 误差 <5%（M2 验收项，记录数据）
- [ ] BurstTest 跑通并解释突发行为
- [ ] 注解+AOP 模板存入个人片段库
- [ ] 四算法选型表默写
- [ ] `git add . && git commit -m "day27: token bucket limiter"`

---
[← Day 26](day26-并发性能排查.md) | [本月目录](README.md) | [Day 28 · 手写连接池 →](day28-手写连接池.md)
