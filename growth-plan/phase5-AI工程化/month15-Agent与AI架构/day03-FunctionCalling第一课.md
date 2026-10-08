# Day 03 · Function Calling 第一课：模型只点菜不炒菜

> **今日目标**：亲手跑通 Function Calling 的两条路径（Ollama 原生 tools + 手写 JSON 意图协议）；看穿它的本质——不是模型"会调用函数"，是模型"会按约定格式说话"；写出意图解析器的容错版本。
> **时长**：理论 0.5h / 实操 2h / 输出 0.5h
> **今日产出**：两条路径的调用记录 + IntentParser.java（容错版）+ 一次格式漂移应对记录
> **对照教程**：`ai-learning/05-Agent与MCP协议.md` 第 2 节

## 1. 知识地图（先讲人话）

```
Function Calling（函数调用）的真相：
  模型并没有"长出手去调你的 Java 方法"——它做不到！
  它只是被训练成：当上下文里有工具菜单时，
  会按约定的结构化格式"说话"，比如：
    {"tool":"queryOrder","args":{"orderNo":"S20260912003"}}
  然后【你的代码】解析这句话 → 校验 → 真的执行。
  类比：模型是个会填工单的员工——它只能填单子，
       单子能不能被批准执行，车间说了算。

两条实现路径（今天都跑一遍，对比着理解）：

路径① Ollama 原生 tools 字段（工程正路）：
  POST /api/chat，请求里带 "tools":[{工具描述…}]
  模型回复里带 tool_calls 字段（框架帮你约定好了格式）
  优点：格式约定是标准化的、模型训练时专门练过
  注意：小模型输出仍可能漂移（格式偶尔坏），解析器必须容错

路径② 手写 JSON 意图协议（教学+降级两用）：
  System Prompt 里写死约定：
    "你只能输出一行 JSON：
     要用工具 → {"tool":"工具名","args":{...}}
     要回答   → {"answer":"..."}"
  优点：不依赖模型的原生支持（任何模型都能玩），
       亲手解析一遍，Function Calling 的本质就看穿了
  缺点：格式稳定性靠 Prompt 喊话，比原生略差
  ——本地小模型 tools 输出不稳时，这就是兜底方案（README 降级路径）

解析器三原则（明天实战要用）：
  ① 解析失败 ≠ 报错给用户：先重试一次（带上格式提醒），
    再失败 → 降级当普通回答处理或转人工
  ② 意图里的工具名要在白名单里（防模型编造工具名）
  ③ 解析出的参数当成"不可信输入"（day04 校验，day12 专攻）
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| Function Calling | 函数调用 | 模型按约定格式输出"调用意图"（不是真调用） |
| tool_calls | 工具调用字段 | 原生协议里模型回复的意图载体 |
| Intent Protocol | 意图协议 | 手写版的约定：一行 JSON 表达"点菜"或"回答" |
| Intent Parser | 意图解析器 | 应用侧把模型的话解析成结构化意图（必须容错） |
| Format Drift | 格式漂移 | 模型输出没按约定格式来（小模型常见病） |
| Whitelist | 白名单 | 只允许预注册的工具名（防编造） |

## 3. 动手实操：两条路径都跑通

### 3.1 路径①：Ollama 原生 tools

```powershell
# body_tools.json —— 工具菜单+用户问题（Ollama /api/chat 格式）
# {
#   "model": "qwen2.5:7b",
#   "messages": [{"role":"user","content":"订单 S20260912003 到哪了？"}],
#   "tools": [{
#     "type": "function",
#     "function": {
#       "name": "queryOrder",
#       "description": "按订单号查询物流状态。orderNo 为 S 开头加 12 位数字",
#       "parameters": {
#         "type": "object",
#         "properties": {"orderNo": {"type": "string"}},
#         "required": ["orderNo"]
#       }
#     }
#   }],
#   "stream": false
# }
curl -s http://localhost:11434/api/chat -d "@body_tools.json" > resp1.json
# 观察回复里的 tool_calls 字段：
#   预期：{"function":{"name":"queryOrder","arguments":{"orderNo":"S20260912003"}}}
#   □ 记录：模型给了 name+arguments —— 这就是"点菜"
```

### 3.2 路径②：手写 JSON 意图协议

```powershell
# System Prompt（存 sys_intent.txt）：
#   你是工单助手，只能输出一行 JSON，二选一：
#   用工具查数据: {"tool":"queryOrder","args":{"orderNo":"..."}}
#   直接回答用户: {"answer":"..."}
#   可用工具：queryOrder（按订单号查物流，orderNo 为 S+12位数字）
# 同样的问题发过去 → 预期输出 {"tool":"queryOrder","args":{"orderNo":"S20260912003"}}
# 记录两种路径的输出差异（原生给 tool_calls 结构，手写给纯文本 JSON）
```

### 3.3 IntentParser（容错三原则落地，Java 30 行）

```java
// IntentParser.java —— 解析模型意图（生产级容错）
public record Intent(String tool, Map<String,Object> args, String answer) {}
public class IntentParser {
    static final Set<String> WHITELIST = Set.of("queryOrder", "queryPolicy");

