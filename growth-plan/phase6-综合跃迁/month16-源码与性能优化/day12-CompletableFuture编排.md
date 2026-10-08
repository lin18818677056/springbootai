# Day 12 · CompletableFuture 编排：外卖拼单的艺术

> **今日目标**：掌握 CompletableFuture 的编排全家桶（组合/超时/降级）；理解它和虚拟线程的分工——谁也别杀死谁，搭配着用。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：带超时兜底的工具编排代码 + CF 与虚拟线程选型卡

## 1. 知识地图（先讲人话）

```
CompletableFuture（CF）是什么：
  一张"取餐凭证"。下单后立刻给你凭证（CF 对象），
  饭好了凭证会"完成"，你凭凭证拿结果——
  期间你可以继续干别的，也可以挂个"好了叫我"的回调。

五个最常用的方法（按出场频率）：
  supplyAsync(活)          → 派活，返回凭证
  thenApply(加工)          → 饭到手后再加工（同步接续）
  thenCompose(再派活)      → 干完这个接着干下一个（异步接续）
  thenCombine(合并)        → 两份饭都到，合成一桌
  allOf(全等) / anyOf(先到) → 拼单等全部 / 谁先到先吃

超时与降级（Agent 场景的保命两件套）：
  orTimeout(2s)            → 2 秒没好就异常（刹车！）
  completeOnTimeout(默认值, 2s) → 超时不炸，给默认结果（温柔版）
  exceptionally(兜底逻辑)  → 出错给个 Plan B
  ——M15 的三层刹车思想在异步世界的延续：
    没有超时的异步调用 = 没装刹车的 Agent（day11 呼应）

CF 和虚拟线程的分工（不搞二选一）：
  虚拟线程：解决"等待太多"——写起来像串行的直白代码
  CF：解决"编排复杂"——超时/合并/竞速/降级这些组合拳
  推荐搭配：虚拟线程跑任务 + CF 做超时与汇合的"外交协议"
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 大白话 |
|------|------|--------|
| CompletableFuture | 完成态凭证 | 异步任务的"取餐凭证" |
| thenApply vs thenCompose | 同步加工 vs 异步接续 | 前者"饭到再切"，后者"吃完再点下一单"（返回 CF 就用 Compose） |
| allOf / anyOf | 全部完成 / 任一完成 | 拼单全到齐 / 多家店谁快吃谁 |
| Fallback | 降级兜底 | 主路堵了走辅路 |
| Structured Concurrency | 结构化并发 | JDK 25 预览：一族任务同生共死统一管理 |

## 3. 动手实操

### 3.1 全家桶演示（对照注释逐行理解）

```java
public class CfKitchen {
    static CompletableFuture<String> cook(String dish, long ms) {
        return CompletableFuture.supplyAsync(() -> {
            sleep(ms); return dish + "好了";
        });
    }
    public static void main(String[] args) {
        // 拼单：两道菜并行，都到齐开饭（fan-in）
        CompletableFuture<String> meal = cook("鱼", 200)
                .thenCombine(cook("汤", 350), (fish, soup) -> fish + " + " + soup);
        // 超时刹车：3 秒没齐就报"太慢了"
        meal = meal.orTimeout(3, TimeUnit.SECONDS);
        // 降级兜底：报错也别让用户白等
        meal = meal.exceptionally(e -> "厨房出问题，给您上泡面");
        System.out.println(meal.join());   // 预期：鱼好了 + 汤好了
    }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (Exception ignored) {} }
}
```

### 3.2 给 ParallelToolExecutor 加"超时编排"（day11 升级版）

```java
// day11 用的是虚拟线程直等，现在给每个工具调用套上 CF 超时外壳：
List<CompletableFuture<ToolResult>> fs = calls.stream()
        .map(c -> CompletableFuture
                .supplyAsync(() -> tools.dispatch(c))          // 任务本体
                .orTimeout(2, TimeUnit.SECONDS)                // 单工具刹车
                .exceptionally(e -> ToolResult.fail("TIMEOUT", c.name() + " 超时降级")))
        .toList();
CompletableFuture.allOf(fs.toArray(CompletableFuture[]::new)).join();  // 总屏障
List<ToolResult> results = fs.stream().map(CompletableFuture::join).toList();
// 升级点：day11 只有"总屏障超时"，现在是"单工具超时+降级"双保险——
// 一个工具卡 2 秒不再拖累整轮（M15 的工具超时思想 × CF 的表达力）
```

### 3.3 竞速实验：anyOf 缓存预热场景

```java
// 场景：同时查 本地缓存(5ms) 和 数据库(80ms)，谁先到用谁
Object fastest = CompletableFuture.anyOf(
        CompletableFuture.supplyAsync(() -> { sleep(5);  return "cache-hit"; }),
        CompletableFuture.supplyAsync(() -> { sleep(80); return "db-hit"; })
).join();
// 坑位提醒：慢的那个还在跑（anyOf 不取消失败者）！
// 真正的竞速要配合 cancel 或结构化并发（JDK 25 预览的 StructuredTaskScope
// 支持 shutdownOnSuccess——一个赢了全家收工），能讲出这个细节=真用过
```

### 3.4 思考题

thenApply 和 thenCompose 的区别，用"点菜"讲清楚。（提示：thenApply = 饭到了在桌上加个菜（同步加工，不发起新异步）；thenCompose = 吃完这单用结果再点下一单（返回的还是异步凭证，要"展平"不套娃）。用错了会得到 CompletableFuture<CompletableFuture<T>> 套娃）

## 4. 面试连接

**Q：CompletableFuture 和虚拟线程什么关系，怎么选？**
> 解决的问题不同：虚拟线程解决"阻塞等待浪费线程"（代码写法直白）；CF 解决"异步编排"（超时、合并、竞速、降级）。我的搭配是：任务执行用虚拟线程，编排协议用 CF——比如工具调用 each 套 orTimeout 再 allOf 汇合。单选一个都有短板：纯 CF 回调地狱难读，纯虚拟线程缺编排表达力。

**Q：异步任务没有超时会怎样？**
> 无限挂起拖死资源——这是生产事故高发区。我给所有异步调用强制两道：单任务 orTimeout + 降级默认值，外加汇合层总超时。思想来自我 Agent 项目的三层刹车：异步世界没刹车比串行更危险，因为等待被隐藏了。

## 5. 今日验收清单

- [ ] CfKitchen 全家桶跑通（合菜/超时/降级三段都试）
- [ ] ParallelToolExecutor 升级为"单工具超时+降级"版
- [ ] anyOf 竞速实验完成，能讲"失败者不取消"的坑
- [ ] `git add . && git commit -m "day16-12: cf-orchestration"`
- [ ] 笔记：thenApply vs thenCompose 一句话卡

---
[← Day 11](day11-虚拟线程并行工具调用.md) | [本月目录](README.md) | [Day 13 · trace 异步写入 →](day13-trace异步写入.md)
