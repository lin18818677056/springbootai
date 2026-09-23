# 第 13 月：大模型（LLM，Large Language Model）基础与 Prompt（提示词）工程

> **本月一句话目标**：以工程师（而非算法研究员）视角建立 AI 知识体系：大模型如何工作、API 如何调用、Prompt 如何设计。本月结束你能独立开发第一个 AI 应用，并启动 RAG 项目。**详细教程配合阅读 `ai-learning/01~02`。**

## 本月目标与验收标准

- [ ] 讲清 Transformer（变换器架构）、Token（词元）、Embedding（向量化表示）、注意力机制（Attention）的工作直觉
- [ ] 理解训练三部曲：预训练（Pre-training）、监督微调（SFT，Supervised Fine-Tuning）、人类反馈强化学习（RLHF，Reinforcement Learning from Human Feedback）
- [ ] 掌握 OpenAI 兼容 API 协议（Chat Completions）与关键参数（temperature、top_p、max_tokens、上下文窗口 Context Window）
- [ ] 掌握 Prompt 工程方法论：角色设定、Few-shot（少样本示例）、思维链（CoT，Chain of Thought）、结构化输出（JSON Mode）
- [ ] 用 Spring AI 完成：多模型接入（国产 DeepSeek/Qwen + 国际模型 + 本地 Ollama）、流式输出（SSE）、结构化输出
- [ ] 启动项目四（RAG 知识库），完成 step01

---

## 每日计划

### 第 1 周：AI 通识与大模型原理（工程师版）

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | AI 知识地图 | 人工智能（AI）→ 机器学习（ML，Machine Learning）→ 深度学习（DL，Deep Learning）→ 大语言模型（LLM）的层级关系；判别式模型 vs 生成式模型（Generative Model）；AIGC（AI Generated Content 生成式内容） | 画出 AI 知识地图 | 能给非技术人讲清 AI/ML/DL/LLM 关系 |
| D2 | 机器学习基础概念 | 监督学习（Supervised）/无监督（Unsupervised）/强化学习（RL，Reinforcement Learning）；训练集/验证集/测试集（Train/Validation/Test Split）、过拟合（Overfitting）与欠拟合（Underfitting）、损失函数（Loss Function）直觉 | 用一句话+图解释过拟合 | 能讲清"泛化（Generalization）"是什么 |
| D3 | 神经网络直觉 | 神经元与权重（Weight）、激活函数（Activation Function）、反向传播（Backpropagation：误差回传调权重）的直觉理解、梯度下降（Gradient Descent：沿坡下山找最小损失） | 用 Excel 模拟一个 2 层网络学习 XOR | 理解"训练就是不断调参降损失" |
| D4 | Transformer 架构 | Transformer（2017《Attention Is All You Need》）、自注意力机制（Self-Attention：Q/K/V 查询-键-值）、多头注意力（Multi-Head Attention）、位置编码（Positional Encoding）；为何取代 RNN（可并行 + 长依赖） | 看图解视频/文章并手绘注意力计算示意 | 能讲清"注意力就是加权相关度" |
| D5 | Token 与生成机制 | Token（词元：模型的最小处理单位，中文 1 字 ≈ 1-2 token）、BPE（Byte Pair Encoding 字节对编码）、自回归生成（Autoregressive：逐 token 预测下一个）、采样策略（Temperature 温度/Top-p 核采样/Top-K）、上下文窗口（Context Window） | 用 tokenizer 在线工具数 token；对比 temperature=0.1/1.0 输出差异 | 能估算一段中文的 token 成本 |
| D6 | Embedding 与向量空间 | Embedding（嵌入：把文本映射为高维向量）、余弦相似度（Cosine Similarity）、语义相似检索直觉、向量维度（如 1024/1536 维） | 调用 Embedding API 计算几组句子相似度 | 能解释"为什么'苹果手机'和'iPhone'相似度高" |
| D7 | 周复盘 | 原理串讲 | 周记 + 《给产品经理讲大模型：一页纸》输出 | 讲解稿通过"外行听懂"测试 |

