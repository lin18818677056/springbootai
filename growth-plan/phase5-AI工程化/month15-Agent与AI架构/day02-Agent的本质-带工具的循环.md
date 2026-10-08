# Day 02 · Agent 的本质：一个带工具的循环

> **今日目标**：看穿 Agent 的"魔法外壳"——它就是一个 while 循环；手写 60 行 MiniAgent 骨架（假工具版）跑通第一圈；吃透本月安全模型的根："执行权永远在应用侧"。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：MiniAgent.java（假工具版）+ Agent 循环图 + 安全模型三不信任
> **对照教程**：`ai-learning/05-Agent与MCP协议.md` 第 1 节

## 1. 知识地图（先讲人话）

```
Agent 听着玄，剥开就一层皮：

  while (未完成 && 轮次 < MAX) {
      思考：模型看着【目标+历史对话+工具菜单】，
            决定"直接回答" 还是 "我要用某工具"
      行动：应用侧代码真的去执行（查库/调API/写文件）
            —— 权限校验、参数校验都在这一步！
      观察：把工具结果拼回对话历史，喂回给模型
  }
  输出最终答案（或轮次烧完 → 转人工）

三个角色分工（比喻：点餐）：
  模型   = 顾客：看菜单点菜（输出调用意图），但不能进厨房
  应用侧 = 服务员+厨房：接单、验菜（参数校验）、做菜（真执行）
  工具   = 菜品：每个工具一份"菜单描述"（给模型看的说明书）

三句本质（教材原文，本月反复回收）：
  ① 模型不执行任何东西，只输出"调用意图"——执行权永远在应用侧
  ② Agent = LLM 做决策引擎 + 传统代码做手脚与安全带
  ③ 所有可靠性问题（死循环/幻觉参数/越权）都在循环里发生，
    也都在循环里防御

对比前两个月的编排方式（一眼看懂演进）：
  M13：单轮调用（问→答）
  M14：固定流水线（检索→生成，路径写死）
  M15：动态循环（走几步、干什么，模型每轮现场决定）
  ——不是替代关系，是"编排自由度"递增，自由度越大越难管
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| Agent Loop | Agent 循环 | 思考→行动→观察的 while 循环（Agent 的心脏） |
| Iteration | 轮次 | 循环转了一圈（一次模型调用+可能一次工具执行） |
| MAX_ITERATIONS | 轮次上限 | 循环的刹车（防死循环烧钱，day11 专攻） |
| Tool Schema | 工具描述 | 工具的"菜单条目"：名字+说明+参数格式 |
| Invocation Intent | 调用意图 | 模型输出的"我要用某工具+这些参数"（只是一段话！） |
| Execution Authority | 执行权 | 真正动手的权力——永远在应用侧代码手里 |

## 3. 动手实操：60 行 MiniAgent（假工具版）

### 3.1 先跑骨架（今天模型调用手写 JSON 协议模拟，day03 换原生 tools）

```java
// MiniAgent.java —— 骨架版：工具是假的，循环是真的
import java.util.*;

public class MiniAgent {
    record Tool(String name, String desc) {}
    static final int MAX_ITER = 5;                 // 刹车！
    static final List<Tool> MENU = List.of(
        new Tool("queryOrder", "按订单号查物流，参数 orderNo"),
        new Tool("queryPolicy", "按关键词查公司政策，参数 keyword"));

    public static void main(String[] args) {
        String goal = "订单 S20260912003 到哪了？";
        List<String> history = new ArrayList<>(List.of(goal));
        for (int round = 1; round <= MAX_ITER; round++) {
            String decision = fakeLLM(history);    // 今天假装是模型（day03 换真模型）
            System.out.println("第" + round + "轮 模型说: " + decision);
            if (decision.startsWith("ANSWER:")) {  // 决定直接回答 → 出循环
                System.out.println("最终答案: " + decision.substring(7));
                return;
            }
            // 决定调用工具 → 应用侧执行（真权力在这里）
            String[] p = decision.split("\\|");    // TOOL|工具名|参数
            String result = executeTool(p[1], p[2]);  // 校验+执行+审计都该在这
            history.add("工具结果: " + result);      // 观察结果回填
        }
        System.out.println("轮次烧完，转人工");       // 刹车触发
    }
    static String executeTool(String name, String arg) {
        // TODO day04：参数校验+权限校验（今天假实现）
        return "S20260912003 → 已到达上海转运中心 (模拟数据)";
    }
    static String fakeLLM(List<String> h) {
        return h.size() == 1
            ? "TOOL|queryOrder|S20260912003"
            : "ANSWER:您的订单已到达上海转运中心";
    }
}
```

```powershell
javac MiniAgent.java; java MiniAgent
# 预期输出两轮：第1轮调工具 → 第2轮 ANSWER 收工
# 实验：把 MAX_ITER 改成 1 再跑 → "轮次烧完，转人工"
#   ——这就是刹车的体感（day11 会用真模型复现）
```

### 3.2 安全模型三不信任（把执行权讲透）

```
《三不信任清单》（应用侧的职责，一条都不能少）：
  ① 不信模型自述的身份：模型说"我是管理员"=空气，
     身份只从登录态取（CurrentUser.id()）——M14 day20 同款纪律
  ② 不信模型产出的参数：orderNo 可能是编的（幻觉参数），
     格式校验+存在性校验先行（day12 专攻）
  ③ 不信模型的操作申请无代价：每个工具执行都留审计日志
     （谁/何时/调了什么/参数是什么/结果如何——day23 全量留痕）
一句话：把模型当成"一个会打字的、偶尔抽风的实习生"——
  它写的一切都是"申请"，批不批、办不办，代码说了算。
```

## 4. 面试连接

**Q：画一下 Agent 的工作循环，核心安全点在哪？（手撕画图题）**
> 循环四步：模型看目标、历史和工具菜单决定行动——要么输出最终答案，要么输出一个工具调用意图；应用侧解析这个意图，做参数校验和权限校验，通过后真正执行工具；把执行结果拼回对话历史；回到模型继续，直到给出答案或触发轮次上限转人工。核心安全点在"行动"这一步，我的纪律是三不信任：不信模型自述身份，权限只认登录态；不信模型给的参数，格式和存在性校验先行；不信调用无代价，每次执行都进审计日志。因为模型本质上只是输出了一段"我想调这个工具"的文本，执行权永远在应用侧——把模型当成会打字的实习生，它的输出是申请，批不批代码说了算。另外轮次上限这个刹车必须显式设，我在演练里造过死循环工具，没有上限时模型能连续调几十轮，Token 账单和延迟都是线性涨的。

## 5. 今日验收清单

- [ ] MiniAgent 跑通两轮（+MAX_ITER=1 的刹车实验）
- [ ] 循环图手画一遍（思考/行动/观察+刹车站）
- [ ] 三不信任清单能脱稿讲
- [ ] `git add . && git commit -m "day15-02: agent-loop"`
- [ ] 笔记："实习生打申请"类比贴墙（本月安全模型的根）

---
[← Day 01](day01-为什么需要Agent.md) | [本月目录](README.md) | [Day 03 · Function Calling 第一课 →](day03-FunctionCalling第一课.md)
