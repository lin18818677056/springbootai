# Day 08 · 调用 OpenAI 兼容 API：工程师的第一次握手

> **今日目标**：用 curl 裸调一次 OpenAI 兼容接口（Ollama 本地也兼容同一协议）；看懂请求/响应的每个字段，尤其是 usage（用量）——成本意识从这里开始实名化。
> **时长**：实操 2h / 输出 0.5h
> **今日产出**：curl 调用记录 + 请求/响应字段解剖图 + 成本实测
> **对照教程**：`ai-learning/01-大模型基础原理.md` 第 6 节、`03-SpringAI与LangChain4j.md`（M13 后期再深入）

## 1. 知识地图（先讲人话）

```
OpenAI 兼容 API = 大模型界的"JDBC"：
  各家模型（OpenAI/DeepSeek/通义/Ollama 本地）都实现同一套 HTTP 协议
  → 学一次，处处能用；换模型=换 url 和 key，代码不动
  —— Java 工程师秒懂：这就是"面向接口编程"，协议就是那个接口

一次调用的最小骨架（POST /v1/chat/completions）：
  请求三要素：
    model：用哪个模型（qwen2.5:7b / deepseek-chat / gpt-4o ...）
    messages：消息数组（角色+内容）——注意：多轮=把历史也放进去（day01 结论②）
      [ {"role":"system","content":"你是..."},      ← 系统提示（人设/规则）
        {"role":"user","content":"用户的问题"} ]     ← 用户消息
    temperature / max_tokens：day05 的旋钮
  响应四看点：
    choices[0].message.content ← 真正的回答（藏得最深，别用错字段）
    usage.prompt_tokens        ← 输入多少 token（计费①）
    usage.completion_tokens    ← 输出多少 token（计费②，单价更高）
    finish_reason              ← 为什么停：stop 正常 / length 被截断（day05 坑的信号！）

Ollama 的兼容端点：http://localhost:11434/v1/chat/completions（key 随便填）
  —— 本月所有实验都能零成本本地跑
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| OpenAI-Compatible API | OpenAI 兼容接口 | 大模型调用的事实标准协议 |
| chat/completions | 对话补全端点 | 最常用的调用入口 |
| system/user/assistant | 三种角色 | 规则制定者/提问者/模型自己 |
| finish_reason | 结束原因 | stop=说完；length=被截断（要警觉） |
| usage | 用量 | prompt/completion token 数——账单明细 |
| Rate Limit | 限流 | 每分钟请求数/token 数上限（429 错误） |

## 3. 动手实操：curl 裸调解剖

### 3.1 实验①：第一次握手（Ollama 本地，零成本）

```powershell
# 存成 call.json（请求体）后用 curl 发送
# call.json:
# {
#   "model": "qwen2.5:7b",
#   "messages": [
#     {"role": "system", "content": "你是一个严谨的技术助理，回答不超过100字"},
#     {"role": "user", "content": "什么是消息队列？"}
#   ],
#   "temperature": 0.2,
#   "max_tokens": 200
# }
curl.exe -s http://localhost:11434/v1/chat/completions `
  -H "Content-Type: application/json" `
  -d @call.json
# 有付费 API 的把 url/key 换掉即可，请求体一模一样
```

```
解剖练习（对着响应逐字段标注）：
  choices[0].message.content → "消息队列是一种..."   ← 回答在这
  finish_reason → "stop"                            ← 正常说完
  usage.prompt_tokens → 28 / completion_tokens → 96 ← 本次花了 124 token
  —— 实验：把 max_tokens 改成 30 重发 → finish_reason 变 "length"
     且 content 半截 → day05 的"里程刹车"现场，字段会说话
```

### 3.2 实验②：system 的作用对照（角色分工体感）

```
同一条 user 消息，两种 system：
  A. system: "你是严谨的技术助理，回答不超过100字"
  B. system: "你是儿童科普作家，用比喻回答，回答不超过100字"
  → 同问题两种口吻——system 就是"岗位说明书"（但防线仍靠工程，day06）
记录两版输出，体会"角色是最高优先级的指令"
```

### 3.3 实验③：成本实名化

```
拿实验①的真实 usage 算账：
  单次成本 = prompt_tokens × 输入单价 + completion_tokens × 输出单价
  （查所用模型的价目表；本地模型成本≈电费，但流程一样要跑——习惯比数字重要）
顺手把 day02 的《上下文预算表》里的"估算值"替换成"实测值"
—— 估算→实测校准，和吞吐倒推→压测校准是同一套工程动作
```

## 4. 面试连接

**Q：讲讲 OpenAI 兼容接口？为什么它成了事实标准？（架构题）**
> 它是大模型界的 JDBC：请求就是 model 加 messages 数组（system 定规则、user 提问、assistant 是历史回答），响应里 choices 给内容、usage 给 token 用量、finish_reason 给结束原因。成为事实标准的原因很简单——OpenAI 先发占了生态，后来者（国内外各家、以及 Ollama 这类本地运行时）为了让存量代码零成本迁移，纷纷兼容同一协议。工程收益是解耦：我的应用层代码只依赖协议不依赖具体厂商，换模型改个 url 和 key 就行，选型自由度（day22）由此而来。唯一要注意的是"兼容程度"差异：结构化输出、function calling 这些高级特性各家支持度不一，选型时要把协议覆盖面当一项指标。

**Q：finish_reason 有什么用？（细节题，答出=真调过）**
> 它是"结束原因"的自述：stop 表示正常说完；length 表示被 max_tokens 截断——这是 day05 说的"JSON 被腰斩"bug 的直接信号，生产上必须检查这个字段，发现 length 就要告警或重试放大 max_tokens；还有内容过滤类的 finish_reason（安全拦截）也要区分处理。我的实践是把它当"健康指标"用：finish_reason 的分布突然变化（比如 length 占比升高），往往说明用户输入变长了或者 Prompt 漂移了，是比报错更早的预警信号。

## 5. 今日验收清单

- [ ] curl 裸调成功（响应 JSON 完整保存）
- [ ] 逐字段解剖练习完成（尤其 finish_reason 的 length 实验）
- [ ] system 角色对照实验完成
- [ ] 成本公式用实测 token 算过一遍
- [ ] `git add . && git commit -m "day13-08: first-api-call"`
- [ ] 笔记：请求/响应字段解剖图

---
[← Day 07](day07-第一周复盘.md) | [本月目录](README.md) | [Day 09 · 流式输出 →](day09-流式输出.md)
