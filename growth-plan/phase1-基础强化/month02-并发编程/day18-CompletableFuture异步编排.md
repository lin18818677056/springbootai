# Day 18 · CompletableFuture 异步编排（串行 300ms → 并行 100ms）

> **今日目标**：接口聚合是 CF 最硬核的应用场景。今天把"串行调 3 个下游（300ms）"改造成"并行聚合（100ms）"留压测数据——这是 M2 验收项之一；顺带打通链式 API 与超时/异常处理。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：AggCompare（300ms→100ms 压测数据）+ ChainDemo + 超时降级模板

## 1. 知识地图

```
演进线：为什么有了 Future 还要 CompletableFuture？

  Future.get()   阻塞轮询式：拿结果必须 get 挂起，无法编排
  CompletableFuture  链式/组合/回调式：任务图编程

  CF API 家族地图（按"收尾-变换-组合-异常"四类记）：
    ┌─ 发起 ────────────────────────────────────────┐
    │ supplyAsync(supplier, executor)  有返回值      │
    │ runAsync(runnable, executor)     无返回值      │
    ├─ 变换（一条链）────────────────────────────────┤
    │ thenApply   T→U     同步映射                  │
    │ thenCompose T→CF<U> 异步扁平化（避免嵌套 CF）   │
    │ thenAccept/thenRun  消费/收尾                  │
    ├─ 组合（两张卡汇合）────────────────────────────┤
    │ thenCombine(a, b, fn)    两个都完成            │
    │ allOf(cf1, cf2, cf3)     全部完成（聚合神器）  │
    │ anyOf(...)               任一完成（竞速/容灾） │
    ├─ 异常与超时 ──────────────────────────────────┤
    │ exceptionally(fn) / handle(fn)   兜底          │
    │ orTimeout(1, SECONDS)  JDK9+ 超时（替代 get 超时）│
    │ completeOnTimeOut(默认值, 超时)  超时给默认值    │
    └───────────────────────────────────────────────┘

  ★ 生产第一坑：不传 executor 时用 ForkJoinPool.commonPool()
    —— IO 任务会拖垮全局公共池，所有 CF 共享！必须自带线程池。
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Aggregation | 聚合 | 一次请求合并多个下游结果 |
| Completion Stage | 完成阶段 | CF 实现的接口，"任务图中的一站" |
| Compose / Combine | 扁平组合/汇聚 | 链式衔接 / 并行汇合 |
| Fan-out / Fan-in | 扇出/扇入 | 并行调用下游再合并 |
| Degradation | 降级 | 超时或异常时返回兜底数据 |
| commonPool | 公共池 | CF 默认线程池，IO 任务勿用 |

## 3. 动手实操

### 3.1 主菜：串行 vs 并行聚合（留压测数据，M2 验收）

```java
// AggCompare.java —— 串行 300ms → 并行 ~100ms（3 个下游各 100ms）
import java.util.concurrent.*;

public class AggCompare {
    static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(
            8, 8, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100),
            r -> new Thread(r, "agg-" + System.nanoTime() % 1000));

    public static void main(String[] args) throws Exception {
        System.out.println("== 版本① 串行调用（大多数老代码的样子）==");
        long t1 = System.currentTimeMillis();
        String r1 = rpc("用户服务");
        String r2 = rpc("订单服务");
        String r3 = rpc("营销服务");
        System.out.println("结果: " + r1 + r2 + r3 + " 耗时=" + (System.currentTimeMillis() - t1) + "ms");

        System.out.println("== 版本② 并行聚合（今天的改造目标）==");
        long t2 = System.currentTimeMillis();
        CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> rpc("用户服务"), POOL);
        CompletableFuture<String> f2 = CompletableFuture.supplyAsync(() -> rpc("订单服务"), POOL);
        CompletableFuture<String> f3 = CompletableFuture.supplyAsync(() -> rpc("营销服务"), POOL);
        CompletableFuture.allOf(f1, f2, f3).join();           // ★三个全完成才放行
        System.out.println("结果: " + f1.join() + f2.join() + f3.join()
                + " 耗时=" + (System.currentTimeMillis() - t2) + "ms");
        POOL.shutdown();
    }
    static String rpc(String name) {                          // 模拟 100ms RPC
        try { Thread.sleep(100); } catch (Exception e) {}
        return "[" + name + " ok]";
    }
}
// 预期数据：串行 ≈300ms，并行 ≈100ms（加上调度开销略高一点）
// ★把这两个数字记进笔记：面试讲"我改造过接口聚合，300ms→100ms"的实证
```

### 3.2 实验②：链式编排（thenApply / thenCompose / thenCombine）

```java
// ChainDemo.java —— 一个真实业务链：查用户 → 查订单 → 合并算优惠
import java.util.concurrent.*;

