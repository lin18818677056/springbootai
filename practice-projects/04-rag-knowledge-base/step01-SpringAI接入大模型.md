# Step 1：Spring AI 接入大模型（流式对话 / 结构化输出）

> **本步目标**：搭好 Spring AI 环境，接通一个大模型（OpenAI 兼容协议），实现普通对话、流式输出（SSE，Server-Sent Events 服务器推送事件）、结构化输出三件事，并建立"所有 LLM 调用必须有超时/重试/用量统计"的工程习惯。

## 前置术语

| 英文 | 中文 |
|------|------|
| LLM (Large Language Model) | 大语言模型 |
| Token | 词元（模型处理文本的最小单位，1 个汉字 ≈ 1~2 个 Token，按 Token 计费） |
| Context Window | 上下文窗口（模型一次能"看见"的 Token 上限） |
| System / User / Assistant Message | 系统 / 用户 / 助手消息（三种角色） |
| Temperature | 温度（采样随机度：0 严谨，1 活跃；知识库场景用 0~0.3） |
| ChatClient | Spring AI 的对话客户端（类似 RestTemplate 之于 HTTP） |
| Streaming / SSE | 流式输出 / 服务器推送事件（打字机效果的技术实现） |
| Structured Output | 结构化输出（让模型按 JSON Schema 返回） |
| Prompt Template | 提示词模板（带占位符的 Prompt） |
| Rate Limit | 速率限制（模型服务方的限流） |

## 1. 依赖与配置

```gradle
// build.gradle
dependencies {
    implementation platform("org.springframework.ai:spring-ai-bom:1.0.0")
    implementation 'org.springframework.ai:spring-ai-starter-model-openai'
    implementation 'org.springframework.boot:spring-boot-starter-web'
}
```

```yaml
# application.yml —— 任何 OpenAI 兼容厂商都这么接（base-url 一换就行）
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}            # 环境变量注入，绝不进代码库
      base-url: https://api.deepseek.com    # 或 https://dashscope.aliyuncs.com/compatible-mode
      chat:
        options:
          model: deepseek-chat              # 按厂商填模型名
          temperature: 0.2                  # 知识库场景压低随机性
          max-tokens: 2048
      embedding:
        options:
          model: text-embedding-v3          # 向量模型（step02 用）
```

**认知**：Spring AI 把"换模型"抽象成换配置——代码里只面向 `ChatModel`/`EmbeddingModel` 接口编程。这就是**端口与适配器**思想在 AI 时代的样子。

## 2. ChatClient：对话的最小可用形态

```java
@Configuration
public class AiConfig {
    @Bean
    ChatClient chatClient(ChatModel chatModel, ChatClientCustomizer customizer) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是企业知识库助手，只依据给定资料回答；资料中没有就说不知道。")
                .build();   // System Prompt（系统提示词）定角色与边界
    }
}
```

```java
@RestController
@RequestMapping("/chat")
public class ChatController {
    private final ChatClient chat;

    // 1) 一次性返回（调试用）
    @GetMapping("/simple")
    public String simple(@RequestParam String q) {
        return chat.prompt().user(q).call().content();
    }

    // 2) 流式返回（生产形态，SSE）
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam String q) {
        return chat.prompt().user(q)
                .stream().content()                 // 逐 Token 推给前端（打字机效果）
                .timeout(Duration.ofSeconds(60));   // 必须有超时！
    }
}
```

三个工程铁律（面试官看代码先扫这个）：
1. **超时**：LLM 偶发抽风 30 秒不出字，无超时 = 线程池被拖垮。
2. **重试**：Spring AI 自带 `RetryTemplate`，对 429（Rate Limit，限流）与 5xx 指数退避重试。
3. **用量统计**：每次调用记录 `usage.promptTokens/generationTokens`（用量.提示词/生成词元数）→ 成本可观测，后面接告警。

## 3. 结构化输出：让模型当"解析器"用

```java
// 场景：把用户口语化问题改写成检索友好形式
record RewrittenQuery(String rewritten, String intent, List<String> keywords) {}

RewrittenQuery result = chat.prompt()
    .system("""
        将用户问题改写为适合知识库检索的查询，输出 JSON：
        {"rewritten":"改写后问题","intent":"咨询|投诉|查询","keywords":["关键词"]}
        """)
    .user(q)
    .call()
    .entity(RewrittenQuery.class);   // Spring AI 自动按 record 结构解析校验
```

价值：不需要手写 JSON 解析和容错——反序列化失败自动重试。Agent（step04）与查询改写（step03）全靠它。

## 4. 多轮对话记忆（手动版，理解原理）

LLM 是**无状态**的："它"记得上一句，是因为你把历史又发了一遍。

```java
// ChatMemory（对话记忆）接口：按 conversationId 存取历史
@Bean
ChatMemory chatMemory() {
    return MessageWindowChatMemory.builder()
            .maxMessages(20)        // 滑动窗口：只保留最近 20 条，防上下文爆窗
            .build();
}
```

```java
// 带 Advisor（拦截器）的流式调用：记忆自动注入
public Flux<String> chat(String conversationId, String q) {
    return chat.prompt()
        .user(q)
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
        .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
        .stream().content();
}
```

**记忆策略权衡**：全量历史（贵、会爆窗口）vs 滑动窗口（丢早期）vs 摘要压缩（让模型把旧对话总结成一段）——知识库场景 20 条窗口 + 关键信息靠 RAG 补，通常够用。

## 5. 成本与可观测性（从第一天就做）

```java
@Component
public class LlmMetricsListener {
    // 每次调用结束记录：模型名、耗时、Token 数、成功/失败
    void onCallComplete(Usage usage, long costMs, boolean ok) {
        meterRegistry.counter("llm.calls.total", "ok", String.valueOf(ok)).increment();
        meterRegistry.summary("llm.call.cost.ms").record(costMs);
        meterRegistry.counter("llm.tokens",
            "type", "prompt").increment(usage.getPromptTokens());
    }
}
```

接 Grafana 看：单问题 Token 成本、P99 耗时、错误率。**AI 应用不谈成本和延迟就是耍流氓**——这句话面试说出来很加分。

## 验收清单

- [ ] `curl "localhost:8080/chat/stream?q=..."` 看到打字机流式输出
- [ ] 切换 base-url 到另一家厂商，代码零改动跑通（截图两个配置）
- [ ] 结构化输出：输入口语问题 → 返回合法 JSON 并映射成 record
- [ ] 多轮对话：同一 conversationId 连问"它叫什么？"能指代前文
- [ ] Grafana/Metrics 端点能看到 llm.calls.total 与 Token 用量指标
- [ ] 429 模拟（把 key 临时改错再恢复）触发重试日志

## 常见坑

1. **温度没压低**：知识库回答天马行空甚至编造 → temperature 0~0.3，后面再加 Grounding（第 3 步）双保险。
2. **中文 Token 误区**：以为 1 汉字 = 1 Token，实际 1~2 个，成本估算直接翻倍出错。
3. **SSE 被网关缓冲**：Nginx 需 `proxy_buffering off;`，否则"打字机"变"憋半天一口气全出"。
4. **密钥泄漏**：api-key 硬编码推上 Git → 立即作废重发；用环境变量或密钥管理服务。
5. **上下文爆窗**：长文档直接塞进 Prompt 报 413/超限错误 → 这正是下一步 RAG 要解决的问题。

> 完成后进入 `step02-文档处理与向量检索.md`。
