# 第 14 月：RAG（Retrieval-Augmented Generation，检索增强生成）与知识库进阶

> **本月一句话目标**：把 RAG 从"能跑通"做到"生产级"：混合检索、重排（Rerank）、引用溯源、评估体系、效果优化闭环。RAG 是当前企业级 AI 落地最广的架构模式，P8 面试 AI 问题的核心弹药。项目四完成 step03-step04。

## 本月目标与验收标准

- [ ] 讲清 RAG 完整链路与每个环节的优化手段（解析/切分/向量化/召回/重排/生成）
- [ ] 实现混合检索（Hybrid Search：向量 + 关键词 BM25）与重排（Rerank）两段式检索
- [ ] 实现引用溯源（引用出处页码/原文展示）与权限过滤（不同用户看到不同知识）
- [ ] 建立 RAG 评估体系（检索命中率/答案忠实度/相关性），完成 2 轮效果优化并有数据
- [ ] 项目四 step03（RAG 问答链路）+ step04（Agent 工具与评估）完成
- [ ] 产出 1 篇 RAG 优化实践博客

---

## 每日计划

### 第 1 周：RAG 系统化认知与检索优化(上)

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | RAG 全链路鸟瞰 | 离线链路（Ingestion 数据摄入：加载→解析→切分→向量化→入库）与在线链路（Query 检索→Rerank→生成）；Naive RAG（朴素）→ Advanced（进阶）→ Modular（模块化）演进 | 画出 RAG 全链路图并标注各环节可优化点 | 图上每个点能说出 1-2 个优化手段 |
| D2 | 文档解析进阶 | 复杂文档处理：表格（转 Markdown 保留结构）、扫描件 OCR（Optical Character Recognition 光学字符识别）、图片（多模态模型描述 Captioning）；解析质量决定上限 | 找 3 份真实 PDF（含表格/扫描页）解析并评估 | 输出解析质量评估记录 |
| D3 | 切分策略深入 | 固定长度 vs 递归字符（RecursiveCharacterTextSplitter）、语义切分（Semantic Chunking：Embedding 相似度断句）、父子块（Parent-Child：小块检索大块生成）、切分参数（size/overlap）调优实验 | 同一文档 4 种切分策略对比检索命中率 | 有量化对比表与结论 |
| D4 | Embedding 模型选型 | 中文 Embedding 模型对比（BGE 系列/通义/GPT 系 text-embedding）、MTEB（Massive Text Embedding Benchmark 中文基准）榜单怎么看、维度与存储成本、微调 Embedding 的边界 | 用 3 个 Embedding 模型对同一批文档建索引对比 | 选型结论有评测数据支撑 |
| D5 | 向量检索原理 | ANN（Approximate Nearest Neighbor 近似最近邻）、索引算法直觉：HNSW（分层可导航小世界图）与 IVF（倒排文件）、召回率 vs 速度权衡、向量库参数（ef/M/nprobe） | 用 PgVector/Milvus 调整参数对比召回与延迟 | 参数影响能解释 |
| D6 | 检索质量诊断 | 检索失败模式分析（漏检 Miss/误检 Noise/排序差 Ranking）、Hit Rate@K（命中率）与 MRR（Mean Reciprocal Rank 平均倒数排名）指标、构建 50 条测试问答对 | 建立评估集：50 问 + 标注正确出处 | 评估脚本可一键跑分 |
| D7 | 周复盘 | RAG 检索优化(上)串讲 | 周记 + 评估基线报告（当前 v1 成绩单） | 达成周验收 |

### 第 2 周：检索优化(下)：混合检索与重排

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 关键词检索 BM25 | BM25（Best Matching 25：基于词频与文档长度的经典打分）、倒排索引（Inverted Index）、分词器（中文 IK/jieba）、向量检索的短板（专有名词/编号检索差） | 用 ES 实现 BM25 检索并与纯向量对比 | 专有名词类问题 BM25 胜出的证据 |
| D2 | 混合检索 | Hybrid Search（混合检索：向量+BM25 双路召回）、RRF（Reciprocal Rank Fusion 倒数排名融合）融合打分、权重调优 | 实现双路召回 + RRF 融合 | 混合检索整体命中率优于单路 |
| D3 | Rerank 重排 | 为什么需要 Rerank（召回粗排 + 精排两段式）、Cross-Encoder（交叉编码器：精度高但慢）vs Bi-Encoder（双塔：快）、Rerank 模型（BGE-Reranker/Cohere Rerank） | 接入 Rerank：召回 top20 → 精排 top5 | Rerank 后命中率提升有数据 |
| D4 | 查询改写 | Query Rewrite（查询改写：口语→检索友好）、多查询扩展（Multi-Query：一问生成多问合并召回）、HyDE（Hypothetical Document Embeddings 假设文档嵌入：先生成假设答案再检索）、问题澄清（反问消歧） | 实现 Multi-Query + 消歧链路 | 3 类难问题效果对比 |
| D5 | 元数据过滤与权限 | Metadata Filtering（元数据过滤：部门/时间/文档类型）、检索前过滤 vs 检索后过滤、权限模型（用户可见性 → 过滤条件注入）、Spring AI Filter Expression | 实现按部门权限的知识隔离 | A 部门用户检索不到 B 部门文档 |
| D6 | 生成环节优化 | 引用注入（Prompt 中标注来源编号）、答案忠实度（Faithfulness：只依据检索内容）、拒答策略（检索无果时明确说不知道）、防幻觉提示技巧 | 生成端优化：带编号引用 + 拒答 | 无关问题正确拒答 |
| D7 | 周复盘 | 混合检索+Rerank 串讲 | 周记 + 评估 v2 成绩单（对比 v1） | 检索命中率提升 ≥ 10% |

