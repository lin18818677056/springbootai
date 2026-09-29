# Day 20 · Hive 调优：让任务跑得更快的 6 个旋钮

> **今日目标**：掌握 Hive 最常用的 6 个调优手段（Fetch 抓取/本地模式/并行执行/JVM 重用/推测执行/Map Join 自动转换）——一句话：**每个旋钮都是在"省启动开销、省 Shuffle、省磁盘"三个方向上拧**。今天给已有任务配置调优参数并做前后耗时对比。
> **时长**：原理 1.5h / 调参实验 2.5h
> **今日产出**：《Hive 调优参数卡》（6 旋钮+何时拧）+ 调参前后对比数字

## 1. 知识地图

```
调优先建立"钱的去向"模型：一个 Hive 查询的时间花在四件事上——
  ①启动开销（起 JVM/AM）②读数据 ③Shuffle（网络+落盘）④计算本身。
  六个旋钮分别对应省钱方向：

旋钮①：Fetch 抓取（最简单，先拧它）
  现象：SELECT * FROM t LIMIT 10 这种小查询也起一个 MR 作业（分钟级）
  原理：简单查询不走 MR，Hive 直接读文件返回（像 MySQL 客户端查一下）
  配置：hive.fetch.task.conversion=more（默认已开，老集群要手动）
  什么时候有效：无聚合/无排序/limit 的查询——检查工作里 80% 的"查一下"瞬间变秒回

旋钮②：本地模式（小任务别惊动集群）
  现象：处理 100MB 数据的查询也走 YARN 申请资源（排队 30 秒，跑 3 秒）
  原理：数据量小+Map 数少时，在提交机本地单进程跑完
  配置：hive.exec.mode.local.auto=true + 输入上限 128MB + Map 数上限 4
  什么时候有效：测试环境验证 SQL、小分区维护任务——省的全是排队和启动时间

旋钮③：并行执行（不相干的 Stage 同时跑）
  现象：SQL 被拆成多个 Stage（如两段无依赖的子查询），默认串行排队
  原理：无依赖的 Stage 并行提交
  配置：hive.exec.parallel=true + hive.exec.parallel.thread.number=8
  什么时候有效：执行计划里有多个独立 Stage 的复杂 SQL（EXPLAIN 看 Stage 图）

旋钮④：JVM 重用（治"任务太碎"）
  现象：一万个 Map 任务，每个起一个 JVM（每个启动 3 秒）→ 光启动 8 小时
  原理：一个 JVM 容器连续跑多个任务再释放（mapreduce.job.reuse.jvm.num.tasks）
  什么时候有效：小文件多/任务碎的场景（配合 day04 小文件治理食用）
  副作用：占着容器直到跑完配额，资源弹性下降——有其他任务抢资源时慎开

旋钮⑤：推测执行（治"长尾任务"）
  现象：99% 的任务完成了，剩 1 个因为那台机器磁盘坏了跑 40 分钟——整条链路等它
  原理：检测到拖后腿的任务，另起一份备份同时跑，谁先完成用谁
  配置：mapreduce.map.speculative=true / reduce.speculative=true（MR 默认开）
  权衡：用双份资源买尾延迟——和 M8 容量冗余同思想（资源换稳定）

旋钮⑥：Map Join 自动转换（治"大表 Join 小表"最狠的一招）
  现象：1 亿行订单 Join 1 万行商品表，走 Shuffle（day22 讲倾斜的主力场景）
  原理：小表装进内存广播到每个 Map 端，Join 在本地完成——零 Shuffle
  配置：hive.auto.convert.join=true（默认开）+ hive.mapjoin.smalltable.filesize（默认 25MB 上限）
  前置条件：判断"够小"基于统计信息——ANALYZE TABLE ... COMPUTE STATISTICS 要跑

调优方法论（比背参数重要）：
  顺序：先看执行计划（EXPLAIN）定位 Stage 瓶颈 → 再选旋钮 → 改一个测一个
  纪律：一次只拧一个旋钮并记录前后数字（不然不知道谁起的作用——M8 压测纪律）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Fetch Task | 抓取模式（简单查询不起 MR，直接读） |
| Local Mode | 本地模式（小任务单机跑，不惊动集群） |
| Parallel Execution | 并行执行（无依赖 Stage 同时跑） |
| JVM Reuse | JVM 重用（一个容器连续干多个活） |
| Speculative Execution | 推测执行（备份任务赛跑治长尾） |
| Map Join | 广播 Join（小表进内存，本地完成 Join） |
| EXPLAIN | 执行计划（调优前必看的"施工图"） |

## 3. 动手实操：调参前后对比实验

```sql
-- 实验①：Fetch 抓取（对比效果）
SET hive.fetch.task.conversion=minimal;   -- 老行为
SELECT * FROM shop.orders_pt LIMIT 10;    -- 观察是否起 MR（日志有 Stage）
SET hive.fetch.task.conversion=more;      -- 开启 Fetch
SELECT * FROM shop.orders_pt LIMIT 10;    -- 秒回，无 MR 作业
-- 对照记录：分钟级（起作业）→ 毫秒级（直接读）

