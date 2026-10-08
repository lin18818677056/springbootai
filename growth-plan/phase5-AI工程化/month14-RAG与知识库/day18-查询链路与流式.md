# Day 18 · 查询链路与流式：SSE 清账之日

> **今日目标**：兑现 M13 诚实未达②——RagService 全流水线编排 + SSE 流式接口；citation/delta/done 三帧契约落地；首字延迟压进 3 秒。
> **时长**：编排编码 2h / 流式 1.5h / 压延迟 0.5h / 输出 0.5h
> **今日产出**：`RagService` + `POST /api/kb/ask`（SSE 版）+ 延迟拆解记录
> **对照教程**：`ai-learning/03-SpringAI与LangChain4j.md` 流式部分

## 1. 知识地图（先讲人话）

```
查询链路全流程（W1~W2 的零件 + day16/17 的工程件，总装）：
  请求进来
    → ① 鉴权+限流（M13 网关层手艺：谁能问、问多快）
    → ② 多轮改写？（sessionId 有历史 → 改写，day11 决策卡判断）
    → ③ 海选：向量 Top20 + BM25 Top20 并行（毫秒级）
    → ④ 取爹：子块命中 → 父块扩充（day13）
    → ⑤ 重排：Top8 → Top3（配置开关，秒开场景可跳过，day12）
    → ⑥ 组装：模板⑨号（有据+引用+拒答，day06）
    → ⑦ 生成：OllamaClient 流式调用（stream=true）
    → ⑧ 记账：usage/latency/引用 → call_log + 检索日志

流式为什么重要（M13 day09 的体感课，工程版）：
  等全部生成完再返回：3~10s 白屏 → 用户以为死了
  流式（SSE）：首字 1~2s 出现 → 用户看着字往外蹦，体感"很快"
  TTFT 决定体感，总时长决定完成感——流式两头都占。

SSE 三帧契约（day15 占位的今天兑现）：
  event: citation  data: [{no:1,docTitle:...}]   ← 检索完先发
    —— 用户在读引用时，正文还在生成（时间重叠，白赚体感）
  event: delta     data: {"t": "退款将在"}        ← 逐段正文
  event: done      data: {"usage":{...}}         ← 结束+记账
  类比：外卖 App——先显示骑手已接单（citation），
  再一路刷新位置（delta），最后送达确认（done）。

Spring 实现：SseEmitter（自带，不用引 WebSocket——
  单向推送场景 SSE 够用且是普通 HTTP，过网关/代理更省心）
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| SSE (Server-Sent Events) | 服务端推送 | HTTP 上的单向流（够用、简单、过网关友好） |
| SseEmitter | Spring 的 SSE 工具 | 服务端往连接里写事件帧 |
| TTFT | 首 token 延迟 | 第一个字出来的时间（体感核心） |
| First-Byte Optimization | 首包优化 | 检索完先发引用，正文边生成边发 |
| Pipeline Orchestration | 流水线编排 | RagService 把八步按序串起来 |
| Degraded Stream | 降级流 | 检索空/模型挂 → 拒答帧/错误帧（不白屏） |

## 3. 动手实操：RagService + SSE 接口

### 3.1 编排核心（八步串成一条线）

```java
// kb/service/RagService.java —— 查询链路总编排
public void ask(AskRequest req, SseEmitter emitter) {
    // ①②③④⑤ 检索段（同步，快）：
    String query = history ? rewriter.rewrite(req) : req.question();  // ②
    RetrievalResult r = retriever.retrieve(query, req.userId());      // ③④⑤
    //    （userId 用于权限过滤——day20 的钩子今天先留位）
    // ⑥⑦ 生成段（流式）：
    emitter.send(event("citation", r.citations()));                   // 先发引用
    if (r.isEmpty()) {                                                // 检索空
        emitter.send(event("delta", REFUSAL_TEXT));                   // 拒答帧
        emitter.send(event("done", usageOf(refusal)));
        return;                                                       // 不调模型
    }
    Prompt p = promptAssembler.grounded(query, r.parents());          // ⑥ 模板⑨
    ollamaClient.chatStream(p, delta ->
        emitter.send(event("delta", delta)));                         // ⑦ 逐段转发
    emitter.send(event("done", metering.flush()));                    // ⑧ 记账
}
// 异常兜底：任何一步抛 KbUnavailableException
//   → emitter.send(event("done", {"refusal":true,...})) + 转人工话术
//   —— 流式接口的降级也是"发一帧"，不是让连接干挂着
```

### 3.2 延迟拆解实验（把 3 秒花在哪看清楚）

```powershell
# 在八步各埋一个耗时日志（StopWatch），跑 5 个问题取平均：
#   改写 __ms | 双路检索 __ms | 取爹 __ms | 重排 __ms |
#   模型首 token __ms | 生成全程 __ms
# 预期分布：检索段合计 <300ms；模型首 token 1~2s（大头）；
#   生成全程 3~10s（流式掩盖）
# 首字体感 = 检索段 + 首 token ≈ 2s 内 → 达标（<3s）
```

```
《延迟拆解表》（填实测）+ 两个优化旋钮记录：
  旋钮A：跳过重排 → 首 token 提前 __ms（精度换速度，配置开关）
  旋钮B：Top3→Top2 → 窗口小了生成快 __%（引用变少，权衡记录）
```

### 3.3 流式降级三帧演练

```powershell
# 演练① 检索空（问库外题）→ citation 空数组 + 拒答 delta + done
# 演练② 模型挂（停 Ollama）→ citation 已发 + done(refusal=true)
#   —— 客户端永远收到收尾帧，不会白屏/转圈死
# 演练③ 重排超时 → 跳过重排直接 Top3（降级开关日志验证）
```

## 4. 面试连接

**Q：RAG 问答的流式接口怎么设计？延迟怎么优化？（工程收官题）**
> 接口用 SSE，Spring 自带 SseEmitter，单向推送场景比 WebSocket 简单，普通 HTTP 过网关代理都省心。帧协议三帧：citation、delta、done——检索完成后先把引用元数据推给前端，用户读引用的同时正文在生成，时间重叠白赚体感；正文逐段转发；结束帧带 usage 记账。编排上八步流水线：鉴权限流、多轮改写、双路检索、取爹、重排、模板组装、流式生成、记账埋点。延迟上我做过拆解：检索段合计三百毫秒以内，大头是模型首 token 的一两秒，所以首字体感两秒左右达标。优化两个旋钮都有代价记录：跳过重排省几百毫秒但 MRR 掉分——所以做成配置开关按场景切换；缩 Top 数省生成时间但引用变少。降级设计最重要的细节是流式接口的降级也是发帧：检索空发拒答帧，模型挂发 refusal 的 done 帧——客户端永远收到收尾信号，不会白屏转圈死。这套设计和 M13 的流式理论对上了：TTFT 决定体感，拼接责任在客户端，现在补上了工程实现。

## 5. 今日验收清单

- [ ] RagService 八步编排跑通（30 条回归过）
- [ ] SSE 三帧契约实测（curl -N 看到三帧顺序）
- [ ] 首字 <3s 达标（延迟拆解表填实）
- [ ] 流式降级三演练通过（客户端永不白屏）
- [ ] `git add . && git commit -m "day14-18: rag-service-sse"`
- [ ] 笔记：M13 未达②正式销账（两大欠账都清了）

---
[← Day 17](day17-入库链路.md) | [本月目录](README.md) | [Day 19 · 引用溯源落地 →](day19-引用溯源落地.md)
