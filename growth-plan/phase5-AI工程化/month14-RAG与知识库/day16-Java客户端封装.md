# Day 16 · Java 客户端封装：欠账清偿之日

> **今日目标**：兑现 M13 诚实未达①——把 curl 时代的调用升级成正式的 Java 客户端：超时、重试（指数退避）、降级链三件套全代码化；含单元测试思路。
> **时长**：编码 2.5h / 演练 1h / 输出 0.5h
> **今日产出**：`OllamaClient` + `EmbeddingClient`（可复用组件）+ 演练记录
> **对照教程**：`ai-learning/03-SpringAI与LangChain4j.md`（生产可换框架，今天手写懂原理）

## 1. 知识地图（先讲人话）

```
M13 时代我们的调用长这样：curl.exe 一把梭——能跑，但生产不合格：
  ✗ 默认无超时：模型卡住 → 线程吊死 → 线程池耗尽（M6 讲过的雪崩链）
  ✗ 无重试：网络抖动一次就报错给用户
  ✗ 无降级：主模型挂了=整个功能瘫痪
  ✗ 无计量：token 花了多少没人记账（M13 day20 的 call_log 接不上）

今天写的两个客户端，本质是把 M6/M13 的预案"代码化"：
  OllamaClient   → 聊天/生成（/v1/chat/completions，OpenAI 兼容）
  EmbeddingClient → 向量化（/api/embeddings）
  两者共同的"安全带"：
  ① 超时：连接 3s / 读取 60s（生成慢是常态，读取别设 5s 自己吓自己）
  ② 重试：只重试"值得重试的错"——超时/5xx/429；
     4xx（参数错）重试一万次也是错，直接抛
  ③ 指数退避：1s → 2s → 4s（别 1s 连打三炮，那是 DDoS 自己）
  ④ 降级链：主模型超时/失败 → 备用模型 → 抛出受控异常（上层走人工队列）
  ⑤ 计量埋点：每次调用把 model/tokens/耗时/status 喂给 call_log
     —— M13 day20 的记账表，Java 版接口对齐

为什么不直接用 Spring AI/LangChain4j？
  —— 先手写懂原理（超时重试降级长什么样），下个项目换框架时
  你知道框架帮你干了什么、坑在哪（面试也这么答，站得住）。
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| HttpClient | HTTP 客户端 | Java 自带的 HTTP 工具（java.net.http，无需依赖） |
| Exponential Backoff | 指数退避 | 重试间隔翻倍（1s/2s/4s），给对面喘息 |
| Retryable Error | 可重试错误 | 超时/5xx/429 才重试；4xx 重试无意义 |
| Fallback | 降级 | 主模型不行换备胎，备胎不行交人工 |
| Metering | 计量 | 每次调用记 usage（对齐 M13 call_log） |
| Circuit Breaker | 熔断 | 连续失败就"跳闸"，一段时间内直接走降级 |

## 3. 动手实操：两个客户端 + 演练

### 3.1 OllamaClient 核心代码（可整段抄进工作区）

```java
// kb/client/OllamaClient.java —— 生成模型客户端（安全带五件套）
public class OllamaClient {
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))          // ① 连接超时
        .build();
    private final String baseUrl;                       // http://localhost:11434
    private final String model;                         // qwen2.5:7b
    private final String fallbackModel;                 // qwen2.5:3b（备胎）

    public ChatResult chat(List<Message> messages, int maxTokens) {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {  // ② 重试 3 次
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(60))     // ① 读取超时（生成慢是常态）
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(toJson(messages, maxTokens)))
                    .build();
                HttpResponse<String> resp =
                    http.send(req, BodyHandlers.ofString());
                if (resp.statusCode() == 429 || resp.statusCode() >= 500) {
                    throw new RetryableException("status=" + resp.statusCode());
                }
                if (resp.statusCode() != 200) {
                    throw new IllegalArgumentException("不可重试: " + resp.statusCode());
                }                                            // ② 4xx 直接抛
                return parseAndMeter(resp.body());           // ⑤ usage → call_log
            } catch (RetryableException | IOException e) {
                last = e;
                sleep((long) Math.pow(2, attempt) * 1000);   // ③ 1s/2s/4s
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        return fallback(last);                               // ④ 降级：换备胎
    }
    // fallback()：用 fallbackModel 再试一轮；仍失败 → 抛 KbUnavailableException
    //  → 上层 RagService 捕获后走"转人工"话术（受控降级，不是裸报错）
}
```

```java
// kb/client/EmbeddingClient.java —— 向量化客户端（结构同上，简化版）
// POST /api/embeddings  { "model": "nomic-embed-text", "prompt": text }
// 返回 double[768]；重试 2 次、超时 10s（嵌入比生成快得多）
// 批量接口 batch(List<String>)：入库链路用，减少 HTTP 往返
```

### 3.2 演练：三条安全带逐个点火

```powershell
# 演练① 超时触发：Ollama 停掉 → 调 chat()
#   预期：连接超时 3s 触发（不是挂到天荒地老），重试 3 次后走 fallback，
#   fallback 也失败 → KbUnavailableException → 上层"转人工"话术
# 演练② 429 模拟：写个假 controller 固定返回 429
#   预期：重试间隔 1s/2s/4s（日志时间戳作证），3 次后降级
# 演练③ 4xx 不重试：假 controller 返回 400
#   预期：立即抛 IllegalArgumentException（日志确认只有 1 次调用）
```

### 3.3 单测思路（三招）

```
① 假服务：com.sun.net.httpserver 起个本地假 Ollama（JDK 自带，零依赖）
   —— 想让它超时就延时、想 429 就 429（演练①②③的单测版）
