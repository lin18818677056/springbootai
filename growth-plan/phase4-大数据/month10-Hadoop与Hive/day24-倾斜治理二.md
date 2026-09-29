# Day 24 · 倾斜治理（二）：加盐打散与 SQL 改写

> **今日目标**：掌握倾斜治理后两件武器——**加盐两阶段聚合（给热点 key 加随机前缀拆成 N 份算，最后合并）**和**倾斜 key 单独处理（热点拆出来特殊照顾，普通 key 正常跑）**；完成 day22 复现的三种倾斜场景的完整治理闭环（前后耗时对比全出数字）。
> **时长**：加盐两阶段 1.5h / 热点拆分 1h / 全场景治理闭环 2h
> **今日产出**：四种武器全量对比表 + 《倾斜治理手册》（面试直接讲的完整材料）

## 1. 知识地图

```
武器三：加盐两阶段聚合（Salting）——"王字组拆成王一/王二/王三组，最后并成绩"
针对：热点 key 聚合倾斜（TOP1 商家独扛 90% 数据的 GROUP BY）
原理（两阶段，先拆后合）：
第一阶段：给热点 key 加随机前缀（0~N），分组键变成 (uid, salt)——
  uid=888 被拆成 888_0/888_1/.../888_9 十份，hash 后散到 10 个 Reduce 并行算
第二阶段：把前缀去掉再聚一次（此时每份只有 1/N 数据，合并很轻）
-- 治理前（热点独扛）：
SELECT uid, SUM(amount) FROM skew_orders GROUP BY uid;
-- 治理后（两阶段加盐）：
SELECT SPLIT(k,'_')[0] AS uid, SUM(amt) FROM (          -- 第二阶段：去盐合并
  SELECT CONCAT(CAST(uid AS STRING),'_', CAST(oid % 10 AS STRING)) k,
         amount amt
  FROM skew_orders                                       -- 第一阶段：加盐
) t GROUP BY SPLIT(k,'_')[0];
加盐数 N 怎么定：让热点 key 拆分后的单份量 ≈ 普通 key 的量
  （TOP1 占 40%、普通 key 占 0.01% → N 取几十到几百；N 太小拆不动，太大第二阶段轻了但第一阶段任务数膨胀）
权衡：两条 SQL 的代价（多一次作业）换长尾消除——离线批处理值得，
  但交互式查询要掂量（这也是 Presto/ClickHouse 存在的理由之一）

武器四：热点 key 单独处理（Filter 拆分 UNION）——"特殊学生单独辅导，其他人正常上课"
针对：Join 的热点键（某爆款 sku 的百万行点击 Join 订单）
思路：把热点 key 从数据里拆出来走特殊路径，普通 key 走正常路径，结果 UNION：
-- 热点 key 列表（一般是固定几个，配置化）：
WITH hot AS (SELECT 888 AS uid)                            -- 热点名单（可配置化）
-- 路径一：热点 key 走 Map Join（小结果集广播）
SELECT a.page, b.name FROM
  (SELECT * FROM skew_logs WHERE uid = 888) a             -- 只取热点行
  LEFT JOIN (SELECT * FROM users_t WHERE uid = 888) b ON a.uid = b.uid
UNION ALL
-- 路径二：非热点 key 走正常 Join（此时分布均匀，不倾斜）
SELECT a.page, b.name FROM
  (SELECT * FROM skew_logs WHERE uid <> 888 OR uid IS NULL) a
  LEFT JOIN users_t b ON a.uid = b.uid;
适用判断：热点 key 少而固定（TOP10 商家/爆款 sku 名单）——名单可配置；
  热点 key 多而漂移（每天不同）→ 用加盐或动态收集热点（先跑一遍 TOP-N）
两种武器的选择口诀：热点固定→拆分 UNION（省一次全量作业）；
  热点漂移或热点多→加盐两阶段（通用但多一阶段）

《倾斜治理手册》总装（四种武器一张图，面试直接讲）：
| 根因 | 武器 | 关键动作 | 代价 |
|------|------|---------|------|
| Join 一边小 | Map Join | 广播/统计信息/hint | 内存 |
| 两边大可分桶 | Bucket/SMB Join | 建表分桶（成倍数） | 建表约束 |
| 空值堆积 | 打散/COALESCE | 随机后缀（Join）/统一组（聚合） | 语义注意 |
| 热点聚合 | 加盐两阶段 | (key,salt) 先拆后合 | 多一次作业 |
| Join 热点固定 | Filter 拆分 UNION | 热点走广播+普通正常+UNION | 名单维护 |
| Count Distinct | 两阶段改写 | GROUP 再 COUNT | 无 |
—— 万能兜底：DWS 预聚合（让参与 Join/聚合的数据先变小）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Salting | 加盐（key 加随机前缀拆散热点） |
| Two-phase Aggregation | 两阶段聚合（先加盐分散算，再去盐合并） |
| Filter Split + UNION | 热点拆分（特殊 key 单独走、普通 key 正常走） |
| Hot Key List | 热点名单（可配置的热点 key 集合） |
| 兜底预聚合 | DWS 层先变小再算（一切倾斜的终极解） |

## 3. 动手实操：全场景治理闭环

```sql
-- ===== 实验③：加盐两阶段治热点聚合（day22 场景一）=====
-- 治理前基线（记录长尾 Reduce 耗时）：
SELECT uid, SUM(amount) FROM shop.skew_orders GROUP BY uid;
-- 治理后：两阶段加盐（拆 10 份）
SELECT SPLIT(k, '_')[0] AS uid, SUM(amt) AS total FROM (
  SELECT CONCAT(CAST(uid AS STRING), '_', CAST(oid % 10 AS STRING)) k, amount amt
  FROM shop.skew_orders
) t GROUP BY SPLIT(k, '_')[0];
-- 对比记录：长尾 Reduce 从独扛 90% → 10 个 Reduce 各扛 9%；总耗时（多一个 Stage）换长尾消除