### 第 2 周：模型生态、API 与工程化调用

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 模型生态全景 | 国际：GPT 系（OpenAI）/Claude 系（Anthropic）/Gemini（Google）/Llama（Meta 开源）；国内：DeepSeek/Qwen 通义千问/GLM 智谱/文心/Kimi；开源权重模型 vs 闭源 API；多模态（Multimodal：文本/图像/音频） | 输出《模型选型对比表》（能力/价格/上下文/合规） | 表格可支撑真实项目选型 |
| D2 | OpenAI 兼容协议 | Chat Completions API 协议（messages 数组角色 system/user/assistant、choices/usage）、流式 SSE（Server-Sent Events：chunk 拼接）、函数调用（Function Calling）字段 | 用 curl/HTTP 客户端裸调 API（不经 SDK） | 能徒手构造完整请求与解析响应 |
| D3 | Spring AI 初体验 | Spring AI 核心抽象：ChatClient/ChatModel、自动配置、多模型 starter 切换、Prompt 模板（Template） | 在 springbootai 项目接入 Spring AI + 一家商用模型 | `/ai/chat` 接口返回结果 |
| D4 | 本地模型 Ollama | Ollama（本地大模型运行时）、模型量化（Quantization：GGUF 格式 Q4/Q8 精度-体积权衡）、显存/内存估算（7B Q4 ≈ 5GB 内存） | Ollama 拉取 qwen/deepseek 小模型，Spring AI 接入本地模型 | 本地对话可用，记录资源占用 |
| D5 | 流式与高并发 | SSE 完整实现（Flux\<String\> 流式接口）、前端打字机效果原理、超时与取消（Abort）、重试与限流（Rate Limit 429 处理）、成本核算（输入/输出 token 单价） | 实现流式对话接口 + 并发压测 | 50 并发下 SSE 稳定 |
| D6 | 提示上下文管理 | 上下文窗口溢出处理（截断/摘要压缩）、多轮对话记忆（会话历史管理：数据库存 messages）、系统提示词（System Prompt）与角色分离 | 实现带历史记忆的多轮对话接口 | 10 轮对话不超窗、记忆正确 |
| D7 | 周复盘 | API 工程化串讲 | 周记 + 《AI 接口网关设计要点》（限流/计费/降级/日志） | 达成周验收 |

### 第 3 周：Prompt 工程（Prompt Engineering，提示词工程）

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | Prompt 基本结构 | 四要素：角色（Role）+ 任务（Task）+ 约束（Constraints）+ 输出格式（Format）；指令清晰化技巧（分隔符/示例/负面清单）；中英文 Prompt 差异 | 重写 5 个失败 Prompt 并对比效果 | 有前后效果对比记录 |
| D2 | Few-shot 与 CoT | 少样本示例（Few-shot Learning：给 2-3 个输入输出示例）、零样本（Zero-shot）vs Few-shot、思维链（CoT，Chain of Thought："让我们一步步思考"）、自洽性（Self-Consistency：多次采样投票） | 对分类任务做 Zero/Few-shot/CoT 三组实验 | 三组准确率对比数据 |
| D3 | 结构化输出 | JSON Mode / 结构化输出（Structured Output：JSON Schema 约束）、Spring AI 的 Bean 转换（entity 方法）、枚举约束、校验与重试（格式错误自动重试） | 实现简历解析接口（自然语言→结构化 JSON 入库） | 解析成功率 > 95%，字段可校验 |
| D4 | Prompt 模板工程化 | 模板管理（版本化/变量化/配置中心下发）、Prompt 调试技巧（小模型快速迭代→大模型验证）、评测集（Eval Set：30 条标注数据回归测试） | 建立"营销文案生成"Prompt 模板 + 评测集 | 模板改动有回归评测兜底 |
| D5 | 对抗与安全 | 提示词注入（Prompt Injection：用户覆盖系统指令）与防护（指令隔离/输入过滤/输出审查）、越狱（Jailbreak）、内容安全（合规审核 API） | 攻击自己的对话机器人并修复 | 至少演示 1 种攻击与 2 层防护 |
| D6 | RAG 前置知识 | 为什么需要 RAG（知识时效/私有知识/幻觉 Hallucination 问题）、RAG vs 微调（Fine-tuning）选型（成本/时效/可控性）、RAG 基本流程预习（检索→增强→生成） | 输出《RAG vs 微调决策表》 | 决策表能应对追问 |
| D7 | 月度中点复盘 | 原理+API+Prompt 串讲 | 博客：《写给 Java 工程师的 LLM 入门：从 Transformer 到第一个 AI 接口》发布 | 博客完成 |

