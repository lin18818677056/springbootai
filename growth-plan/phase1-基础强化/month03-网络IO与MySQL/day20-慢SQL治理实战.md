# Day 20 · 慢 SQL 治理实战（5 条经典病例全程会诊）

> **今日目标**：把 day15-19 的全部武器用于实战：开启慢日志，抓 5 条经典慢 SQL（含旧大纲验收点"深分页"），每条走完整流程"症状 → 化验 → 处方 → 前后耗时对比"。今天结束你就有了一套可复用的《慢 SQL 门诊手册》。
> **时长**：理论 0.5h / 实操 2.5h / 输出 1h
> **今日产出**：5 条治理记录（优化前后耗时对比）+《SQL 优化检查清单》v1（day21 完善）

## 1. 知识地图

```
慢 SQL 治理标准流程（五步，背下来）：
  ① 慢日志抓取定位（是谁、多慢、多频繁）
  ② EXPLAIN 化验（type/key/rows/Extra 四要素）
  ③ 对照失效场景与成本直觉定根因（day17/18）
  ④ 处方：改索引 / 改写法 / 改业务（优先级从低到高成本）
  ⑤ 前后对比验证（耗时 + 新 EXPLAIN 双证据）

今日 5 个病例总览：
  病例1 深分页     LIMIT 900000,10   → 延迟关联 / 游标法
  病例2 隐式转换   varchar列=数字     → 参数对齐类型
  病例3 函数包裹   UPPER(col)=...    → 函数移到常量侧
  病例4 大量回表   status=5 SELECT * → 覆盖索引 + LIMIT
  病例5 无序排序   ORDER BY 无索引序  → 联合索引吃掉 filesort
诊断哲学：90% 的慢 SQL 是"扫描量失控"——
  优化 = 让树定位更准（索引）+ 让传输更少（覆盖/分页）+ 让排序免费（索引序）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Slow Query Log | 慢查询日志 | 超过 long_query_time 的 SQL 流水 |
| Deep Pagination | 深分页 | LIMIT 大偏移的性能悬崖 |
| Deferred Join | 延迟关联 | 先索引取 id 再回表小批量 |
| Cursor Pagination | 游标分页 | WHERE id>last ORDER BY id LIMIT n |
| Profile | 执行画像 | SHOW PROFILE 看各阶段耗时 |

## 3. 动手实操

### 3.0 开慢日志（今天的数据采集器）

```sql
SET GLOBAL slow_query_log = ON;
SET GLOBAL long_query_time = 0.05;          -- 50ms 就记（学习环境故意灵敏）
SET GLOBAL log_output = 'TABLE';            -- 写到 mysql.slow_log 表，方便查
SELECT SLEEP(0.1);                          -- 造一条慢的验证采集生效
SELECT start_time, query_time, sql_text
FROM mysql.slow_log ORDER BY start_time DESC LIMIT 3;
```

### 3.1 病例 1：深分页（★旧大纲验收点，必精讲）

```sql
-- 症状：运营后台翻到 90 万页时超时
SELECT SQL_NO_CACHE * FROM t_index ORDER BY id LIMIT 900000, 10;
-- 化验：type=ALL? 不，type=index 但 rows≈90万 —— LIMIT 90 万行全读再丢弃
-- 处方 A：延迟关联（子查询先在索引里定位 id，只回表 10 行）
SELECT SQL_NO_CACHE * FROM t_index t
JOIN (SELECT id FROM t_index ORDER BY id LIMIT 900000, 10) tmp ON t.id = tmp.id;
-- 处方 B：游标分页（业务侧记住上一页最后一个 id）—— 最优
SELECT SQL_NO_CACHE * FROM t_index WHERE id > 900000 ORDER BY id LIMIT 10;
-- 前后耗时记录：____ms → ____ms（预期 10 倍以上差距，写进门诊手册）
```

### 3.2 病例 2 & 3：写法杀（隐式转换 / 函数包裹）

```sql
-- 病例2：参数类型不齐（应用层拼接 SQL 的经典事故）
SELECT SQL_NO_CACHE * FROM t_index WHERE name = 42;
--   化验：key=NULL，type=ALL。处方：WHERE name='42'（参数对齐列类型）
--   Java 侧规范：MyBatis #{} 传 String 就按 String，别拼数字

