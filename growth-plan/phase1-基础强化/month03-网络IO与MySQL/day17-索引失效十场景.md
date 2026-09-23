# Day 17 · 索引失效十大场景（逐个复现，一个不漏）

> **今日目标**：面试"索引为什么失效"年年必考，网上答案多是背口诀。今天在 100 万行的表上把十大失效场景逐个复现——每个场景一条 SQL + EXPLAIN 证据 + 一句根因，从此这题变成送分题。
> **时长**：理论 0.5h / 实操 2.5h / 输出 0.5h
> **今日产出**：十场景复现记录表（SQL+EXPLAIN+根因）+ 失效场景速查卡

## 1. 知识地图

```
索引失效的本质：优化器认为"用索引的代价比全表扫还高"或"根本无法在树上定位"
两大类根因：
  ① 树定位失效（索引结构被破坏）：函数/运算/隐式转换/前模糊/最左缺失
  ② 代价判定失效（全表更便宜）：低区分度/回表行太多/小表
     （后者不是"失效"，是优化器聪明地放弃——面试要分清，高级感立刻上来）

十大场景总览（今天全部复现）：
  ① 索引列上用函数      name 上 UNION 函数 → 树按原值排的，函数后无序
  ② 索引列参与运算      id + 1 = 42   → 同上，运算破坏定位
  ③ 隐式类型转换        name VARCHAR 却写 name = 42（字符串列被转成数字）
  ④ 前导模糊 LIKE '%x'  树按前缀排的，前缀未知=没法定位起点
  ⑤ 不等于/NOT IN       命中面太大，代价判全表更便宜
  ⑥ OR 混非索引列       OR 一边没索引 → 另一边白走（除非 index merge）
  ⑦ 最左前缀缺失        联合索引跳过首列（昨天已验）
  ⑧ 隐式字符集转换      join 两表 utf8mb4 vs utf8 → 关联列被转码
  ⑨ 优化器主动放弃      区分度低（status 只有 0-9）+ 回表太贵
  ⑩ IS NOT NULL         与 ⑤ 同理（IS NULL 在 8.0 反而可以走索引）
速查卡结论：写 SQL 前过一遍——"索引列保持裸奔（无函数无运算）、
  类型对齐、左模糊不做、OR 全列有索引、命中行数可控"
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Cardinality | 区分度/基数 | 索引列不同值数量，越低越可能被弃用 |
| Implicit Conversion | 隐式类型转换 | 字符串↔数字自动转换，静默杀索引 |
| Sargable | 可搜索参数化 | "查询条件能利用索引"的术语 |
| Index Merge | 索引合并 | OR 两边各走索引再取并集（少用） |
| Optimizer | 优化器 | 基于成本估算选择执行计划 |
| Full Table Scan | 全表扫描 | type=ALL，10 万行以下常是最优解 |

## 3. 动手实操

### 3.1 场景①~⑤：树定位失效组（逐条 EXPLAIN 记录）

```sql
USE learn;
-- 基准：裸列等值 → 走索引
EXPLAIN SELECT * FROM t_index WHERE name = 'user-42';
--   key=idx_name? 若没建先补：ALTER TABLE t_index ADD KEY idx_name(name);

-- ① 函数包裹索引列 → 失效
EXPLAIN SELECT * FROM t_index WHERE UPPER(name) = 'USER-42';
--   key=NULL。根因：B+ 树按 name 原值排序，UPPER 后无法树上定位
--   修复：改写 SQL 不用函数（UPPER 放等号右侧：UPPER('user-42')）

-- ② 索引列参与运算 → 失效
EXPLAIN SELECT * FROM t_index WHERE id + 1 = 43;
EXPLAIN SELECT * FROM t_index WHERE id = 42;      -- 对照组：秒回
--   修复：把运算搬到常量侧（id = 43 - 1）

-- ③ 隐式类型转换 → 失效（字符串列 = 数字）
EXPLAIN SELECT * FROM t_index WHERE name = 42;    -- name 是 VARCHAR
--   key=NULL！MySQL 把每行 name 转数字比较 → 逐行转换=放弃树
--   反向安全：INT 列 = '42' 走索引（字符串转数字一次，不破坏定位）
--   记忆："数字列遇字符串参数不慌，字符串列遇数字参数遭殃"

-- ④ 前导模糊 → 失效；尾模糊 → 能走
EXPLAIN SELECT * FROM t_index WHERE name LIKE '%42';   -- ❌ 前缀未知
EXPLAIN SELECT * FROM t_index WHERE name LIKE 'user-%';-- ✅ 前缀可定位
--   根因一句话：树是按前缀字典序排的，前缀未知等于没有搜索起点

-- ⑤ 不等于 / NOT IN → 大概率弃用（代价判定）
EXPLAIN SELECT * FROM t_index WHERE status <> 5;
--   status 只有 0-9，<>5 命中 90 万行 → 全表更便宜，key=NULL（这是聪明的放弃）
```

### 3.2 场景⑥~⑧：写法与配置坑组

```sql
-- ⑥ OR 一边没索引 → 整句弃用
EXPLAIN SELECT * FROM t_index WHERE id = 42 OR created = NOW();
--   id 有索引但 created 没有 → index merge 不划算 → 全表
--   修复：两边都有索引，或改 UNION