② 时序断言：重试间隔用记录时间戳断言（≈1s/2s/4s，放宽 ±30%）
③ 降级断言：主服务永远 500 → 断言 fallbackModel 被调用过
（框架：JUnit5 即可，不用上 MockServer——工具也是按需选型）
```

## 4. 面试连接

**Q：你们怎么封装大模型调用的客户端？（Java 工程细节题，M13 未达的翻身仗）**
> 自研了两个轻客户端，核心是安全带五件套。超时两档：连接三秒、读取六十秒——生成模型慢是常态，读取超时不能照搬接口的五秒习惯，否则把自己的正常请求全掐死。重试讲究"值得才重试"：只对超时、5xx、429 重试，三次机会，指数退避一秒两秒四秒；4xx 是参数或权限问题，重试一万次也是错，立即抛。降级链代码化：主模型失败换备用模型再试，仍失败抛受控异常，上层捕获后走转人工话术——降级是设计出来的路径，不是事故现场。另外每次调用把 model、token 用量、耗时、状态喂给调用日志表，成本看板和审计都从这里出。测试用 JDK 自带的 HttpServer 起假服务，能精确控制超时、429、4xx 三种剧本，时序断言验证退避间隔。为什么不用 Spring AI？先手写懂原理——超时重试降级长什么样，下个项目换框架时才知道框架帮你省了什么、坑在哪。这套封装在 M6 学的熔断降级思想上加了 AI 域的细节：读取超时放宽和 usage 计量，本质还是那套稳定性方法论。

## 5. 今日验收清单

- [ ] 两个客户端写完并过单测（三条剧本全绿）
- [ ] 演练①②③跑通（日志时间戳留档）
- [ ] call_log 埋点接通（Java 调用能记账了）
- [ ] "4xx 为什么不重试"能答
- [ ] `git add . && git commit -m "day14-16: java-client"`
- [ ] 笔记：M13 未达①正式销账（day30 对账表打钩）

---
[← Day 15](day15-项目四总设计.md) | [本月目录](README.md) | [Day 17 · 入库链路 →](day17-入库链路.md)
