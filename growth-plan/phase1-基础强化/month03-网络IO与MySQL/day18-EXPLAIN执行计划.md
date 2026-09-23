# Day 18 · EXPLAIN 执行计划逐字段解读（优化的"化验单"）

> **今日目标**：前三天都在"看结果"，今天系统学"看过程"：EXPLAIN 输出的每一列是什么、type 访问类型的七档天梯、Extra 里的关键暗号。学完你能对任何慢 SQL 出具"化验单解读报告"——这是慢 SQL 治理（day20）的前置技能。
> **时长**：理论 1.5h / 实操 1.5h / 输出 0.5h
> **今日产出**：type 天梯默写卡 + Extra 暗号表 + 三张真实 EXPLAIN 解读报告

## 1. 知识地图

```
EXPLAIN 12 列，重点只盯 6 列（★面试与实战权重）：

  id          同 id 从上往下；大 id 先执行（子查询嵌套）
  select_type SIMPLE/PRIMARY/SUBQUERY/DERIVED（衍生表）
  table       这一步访问哪张表
  type  ★★★  访问类型天梯（从好到坏）：
    system > const   主键/唯一键等值，最多 1 行（id=42）
    eq_ref           join 时被驱动表走主键/唯一键（每行精准命中）
    ref              普通二级索引等值（status=5）
    range            索引范围（id > 100 / BETWEEN）
    index            扫整棵索引树（比 ALL 好：索引小，但仍全扫）
    ALL              全表扫（10 万行以上就要警惕）
    记忆口诀："const 精准、eq_ref 配 join、ref 等值、range 范围、
              index 扫索引、ALL 扫全表；优化目标是 ref 及以上"
  possible_keys / key ★★  候选 vs 实际用上的索引（key=NULL = 没用上）
  key_len ★★  索引用了多长（字节）：判断联合索引用了前几列
    例：TINYINT 1B + BIGINT 8B → key_len=9 说明两列都命中
    （VARCHAR(n) utf8mb4 = 4n+2；可空再 +1）
  rows ★★★   预估扫描行数——成本直觉的第一指标
  filtered    存储层返回后 Server 层再过滤的比例估计
  Extra ★★★   关键暗号：
    Using index           覆盖索引（好）
    Using index condition ICP 下推（好）
    Using where           Server 层过滤（中性）
    Using temporary       临时表（group by/distinct 没走索引，坏）
    Using filesort        额外排序（ORDER BY 没走索引序，坏）
    Using join buffer (Block Nested Loop) 被驱动表无索引 join（很坏）

成本直觉公式：总代价 ≈ rows × 单行成本(是否回表/是否排序)
  rows 小 ≠ 快（10 万次回表 > 全表扫，day17 场景⑨）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Execution Plan | 执行计划 | 优化器选定的访问路径 |
| Access Type | 访问类型 | const→ALL 的七档天梯 |
| key_len | 索引使用长度 | 判联合索引命中列数 |
| Filesort | 文件排序 | 内存/磁盘排序缓冲（非真读文件） |
| Derived Table | 衍生表 | 子查询物化成的临时表 |
| Query Cost | 查询成本 | 优化器按 IO+CPU 估算的代价值 |

## 3. 动手实操

### 3.1 七档 type 逐档复现（100 万行 t_index 上全都能演）

```sql
USE learn;
-- const：主键等值
EXPLAIN SELECT * FROM t_index WHERE id = 42;
--   type=const, rows=1 —— 天花板

-- ref：二级索引等值（注意选区分度好的写法让它稳定出现）
EXPLAIN SELECT id FROM t_index WHERE status = 5 LIMIT 10;
--   type=ref, key=idx_status

-- range：索引范围
EXPLAIN SELECT * FROM t_index WHERE id BETWEEN 100 AND 200;
--   type=range, key=PRIMARY

-- index：扫全索引（覆盖但无过滤条件）
EXPLAIN SELECT status FROM t_index;
--   type=index —— 全索引扫，比 ALL 好在索引小

-- ALL：全表扫（对照）
EXPLAIN SELECT * FROM t_index WHERE name LIKE '%42';
--   type=ALL —— day17 场景④的失效现场

