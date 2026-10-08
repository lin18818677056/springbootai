# Day 19 · 多智能体入门：主 Agent 拆解，子 Agent 干活

> **今日目标**：认识 Multi-Agent 的主管模式（Orchestrator-Workers）——主 Agent 拆解分派、子 Agent 各司其职、结果汇总；用教学版双 Agent 跑通一次协作；记录通信与上下文隔离的第一手体感。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：双 Agent 教学版代码 + 协作时序记录 + Multi-Agent 架构图
> **对照教程**：`ai-learning/05-Agent与MCP协议.md` 第 3 节 Multi-Agent 行

## 1. 知识地图（先讲人话）

```
Multi-Agent 的主管模式（最常用的编排）：

  ┌─────────────────────────────┐
  │ 主 Agent（主管/Orchestrator）│ ← 只做两件事：拆任务 + 汇总
  └──────┬──────────┬───────────┘
         │ 派活      │ 派活
   ┌─────▼────┐ ┌───▼──────┐
   │ 子 Agent A│ │ 子 Agent B│ ← 各自带专属工具+专属 System Prompt
   │ 退款专员  │ │ 技术支持  │   （子 Agent 内部自己跑 ReAct 循环）
   └─────┬────┘ └───┬──────┘
         └────┬─────┘
        结果交回主 Agent 汇总成最终答案

类比：客服中心。
  用户打进电话 → 前台（主 Agent）判断这是俩事：
    "我要退款，而且系统报错"
  → 转退款专员（子 A：带退款工具+退款话术）
  → 转技术支持（子 B：带日志查询工具+排障话术）
  → 前台收两份处理结果，合成一段话回复用户

为什么要拆？（Multi-Agent 的真实价值就三个，今天验证前两个）
  ① 专业隔离：每个子 Agent 的 System Prompt 精而专
    （退款专员不用懂排障——Prompt 短而准，幻觉更少）
  ② 上下文隔离：A 的十轮工具调用记录不会撑爆 B 的窗口
    （单 Agent 干两件事时，两摊历史混在一起互相干扰）
  ③ 并行加速：两个子 Agent 可以同时跑（day24 压测预埋）

子 Agent 的通信契约（拆与合的关键）：
  主 → 子：派工单（目标+约束+输出格式）——像 day09 的计划清单
  子 → 主：结构化回执（结论+依据+置信度/失败原因）
  ——不能只是"一坨文本"：主 Agent 要能比对、能追问、能汇总
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| Multi-Agent | 多智能体 | 多个 Agent 分工协作（主管+专员） |
| Orchestrator-Workers | 主管模式 | 主 Agent 拆解分派、子 Agent 执行（最常用编排） |
| Sub-Agent | 子智能体 | 专职专工具专 Prompt 的执行单元（内部自己跑循环） |
| Dispatch | 派工 | 主 Agent 发出的结构化任务单 |
| Context Isolation | 上下文隔离 | 各子 Agent 历史分开（互不污染） |
| Result Synthesis | 结果汇总 | 主 Agent 把回执合成最终答案 |

## 3. 动手实操：教学版双 Agent

### 3.1 场景与拆分

```
任务："我的订单 S202609120037 要退款，顺便帮我看看物流为啥卡三天了"
单 Agent 的问题：一个 Prompt 又当退款客服又当物流侦探，
  工具菜单四个混着，day06 考卷证明菜单越长选择越糊。

拆法：
  主 Agent：不带业务工具（只带"派工"这个元工具 dispatch）
  子 Agent A 退款专员：processRefund（走 day13 审批流）+ queryOrder
  子 Agent B 物流侦探：queryOrder + kbSearch（物流政策）
  ——各自 System Prompt 只讲自己那摊事
```

### 3.2 双 Agent 协作代码骨架（关键 35 行）

```java
// Orchestrator.java —— 主管模式教学版
String plan = llm.chat(dispatchPrompt, userMsg);        // 主 Agent 拆解
// plan 示例：DISPATCH|refund|处理订单退款申请
//           DISPATCH|logistics|排查订单物流停滞原因
List<String> receipts = new ArrayList<>();
for (String task : parseDispatches(plan)) {             // （可并行，教学版串行）
    String subPrompt = SUB_PROMPTS.get(task.domain());  // 专员专属 Prompt
    String receipt = subAgentRun(subPrompt, task, subTools(task.domain()));
    receipts.add(receipt);                              // 结构化回执
}
String answer = llm.chat(synthesizePrompt, receipts);   // 主 Agent 汇总
// □ subAgentRun 内部就是 day08 的 ReAct 循环（含三层刹车）——复用！
```

```powershell
# 跑通并记录协作时序：
#   主:拆出 2 单 → 子A:发起退款→进审批(day13 流) → 回执"待审批"
#   → 子B:查物流+检索政策 → 回执"转运中心积压，预计+2 天，政策依据#3"
#   → 主:汇总话术（含审批进度+物流解释）
# □ 观察：主 Agent 全程没碰业务工具（它只管拆和合）
```

### 3.3 拆分收益的验证实验

```powershell
# 对照实验：同一任务 单 Agent（四工具）vs 双 Agent
#   记录：工具选择正确次数 | Prompt 总长度（上下文压力）| 汇总质量
#   预期：双 Agent 的子任务正确率更高、各自窗口更干净
#   但也记录代价：多两次模型调用（延迟+）| 代码复杂度+
```

## 4. 面试连接

**Q：多智能体怎么协作？什么场景值得拆？（架构题，明天是选型警告篇）**
> 我们用的是最常用的主管模式：主 Agent 负责拆解和汇总，本身不带业务工具；子 Agent 按专业分工，各自带专属工具和专属 System Prompt，内部自己跑 ReAct 循环——刹车、三道闸这些安全件原样复用。举个真实场景：用户一句话提了两个诉求——退款加物流查询，单 Agent 的话四个工具混一个菜单，我们 day06 的考卷证明菜单越长选择越糊；拆成退款专员和物流侦探后，各自菜单只有两三个工具，Prompt 短而准，正确率回血。通信靠结构化契约：主 Agent 发结构化派工单，子 Agent 回结构化回执——结论、依据、置信度，主 Agent 才能比对和汇总。拆分真正的收益有三个：专业隔离、上下文隔离——两个子 Agent 的十轮工具历史互不污染，单 Agent 干两摊事时历史混在一起容易互相干扰——以及可并行的加速潜力。但今天必须先埋一句话：Multi-Agent 不是银弹，每加一个 Agent，延迟、成本、调试难度全在乘——明天我会专门讲什么情况下坚决不拆。

## 5. 今日验收清单

- [ ] 双 Agent 协作跑通（时序记录完整）
- [ ] 结构化派工单/回执契约落地（不是一坨文本）
- [ ] 对照实验有数据（单 vs 双的正确率/上下文/代价）
- [ ] 主 Agent"只拆不合不碰业务工具"的纪律能讲
- [ ] `git add . && git commit -m "day15-19: multi-agent"`
- [ ] 笔记：客服中心类比卡（前台/专员/回执）贴墙

---
[← Day 18](day18-MCP架构与传输.md) | [本月目录](README.md) | [Day 20 · MultiAgent 选型警告 →](day20-MultiAgent选型警告.md)