### 第 4 周：RAG 项目启动

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 项目四启动 | 企业知识库需求：上传文档（PDF/Word/Markdown）→ 问答（含出处引用）→ 权限隔离；架构：Spring AI + 向量库 + LLM | 阅读 `practice-projects/04-rag-knowledge-base/step01` 并完成设计 | 架构图 + 接口设计完成 |
| D2 | Spring AI 深入 | ChatClient 编排（Advisor 链）、对话记忆 Advisor（MessageChatMemoryAdvisor）、日志与观测（Observability） | 完成 step01：ChatClient 封装（多模型路由） | 一套代码切换 3 个模型 |
| D3 | 文档解析 | Tika/PDFBox 解析 PDF/Word、Markdown 解析、清洗（去页眉页脚/表格处理） | 完成 step02 前半：文档解析模块 | 3 种格式解析正确 |
| D4 | 切分与向量化 | 切分策略（Chunking：固定长度/递归字符/按语义段落/滑动窗口重叠 Overlap）、Embedding 批量生成、元数据（Metadata：来源/页码/权限） | 完成 step02 后半：切分 + 向量化入库 | 切分实验：3 种策略对比检索效果 |
| D5 | 向量库选型与接入 | 向量数据库对比（Milvus 分布式/PgVector 复用 PG/Elasticsearch 混合检索/Redis 向量）、相似度度量（余弦/内积/L2）、topK 召回 | 选型并接入（推荐 PgVector 起步） | 检索接口返回相似段落 |
| D6 | 第一版 RAG 链路 | 完整链路：问题→向量化→召回→拼 Prompt→生成→带引用返回 | 完成 step03 前半：基础 RAG 问答 | 能正确回答知识库内问题 |
| D7 | 月度总复盘 | 大模型+Prompt+RAG v1 串讲 | 月记 + 项目周报 | 达成本月全部验收标准 |

---

## 本月产出清单

1. 《模型选型对比表》《RAG vs 微调决策表》《AI 接口网关设计要点》
2. 博客 1 篇（LLM 入门）
3. Prompt 模板库（含评测集与回归脚本）
4. RAG 项目：多模型 ChatClient + 文档解析 + 向量检索 + 基础问答链路

## 本月术语表

| 英文 | 中文 |
|------|------|
| LLM (Large Language Model) | 大语言模型 |
| Transformer / Attention | 变换器架构 / 注意力机制 |
| Token / BPE | 词元 / 字节对编码（分词算法） |
| Embedding | 嵌入向量（文本的数值化表示） |
| Cosine Similarity | 余弦相似度 |
| Context Window | 上下文窗口（模型一次能处理的最大 token 数） |
| Temperature / Top-p | 温度（随机度）/ 核采样（累积概率截断） |
| Hallucination | 幻觉（模型一本正经地编造） |
| Prompt Injection | 提示词注入（对抗攻击） |
| Chain of Thought (CoT) | 思维链（引导模型分步推理） |
| SFT / RLHF | 监督微调 / 基于人类反馈的强化学习 |
| RAG (Retrieval-Augmented Generation) | 检索增强生成 |

> 下月：`../month14-RAG与知识库.md`——把 RAG 项目做到生产级。
