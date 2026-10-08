# Day 23 · 并行调用与流式落地：优化点①②复测闭合

> **今日目标**：把 day11 并行工具和 day13 异步留痕的复测数字补全（台账四格闭合）；再加一道"感知延迟"优化——SSE 流式中间帧。
> **时长**：实操 2h / 复测 1h / 输出 0.5h
> **今日产出**：台账优化点①②复测数字 + 流式中间帧上线

## 1. 知识地图（先讲人话）

```
今天做两件事：

① 复测闭合（工程纪律）：
   优化点①②在 W2 就改造完了，但复测数字一直空着——
   台账的规矩：四格不全 = 白干。今天用 day04 的 MiniLoad
   和 day01 的分段计时把数字补上。

② 流式中间帧（感知延迟优化）：
   实际延迟：等模型想完+工具查完才出结果（没变快）
   感知延迟：第 1 秒就让用户看到"正在思考 → 正在查订单 → ..."
   ——外卖 App 的"骑手已接单"：骑手没变快，但你不焦虑了。
   对 Agent 尤其重要：多轮循环本来就要好几秒，
   黑盒等待 = 用户以为死了；过程可见 = 体验立涨。

流式帧设计（SSE 三种帧）：
  thinking 帧：每轮模型开想时推送 {round, "正在分析..."}
  tool 帧：工具发起/返回时推送 {tool, elapsed}
  answer 帧：最终答案（追加式 token 流，M14 的老朋友）
  协议：event: 类型 + data: JSON——前端按类型渲染
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 大白话 |
|------|------|--------|
| Perceived Latency | 感知延迟 | 用户"觉得"等多久（可被进度反馈压缩） |
| SSE (Server-Sent Events) | 服务器推送事件 | 服务端单向流式推送（M14 流式输出的老朋友） |
| Intermediate Frame | 中间帧 | 结果没到，过程先报（"正在想/正在查"） |
| Token Streaming | 令牌流 | 答案边生成边推（不等全文） |
| TTFT (Time To First Token) | 首字时间 | 用户看到第一个字之前等的时间——流式优化的核心指标 |

## 3. 动手实操

### 3.1 复测闭合（台账四格补全）

```powershell
# 优化点①并行：改造后双工具轮次 20 次分段计时
#   串行基线 400ms → 并行 ___ms（预期 ≈220ms，-45%）→ 填台账
# 优化点②异步：Arthas 复测 save 耗时
#   同步 45ms → 异步 offer 微秒级；主链路增量 <1ms ✓ → 填台账
# 然后跑一遍全链路（day04 MiniLoad 并发 5 档）留 day25 对比用
```

### 3.2 流式中间帧落地（SSE 端点改造）

```java
@GetMapping(value = "/agent/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter ask(@RequestParam String q) {
    var emitter = new SseEmitter(60_000L);
    agentLoop.run(q, new LoopListener() {              // 循环里埋三个钩子
        public void onThinking(int round) {           // thinking 帧
            send(emitter, "thinking", Map.of("round", round, "msg", "正在分析..."));
        }
        public void onTool(String name, long ms) {    // tool 帧
            send(emitter, "tool", Map.of("tool", name, "elapsed", ms));
        }
        public void onAnswerChunk(String piece) {     // answer 帧（token 流）
            send(emitter, "answer", Map.of("piece", piece));
        }
    });
    return emitter;
}
// 要点：钩子是"发布订阅"不是硬编码——LoopListener 接口解耦
// 循环逻辑和推送逻辑（M7 DDD 的老思想：领域不管展示）
```

### 3.3 TTFT 实测（感知优化的数字）

```powershell
# curl -N http://localhost:8080/agent/ask/stream?q=测试
# 手表计时：第一个 thinking 帧到达 ≈ ___ ms（原来用户等到答案 ≈ 数秒）
# TTFT 从"秒级"降到"百毫秒级"——写进台账（感知优化行）
```

### 3.4 思考题

流式中间帧有什么副作用？（提示：①SSE 连接占用服务资源——客户端断开要正确清理 emitter；②"正在想"的帧太多也是噪音——限频；③异常时要推送 error 帧并关闭连接，否则前端永远转圈。进度反馈是把双刃剑：断了的进度条比没有进度条更伤信任）

## 4. 面试连接

**Q：除了让代码更快，还有什么办法优化体验？**
> 优化感知延迟：流式中间帧让用户第 1 秒看到"正在分析"，TTFT 从秒级降到百毫秒级——实际总时长没变，但等待焦虑没了。原理是心理学：黑盒等待最痛苦，过程可见即可接受。副作用要管住：连接清理、帧限频、异常必须推 error 帧关闭。

**Q：你的优化台账长什么样？**
> 四格：基线→归因→改造→复测，一行一个优化点。目前四行全部闭合：双工具 400→220ms、留痕 45ms→<1ms、存储 -70%、GC P99 <50ms。台账的价值是"证据链"——任何人质疑某个数字，我都能翻出当时的测量环境和复测脚本。

## 5. 今日验收清单

- [ ] 优化点①②复测数字入台账（四格闭合 ✓✓）
- [ ] SSE 三种帧上线，curl -N 实测 TTFT 记录
- [ ] 能讲"感知延迟 vs 实际延迟"+外卖 App 类比
- [ ] `git add . && git commit -m "day16-23: streaming-retest"`
- [ ] 笔记：台账全景图更新（2/4 闭合）

---
[← Day 22](day22-项目六总设计.md) | [本月目录](README.md) | [Day 24 · 采样与分层落地 →](day24-采样与分层落地.md)
