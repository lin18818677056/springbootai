# 03 · Spring AI 与 LangChain4j：Java 生态的 AI 应用开发

> **本篇目标**：掌握 Spring AI 的完整能力面（对话/Embedding/向量库/结构化输出/Advisor/可观测），了解 LangChain4j 的定位差异；能独立搭建生产级 AI 应用骨架。Python 的 LangChain 你**不需要**——Java 生态已足够成熟。

## 术语表

| 英文 | 中文 |
|------|------|
| Spring AI | Spring 官方 AI 应用框架（1.0 已 GA，正式版） |
| LangChain4j | Java 版 LangChain（社区驱动，组件丰富） |
| ChatModel / ChatClient | 模型抽象接口 / 流式调用门面（Spring AI 核心两件套） |
| EmbeddingModel / VectorStore | 向量模型抽象 / 向量存储抽象（Chroma/Milvus/pgvector/Redis 皆可插拔） |
| Advisor | 调用拦截器链（检索增强/记忆/日志等横切能力插件化） |
| Structured Output Converter | 结构化输出转换器（LLM 输出 → Java 对象） |
| Function Callback / @Tool | 工具回调（Function Calling 的 Java 封装） |
| Observability | 可观测性（Spring AI 内置 Micrometer 埋点） |
| Prompt Stuffs | 提示词填充器（把检索结果填进模板） |

## 1. Spring AI 全景图（学这个顺序）

```
ChatClient（门面，链式 API）
  ├── prompt()      → 组装 System/User 消息（Prompt Template 模板渲染）
  ├── advisors()    → 拦截器链：ChatMemory 记忆 / QuestionAnswerAdvisor RAG / 自定义日志
  ├── tools()       → Function Calling 工具注册
  ├── call()        → 同步一次返回
  └── stream()      → 流式（Flux<String>，SSE）
底层抽象（自动配置注入）：
  ChatModel（OpenAI/Azure/Ollama/... 可换）× EmbeddingModel × VectorStore
```

**设计哲学与 Spring 一脉相承**：接口抽象 + 自动配置 + Starter 生态——你换模型厂商只改依赖与配置，业务代码零改动。

## 2. 核心代码速成（30 分钟上手）

```java
// 1) 对话 + 流式 + 记忆 + RAG 一步到位（生产形态）
@Bean
ChatClient chat(ChatModel model, VectorStore store, ChatMemory memory) {
    return ChatClient.builder(model)
        .defaultSystem("你是企业助手，只依据资料回答，标注引用编号")
        .defaultAdvisors(
            MessageChatMemoryAdvisor.builder(memory).build(),        // 多轮记忆
            QuestionAnswerAdvisor.builder(store)                      // 自动 RAG：
                .searchRequest(SearchRequest.builder()                //   检索→填模板→生成
                    .topK(5).similarityThreshold(0.7).build())
                .build())
        .build();
}

// 2) 结构化输出
record TripPlan(String destination, List<String> days, BigDecimal budget) {}
TripPlan plan = chat.prompt().user(u -> u.text("帮我规划{city}三日游预算{money}元")
        .param("city", "杭州").param("money", "3000"))
    .call().entity(TripPlan.class);      // JSON → Java 对象，自动校验重试

// 3) 工具调用
class WeatherTools {
    @Tool(description = "查询城市天气，city 为中文城市名")
    String weather(String city) { return http.get("/api/weather?city=" + city); }
}
String s = chat.prompt().user("上海明天适合跑步吗").tools(new WeatherTools()).call().content();
```

## 3. Advisor 机制：AI 版的"拦截器链"（Spring AI 最优雅的设计）

```
请求 → [日志 Advisor] → [记忆 Advisor] → [RAG Advisor] → ChatModel → 响应
每个 Advisor 可改请求/改响应/短路，Order 控制顺序
```

自定义示例（调用日志 + Token 统计）：

```java
public class AuditAdvisor implements CallAdvisor {
    public ChatClientResponse adviseCall(ChatClientRequest req, CallAdvisorChain chain) {
        long start = System.currentTimeMillis();
        ChatClientResponse resp = chain.nextCall(req);
        log.info("model={} cost={}ms tokens={}", /* ... */);
        return resp;
    }
}
```

对比 Python 生态：LangChain 的 Chain/Callback 概念类似，但 Advisor 与 Spring AOP 心智模型完全一致——**Java 程序员零成本理解**。

## 4. 向量库抽象：一行配置换存储

```java
// pgvector（推荐起步）
implementation 'org.springframework.ai:spring-ai-starter-vector-store-pgvector'
// 换 Milvus（生产大规模）
implementation 'org.springframework.ai:spring-ai-starter-vector-store-milvus'
// 代码不变：vectorStore.add(docs) / vectorStore.similaritySearch(query)
```

## 5. LangChain4j：什么时候考虑它

| 维度 | Spring AI | LangChain4j |
|------|-----------|-------------|
| 出品 | Spring 官方 | 社区 |
| 心智 | Spring 原生（Advisor/自动配置） | 对标 Python LangChain |
| 强项 | 企业集成顺滑、可观测内置 | 组件数量多（更多模型/存储适配）、AI Services 声明式接口 |
| 选型 | **Spring 技术栈默认选它** | 需要 Spring AI 没有的冷门组件时补充 |

两者概念映射：`ChatModel≈ChatLanguageModel`、`Advisor≈AiServices+Tools`、`VectorStore≈EmbeddingStore`——学会一个，另一个看文档就能上手。

## 6. 生产骨架清单（AI 应用 ≠ demo 的分水岭）

- [ ] 统一 AI 网关层：多模型路由（大模型答难题/小模型做分类省成本）+ 降级链路（主模型挂→备模型→友好错误）
- [ ] 全链路可观测：Micrometer 指标（调用量/Token/耗时/错误率）+ 请求日志留痕（traceId 贯通）
- [ ] 配置化：Prompt/模型参数放配置中心，热更+灰度
- [ ] 弹性：超时/重试/限流/熔断（Resilience4j 包住 ChatModel 调用）
- [ ] 成本治理：用户/租户级 Token 预算与熔断
- [ ] 评测回归：Prompt 与模型变更前跑评测集

## 验收自测

- [ ] 能用 ChatClient 写出"流式 + 记忆 + RAG"的完整调用
- [ ] 能解释 Advisor 机制并手写一个自定义 Advisor
- [ ] 能用结构化输出拿到强类型 Java 对象
- [ ] 能说出 Spring AI 与 LangChain4j 的选型逻辑
- [ ] 能列出生产骨架 6 项检查清单并逐项落地到项目四

## 延伸阅读

- Spring AI 官方文档（spring.io/projects/spring-ai，英文，质量高更新快）
- 实战配套：`../practice-projects/04-rag-knowledge-base/step01-SpringAI接入大模型.md`
