# Step 2：文档处理与向量检索（解析 / 切分 / Embedding / 混合检索）

> **本步目标**：打通"上传文档 → 解析 → 切分 → 向量化 → 入库 → 可检索"的入库链路，吃透 Embedding（向量化）与相似度检索原理，并实现向量 + 关键词的混合检索。

## 前置术语

| 英文 | 中文 |
|------|------|
| Embedding | 向量化（把文本映射成高维向量，语义相近则向量相近） |
| Vector Database | 向量数据库（存向量 + 近似最近邻检索） |
| Chunk / Chunking | 块 / 切分（把长文档切成检索粒度的片段） |
| Cosine Similarity | 余弦相似度（向量夹角，越大越相似） |
| ANN (Approximate Nearest Neighbor) | 近似最近邻检索（用索引换速度，容忍微小误差） |
| HNSW | 分层可导航小世界图（pgvector 的索引算法之一） |
| BM25 | 最佳匹配 25（经典关键词检索打分算法，Elasticsearch 默认） |
| Hybrid Search / RRF | 混合检索 / 倒数排名融合（多路检索结果融合算法） |
| Metadata Filter | 元数据过滤（按部门/文档类型等先过滤再检索） |
| ETL (Extract-Transform-Load) | 抽取-转换-加载（文档入库的工程化叫法） |

## 1. 原理先行：Embedding 到底在算什么

- 每个 Chunk 被模型编码成一个 1024~1536 维的向量，**语义相近的文本在向量空间里距离近**："如何退货" 和 "退货流程是什么" 字面不同、向量很近；"退货流程" 和 "发票开具" 距离远。
- 检索 = 把用户问题也向量化 → 在库里找余弦相似度最高的 K 个 Chunk。
- **纯向量检索的盲区**：型号、人名、错误码这类**精确关键词**，向量反而会失手（语义近似但字面检索更快更准）→ 所以生产必须混合检索。

## 2. PostgreSQL + pgvector 准备

```yaml
  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_PASSWORD: rag123
    ports: ["5432:5432"]
    volumes: [pg-data:/var/lib/postgresql/data]
```

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_chunk (
  id          BIGSERIAL PRIMARY KEY,
  doc_id      BIGINT       NOT NULL,        -- 属于哪篇文档
  doc_name    TEXT         NOT NULL,
  department  VARCHAR(64)  NOT NULL,        -- 元数据：部门（权限过滤用）
  chunk_no    INT          NOT NULL,        -- 第几块
  content     TEXT         NOT NULL,        -- 原文（生成答案的依据！）
  embedding   vector(1024) NOT NULL,        -- 与 Embedding 模型维度一致
  created_at  TIMESTAMP    DEFAULT now()
);
-- HNSW 索引：查询快；构建慢、占内存（<100 万块用 HNSW 都没问题）
CREATE INDEX ON knowledge_chunk
  USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
```

**关键认知**：`content` 原文必须存——向量只用来"找"，回答要"看着原文说"，这是引用溯源的基础。

## 3. 文档解析与切分（RAG 效果的第一决定因素）

```java
@Service
public class IngestService {
    private final EmbeddingModel embeddingModel;     // Spring AI 抽象
    private final JdbcTemplate db;