-- 实验②：本地模式（对小表聚合对比）
SET hive.exec.mode.local.auto=false;
SELECT category, COUNT(*) FROM shop.f_orc GROUP BY category;   -- 走 YARN，记耗时
SET hive.exec.mode.local.auto=true;
SET hive.exec.mode.local.auto.inputbytes.max=134217728;
SELECT category, COUNT(*) FROM shop.f_orc GROUP BY category;   -- 本地跑，记耗时
-- 5 万行小数据下：YARN 模式 ~20s（排队+启动）→ 本地 ~3s

-- 实验③：Map Join 自动转换验证（EXPLAIN 证据）
ANALYZE TABLE shop.orders_pt COMPUTE STATISTICS;   -- 统计信息是自动转换的前提
EXPLAIN SELECT o.oid, d.category FROM shop.orders_pt o
  JOIN (SELECT DISTINCT category FROM shop.big_src LIMIT 3) d
  ON o.category = d.category;
-- 计划里出现 Map Join Operator（而非 Common Join）即自动转换生效

-- 实验④：推测执行观察（YARN UI 看 Map 任务出现 attempt 2 副本——故意难造，读日志即可）
-- docker exec hadoop-single bash -c "grep -i 'speculative' /opt/hadoop/logs/*job*.log | tail"
```

```text
《Hive 调优参数卡》骨架（填完实测数字后归 docs/hive/tuning-card.md）：
| 旋钮 | 配置 | 适用场景 | 我的实测（前→后） |
|------|------|---------|------------------|
| Fetch | fetch.task.conversion=more | LIMIT/无聚合查询 | 20s→0.3s |
| 本地模式 | exec.mode.local.auto=true | <128MB 小任务 | 20s→3s |
| 并行执行 | exec.parallel=true | 多独立 Stage | 视计划 |
| JVM 重用 | reuse.jvm.num.tasks=10 | 任务碎/小文件多 | 视任务数 |
| 推测执行 | map.speculative=true | 长尾任务 | 尾延迟↓ |
| Map Join | auto.convert.join=true | 大表 Join 小表 | Shuffle=0 |
```

## 4. 面试连接

**Q：一个 Hive 查询跑得很慢，你怎么优化？（开放题，考方法论）**
> 我的套路是"先诊断再拧旋钮，一次一个记数字"。诊断用 EXPLAIN 看执行计划：有几个 Stage、有没有冗余扫描、Join 走的是 Common 还是 Map Join、数据倾斜的迹象（后面 day22 专讲）。然后按"钱的去向"选旋钮：时间花在启动上——小查询确认 Fetch 抓取开启、小任务开本地模式（实测一个 5 万行的聚合从 20 秒降到 3 秒，省的全是排队和 JVM 启动）；时间花在 Shuffle 上——大表 Join 小表确认 Map Join 自动转换生效，前提是统计信息要新（ANALYZE TABLE 要定期跑）；任务碎——JVM 重用加小文件治理双管齐下；长尾——确认推测执行开着。三条纪律：一次只改一个参数并记录前后数字（否则归因不清）；改参数前先确认它是问题相关的（不是把 30 个参数全抄一遍）；调优的天花板是模型和分区——SQL 写得歪（比如没带分区条件、大表 Join 没预聚合）时，参数救不了，回到 DWS 预聚合才是数量级改进。这个"诊断→定向→单变量→记录"的流程和 M8 的压测瓶颈定位三板斧是同一套方法论。

**Q：Map Join 和 Common（Reduce）Join 的区别？什么时候 Map Join 失效？（Join 必考）**
> 原理一句话：Common Join 走 Shuffle——两边按 Join 键重新分发到同一批 Reduce 再配对；Map Join 是小表整个广播进每个 Map 端的内存，大表的每行在本地直接查小表配对——Shuffle 完全消失，速度能快几倍到几十倍。失效的三种情况：小表不够小——超过 hive.mapjoin.smalltable.filesize（默认 25MB）就回退 Common Join，所以统计信息过期导致"以为小实际大"是常见翻车点，ANALYZE TABLE 的定时任务不能省；关联键基数问题——小表也要有内存装得下的键集合，极端长字符串键会撑爆 Map 端内存；Full Outer Join 等少数类型广播语义不适配。如果两张表都大（都装不进内存）且倾斜，就走 bucket 分桶表对分桶表（Bucket Map Join/SMB Join，day23 倾斜治理讲），或者干脆在 DWS 层把两边预聚合再 Join——数仓的终极答案永远是"让 Join 的两边变小"。

## 5. 今日验收清单

- [ ] "钱的去向"四开销模型+六旋钮对应关系能讲
- [ ] Fetch/本地模式/Map Join 三个实验完成（前后数字进参数卡）
- [ ] EXPLAIN 定位 Stage 瓶颈的操作流程能演示
- [ ] Map Join 失效三情况+终极答案"让两边变小"能展开
- [ ] `git add . && git commit -m "day10-20: tuning"`

---
[← Day 19](day19-存储格式与压缩.md) | [本月目录](README.md) | [Day 21 · 第三周复盘 →](day21-第三周复盘.md)