-- eq_ref：join 场景（造小表驱动大表）
CREATE TABLE t_user_ext (id BIGINT PRIMARY KEY, tag VARCHAR(16)) ENGINE=InnoDB;
INSERT INTO t_user_ext SELECT id, CONCAT('tag-', id % 7) FROM t_index WHERE id <= 100;
EXPLAIN SELECT * FROM t_user_ext e JOIN t_index t ON e.id = t.id;
--   t 表那行 type=eq_ref：被驱动表按主键精准命中（join 的最优形态）
```

### 3.2 key_len 解码实验：联合索引用了哪几列

```sql
-- 回忆表结构：status TINYINT(1B, NOT NULL)，created DATETIME(5B, NOT NULL)
EXPLAIN SELECT * FROM t_index WHERE status = 1 AND created > '2026-01-01';
--   key_len = 1 + 5 = 6 → 两列全用上
EXPLAIN SELECT * FROM t_index WHERE status = 1;
--   key_len = 1 → 只用了 status 一列
-- 面试价值：不看 Extra 也能从 key_len 推联合索引命中列数——高级证据
```

### 3.3 Extra 暗号现场：filesort 与 temporary 的制造与消除

```sql
-- 制造 filesort：ORDER BY 列不在索引序里
EXPLAIN SELECT * FROM t_index WHERE status = 1 ORDER BY name;
--   Extra: Using filesort ← (status,created) 的索引序对 name 无效
--   消除：若该查询高频，建 (status,name) 或 (status,name,created)

-- 制造 temporary：GROUP BY 无索引序
EXPLAIN SELECT name, COUNT(*) FROM t_index WHERE status = 1 GROUP BY name LIMIT 5;
--   Extra: Using temporary; Using filesort ← 双坏
--   消除思路：给 GROUP BY 列配联合索引前缀 (status,name)
ALTER TABLE t_index ADD KEY idx_status_name (status, name);
EXPLAIN SELECT name, COUNT(*) FROM t_index WHERE status = 1 GROUP BY name LIMIT 5;
--   Extra 干净了（Using index condition 或 nothing）——前后对比截图！
```

### 3.4 三份"化验单解读报告"（输出模板，直接套用）

```text
报告模板（每份 5 行，day20 慢 SQL 门诊会实际用到）：
  ┌ 化验单 ─────────────────────────────────┐
  │ SQL：SELECT * FROM t WHERE status=5 ORDER BY name │
  │ type: ref(索引等值 OK)                   │
  │ key/len: idx_status / 1（只用 1 列）      │
  │ rows: 100000（+回表×10万 = 成本高危）     │
  │ Extra: Using filesort（额外排序坏点）     │
  │ 结论/处方：建 (status,name) 覆盖排序；     │
  │   评估命中量是否过大需加分页条件          │
  └─────────────────────────────────────────┘
今日产出：给以下三条 SQL 各写一份：
  ① SELECT * FROM t_index WHERE status<>5;
  ② SELECT name,COUNT(*) FROM t_index GROUP BY name LIMIT 5;
  ③ SELECT * FROM t_index WHERE id+1=43;
```

## 4. 面试连接

**Q：拿到一条慢 SQL，你的标准分析流程？**
> 五步：① EXPLAIN 看 type/key/rows/Extra 四要素定位坏点；② 对照失效场景查写法（day17 十场景）；③ 看命中行数与回表量判断索引性价比；④ 优化方案（改索引/改写法/覆盖）后 EXPLAIN 对比验证；⑤ 上线前压测确认。加分句："我会顺手看 key_len 判断联合索引用了几列，Extra 有 filesort/temporary 说明排序分组没吃上索引序。"

**Q：type 里 index 和 ALL 都是全扫，差别是什么？**
> 都是"从头扫到尾"，但 index 扫的是索引树（顺序读索引页，数据小且连续，还能覆盖免回表），ALL 扫聚簇叶子（整行肥大）。所以 SELECT status FROM t 是 index，SELECT * 是 ALL——同一个查询只改 SELECT 列就换了访问类型，这正是覆盖索引的价值。本质：扫描的数据体积不同。

**Q：Using filesort 一定是问题吗？**
> 不一定。名字吓人，实际是"排序没吃到索引序，需要额外 sort buffer"，少量行（LIMIT 分页前几百行）代价可忽略；危险的是"大 WHERE 命中量 + 大排序"的组合。判断标准：rows 数量级。处理优先级：能建索引吃上索引序 > SQL 改写 > 业务上限制排序规模（分页）。这个"不是非黑即白"的回答比背概念高一档。

## 5. 今日验收清单

- [ ] 七档 type 全部现场复现并截图
- [ ] key_len 两连测（6 vs 1）记录，会算 TINYINT/DATETIME 长度
- [ ] filesort/temporary 制造与消除的前后 EXPLAIN 对比
- [ ] 三份化验单解读报告写完
- [ ] `git add . && git commit -m "day18: explain fields & type ladder"`

---
[← Day 17](day17-索引失效十场景.md) | [本月目录](README.md) | [Day 19 · 优化器与 Join 算法 →](day19-优化器与Join算法.md)
