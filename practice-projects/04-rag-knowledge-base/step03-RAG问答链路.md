# Step 3：RAG 问答链路（检索增强生成 / 引用溯源 / 效果评估）

> **本步目标**：把检索与生成串成完整 RAG（Retrieval-Augmented Generation，检索增强生成）链路：查询改写 → 混合检索 → 重排 → 组装 Prompt → 流式生成 + 引用溯源；建立 RAGAS 评估基线，用数据驱动优化。

## 前置术语

| 英文 | 中文 |
|------|------|
| RAG (Retrieval-Augmented Generation) | 检索增强生成（先查资料再回答） |
| Hallucination | 幻觉（模型一本正经地编造） |
| Grounding | 有据生成（答案严格基于提供的资料） |
| Citation / Attribution | 引用 / 溯源（答案标注出自哪篇文档哪段） |
| Query Rewriting / Expansion | 查询改写 / 扩展（把口语问题改写成检索友好形式） |
| HyDE (Hypothetical Document Embeddings) | 假设文档嵌入（先让模型编个假答案，用假答案向量去检索） |
| Rerank | 重排序（用更精的模型把召回的 20 条精选出 5 条） |
| RAGAS | RAG 评估框架（忠实度/相关性等自动化指标） |
| Faithfulness | 忠实度（答案是否忠于检索到的资料） |
| Advisor | Spring AI 的调用拦截器（类似 Web 拦截器，链路各环节插件化） |

## 1. 全链路时序（背下来，面试画图就画这个）

```
用户提问 q
   │
   ▼
[1] 查询改写：结合多轮历史 → RewrittenQuery{rewritten, intent, keywords}   (step01 能力)
   │
   ▼
[2] 混合检索：向量 Top20 + 关键词 Top20 → RRF 融合 Top20                    (step02 能力)
   │
   ▼
[3] 重排 Rerank：模型精排 → Top5（或不做，直接 Top5，先跑通再加）
   │
   ▼
[4] 组装 Prompt：系统提示(Grounding 约束) + 资料[1..5](带编号) + 问题
   │
   ▼
[5] 流式生成：SSE 输出答案；答案中要求标注 [1][2] 引用编号
   │
   ▼
[6] 响应组装：answer + citations[{id, doc_name, snippet}] 一起返回
```

## 2. 核心 Prompt 模板（Grounding 三约束）

```java
String SYSTEM_PROMPT = """
    你是企业知识库助手。严格遵守：
    1. 只依据【资料】回答，禁止使用资料之外的知识；
    2. 每个关键结论后标注来源编号，如 [1]；
    3. 资料不足以回答时，明确回复"知识库中暂无相关资料"，不要猜测。
    """;

String USER_TEMPLATE = """
    【资料】
    {chunks}

    【问题】{query}
    """;
// chunks: 每条格式 → [编号] (文档名) 换行 原文
```

拒答是**功能不是缺陷**：知识库助手答"不知道"的正确率，是可信度的基石。用 20 个库外问题测拒答率，应 > 80%。

## 3. 代码串联（Spring AI Advisor 风格）

```java
public Flux<AnswerChunk> ragChat(String conversationId, String q, String department) {
    // [1] 查询改写（结构化输出，step01）
    RewrittenQuery rq = rewrite(conversationId, q);
    // [2] 混合检索 + RRF
    List<Chunk> hits = retriever.retrieve(rq.rewritten(), department, 20);
    // [3] 重排（接 Cohere/ siliconflow 的 rerank API，或先跳过）
    List<Chunk> top5 = reranker.topN(hits, rq.rewritten(), 5);
    // [4][5] 组装 + 流式生成
    Flux<String> answer = chat.prompt()
            .system(SYSTEM_PROMPT)
            .user(u -> u.text(USER_TEMPLATE)
                        .param("chunks", numbered(top5))
                        .param("query", rq.rewritten()))
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
            .stream().content();
    // [6] 先推答案流，结尾推引用块（自定义 SSE 事件类型）
    return answer.map(AnswerChunk::token)
                 .concatWith(Flux.just(AnswerChunk.citations(toCitations(top5))));
}
```

前端按 SSE 事件类型分流：`token` 事件追加正文，`citations` 事件渲染"来源列表"（点开看原文片段）。

## 4. 效果优化三板斧（按性价比排序）

| 手段 | 解决什么 | 成本 |
|------|---------|------|
| 查询改写 | 多轮指代（"它多少钱"）、口语化检索词 | 一次 LLM 调用，几乎必做 |
| 重排 Rerank | 向量召回排序不准，Top5 混入噪声 | 一次 rerank API 调用，效果提升明显 |
| HyDE | 问题太短/太抽象，向量不可代表 | 一次 LLM 调用，疑难杂症用 |

调优方法论：**每次只改一个变量 → 跑黄金集 → 对比 Recall@5/MRR/忠实度 → 记录结论**。三天玄学调参，不如一天规范评测。

## 5. RAGAS 评估（生产上线前必做）

四个核心指标（框架自动算，原理要能口述）：

| 指标 | 度量什么 | 算法直觉 |
|------|---------|---------|
| Faithfulness 忠实度 | 答案是否都能从资料推出 | 把答案拆成陈述句，逐条问"资料能支撑吗" |
| Answer Relevancy | 答案与问题的相关性 | 让模型由答案反推 N 个问题，与原问题算相似度 |
| Context Precision | 检索结果里有用的排得靠前吗 | 逐条问"这条对回答有用吗"加权 |
| Context Recall | 该检索到的都检索到了吗 | 对照标准答案逐要点检查是否被资料覆盖 |

准备 30~50 条评测样本（问题 + 标准答案 + 应命中文档），产出基线报告：

```
基线（固定切分 500 Token）      Faithfulness 0.72 | Context Recall 0.64
优化（结构感知切分 + 改写 + 重排） Faithfulness 0.89 | Context Recall 0.85
```

这张对比表直接放进简历/晋升述职——**"我用数据证明了我的优化有效"** 是 AI 工程师最稀缺的表达。

## 验收清单

- [ ] 全链路：提问 → 5 秒内开始流式出字 → 结尾推送引用块
- [ ] 引用可溯源：点击引用编号能看到对应原文片段
- [ ] 20 个库外问题拒答率 > 80%（记录数字）
- [ ] 多轮场景："详细介绍下它"（指代前文）能被改写并正确检索
- [ ] RAGAS 基线 vs 优化后对比报告完成（四指标表格）
- [ ] 断网/LLM 超时演练：用户拿到友好错误提示而非空白卡死

## 常见坑

1. **Prompt 里塞 Top20 全量资料**：窗口爆 + 噪声大 + 贵 → 重排精选 Top5。
2. **引用让模型自由发挥**：模型编造 "[7]"（只有 5 条资料）→ Prompt 明确"只能引用给定编号"，后处理校验引用编号合法性。
3. **评估集太小或全是"友好问题"**：30 条起步，必须包含库外问题、多轮指代、模糊问题、错别字问题。
4. **重排用 chat 模型凑合**：专项 rerank 模型（bge-reranker 类）效果/成本远优于让 chat 模型"帮我排个序"。
5. **只看生成不看检索**：一半以上"答非所问"根因在检索（Context Recall 低），先修检索再怪模型。

> 完成后进入 `step04-Agent工具与评估.md`。
