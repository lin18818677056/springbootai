# Day 03 · 向量库初体验：内存版起步，PGVector 进阶

> **今日目标**：搞懂向量库到底比"自己写循环"强在哪；先用 30 行代码手写一个内存版向量库（原理戳穿），再上 PGVector（生产正路）；理解 ANN 近似索引为什么"够用就行"。
> **时长**：内存版 1h / PGVector 1.5h / 输出 0.5h
> **今日产出**：内存版 kNN 代码 + PGVector 建表插入查询实操记录
> **对照教程**：`ai-learning/04-RAG检索增强.md` 第 2 节

## 1. 知识地图（先讲人话）

```
先泼冷水：向量库不是魔法，核心就一句话——
  "存一堆坐标，来一个新坐标，找出最近的 K 个。"

那为什么不直接存 MySQL 里自己算？
  能！（几百条数据时完全够）——今天的第一件事就是手写这个"穷人版"，
  写完你就彻底懂原理了。
  但数据到几十万条后：每次查询要算几十万次余弦 → 慢死。
  向量库的增值在"索引"：不挨家挨户量距离，而是先分区再细找——
  类比快递分拣：不把你的包裹和全国每个网点比对，先按省、市两级缩小范围。
  这个"近似找最近"叫 ANN（Approximate Nearest Neighbor）——
  牺牲一点点精度（99% 命中 instead of 100%），换百倍千倍速度。够用就行。

选型只说一句（别陷入选择困难）：
  PGVector：会 SQL 就会上手，百万级随便扛 → 后端工程师本月正路
  Milvus/Qdrant：亿级/专业场景 → 知道名字和定位即可（面试说得出差异）
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| Vector Store / Vector DB | 向量库 | 存坐标+找最近邻的数据库 |
| Brute-force kNN | 暴力检索 | 挨个算距离（数据小完全够用） |
| ANN Index | 近似最近邻索引 | 先分区再细找的加速结构（HNSW/IVF 等） |
| Recall | 召回（率） | 该找到的找到没有（95% vs 100% 就是 ANN 的取舍） |
| Collection / Table | 集合/表 | 向量的"抽屉"，按业务分（如知识库 A 一张表） |
| pgvector | PGVector | PostgreSQL 的向量插件（会 SQL 就会用） |

## 3. 动手实操

### 3.1 实验一：30 行内存版向量库（原理戳穿，建议手敲）

```java
// MiniVectorStore.java —— 穷人版向量库（数据量 < 1 万完全能用）
public class MiniVectorStore {
    record Item(String text, double[] vec) {}
    private final List<Item> items = new ArrayList<>();

    void add(String text, double[] vec) { items.add(new Item(text, vec)); }

    // 余弦相似度 = 点积 / (两个模长相乘)；方向一致性
    static double cosine(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    List<Item> topK(double[] query, int k) {          // 暴力 kNN：全算一遍排序
        return items.stream()
            .sorted((x, y) -> Double.compare(cosine(query, y.vec()), cosine(query, x.vec())))
            .limit(k).toList();
    }
}
// 用 day02 的三句话喂进去跑 topK(2)——验证检索结果和肉眼判断一致
// 写完的感受应该是："就这？"——对，原理就这，难的是后面四周的工程化
```

### 3.2 实验二：PGVector 上手（生产正路）

```powershell
docker run -d --name pgvector -e POSTGRES_PASSWORD=pg123 -p 5432:5432 `
  pgvector/pgvector:pg16
docker exec -it pgvector psql -U postgres -c "CREATE EXTENSION vector;"
```

```sql
-- 建表（对标 MySQL 建表习惯，就是多了个 vector 类型）
CREATE TABLE kb_chunks (
  id        bigserial PRIMARY KEY,
  doc_id    text NOT NULL,          -- 哪篇文档（更新链路 day23 靠它幂等）
  content   text NOT NULL,
  embedding vector(768) NOT NULL,   -- 和 nomic-embed-text 维度对齐
  meta      jsonb DEFAULT '{}'      -- 元数据（标题/权限标签 day20 用）
);
-- 插入（向量以字符串形式给：'[0.1,0.2,...]'）
INSERT INTO kb_chunks (doc_id, content, embedding)
VALUES ('refund-faq', '退款流程：订单页→申请退款→…', '[0.11,0.05,...]');
-- 查询：找最近 3 条（<=> 是余弦距离操作符，值越小越近）
SELECT doc_id, content, embedding <=> '[0.11,0.05,...]' AS dist
FROM kb_chunks ORDER BY embedding <=> '[0.11,0.05,...]' LIMIT 3;
```

```
降级路径：Docker 起不来就全程用内存版，W1-W2 实验全部不受影响
  （诚实记录在 day30 未达清单）；PGVector 推迟到 W3 项目时再补。
```

### 3.3 认知三连（自问自答）

```
① 几百条数据需要向量库吗？→ 不需要，内存版/PGVector 都行（别过度工程）
② ANN 丢了 5% 召回怎么办？→ RAG 场景取 Top3 时通常取 Top20 再精选
   （day12 Rerank 的另一个动机）——近似换来的是百倍速度
③ 向量库会取代 MySQL 吗？→ 不会，元数据/权限/业务数据还在关系库，
   向量库只管"找相似"（对照 MySQL 全文索引的定位差异，day09 细讲）
```

## 4. 面试连接

**Q：向量库选型和原理讲一下？（RAG 必问）**
> 原理上向量库就两件事：存嵌入向量和近邻检索。我建议先手写一个暴力 kNN 破除迷信——三十行代码，余弦相似度加排序，几百条数据完全够用；向量库的真正价值是数据量上来之后的 ANN 索引，像 HNSW，思路类似分层分区的快递分拣，牺牲一点点召回换百倍查询速度，RAG 场景配合先粗取 Top20 再重排的流程，召回损失可以忽略。选型上我们用 PGVector：团队会 SQL 零学习成本，和业务表同一个库里，权限过滤直接 where 条件，百万级向量毫无压力；亿级或者超低延迟场景才考虑 Milvus、Qdrant 这种专用向量库。我的原则是先问数据量级和团队栈，别上来就追新——很多团队一个 PGVector 就够到天荒地老，这也是架构师的克制。

## 5. 今日验收清单

- [ ] 内存版 kNN 手敲跑通（"就这？"的顿悟时刻）
- [ ] PGVector 起成功：建表/插入/相似查询三条 SQL 全过
- [ ] ANN 取舍（召回换速度）能讲
- [ ] 认知三连自答通过
- [ ] `git add . && git commit -m "day14-03: vector-store"`
- [ ] 笔记：画"暴力 vs ANN"对比图（挨家挨户 vs 分区分拣）

---
[← Day 02](day02-Embedding向量化.md) | [本月目录](README.md) | [Day 04 · 文档切分 →](day04-文档切分.md)