    public void ingest(MultipartFile file, String department) throws Exception {
        // 1) 解析：Tika 统一吃 PDF/Word/Markdown → 纯文本
        String text;
        try (InputStream in = file.getInputStream()) {
            text = new BodyContentHandler(-1).toString(
                new AutoDetectParser().parse(in, new Metadata(), new ParseContext()));
        }
        // 2) 切分：按段落聚合到 ~500 Token，重叠 50 Token
        List<String> chunks = split(text, 500, 50);
        // 3) 批量向量化 + 入库（批量调用省一半网络开销）
        List<float[]> vectors = embeddingModel.embed(chunks);
        // ... 批量 INSERT，参数略
    }
}
```

**切分策略（面试必问，给结论）**：

| 策略 | 参数经验 | 适用 |
|------|---------|------|
| 固定长度 + 重叠（overlap） | 300~500 Token，重叠 10%~20% | 通用兜底，先跑通 |
| 按标题/章节切（结构感知） | Markdown 标题、Word 样式 | 手册、规范类文档（本实践首选） |
| 父子块 | 子块检索、父块喂给模型 | 兼顾检索精度与上下文完整性 |
| 表格 | 整表不切 + 行级补切 | 价格表、参数表（切散就废了） |

切太碎 → 每块缺上下文，答案断章取义；切太大 → 噪声稀释关键信息且挤占上下文窗口。**判定标准：单独拿一块给同事看，不看上下文能懂它在说什么。**

## 4. 混合检索：向量 + 关键词 + 融合

```sql
-- 1) 向量路：语义召回
SELECT id, content, doc_name, 1 - (embedding <=> :queryVec) AS score
FROM knowledge_chunk
WHERE department = :department                 -- 元数据先过滤（权限！）
ORDER BY embedding <=> :queryVec
LIMIT 20;

-- 2) 关键词路：全文检索召回（中文需配 zhparser/pg_jieba 分词插件）
SELECT id, content, doc_name,
       ts_rank(content_tsv, websearch_to_tsquery('chinese_zh', :query)) AS score
FROM knowledge_chunk
WHERE content_tsv @@ websearch_to_tsquery('chinese_zh', :query)
ORDER BY score DESC LIMIT 20;
```

```java
// 3) RRF（Reciprocal Rank Fusion，倒数排名融合）：不比分数只比名次
double rrf(List<String> docIds, String id) {
    int rank = docIds.indexOf(id) + 1;
    return rank == 0 ? 0 : 1.0 / (60 + rank);   // 60 是经验平滑常数
}
// 两路结果按 RRF 分数排序，取 TopK 进重排/生成
```

为什么用 RRF：向量分和 BM25 分**量纲不同不能直接加权**，RRF 只看"各自排第几"，稳健且免调参。

## 5. 检索质量验证（不评估就调参 = 玄学）

建 20 条"问题 → 正确出处 Chunk"的标注集（黄金集），计算：

- **Recall@K**（前 K 召回率）：正确 Chunk 出现在前 K 的比例 → 检索保底指标
- **MRR**（Mean Reciprocal Rank，平均倒数排名）：正确结果排得越靠前分越高

调参顺序：切分粒度 → 加关键词路 → Rerank 模型（step03）→ 查询改写。每调一次跑一遍黄金集，**用数字说话**。

## 验收清单

- [ ] 上传 3 类文档（PDF/Word/Markdown）全部入库成功，Chunk 数合理（100 页 → 数千块）
- [ ] 语义问题（"怎么把东西退了"）能命中"退货流程"块 → 向量路生效
- [ ] 精确关键词（错误码 E-4021）能命中 → 关键词路生效
- [ ] RRF 融合后 Top5 质量 ≥ 任一单路（用黄金集 Recall@5 对比记录）
- [ ] 不同 department 的检索互相隔离（权限过滤验证）
- [ ] 记录：切分参数、Recall@5、MRR 基线数字（step03 优化后对比）

## 常见坑

1. **向量维度不匹配**：模型输出 1024 维，表建成 1536 维 → 入库直接报错；维度由模型决定，先定模型再建表。
2. **切分按字符数硬切**：把表格/代码/一句话拦腰斩断 → 结构感知切分或表格特殊处理。
3. **忘了存原文**：只存向量，答案引用时没处取 → content 必须落库。
4. **全文检索没配中文分词**：英文空格分词逻辑对中文无效 → zhparser/pg_jieba，或关键词路换 Elasticsearch。
5. **入库不幂等**：同一文档重复上传向量翻倍 → 按 `doc_id + hash(content)` 去重，重传先删后插。
6. **检索没做权限过滤**：A 部门问出 B 部门薪资制度 → 元数据过滤要在**检索前**做，不是检索后。

> 完成后进入 `step03-RAG问答链路.md`。