-- ===== 实验④：热点拆分 UNION（uid=888 特殊处理）=====
SELECT a.uid, COUNT(*) FROM (
  SELECT * FROM shop.skew_orders WHERE uid = 888
) a GROUP BY a.uid
UNION ALL
SELECT uid, COUNT(*) FROM (
  SELECT * FROM shop.skew_orders WHERE uid IS NULL OR uid <> 888
) b GROUP BY b.uid;
-- 观察：热点行只在一个轻量任务里处理（行数少），其余 key 均匀分布

-- ===== 实验⑤：COUNT DISTINCT 两阶段（day22 预告的完整版）=====
SELECT SUM(c) AS distinct_uid FROM (
  SELECT uid, COUNT(*) c FROM shop.skew_orders GROUP BY uid
) t;
-- 与 SELECT COUNT(DISTINCT uid) 对比 EXPLAIN：Stage 数 1→2，但单 Reduce 压力摊开

-- 治理效果汇总表（填实测数字）：
-- 场景一热点聚合：加盐前后 长尾 task 40min→3min（演示数据比例，真实集群差距更夸张）
-- 场景二空值：COALESCE 后无 NULL Reduce 独扛
-- 场景三 DISTINCT：两阶段后长尾消除
```

## 4. 面试连接

**Q：热点 key 的聚合倾斜，用加盐怎么做？加盐数怎么定？（治理核心题）**
> 加盐两阶段的原理用分组打比方：全班 60% 姓王，按姓氏分组打扫就"王字组"累死——第一阶段给每个 key 加随机后缀（王→王一、王二……王十），原来的一个组被拆成十个组，hash 后散到不同 Reduce 并行算；第二阶段把后缀去掉再合并一次，此时每个"王 X"只剩十分之一的数据，合并很轻。SQL 形状：内层 CONCAT(uid, '_', oid%10) 构造新键，外层 SPLIT 去盐后再 GROUP BY 求和。加盐数 N 的定法：让拆分后的单份热点量约等于普通 key 的量——TOP1 占 40% 就拆到几十份，N 太小拆不动、N 太大第一阶段任务数膨胀且第二阶段合并变多。代价是两次作业（多一个 Stage），离线批处理完全值得。最后一个加分点：如果热点 key 固定且少（比如固定的几个大商家），用 Filter 拆分 UNION 更省——热点行单独走一个轻量路径，普通 key 正常跑，不用多一次全量作业；热点漂移就老实加盐。这两者的选择标准是"热点名单是否稳定可配置"。

**Q：治倾斜的方法你能按优先级排一下吗？什么情况下你什么都不做？（工程判断题）**
> 我的优先级是五层。第一层先问"要不要治"：任务半夜跑、晚十分钟没人感知、不阻塞下游——那就什么都不做，加个注释"已知倾斜，T+1 报表可容忍"，治理也是要花人力和算力成本的，这是最重要也最容易被忽略的判断。第二层是免费方案：开 Map Join 自动转换（统计信息更新）、Count Distinct 改两阶段写法——配置和写法层面的零成本优化，能用就用。第三层是结构性方案：空值 COALESCE/打散、固定热点拆 UNION——改写 SQL 但语义等价，一次改长期受益。第四层是重武器：加盐两阶段、Bucket Join——需要多阶段或建表约束，用在真扛不住的长尾上。第五层是兜底重构：回到建模层解决——DWS 预聚合让 Join 两边变小，或者分桶重建设计——前面全失效时才动。判断"值得治"的量化标准：长尾让任务 SLA 逼近调度基线（比如 03:30 就绪红线）或阻塞下游依赖链时，必须治；平时记录在案就行。这套优先级和 M8 处理线上性能问题的思路一致：先量化影响面，再按成本递增的顺序上手段——工程师的克制比技术堆砌更值钱。

## 5. 今日验收清单

- [ ] 加盐两阶段原理（拆组比喻）+SQL 形状能白板写
- [ ] 加盐数 N 的定法+两种武器（加盐 vs 拆分 UNION）选择口诀能讲
- [ ] 三个治理实验全部完成（前后对比有数字）
- [ ] 《倾斜治理手册》总装表成文（四武器+万能兜底）
- [ ] "要不要治"的优先级判断能展开（工程克制）
- [ ] `git add . && git commit -m "day10-24: skew fix2"`

---
[← Day 23](day23-倾斜治理一.md) | [本月目录](README.md) | [Day 25 · 数仓项目一 →](day25-数仓项目一.md)
