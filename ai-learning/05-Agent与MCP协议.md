# 05 · Agent 与 MCP 协议：从"会聊天"到"会办事"

> **本篇目标**：掌握 Agent（智能体）的核心循环与工程化要点：Function Calling（函数调用）、ReAct（推理-行动循环）、规划与多智能体协作、MCP（Model Context Protocol，模型上下文协议）生态。**架构师定位：知道什么时候不该用 Agent，和知道怎么用一样重要。**

## 术语表

| 英文 | 中文 |
|------|------|
| Agent | 智能体（LLM + 工具 + 循环 + 记忆 = 会办事的系统） |
| Function Calling / Tool Use | 函数调用 / 工具使用 |
| ReAct (Reason + Act) | 推理-行动循环（思考→行动→观察→再思考） |
| Plan-and-Execute | 计划-执行（先拆解任务清单，再逐项执行） |
| Reflexion | 反思（执行失败后自我复盘再重试） |
| Multi-Agent | 多智能体（多个角色分工协作，如程序员+评审员） |
| MCP (Model Context Protocol) | 模型上下文协议（AI 工具接入的开放标准） |
| MCP Host / Client / Server | 宿主（如 IDE/应用）/ 客户端（协议转换）/ 服务端（工具提供方） |
| Tool / Resource / Prompt | MCP 三类能力：工具（可调用）/ 资源（可读取）/ 提示（可复用） |
| Human-in-the-loop | 人在回路（关键动作需人工确认） |
| Guardrail | 护栏 |

## 1. Agent 的本质：一个带工具的循环

```
while (未完成 && 轮次 < MAX) {
    思考：模型根据目标+历史+工具清单，决定"回答"或"调用工具"
    行动：应用侧执行工具（查库/调 API/写文件），权限校验在此！
    观察：工具结果回填给模型
}
输出最终答案（或超限转人工）
```

**三句本质**：
1. 模型不执行任何东西，只输出"调用意图"——执行权永远在应用侧。
2. Agent = LLM 做决策引擎 + 传统代码做手脚与安全带。
3. 所有可靠性问题（死循环/幻觉参数/越权）都在循环里发生，也都在循环里防御。

## 2. Function Calling 工程要点

```java
@Tool(description = "按订单号查询物流。orderNo: S开头订单号，如 S2026xxxx")
public Logistics queryLogistics(String orderNo) {
    // 1. 参数校验（模型输出不可信！）
    if (!orderNo.matches("S\\d{10,}")) throw new BizException("订单号格式错误");
    // 2. 权限校验（必须以应用侧身份为准，绝不信模型自述的 userId）
    security.assertOrderOwner(orderNo, CurrentUser.id());
    return logisticsService.query(orderNo);
}
```

**五条军规**：
1. description（工具描述）写给模型看：何时该调 + 参数格式，含糊描述 = 选错工具。
2. 模型产出的参数一律当不可信输入：格式校验 + 存在性校验 + 权限校验。
3. 工具数量 ≤ 10 个为宜：多了做意图路由分组动态挂载。
4. 工具失败信息要"可被模型理解"（返回结构化错误说明，模型能向用户转述或重试）。
5. 敏感操作（退款/删除）必须 Human-in-the-loop（人工确认）。

## 3. 复杂任务的两种模式

| 模式 | 流程 | 适用 |
|------|------|------|
| ReAct 循环 | 走一步看一步 | 未知路径的探索型任务（排障/查数） |
| Plan-and-Execute | 先产出计划清单 → 逐项执行 → 汇总 | 步骤可预期的结构化任务（生成周报） |
| Multi-Agent | 主 Agent 拆解分派 → 子 Agent 各司其职 → 汇总 | 角色专业分工明确（代码生成+测试+评审） |

**架构师警告**：Multi-Agent 不是银弹——每加一个 Agent，延迟/成本/调试难度都乘上去。**先问：单 Agent + 更好的工具设计能不能解决？** 大多数场景答案是能。

## 4. MCP：工具生态的 USB-C 标准

**解决什么**：以前每个 AI 应用 × 每个工具都要写一遍私有适配（M×N 问题）；MCP 把工具的"定义/发现/调用"标准化 → 工具方写一次 MCP Server（服务端），所有支持 MCP 的应用即插即用（M+N）。

```
MCP Host（宿主：你的应用/IDE）内嵌 MCP Client（客户端）
    ↕ JSON-RPC（标准协议，stdio 或 HTTP 传输）
MCP Server（服务端：数据库工具/订单工具/Git 工具…）
    暴露三类能力：
    Tools 工具（模型可调用，如 query_order）
    Resources 资源（可读取的数据，如文件/表结构）
    Prompts 提示模板（预置任务模板）
```

**Java 落地**：Spring AI 提供 MCP Client/Server Starter——把项目四的 OrderTools 包成 MCP Server，Claude Desktop/IDE 等任何 MCP 宿主都能直接调用你的业务工具。这就是"AI 应用互联"的雏形。

**面试定位答法**："Function Calling 是模型的能力机制，MCP 是基于它的生态协议；MCP 没有改变执行权在应用侧的安全模型，只是把工具定义与发现标准化了。"

## 5. Agent 可靠性工程（生产级的关键）

| 风险 | 防御 |
|------|------|
| 死循环烧钱 | MAX_ITERATIONS 上限 + 单任务 Token 预算熔断 |
| 幻觉参数/越权调用 | 参数白名单 + 服务端权限校验 + 审计日志 |
| 提示注入劫持 | 输入隔离 + 工具白名单 + 高危动作人工确认 |
| 工具超时拖垮会话 | 工具级超时 + 降级响应（"该查询暂不可用"） |
| 错误难以归因 | 全量留痕：每轮的思考摘要/工具入参出参/Token 消耗 |

**验收级问题**（上线前自问）："如果模型今天抽风连续调错 20 次工具，最坏损失是什么？"——答不上来就是没做防护。

## 6. 实战路径（结合项目四）

1. 单工具：知识库 + queryOrder 工具（Function Calling 闭环）。
2. 多工具 + 路由：加 queryStock/退货政策检索，观察模型选择正确率。
3. 循环控制：人为造死循环工具，验证 MAX_ITERATIONS 与熔断。
4. MCP 化：把工具包成 MCP Server，用 MCP 宿主接入体验生态价值。
5. 复盘：产出"Agent 上线检查单"（迭代上限/权限/预算/审计/人工确认五项）。

## 验收自测

- [ ] 能画出 Agent 循环并说清"执行权在应用侧"的安全含义
- [ ] 能默写 Function Calling 五条军规
- [ ] 能对比 ReAct / Plan-and-Execute / Multi-Agent 并给出选型警告
- [ ] 能讲清 MCP 解决的 M×N 问题与三类能力
- [ ] 能列出 Agent 可靠性五风险五防御
- [ ] 完成项目四 step04 的全部验收项

## 延伸阅读

- Anthropic《Building Effective Agents》（最佳实践短文，英文，架构师必读）
- MCP 官方规范 modelcontextprotocol.io（英文，重点读 Architecture 章节）