-- 病例3：函数包裹索引列（需求"忽略大小写查询"）
SELECT SQL_NO_CACHE * FROM t_index WHERE UPPER(name) = 'USER-42';
--   处方：改写 WHERE name = 'user-42'（把函数移到常量侧）
--   若业务必须大小写不敏感：8.0 函数索引（见下）或查询列用 ci 排序规则
ALTER TABLE t_index ADD KEY idx_upper_name ((UPPER(name)));   -- 8.0.13+ 函数索引
EXPLAIN SELECT * FROM t_index WHERE UPPER(name) = 'USER-42';  -- 这次走索引！
```

### 3.3 病例 4 & 5：扫描量失控（回表 / 排序）

```sql
-- 病例4：大命中量 + 全列回表
SELECT SQL_NO_CACHE * FROM t_index WHERE status = 5;
--   化验：可能弃用索引全表（day17 场景⑨）或 ref 10 万次回表
--   处方：① 业务问清楚要不要 10 万行——加 LIMIT 100；② 列表页改覆盖
SELECT SQL_NO_CACHE id, status FROM t_index WHERE status = 5 LIMIT 100;
--   Using index，耗时骤降

-- 病例5：ORDER BY 没吃上索引序
SELECT SQL_NO_CACHE * FROM t_index WHERE status = 1 ORDER BY name LIMIT 20;
--   化验：Extra=Using filesort
--   处方：联合索引让排序免费（day18 已建 idx_status_name）
EXPLAIN SELECT * FROM t_index WHERE status = 1 ORDER BY name LIMIT 20;
--   Extra 无 filesort，index_condition + 索引序直接出前 20 行
```

### 3.4 《SQL 优化检查清单》v1（明天复盘升正式版）

```text
写完/收到一条 SQL，30 秒过一遍：
□ SELECT 列是否最小化？（别 * ，覆盖机会）
□ WHERE 列是否"裸奔"？（无函数/运算/隐式转换）
□ 命中行数量级是否可控？（低区分度列单独用 = 危险）
□ 深分页是否用游标/延迟关联？
□ ORDER BY / GROUP BY 是否有索引前缀接住？
□ join 被驱动表列有索引吗？驱动表过滤后够小吗？
□ 上线前 EXPLAIN 过吗？耗时记录了吗？
（week4 学完事务/锁后，本清单会追加"长事务/锁等待"两项）
```

## 4. 面试连接

**Q：讲一个你做过的 SQL 优化案例（必考，今天攒素材）**
> STAR 结构套病例 1：背景（运营后台分页查询 90 万页超时）→ 任务（P95 从 2s 降到 100ms）→ 行动（EXPLAIN 定位 LIMIT 90 万行扫描；改游标分页 WHERE id>last ORDER BY id LIMIT n，索引序直接定位）→ 结果（耗时 1.8s→8ms，日志吞吐下降 95%）。加分句："我还留了延迟关联方案作为通用兜底，两种方案的业务约束讲得清：游标要求排序键唯一有序且不支持跳页。"

**Q：深分页为什么慢？两种方案怎么选？**
> 根因：LIMIT 900000,10 要真读 90 万+10 行再丢前 90 万（回表版还要回 90 万次表）。延迟关联：子查询在【索引】里滑过 90 万（免回表，但仍有 90 万索引行扫描），最后只回表 10 行。游标分页：直接从上次位置开始索引定位，连 90 万滑过都没有——最优，但约束是"只支持连续翻页、要求排序键唯一"。面试答出约束对比就是方案级思维。

**Q：慢日志抓到了几百条慢 SQL，怎么定优先级？**
> 三维排序：① 频率（出现次数 × 平均耗时 = 总伤害，pt-query-digest/慢日志聚合）；② 业务权重（下单链路 > 后台报表）；③ 治理成本（建索引分钟级 > 改写 SQL > 拆表重构）。口诀："先修主干道，再修小巷子。"这个回答体现的是工程取舍而非纯技术。

## 5. 今日验收清单

- [ ] 慢日志开启并验证采集（SLEEP 案例）
- [ ] 5 个病例全程记录：症状/化验/处方/前后耗时
- [ ] 深分页两种方案耗时对比（延迟关联 vs 游标）
- [ ] 函数索引 (UPPER(name)) 实验成功
- [ ] 检查清单 v1 抄入笔记；`git add . && git commit -m "day20: slow-sql clinic 5 cases"`

---
[← Day 19](day19-优化器与Join算法.md) | [本月目录](README.md) | [Day 21 · 第三周复盘输出 →](day21-第三周复盘与SQL清单.md)