-- ⑦ 联合索引最左缺失（day16 已验，快跑一遍确认）
EXPLAIN SELECT * FROM t_index WHERE created > NOW() - INTERVAL 1 DAY;

-- ⑧ 隐式字符集转换（造一个 utf8 表演示跨表 join 的坑）
CREATE TABLE t_other (name VARCHAR(32)) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- 若 t_index 是 utf8mb4 同款则此坑不触发（8.4 默认一致）；
-- 生产里新老表混用 utf8/utf8mb4 时：join 条件列被 CONVERT → 索引失效
-- 检查命令：SHOW CREATE TABLE t_index\G   -- 两表 COLLATE 必须一致
```

### 3.3 场景⑨~⑩：代价判定组（理解优化器是"精算师"）

```sql
-- ⑨ 区分度太低 + 回表太贵 → 主动放弃
EXPLAIN SELECT * FROM t_index WHERE status = 5;   -- 命中 10 万行
EXPLAIN SELECT id   FROM t_index WHERE status = 5;-- 覆盖版：照样走（day16 结论）
--   同一个条件，SELECT * 弃用、SELECT id 使用 → 证据：弃用不是索引坏了，
--   是"10 万次回表 > 一次全表扫"的算术。加上 LIMIT 10 立刻又能走：
EXPLAIN SELECT * FROM t_index WHERE status = 5 LIMIT 10;

-- ⑩ IS NOT NULL vs IS NULL
EXPLAIN SELECT * FROM t_index WHERE status IS NULL;     -- 可以走索引
EXPLAIN SELECT * FROM t_index WHERE status IS NOT NULL; -- 命中面太大常弃用
--   破除谣言："IS NULL 不走索引"是错的——8.0 里 IS NULL 是精准定位
```

### 3.4 十场景速查卡（抄笔记，面试前 5 分钟过一遍）

```text
| # | 场景              | 根因一句话                     | 修复                |
|---|-------------------|-------------------------------|---------------------|
| 1 | 列上用函数        | 树按原值排，函数后无定位       | 函数移到常量侧      |
| 2 | 列参与运算        | 同上                          | 运算移到常量侧      |
| 3 | 隐式转换(串列=数字)| 逐行转数字，树废              | 参数对齐列类型      |
| 4 | LIKE '%x'         | 前缀未知无搜索起点             | 尾模糊/全文索引     |
| 5 | <> / NOT IN       | 命中面太大，代价判全表         | 拆条件/控制命中量   |
| 6 | OR 混非索引列     | 一边没索引另一边白走           | 两边建索引/UNION    |
| 7 | 最左缺失          | 联合索引从左连续才有效         | 按查询模式设计索引  |
| 8 | 字符集不一致      | join 列被 CONVERT 转码         | 统一 utf8mb4        |
| 9 | 低区分度+大量回表 | 优化器算出全表更便宜           | 覆盖索引/加 LIMIT   |
|10 | IS NOT NULL       | 命中面太大（IS NULL 反而能走）  | 同⑤                |
```

## 4. 面试连接

**Q：线上 SQL 突然变慢，怀疑索引失效，你怎么排查？**
> 四步：① EXPLAIN 看 type/key/rows——key=NULL 即失效，type=ALL 全表；② 对照十大场景查写法（函数/运算/隐式转换/模糊）；③ 查统计信息是否过期 `SHOW TABLE STATUS` 的 Rows 与实际偏差大就 `ANALYZE TABLE`；④ 还不行看数据量变化——可能不是失效，是命中行数涨到全表更便宜。加分句："我把十大失效场景在 100 万行表上全部复现过，每个都留了 EXPLAIN 证据。"

**Q：隐式类型转换为什么会让索引失效？有什么实际事故？**
> 规则：字符串列与数字比较时，MySQL 把【列】转成数字逐行比较——转换发生在每一行上，B+ 树的有序性对转换后的值无效，只能全表扫。事故模板：Java 传参 Long 误传 String 前还好，SQL 拼接 `phone = 13800138000`（列是 VARCHAR）就是全表；更隐蔽的是返回结果错误——`'1abc'` 转数字得 1，会匹配错行。规范：参数类型与列定义严格一致。

**Q：区分度低的列要不要建索引？**
> 单列视角：status 只有 10 个值、命中 1/10 数据，单独建常被弃用。但三个反转：① 组进联合索引当首列等值条件（status=5 AND created>）就是好索引；② 覆盖查询场景照样快（day16 实证）；③ LIMIT 少量行时照样走。结论：别孤立看单列区分度，要看"这个列在联合索引和查询模式里扮演什么角色"。

## 5. 今日验收清单

- [ ] 十个场景全部 EXPLAIN 复现，截图/记录 key 列变化
- [ ] 速查卡默写（10 行表格）
- [ ] "聪明地放弃"vs"真失效"能举例区分
- [ ] 隐式转换的"方向规则"能讲（串列遇数字遭殃）
- [ ] `git add . && git commit -m "day17: 10 index-failure scenarios"`

---
[← Day 16](day16-聚簇回表覆盖与联合索引.md) | [本月目录](README.md) | [Day 18 · EXPLAIN 执行计划逐字段 →](day18-EXPLAIN执行计划.md)