### 第 3 周：评估体系与效果优化闭环

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | RAG 评估框架 | RAGAS 框架四指标：忠实度（Faithfulness）、答案相关性（Answer Relevancy）、上下文精确率（Context Precision）、上下文召回率（Context Recall）；LLM-as-a-Judge（大模型当裁判）方法论与偏差 | 接入 RAGAS（或手写等价评估）跑通 | 四指标出数 |
| D2 | 评估集建设 | 评估集来源（真实用户问题 > 自造）、覆盖维度（事实型/汇总型/推理型/拒答型）、坏例（Bad Case）收集机制（线上点赞点踩回流） | 评估集扩充到 100 条并分类 | 分类分布合理 |
| D3 | 优化实验设计 | 单变量实验法（一次只改一个环节）、实验记录表（假设/改动/指标/结论）、AB 测试（A/B Testing）思想 | 做 3 组对照实验并记录 | 每组结论可复现 |
| D4 | 常见病与药方 | 病：切块太碎丢上下文/表格错乱/多跳问题（Multi-hop：需跨段落推理）/时效知识；药方：父子块/表格专用解析/问题分解（Query Decomposition）/增量更新管道 | 针对自己知识库的 3 个坏例各出一药 | 坏例修复前后对比 |
| D5 | 成本与性能 | token 成本模型（输入输出单价计算）、缓存（语义缓存 Semantic Cache：相似问题直接命中）、检索与生成并行化、流式首字延迟（First Token Latency）优化 | 实现语义缓存 + 测首字延迟 | 缓存命中率 > 20%，首字 < 2s |
| D6 | 项目 step03 收尾 | RAG 问答链路生产化：接口幂等、超时降级（检索失败退化为纯 LLM + 提示）、日志埋点（问题/召回/答案全链路落库） | 完成 `practice-projects/04-rag-knowledge-base/step03` | 全链路日志可回放任一次问答 |
| D7 | 周复盘 | 评估与优化串讲 | 博客：《企业知识库 RAG 优化实录：命中率从 62% 到 89%》发布（用你的真实数据） | 博客完成 |

### 第 4 周：Agent 预热 + 项目 step04 + 阶段中点检查

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | Function Calling | 函数调用（Function Calling/Tool Use 工具使用）：JSON Schema 定义工具、模型决定调用 → 执行 → 回传结果 → 继续生成；Spring AI 的 @Tool/FunctionCallback | 给知识库加 3 个工具（查订单/查天气/算术） | 模型能正确选择并调用工具 |
| D2 | ReAct 模式 | ReAct（Reasoning + Acting 推理+行动循环：Thought→Action→Observation）、循环终止条件、最大轮数防护、错误重试 | 手写一个 ReAct 循环（不用框架） | 理解 Agent 的本质 = LLM + 循环 + 工具 |
| D3 | MCP 协议初识 | MCP（Model Context Protocol，模型上下文协议：Anthropic 2024 开源的标准化工具/资源接入协议）、Server/Client/Host 架构、与 Function Calling 的关系（MCP 是标准化后的工具生态） | 阅读 `ai-learning/05` MCP 章节并跑一个官方示例 | 能画出 MCP 三角色交互图 |
| D4 | Spring AI MCP 集成 | spring-ai-mcp 依赖、MCP Server（STDIO/SSE 传输）、把知识库工具暴露为 MCP Server | 完成 step04 前半：MCP Server 化 | 外部 MCP Client 可调用工具 |
| D5 | Agent 化知识库 | 把 step04 做成 Agent：自动决定"检索知识库 or 调用业务工具 or 直接回答"、多工具编排、失败回退 | 完成 step04 后半：Agent 版问答 | 复杂问题自动多工具协作 |
| D6 | 效果回归 + 打磨 | 全量评估回归（100 问）、Prompt 最终打磨、压测（并发下检索+生成性能）、部署脚本 | step04 验收 + 项目 README 编写 | 项目可一键启动演示 |
| D7 | 月度总复盘 | RAG+Agent 串讲 | 月记 + 项目答辩稿（20 分钟） | 达成本月全部验收标准 |

---

## 本月产出清单

1. 评估集（100 问）+ RAGAS 式评估脚本 + v1/v2/v3 成绩单
2. 混合检索 + Rerank + 查询改写 + 权限过滤完整实现
3. 博客 1 篇（RAG 优化实录，真实数据）
4. 项目四完整交付（含 MCP Server 化与 Agent 能力）

## 本月术语表

| 英文 | 中文 |
|------|------|
| Ingestion Pipeline | 数据摄入管道（离线建库链路） |
| Chunking / Overlap | 文本切分 / 重叠窗口 |
| ANN (Approximate Nearest Neighbor) | 近似最近邻检索 |
| HNSW | 分层可导航小世界图（主流向量索引算法） |
| BM25 | 经典关键词检索打分算法 |
| RRF (Reciprocal Rank Fusion) | 倒数排名融合（多路召回合并） |
| Cross-Encoder / Bi-Encoder | 交叉编码器（精排）/ 双塔编码器（召回） |
| HyDE | 假设文档嵌入（先造答案再检索） |
| Multi-hop Question | 多跳问题（需组合多段知识回答） |
| Faithfulness | 忠实度（答案是否忠于检索到的内容） |
| LLM-as-a-Judge | 以大模型为裁判的评估方法 |
| Semantic Cache | 语义缓存（相似问题命中缓存） |
| ReAct | 推理-行动循环（Agent 基本范式） |
| MCP (Model Context Protocol) | 模型上下文协议 |

> 下月：`../month15-Agent与AI架构.md`——Agent 生态、推理部署优化与 AI 应用架构师能力。
