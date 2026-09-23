# Day 27 · MySQL 参数与容量评估（把数据库当系统来管）

> **今日目标**：从"会写 SQL"升级到"会管数据库"：Buffer Pool 的内部机制（改良 LRU/脏页/命中率）、五个必须会调的参数、一套容量评估方法论（内存/磁盘/连接/QPS）——面试"线上 MySQL 怎么配置"题的系统化答案。
> **时长**：理论 1.5h / 实操 1.5h / 输出 0.5h
> **今日产出**：参数调优卡 + Buffer Pool 命中率实测 + 容量评估表（以 1000 万行订单表为例）

## 1. 知识地图

```
Buffer Pool 内部（内存中的页仓库，DB 性能的心脏）：
  ┌────────────────────────────────────────┐
  │ Buffer Pool（默认 128MB，生产要改！）     │
  │  ├ 数据页/索引页/undo 页/change buffer  │
  │  └ 改良版 LRU 链表：                    │
  │     young 区(热,5/8) ←→ old 区(冷,3/8)  │
  │     ↑ 新页先进 old 头，停留超 1s 再访问   │
  │       才晋升 young —— 防全表扫污染热数据 │
  └────────────────────────────────────────┘
  脏页：内存改过未刷盘的页 → checkpoint 推动 flush
  命中率：(1 - Innodb_buffer_pool_reads/Read_requests) × 100%
  生产标准：>99%；掉到 95% 以下 = 内存不够，先查 BP

五大必会参数（★背表）：
  innodb_buffer_pool_size    物理内存 50%~75%（专用 DB 机）
  max_connections            按峰值×2；配应用连接池（month02 手写连接池对号）
  innodb_io_capacity         磁盘 IOPS 水位（SSD 2000+，控制刷脏节奏）
  innodb_flush_log_at_trx_commit + sync_binlog  双 1（day25）
  long_query_time            慢 SQL 阈值（1s 太粗，建议 0.1~0.5s）

容量评估四步法（以"1000 万行订单表"为例）：
  ① 行宽估算：订单行约 500B → 数据 ≈ 5GB（含页填充率放大 1.2 = 6GB）
  ② 索引空间：3 个二级索引 ≈ 数据的 30%~50% ≈ 2~3GB
  ③ 日志预算：redo 1~2GB + binlog 按写入速率×保留天数
     （1000 TPS × 500B × 86400s ≈ 43GB/天 → 保留 3 天 ≈ 130GB！）
  ④ 总量 = (数据+索引)×2(预留增长) + 日志 + 20% 安全水位
  连接与内存账：每连接约 256KB~1MB → 2000 连接 ≈ 0.5~2GB（别超 BP 预算）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Buffer Pool | 缓冲池 | 页的内存仓库，性能心脏 |
| Young/Old Area | 热区/冷区 | 改良 LRU 防扫描污染 |
| Dirty Ratio | 脏页比例 | 过高会拖慢写（刷不过来） |
| Checkpoint | 检查点 | redo 推进的刷盘水位 |
| Capacity Planning | 容量评估 | 内存/磁盘/连接/QPS 四账 |
| Baseline | 基线 | 参数调优前的对照数据 |

## 3. 动手实操

### 3.1 Buffer Pool 体检（用真实数据算命中率）

```sql
-- ① 现状配置
SELECT @@innodb_buffer_pool_size/1024/1024 AS bp_mb;   -- 容器默认 128MB
-- ② 命中率三连查（跑几条查询后再看，对比冷启动）
SHOW GLOBAL STATUS LIKE 'Innodb_buffer_pool_read%';
--   Innodb_buffer_pool_read_requests = 逻辑读(命中+未命中)
--   Innodb_buffer_pool_reads        = 真去磁盘读的次数
SELECT ROUND((1 - (
  (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_reads') /
  (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_read_requests')
)) * 100, 3) AS hit_rate_pct;
-- ③ 用 day15 的 100 万行表制造压力再算一次：
--   SELECT COUNT(*) FROM learn.t_index;   -- 128MB BP 装不下全部 → 观察命中率变化
```

### 3.2 脏页与刷脏观察

```sql
SHOW GLOBAL STATUS LIKE 'Innodb_buffer_pool_pages_%';
--   关注 pages_dirty / pages_total → 脏页比例
--   经验阈值：脏页比例 > 75% 时 InnoDB 会激进刷脏（写抖动元凶）
-- 跑一批 UPDATE 制造脏页再观察：
--   UPDATE learn.t_index SET status = status % 10 WHERE id BETWEEN 1 AND 50000;
SHOW GLOBAL STATUS LIKE 'Innodb_buffer_pool_pages_dirty';
```

### 3.3 参数实验：改 BP 大小（容器里动态生效）

```sql
SET GLOBAL innodb_buffer_pool_size = 536870912;   -- 512MB（8.0+ 在线改，分块迁移）
SELECT @@innodb_buffer_pool_size/1024/1024;        -- 确认
-- 重跑 §3.1 的 COUNT + 命中率 → 记录前后变化（大 BP 装下更多页）
-- 注意：生产改 BP 要评估内存账：BP = 总内存 - 其他进程 - 连接内存 - OS 缓存
```

### 3.4 容量评估表：1000 万行订单库（填空式输出）

```text
| 项目              | 估算式                          | 结果   |
|-------------------|--------------------------------|--------|
| 数据空间          | 1000万×500B×1.2(填充率)         | ≈6GB   |
| 索引空间(3 个)     | 数据×40%                        | ≈2.4GB |
| redo log          | 固定配置                        | 2GB    |
| binlog(3 天)      | 1000TPS×500B×86400×3            | ≈130GB |
| 备份(全量+日志)    | (数据+索引)×1.5                 | ≈13GB  |
| 总磁盘            | (6+2.4)×2(增长)+130+13 +20%     | ≈180GB |
| 内存账            | BP 8GB(16G 机器 50%)            | 8GB    |
| 连接账            | max_conn 2000×500KB             | ≈1GB   |
| QPS 预估          | 单实例读 5 万+（BP 命中 99%）    | —      |
结论行：16C16G+SSD 200GB 单实例可承载 → 增长到 5000 万行再谈分库分表（month08）
```

## 4. 面试连接

**Q：线上 MySQL 你会重点配置哪些参数？**
> 分层答：内存（BP 50%~75%+命中率监控）、磁盘（io_capacity 对齐 SSD 档位）、安全（双 1，金融不放松）、连接（max_connections×应用连接池上限公式：min(max_conn, 实例数×池大小) < max_conn）、观测（long_query_time 0.1s+慢日志平台）。加分句："我所有调优先打基线再动参数，动一个测一组——参数没有万能值，只有与负载匹配值。"这句直接区分背参数党和懂调优党。

**Q：Buffer Pool 的 LRU 为什么要改造？**
> 原生 LRU 的两个问题：① 全表扫把热数据全冲走（扫描污染）——一次报表查询打垮缓存；② 预读页可能一次没用。InnoDB 改法：新页进 old 头，old 区停留超 innodb_old_blocks_time(默认 1s) 且再次被访问才晋升 young——一次性扫描只污染 old 区。加分句："我用 100 万行的 COUNT 演过：大表扫描后命中率短暂下降又恢复，说明污染被 old 区兜住了。"

**Q：一张表 1000 万行要多少磁盘？数据库服务器怎么选型？**
> 展示评估四步法（§1）：行宽×行数×填充率 → 索引比例 → 日志按速率×保留期 → 总量×增长系数×安全水位。选型：CPU(核多不如稳)/内存(BP 优先)/磁盘(IOPS 与延迟>容量)/网络。最后给台阶："评估不是精确科学，是留出安全边际的工程估算——容量台账要随增长复审，否则磁盘打满就是一次 P2 事故。"

## 5. 今日验收清单

- [ ] 命中率 SQL 跑通并记录（冷/热对比）
- [ ] 脏页观察 + 5 万行 UPDATE 前后变化
- [ ] BP 512MB 在线调整实验记录
- [ ] 容量评估表填完（数字自己算一遍）
- [ ] `git add . && git commit -m "day27: params & capacity planning"`

---
[← Day 26](day26-主从复制与高可用.md) | [本月目录](README.md) | [Day 28 · 一条 UPDATE 的完整旅程 →](day28-一条UPDATE的完整旅程.md)
