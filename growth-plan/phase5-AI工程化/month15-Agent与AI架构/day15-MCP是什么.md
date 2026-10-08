# Day 15 · MCP 是什么：工具生态的 USB-C

> **今日目标**：理解 MCP（Model Context Protocol，模型上下文协议）解决的真问题——M×N 适配地狱；用 USB-C 类比建立直觉；记牢面试定位答法："MCP 没有改变执行权在应用侧的安全模型"。
> **时长**：理论 1h / 概念图 0.5h / 输出 0.5h
> **今日产出**：M×N 问题推导图 + MCP 架构初稿 + 面试定位卡
> **对照教程**：`ai-learning/05-Agent与MCP协议.md` 第 4 节

## 1. 知识地图（先讲人话）

```
先看现状的痛（M×N 问题）：
  我们的工具是自己写的 Java 方法，挂在自己的菜单里——自家闭环没问题。
  但设想生态化的场景：
    M 个 AI 应用（客服系统/IDE 助手/办公 Copilot/手机助手…）
    N 个工具方（订单系统/Git/数据库/日历/我们项目四的知识库…）
  没有标准时：每个应用接每个工具都要写一遍私有适配 → M×N 份代码
    （3 个应用 × 4 个工具 = 12 份胶水代码，全是重复劳动）
  类比 2007 年前的手机充电器：诺基亚圆口/摩托罗拉扁口/索爱专用口——
    每换个手机换根线。USB-C 出现后：一根线全通用 → 生态爆炸。

MCP（Model Context Protocol）= AI 工具的 USB-C：
  Anthropic 推的开放标准，把"工具的定义/发现/调用"标准化：
    工具方：把自己的能力包成 MCP Server（写一次）
    应用方：内嵌 MCP Client，任何标准 Server 即插即用
  → M×N 份适配变成 M+N 份（每方各写一次，中间靠标准协议对话）

  ┌─────────────────┐  JSON-RPC   ┌─────────────────┐
  │ MCP Host（宿主） │ ←─标准协议→ │ MCP Server（工具）│
  │ 你的应用/IDE     │  stdio/HTTP │ 订单/知识库/Git  │
  │ 内嵌 MCP Client │             │ 暴露标准化能力    │
  └─────────────────┘             └─────────────────┘

面试定位答法（教材原文，一字不差背下来）：
  "Function Calling 是模型的能力机制，MCP 是基于它的生态协议；
   MCP 没有改变执行权在应用侧的安全模型，只是把工具定义
   与发现标准化了。"
  ——翻译：MCP 管的是"工具怎么登记、怎么被发现、怎么描述"，
    真调用时该有的三道闸、四层纵深、人工确认，一个都不能少。
    （有人以为上了 MCP 就安全了/模型自己执行了——两样都是误解）

冷静剂（架构师视角，本月心法③再次上场）：
  单体应用、自家工具、没有生态诉求 → 现在的菜单直挂就够好，
  MCP 是"要跟外面的世界打交道时"才需要的东西。
  标准是拿来解决真问题的，不是拿来追时髦的。
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| MCP | 模型上下文协议 | AI 工具接入的开放标准（工具的 USB-C） |
| M×N Problem | M×N 问题 | 每应用×每工具各写一遍适配（胶水地狱） |
| MCP Host | 宿主 | AI 应用本体（客服系统/IDE），内嵌 Client |
| MCP Client | 客户端 | 协议转换层（宿主里的"标准插口"） |
| MCP Server | 服务端 | 工具提供方（把能力按标准暴露） |
| JSON-RPC | JSON 远程调用 | MCP 底层消息格式（stdio 或 HTTP 传输） |

## 3. 动手实操：从 M×N 到 M+N 的推演

### 3.1 先算自家账（MCP 离我们有多远）

```powershell
# 盘点项目四/项目五的工具资产：
#   queryOrder / queryStock / queryPolicy(kbSearch) / processRefund
# 现状：私有 ToolResult 接口 + 自家菜单直挂
# 问题清单（诚实评估）：
#   □ 换一个宿主（如 IDE 里的 AI 插件）要复用 kbSearch？
#     → 现状要重写一遍适配（这就是 MCP Server 化的动机）
#   □ 隔壁团队想用我们的知识库检索？
#     → 现状=发 jar 包+他们写胶水；MCP 化=他们挂个 Server 地址就行
```

### 3.2 手写一次"迷你 MCP 对话"（看清协议本质）

```powershell
# MCP 底层就是 JSON-RPC——用两天前学的意图协议思路手动模拟：
# ① initialize（握手）：
#   → {"jsonrpc":"2.0","method":"initialize","params":{"protocolVersion":"2024-11-05"}}
#   ← {"result":{"serverInfo":{"name":"kb-server"},"capabilities":{...}}}
# ② tools/list（发现——工具菜单的标准化版！）：
#   → {"method":"tools/list"}
#   ← {"result":{"tools":[{"name":"kbSearch","description":"...","inputSchema":{...}}]}}
# ③ tools/call（调用——三道闸之前的那一步）：
#   → {"method":"tools/call","params":{"name":"kbSearch","arguments":{"keyword":"退款"}}}
#   ← {"result":{"content":[{"text":"...检索结果..."}]}}
# □ 对比 day03：tools/list = 我们的"菜单"，tools/call = 我们的"意图执行"
#   ——MCP 没有任何魔法，就是把这两件事标准化成了协议
```

### 3.3 MCP 架构初稿（项目五的 Server 化规划）

```
《项目五 MCP 化规划图》（day17 动手实现）：
  现有 agent/tool/* ──包一层──> KbMcpServer（教学版 JSON-RPC over stdio）
    暴露：kbSearch（Tool）/ kb_doc（Resource，day16 讲）/ 三类模板（Prompt）
  安全线不变：
    Server 侧照样过三道闸（参数/权限/审计）——
    协议标准化 ≠ 安全模型改变（背一遍定位答法）
```

## 4. 面试连接

**Q：MCP 是什么？解决什么问题？它和 Function Calling 什么关系？（本月热点题）**
> MCP 是 Anthropic 推的开放标准，叫模型上下文协议，定位是 AI 工具接入的 USB-C。它解决的真问题是 M×N 适配地狱：M 个 AI 应用要接 N 个工具，没有标准时每对组合都要写私有胶水代码，三个应用四个工具就是十二份重复劳动；MCP 把工具的定义、发现、调用标准化之后，工具方写一次 MCP Server，任何支持协议的宿主即插即用，复杂度从 M 乘 N 降到 M 加 N。和 Function Calling 的关系我用一句话定位：Function Calling 是模型的能力机制，决定模型能不能"点菜"；MCP 是基于它的生态协议，决定"菜单"怎么标准化地登记、被发现和被调用。要特别强调一点：MCP 没有改变执行权在应用侧的安全模型——真调用发生时，参数校验、权限校验、审计留痕、高危操作人工确认，这些闸门一个都不能少，协议标准化不等于安全豁免。最后补一个冷静判断：单体应用、自家工具闭环的场景，菜单直挂完全够用，MCP 是需要跨应用、跨团队共享工具能力时才值得上的东西——标准是解决真问题的，不是追时髦的。

## 5. 今日验收清单

- [ ] M×N→M+N 推导能白板画（含充电器类比）
- [ ] 迷你 MCP 三步对话手跑过（握手/发现/调用）
- [ ] 面试定位答法一字不差背下
- [ ] 项目五 MCP 化规划图成稿
- [ ] `git add . && git commit -m "day15-15: mcp-concept"`
- [ ] 笔记：定位答法卡贴墙（本月热点题的保命答案）

---
[← Day 14](day14-第二周复盘.md) | [本月目录](README.md) | [Day 16 · MCP 三类能力 →](day16-MCP三类能力.md)
