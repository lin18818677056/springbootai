# Day 11 · 虚拟线程并行工具调用：M15 交接卡①落地

> **今日目标**：把 Agent 循环里的"串行工具调用"改成"并行"——查订单和查库存同时出发；给并行加上安全约束（只读才并行）；实测改造前后对比。
> **时长**：理论 0.5h / 实操 2h / 输出 0.5h
> **今日产出**：ParallelToolExecutor 落地代码 + before/after 数字

## 1. 知识地图（先讲人话）

```
M15 的痛点回顾（交接卡①）：
  用户："帮我查订单 X 的状态，顺便看这个商品还有货吗？"
  模型一轮点了两道菜：queryOrder + queryStock
  M15 的循环是这么干的：
    queryOrder(200ms) → 等 → queryStock(200ms) → 等 → 汇总
    总耗时 400ms，但这两件事八竿子打不着！

  就像让同一个人跑两个部门盖章——明明可以让两个人分头跑。

改造方案（三步）：
  ① 识别可并行：模型一次返回多个工具调用 = 官方"并行点菜"信号
     （Function Calling 本来就支持一次返回多个 call）
  ② 虚拟线程分头执行：每个工具一个虚拟线程（day10 的红利）
  ③ 全部落地再汇总：等最慢的那个（约 200ms 而不是 400ms）

安全约束（比性能更重要的一条）：
  只读工具（queryXxx）可以并行；
  写操作工具（processRefund）绝不允许和其他工具并行——
  它要独占执行（防止"边退款边改单"的竞态灾难）。
  这条规矩来自 M15 的三道闸思想：性能优化不许突破安全边界。

类比：
  并行 = 两个外卖员同时取两单（互不影响）
  写操作串行 = 收银台一次只处理一单（动钱的必须排队）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 大白话 |
|------|------|--------|
| Parallel Tool Calls | 并行工具调用 | 模型一次点多道菜，厨房分头做 |
| Fan-out / Fan-in | 扇出 / 汇合 | 分头去查 / 结果汇总 |
| Read-only Isolation | 只读隔离 | 只有互不影响的任务才允许并行 |
| Barrier | 屏障 | "都干完再继续"的汇合点（awaitTermination/allOf） |
| Race Condition | 竞态 | 两个任务同时改一个东西，结果看谁手快（必须防） |

## 3. 动手实操

### 3.1 ParallelToolExecutor（落地代码，50 行内）

```java
public class ParallelToolExecutor {
    private static final Set<String> READ_ONLY = Set.of(
            "kbSearch", "queryOrder", "queryStock", "queryLogistics");

    /** 执行本轮模型点的一批菜：只读并行，有写操作就整体串行 */
    public List<ToolResult> executeAll(List<ToolCall> calls) {
        boolean hasWrite = calls.stream().anyMatch(c -> !READ_ONLY.contains(c.name()));
        if (hasWrite || calls.size() == 1) {
            // 安全阀：含写操作 → 按顺序一个一个来（绝不并行）
            return calls.stream().map(this::safeCall).toList();
        }
        // 并行通道：每个只读工具一个虚拟线程
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = calls.stream()
                    .map(c -> executor.submit(() -> safeCall(c)))
                    .toList();
            return futures.stream().map(this::join).toList();  // 屏障：等全部落地
        }
    }

    private ToolResult safeCall(ToolCall c) {
        try { return tools.dispatch(c).withTimeout(2, TimeUnit.SECONDS); }
        catch (Exception e) { return ToolResult.fail("TOOL_ERROR", e.getMessage()); }
    }
    private ToolResult join(Future<ToolResult> f) {
        try { return f.get(3, TimeUnit.SECONDS); }             // 汇合也设上限
        catch (Exception e) { return ToolResult.fail("FANIN_TIMEOUT", "汇总超时"); }
    }
}
```

### 3.2 改造前后对比（台账优化点①复测格）

```powershell
# 用 day01 的分段计时脚本（改造后版本）连跑 20 轮：
# 串行基线：双工具轮次 平均 ___ ms（约 400ms 量级）
# 并行改造：双工具轮次 平均 ___ ms（约 220ms 量级，省 ≈45%）
# 再跑 day04 的 MiniLoad 压测：并发 5 档 P99 从 ___ → ___
# 数字全部填进台账——四格证据链闭合一半
```

### 3.3 一个边界实验（验证安全阀）

```
构造一轮点菜：[queryOrder, processRefund]（一读一写混点）
预期行为：走串行通道（hasWrite=true），日志打印
  "write-op detected, fallback to serial"
这验证了：性能优化没有绑架安全——退款永远独占执行。
```

### 3.4 思考题

三个工具 A(50ms)、B(200ms)、C(200ms) 并行执行，总耗时接近 200ms 而不是 450ms。但如果 A 是"查询后要锁库存"的准写操作呢？（提示：并行的前提是"互不依赖且只读"——准写操作混进并行批次会互相污染结果；识别不了就保守串行，"宁可慢，不可错"）

## 4. 面试连接

**Q：你怎么做串行转并行优化的？**
> 三步：先识别无依赖任务（模型一次返回多个工具调用就是官方信号）；再选择并行手段（IO 型用虚拟线程，每任务一线程不用池化）；最后设汇合屏障和超时（都落地才继续，防个别任务拖死）。实测双工具从 400ms 到 220ms。

**Q：并行有什么风险？你怎么防？**
> 三类：竞态（写操作并行互相污染——我的规矩是含写操作整体回退串行）、资源风暴（并发放大下游压力——汇合有超时、下游有限流）、部分失败（一个工具炸了别全炸——safeCall 单独兜底）。性能优化永远不突破安全边界，这是我从 M15 的三道闸带过来的纪律。

## 5. 今日验收清单

- [ ] ParallelToolExecutor 落地，双工具轮次实测对比入台账
- [ ] 安全阀实验：写操作混入时回退串行（日志为证）
- [ ] 能讲清 fan-out/fan-in 和"只读才并行"的理由
- [ ] `git add . && git commit -m "day16-11: parallel-tools"`
- [ ] 笔记：画"串行 400ms vs 并行 220ms"时序对比图

---
[← Day 10](day10-虚拟线程入门.md) | [本月目录](README.md) | [Day 12 · CompletableFuture 编排 →](day12-CompletableFuture编排.md)
