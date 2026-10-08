# Day 17 · 项目四工具 MCP 化：把 kbSearch 包成 Server

> **今日目标**：动手实现 KbMcpServer（教学版 JSON-RPC over stdio）——把 kbSearch 从"自家菜单直挂"升级为"标准协议暴露"；验证三道闸在 Server 侧原样生效；和自家宿主联调跑通首次跨进程工具调用。
> **时长**：编码 2h / 联调 1h / 输出 0.5h
> **今日产出**：KbMcpServer.java（~120 行）+ 联调记录 + 与 Spring AI starter 的差距清单
> **对照教程**：`ai-learning/05-Agent与MCP协议.md` 第 4 节 Java 落地

## 1. 知识地图（先讲人话）

```
今天的任务一句话：给 kbSearch 换一个"标准插头"。

改造前后对比：
  改造前：Agent 应用内部直接 methods 调用（自家代码自家懂）
  改造后：KbMcpServer 独立进程，按 JSON-RPC 标准对话
    任何 MCP 宿主（别的应用/IDE 插件）都能即插即用

教学版实现的最小骨架（stdio 传输，~120 行）：
  while (从 stdin 读到一行 JSON-RPC 请求) {
      按 method 分发：
        initialize  → 回 serverInfo + capabilities（握手）
        tools/list  → 回工具清单（description 用 day05 的好描述！）
        tools/call  → 解析参数 → 【三道闸】→ 调 kbSearch → 回结果
      往 stdout 写一行 JSON-RPC 响应
  }
  ——本质就是把 day03 的 IntentParser 换成了协议分发器，
    点菜的人从"自家模型"变成了"任何标准宿主"

三个"照旧"（安全模型不变的落地证据）：
  ① 参数校验照旧：tools/call 的 arguments 先过格式/存在性检查
  ② 权限照旧：kbSearch 的权限过滤（M14 检索前过滤）在 Server 内部
    照样执行——跨进程不跨纪律
  ③ 审计照旧：每次 tools/call 记账（调用方是谁要标识——
    教学版用环境变量传 token，生产版走 OAuth——day18 讲边界）

和 Spring AI MCP starter 的差距（诚实清单，day18 展开）：
  教学版：stdio 单线程/无会话管理/无标准错误码/无通知机制
  正式版：Spring AI 帮你处理协议细节（注解 @McpTool 一挂就完）
  ——但亲手写一遍的价值不可替代：协议三步（握手/发现/调用）
    变成肌肉记忆，出了问题知道往哪层查
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| Stdio Transport | 标准IO传输 | Server 作为子进程，stdin/stdout 对话（本地标配） |
| Method Dispatch | 方法分发 | 按 JSON-RPC 的 method 字段路由到处理函数 |
| inputSchema | 输入模式 | tools/list 里声明的参数格式（协议化的参数说明） |
| Tool Handler | 工具处理器 | method=tools/call 时真正干活的函数 |
| Cross-process | 跨进程 | 宿主和 Server 是两个进程（隔离+独立部署） |
| Protocol Envelope | 协议信封 | jsonrpc/jsonrpc版本/id/method/result 的标准包装 |

## 3. 动手实操：KbMcpServer 120 行

### 3.1 Server 骨架（分发器是核心）

```java
// mcp/KbMcpServer.java —— 教学版：JSON-RPC over stdio
public class KbMcpServer {
    public static void main(String[] args) throws Exception {
        var reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = reader.readLine()) != null) {          // 一行一个请求
            JsonNode req = Mapper.readTree(line);
            String resp = switch (req.path("method").asText()) {
                case "initialize" -> handshake(req);           // ① 握手
                case "tools/list"  -> toolList(req);           // ② 发现（好描述！）
                case "tools/call"  -> toolCall(req);           // ③ 调用（三道闸内嵌）
                default            -> error(req, -32601, "method not found");
            };
            System.out.println(resp);                          // 一行一个响应
        }
    }
    static String toolCall(JsonNode req) {
        String name = req.at("/params/name").asText();
        JsonNode argv = req.at("/params/arguments");
        if (!Set.of("kbSearch").contains(name))                // 闸①白名单照旧
            return error(req, -32602, "unknown tool");
        String keyword = argv.path("keyword").asText();
        if (keyword.isBlank())                                 // 闸②参数照旧
            return error(req, -32602, "keyword required");
        List<Chunk> hits = Retriever.search(keyword,           // 闸③权限照旧：
            currentScope());                                   //   M14 检索前过滤
        audit.record("mcp:kbSearch", keyword);                 // 审计照旧
        return ok(req, Map.of("content",
            List.of(Map.of("type","text", "text", join(hits)))));
    }
}
```

### 3.2 联调：自家宿主当第一个 Client

```powershell
# 联调三步（记录全程）：
# ① 手动喂协议（PowerShell 管道模拟宿主）：
#   '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | java -jar kb-mcp-server.jar
#   预期：返回 kbSearch 及好描述+inputSchema
# ② 喂 tools/call：
#   ...method":"tools/call","params":{"name":"kbSearch","arguments":{"keyword":"退款"}}
#   预期：检索结果三块（且权限过滤生效——换 scope 试一次对照）
# ③ 宿主接入：Spring 应用起子进程接 stdio → 模型意图 → 协议调用
#   □ 端到端跑通一次"模型点菜→跨进程→Server 执行→结果回填"
```

### 3.3 验收对照：安全模型不变的证据链

```
□ 白名单：喂一个未注册工具名 → -32602 拒绝
□ 参数：  喂空 keyword → 参数错误拒绝
□ 权限：  两个 scope 各调一次 → 结果集不同（M14 纪律跨进程存活）
□ 审计：  四次调用四条记录（含一次被拒的）
——四条证据 = "MCP 没有改变执行权在应用侧的安全模型"的实锤
```

## 4. 面试连接

**Q：把一个现有内部工具 MCP 化，你会怎么做？有哪些坑？（动手落地题）**
> 我亲手写过教学版，说下完整过程和踩的坑。第一步分类和取舍：按三类能力的控制权把资产归位，我们的 kbSearch 是模型决定调用的动作，归 Tools；政策原文归 Resources；多跳模板归 Prompts；退款工具坚决不对外暴露——暴露即攻击面，最小权限先行。第二步包协议层：本质是把方法分发器套在原工具外面——initialize 握手、tools/list 返回清单、tools/call 做真调用，底层就是 JSON-RPC over stdio。第三步也是最容易被忽略的：安全模型原样平移。我在 Server 侧重新过了全部闸门——工具名白名单、参数格式与存在性校验、M14 的检索前权限过滤、每次调用审计记账，联调时用两个不同权限的 scope 各调一次验证过滤生效，被拒的调用也留痕——协议跨了进程，纪律不能跨掉。坑有三个：一是工具描述别偷懒，tools/list 里的 description 直接决定别的宿主模型会不会用、用得对不对，我们 day05 的好描述原样带过去；二是权限身份的传递，本地教学版用环境变量传 token 凑合，生产必须走 OAuth 这类标准鉴权，不能靠信任调用方自报家门；三是资源内容的注入扫描，资源会进上下文，文档投毒就是间接注入。生产落地我会直接用 Spring AI 的 MCP starter，注解一挂协议细节全托管——但手写一遍的价值在于：出了问题我知道该往哪一层查。

## 5. 今日验收清单

- [ ] KbMcpServer 跑通三方法（握手/发现/调用）
- [ ] 四条安全证据链齐全（白名单/参数/权限/审计）
- [ ] 端到端跨进程联调一次成功（模型点菜→Server 执行）
- [ ] 与 Spring AI starter 的差距清单成文
- [ ] `git add . && git commit -m "day15-17: mcp-server"`
- [ ] 笔记：三道闸跨进程存活的证据截图（面试实锤素材）

---
[← Day 16](day16-MCP三类能力.md) | [本月目录](README.md) | [Day 18 · MCP 架构与传输 →](day18-MCP架构与传输.md)
