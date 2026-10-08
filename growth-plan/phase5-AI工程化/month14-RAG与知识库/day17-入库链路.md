# Day 17 · 入库链路：幂等是底线

> **今日目标**：写 `KBIngestService`——解析→清洗→切分→嵌入→入库五步的 Java 工程版；核心纪律是幂等（同一文档重灌 N 遍，库里结果不变）；给 day23 的更新链路打好地基。
> **时长**：编码 2.5h / 幂等验证 1h / 输出 0.5h
> **今日产出**：`KBIngestService` + 幂等验证记录（连灌 3 遍实验）
> **对照教程**：`ai-learning/04-RAG检索增强.md` 第 4 节"容易被忽略的三件事"

## 1. 知识地图（先讲人话）

```
入库链路五步（day05 手工版的工程化）：
  ① 解析 Parse：txt/md 直接读；PDF/Word 用解析库提文本（表格转 Markdown）
  ② 清洗 Clean：去页眉页脚/乱码/重复空行——垃圾进垃圾出（day04 心法）
  ③ 切分 Chunk：父子块两刀（父 500 / 子 100，day13 的成品策略）
  ④ 嵌入 Embed：子块批量调 EmbeddingClient（day16 的 batch 接口）
  ⑤ 入库 Persist：父子两张表，一个事务里写完

今天的主角是幂等（Idempotent）——同一操作做多少遍，结果都一样：
  场景：运营说"文档改了一版，重新导一下"→ 你点了导入按钮 3 次
  非幂等的惨案：库里出现 3 份重复块 → 检索返回 3 个一模一样的结果
  → 窗口被重复内容塞满 → 答案质量暴跌还没报错（最难查的一种坏）
  幂等的做法：先删后插 + 内容指纹
    · 按 doc_id 删旧块（父子两表都删）
    · 算新内容的指纹 content_hash（SHA-256）：
      hash 没变 → 直接跳过（白灌 N 遍也只有 1 份）
      hash 变了 → 删旧插新（更新成功）
    · 三步包在一个事务里：删了插一半时挂掉=脏库（半新半旧）
      事务保证"要么全换、要么全不换"（M4 MySQL 事务思想直接平移）

类比：Excel 数据导入的两种模式——
  追加式（每导一次多一份）→ 事故模式
  覆盖式（按主键 upsert）→ 幂等模式。RAG 入库必须是覆盖式。
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| Idempotency | 幂等 | 做 N 遍=做 1 遍（导入按钮连点 3 次也不重复） |
| Content Hash | 内容指纹 | 文档内容的 SHA-256，变了才需要重灌 |
| Upsert | 更新或插入 | 有则更新无则插入（覆盖式导入） |
| Transaction | 事务 | 删旧+插新绑在一起：要么全成，要么全不动 |
| Batch Embedding | 批量嵌入 | 一百个子块一趟 HTTP 带回（省 99 次往返） |
| Stale Chunk | 过期块 | 文档改了但库里还是旧块（更新链路失职的产物） |

## 3. 动手实操：KBIngestService 编码+验证

### 3.1 服务骨架（五步一事务）

```java
// kb/service/KBIngestService.java
@Transactional                       // ⑤ 删旧+插新同生共死
public IngestResult ingest(String docId, String rawText) {
    String newHash = sha256(rawText);
    String oldHash = metaMapper.findHash(docId);
    if (newHash.equals(oldHash)) {                    // 指纹没变 → 直接跳过
        return IngestResult.skipped(docId);           // （白点 N 遍也无害）
    }
    // ① ②（解析在 controller 层做完传入；清洗工具方法）
    String text = TextCleaner.clean(rawText);
    // ③ 切分：父子两刀（day13 策略常量化）
    List<ParentChunk> parents = Chunker.splitParents(text, 500);
    parents.forEach(p -> p.setChildren(Chunker.splitChildren(p, 100)));
    // ④ 批量嵌入：所有子块一趟带回
    List<double[]> vecs = embeddingClient.batch(allChildrenTexts(parents));
    // 幂等三步：删旧 → 插父 → 插子（同一事务）
    chunkMapper.deleteByDocId(docId);                 // 子表按 doc_id 级联
    parentMapper.deleteByDocId(docId);
    parents.forEach(p -> parentMapper.insert(docId, p));
    childrenMapper.batchInsert(parents, vecs);
    metaMapper.upsertHash(docId, newHash);            // 记录新指纹
    return IngestResult.ok(docId, parents.size(), totalChildren(parents));
}
```

### 3.2 幂等验证实验（今天的验收仪式）

```powershell
# 同一份文档连灌 3 遍（模拟运营手抖）
POST /api/kb/ingest  ×3   （body 相同）
# 每遍后查库：
SELECT count(*) FROM kb_children;   -- 预期：3 遍都是同一个数
SELECT count(*) FROM kb_parents;    -- 同上
-- 第 4 遍：改一个字再灌
-- 预期：hash 变化 → 删旧插新成功；日志显示 skipped×3 + update×1
```

```
《幂等验证记录》：
  第 1 遍 | inserted: 父__子__ | 第 2 遍 | skipped | 第 3 遍 | skipped
  第 4 遍（改 1 字）| updated: 父__子__ | 旧块残留 = 0 ✓
□ 反向实验（理解为什么事务）：手工在子表插入制造"半新半旧"，
  跑一次检索看污染效果 → 再跑 ingest 修复 → 体会事务的必要性
```

### 3.3 给 day23 预埋的三根桩

```
① doc_id 是更新的主键（今天已落实）
② meta 表记 hash + updated_at + version（今天已记前两个）
③ 删除文档接口：DELETE 按 doc_id 删父子块+meta（今天顺手写，
   30 行——没有删除功能的知识库不敢叫知识库）
```

## 4. 面试连接

**Q：RAG 的知识更新怎么保证不出脏数据？（幂等题，day23 的前哨战）**
> 入库链路我做成了幂等设计，三道保险。第一道内容指纹：文档算 SHA-256 存元数据表，重灌时先比指纹，没变直接跳过——运营连点三次导入按钮也只有一份，还能省嵌入的算力。第二道先删后插加事务：指纹变了就按 doc_id 删掉父子两表的旧块再插新块，整个包在一个数据库事务里——不会出现删了旧的、新的插一半宕机导致的半新半旧脏库。第三道批量嵌入：所有子块一趟 HTTP 带回，一百个子块从一百次往返变一次。我们做过验证实验：同一文档连灌三遍，库里块数纹丝不动，改一个字再灌就精准更新且旧块零残留。还做过反向实验：手工制造半新半旧的脏数据跑检索，看到检索把新旧两版内容一起返回、模型回答精神分裂——这正是事务存在的意义。这套设计给后续的增量更新打了地基：文档级指纹决定要不要重灌，未来上定时同步和消息触发都是在这个骨架上加触发器，核心纪律不变——入库必须幂等，脏库比没库更可怕，因为它不报错、只悄悄污染答案。

## 5. 今日验收清单

- [ ] KBIngestService 五步跑通（事务注解生效）
- [ ] 连灌 3 遍幂等验证过（块数不变截图留档）
- [ ] 改 1 字重灌 = 精准更新（hash 生效证据）
- [ ] 删除接口写完（day23 的三根桩之一）
- [ ] `git add . && git commit -m "day14-17: ingest-pipeline"`
- [ ] 笔记：把"脏库不报错只污染"记下来（最有说服力的事故叙事）

---
[← Day 16](day16-Java客户端封装.md) | [本月目录](README.md) | [Day 18 · 查询链路与流式 →](day18-查询链路与流式.md)
