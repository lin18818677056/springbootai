# Day 09 · 流式输出：打字机效果背后的工程账

> **今日目标**：搞懂 SSE 流式输出的机制（为什么能一个字一个字往外蹦）；算清"流式 vs 非流式"的体验账和工程代价；知道什么场景必须流式、什么场景反而别用。
> **时长**：概念 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：SSE 流式调用记录 + 《流式决策卡》
> **对照教程**：`ai-learning/03-SpringAI与LangChain4j.md`（流式在 Java 侧的姿势）

## 1. 知识地图（先讲人话）

```
先回忆 day03：模型生成是自回归的——本来就是一个词一个词蹦出来的
  非流式：服务端把整段攒完才返回 → 用户盯着空白屏幕等 20 秒（体感"死机"）
  流式：服务端每生成一个词立刻推给你 → 打字机效果（体感"它在思考"）
  —— 总时间没变，变的是"第一个字多久出现"（TTFT）
  —— 体验公式：用户满意度 ≈ TTFT 决定，与总时长关系不大（等待心理学）

SSE（Server-Sent Events）= 流式输出的传输方式：
  HTTP 长连接 + text/event-stream，服务端不断写"一小块数据"
  每块是一个增量 delta（不是全文！客户端自己拼接）
  data: {"choices":[{"delta":{"content":"消息"}}]}
  data: {"choices":[{"delta":{"content":"队列"}}]}
  ...
  data: [DONE]                              ← 结束信号
  —— Java 工程师锚点：就是"分块传输/chunked"思想（M3 网络课的老朋友）

流式的工程代价（别只看爽）：
  ① 拼接责任在你：delta 要自己拼成全文（存历史/入库用全文）
  ② usage 可能不在流里：部分实现要把 stream_options 打开才回 usage，否则成本统计丢失
  ③ 错误处理变难：流到一半挂了怎么办？（重试=从头再来，或者续传要自己设计）
  ④ 结构化输出不友好：JSON 一半没用，流式给"人"看可以，给"程序"解析要等流结束

《流式决策卡》：
  交互式对话/大屏 → 必须流式（TTFT 就是产品体验）
  后台批处理/管道任务 → 别流式（没人看打字机，还多一层拼接复杂度）
  结构化输出 → 输出可以流，但"解析"必须等流结束（收完再 parse）
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| SSE | 服务器推送事件 | HTTP 长连接上服务端持续写小块数据 |
| delta | 增量块 | 每次推送的"新增几个字"（不是全文） |
| [DONE] | 结束标记 | 流结束信号（协议约定） |
| Perceived Latency | 感知延迟 | 用户"觉得"多久出结果（由 TTFT 决定） |
| Chunked Transfer | 分块传输 | HTTP 底层机制（M3 老朋友），SSE 的载体 |
| Stream Reassembly | 流拼接 | 客户端把 delta 拼回全文的责任（在你） |

## 3. 动手实操

### 3.1 实验①：亲眼看 SSE 长什么样

```powershell
# Ollama 的 OpenAI 兼容端点，加 "stream": true
# stream.json 的请求体在 call.json 基础上加一行 "stream": true
curl.exe -s -N http://localhost:11434/v1/chat/completions `
  -H "Content-Type: application/json" `
  -d @stream.json
# -N = 禁用缓冲（不然 curl 会攒一起一次性吐出来，看不到"流"）
# 预期：屏幕上一行行 data: {...} 滚出来，每行的 content 只有几个字
# 记录：数一下每块 delta 平均几个字；[DONE] 出现在哪
```

### 3.2 实验②：流式 vs 非流式的 TTFT 对比

```powershell
# 非流式：Measure-Command { curl.exe -s ... -d @call.json }
# 流式  ：Measure-Command { curl.exe -s -N ... -d @stream.json }
# 注意：流式的"总时长"不含网络缓冲优化时可能还略长——但它赢在首字时间
# 用长回答（比如"详细介绍 Kafka 的 10 个特性"）对比更明显：
#   非流式：空白等待 30s；流式：3s 开始滚字（体感差距巨大）
```

```
实验③：拼接逻辑手写（10 行伪代码，理解"责任在你"）
  full = ""
  for each chunk in sse_stream:
      delta = chunk.choices[0].delta.content
      if delta:  full += delta;  print(delta)   # 边收边显示
  # 流结束后：full 才是完整回答——存历史/入库存 full，别存最后一块 delta
  # 检查：chunk.choices[0].finish_reason == "stop" 才算完整（length=被截断）
```

## 4. 面试连接

**Q：流式输出的原理？什么时候必须用？（体验+工程题）**
> 原理：模型生成本来就是自回归的一个词一个词蹦，SSE 流式把这个过程逐块透传——HTTP 长连接上服务端持续写 text/event-stream，每块是几个字的增量 delta，客户端负责拼接，[DONE] 表示结束。核心收益不是总时长变短（总生成时间一样），而是感知延迟：第一个字 3 秒内出现，用户体感就是"在思考"，而空白等 30 秒体感是"死机"。决策纪律：交互式对话、大屏播报必须流式，TTFT 就是产品体验；后台批处理、管道任务不用流式——没人看打字机，白增一层拼接复杂度；结构化输出场景输出可以流但解析必须等流结束，JSON 拼到一半没有意义。

**Q：流式输出有哪些坑？（踩坑题，答出细节=真做过）**
> 四个坑。一是拼接责任：delta 是增量不是全文，要自己攒 full 文本再存历史或入库，我见过直接把最后一块 delta 存库导致数据只有尾巴的。二是 usage 丢失：部分实现流式默认不返回 token 用量，要显式开 stream_options，否则成本统计直接漏一截。三是中断处理：流到一半网络挂了，重试是从头生成——要么接受重试（交互场景可接受），要么设计断点续传（工程复杂度高，看业务值不值）。四是截断检测：流式也要检查最后一个 chunk 的 finish_reason，length 意味着被 max_tokens 截断，完整文本其实是不完整的。这四个坑的共同点：都是"流式把一步 API 变成了状态机"，状态机的每个转移都要有处理，不然就是线上 bug。

## 5. 今日验收清单

- [ ] SSE 原始流亲眼看过（delta/[DONE] 都见过）
- [ ] TTFT 对比实验完成（有 Measure-Command 数字）
- [ ] 拼接伪代码能手写（full/delta/finish_reason 三要素）
- [ ] 《流式决策卡》三条能讲
- [ ] `git add . && git commit -m "day13-09: streaming"`
- [ ] 笔记：画"非流式 vs 流式"的用户等待体验对比图

---
[← Day 08](day08-调用OpenAI兼容API.md) | [本月目录](README.md) | [Day 10 · 结构化输出 →](day10-结构化输出.md)