public class ChainDemo {
    static final ExecutorService POOL = Executors.newFixedThreadPool(4);   // 演示用

    public static void main(String[] args) {
        CompletableFuture<String> userCf  = supply(() -> "用户:张三");
        CompletableFuture<String> orderCf = supply(() -> "订单:A001");

        String result = userCf
                .thenApply(u -> u + "+会员等级V6")               // 同步加工（主线程或完成线程执行）
                .thenCompose(u -> supply(() -> u + "+权益校验通过")) // 异步衔接（避免 CF<CF<T>>）
                .thenCombine(orderCf, (u, o) -> u + " | " + o + " | 优惠:满100减30")
                .exceptionally(e -> "兜底页: " + e.getMessage())   // 链上任何一环炸了都到这
                .join();
        System.out.println(result);
        POOL.shutdown();
    }
    static CompletableFuture<String> supply(java.util.function.Supplier<String> s) {
        return CompletableFuture.supplyAsync(s, POOL);
    }
}
// 三个 then 的区分考点：thenApply(同步变换)/thenCompose(异步扁平)/thenCombine(双流汇合)
// 再加一刀：thenApply 与 thenApplyAsync 的区别——后者强制切回指定池执行
```

### 3.3 实验③：超时降级（orTimeout + completeOnTimeout）

```java
// TimeoutDemo.java —— 下游 500ms 慢查询，接口 200ms 必须响应（兜底价）
import java.util.concurrent.*;

public class TimeoutDemo {
    public static void main(String[] args) {
        long begin = System.currentTimeMillis();
        String price = CompletableFuture
                .supplyAsync(() -> {
                    try { Thread.sleep(500); } catch (Exception e) {}   // 模拟慢下游
                    return "实时价:199.9";
                })
                .orTimeout(200, TimeUnit.MILLISECONDS)                  // 200ms 大限
                .exceptionally(e -> "默认价:159.0")                      // 超时兜底（降级）
                .join();
        System.out.println(price + " 接口耗时=" + (System.currentTimeMillis() - begin) + "ms");
        // 注意：orTimeout 超时后下游线程还在跑（不会真取消），要彻底取消用 cf.cancel(true)
    }
}
// 对比旧写法：future.get(200, MS) 会阻塞调用线程；orTimeout 非阻塞，更适合编排链
```

### 3.4 观察点

```powershell
cd D:\mywork\springbootai\learning\month02-concurrency
javac AggCompare.java; java AggCompare
javac ChainDemo.java;  java ChainDemo
javac TimeoutDemo.java; java TimeoutDemo

# 观察①：AggCompare 两版耗时差（300 vs 100）
# 观察②：jstack 看 commonPool——把 ChainDemo 的 supplyAsync 去掉第二个参数重跑，
#         再 jstack 会看到线程名 ForkJoinPool.commonPool-worker-N（全局公共池实锤）
# 观察③：TimeoutDemo 输出"默认价"，接口耗时 ≈200ms 而不是 500ms
```

## 4. 面试连接

**Q：CompletableFuture 比 Future 强在哪？**
> Future 只有"提交+阻塞取"，CF 是任务图：链式变换（thenApply/Compose）、并行汇合（allOf/thenCombine）、非阻塞回调与超时异常兜底（orTimeout/exceptionally）。最好带实证："我把一个串行聚合接口从 300ms 优化到 100ms，allOf 扇出扇入。"

**Q：CF 默认线程池有什么坑？**
> 不传 executor 用 ForkJoinPool.commonPool（CPU 核数-1 个线程，daemon），IO 密集任务会占满公共池，拖垮 JVM 里所有 CF 任务。生产规范：自带线程池 + 隔离（不同下游不同池，防一个慢下游连坐）。

**Q：allOf 之后为什么要逐个 join？**
> allOf 返回 CF<Void>，只表示"全部完成"这个事件，不携带任何结果；结果要从各个子 CF 里取。子任务已完成所以 join 不会阻塞，这是"先等事件、再取结果"的组合拳。

## 5. 今日验收清单

- [ ] AggCompare 压测数据记录（串行/并行两组耗时）
- [ ] ChainDemo 跑通，三个 then 区分点脱稿能讲
- [ ] TimeoutDemo 跑通（200ms 出默认价）
- [ ] jstack 亲眼看一次 commonPool
- [ ] `git add . && git commit -m "day18: cf aggregation 300ms-to-100ms"`

---
[← Day 17](day17-手写迷你线程池.md) | [本月目录](README.md) | [Day 19 · ConcurrentHashMap 深度 →](day19-ConcurrentHashMap深度.md)