    /** @return 解析结果；RETRY 表示格式坏但值得再试一次 */
    public static ParseResult parse(String raw) {
        String s = extractJson(raw);              // 剥掉模型常带的 ```json 围栏和废话
        try {
            JsonNode n = Mapper.readTree(s);
            if (n.has("answer")) return ParseResult.done(n.get("answer").asText());
            String tool = n.get("tool").asText();
            if (!WHITELIST.contains(tool))        // 白名单：编造的工具名直接拒
                return ParseResult.fail("未注册工具: " + tool + "（不重试，转人工）");
            return ParseResult.tool(tool, n.get("args"));
        } catch (Exception e) {
            return ParseResult.retry("输出不是合法 JSON，请严格按约定格式重答");
        }
    }
}
```

```powershell
# 对抗实验（记录三行）：
#   ① 喂正常输出 → 解析成功
#   ② 喂"好的，我来帮您查询：{...}"（带废话前缀）→ extractJson 救回
#   ③ 喂纯自然语言"我查一下哈" → RETRY → 二次仍坏 → 降级
```

## 4. 面试连接

**Q：Function Calling 的原理是什么？模型真的会调用函数吗？（本质题，答对就是分水岭）**
> 不会。Function Calling 的本质是约定输出格式：我们把工具的名字、描述、参数格式放进上下文，模型被训练成在这种情况下按结构化格式输出一段"调用意图"——比如 queryOrder 加订单号参数。真正执行的是应用侧代码：解析意图、校验参数、检查权限，然后才调用真实的 Java 方法，把结果拼回上下文再喂给模型。所以模型自始至终只做了一件事：点菜。炒菜的、端菜的、验菜的，全是传统代码。工程上有两个实践：一是解析必须容错，本地小模型经常带废话前缀或者格式漂移，我的解析器会剥围栏、剥前缀，解析失败先带格式提醒重试一次，再失败降级成普通回答或转人工；二是工具名走白名单，模型编造一个不存在的工具名时直接拦截，不给它执行机会。这两条都是拿真实翻车换来的。

## 5. 今日验收清单

- [ ] 原生 tools 路径跑通（tool_calls 字段截图/记录）
- [ ] 手写意图协议跑通（对比记录完成）
- [ ] IntentParser 三原则落地（对抗实验三条有记录）
- [ ] 能脱口而出："模型只点菜，炒菜的是代码"
- [ ] `git add . && git commit -m "day15-03: function-calling"`
- [ ] 笔记：两条路径选型卡（原生稳但依赖支持/手写通用但靠 Prompt）

---
[← Day 02](day02-Agent的本质-带工具的循环.md) | [本月目录](README.md) | [Day 04 · 单工具闭环 →](day04-单工具闭环-queryOrder.md)
