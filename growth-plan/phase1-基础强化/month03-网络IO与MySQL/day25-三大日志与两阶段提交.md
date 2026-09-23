# Day 25 · 三大日志与两阶段提交（crash-safe 的最后一环）

> **今日目标**： month01 埋的"crash-safe"伏笔今天揭晓：redo/undo/binlog 三大日志各司其职、一条 UPDATE 的写盘时序、两阶段提交为什么缺一不可——这是 MySQL 面试的"镇场题"，讲不清它就不算懂 InnoDB。
> **时长**：理论 1.5h / 实操 1.5h / 输出 0.5h
> **今日产出**：三大日志对比表 + 两阶段提交时序图 + crash 场景推演记录

## 1. 知识地图

```
三大日志分工（先记"谁的问题"再记细节）：
  undo log   回滚日志（InnoDB）  解决"改错了能反悔"→ 原子性 + day23 MVCC 版本链
  redo log   重做日志（InnoDB）  解决"宕机了已提交的不丢"→ 持久性（WAL）
  binlog     归档日志（Server 层）解决"复制与恢复"→ 主从/备份回放

redo log 与 binlog 的四大区别（★必背表格）：
  ┌──────────┬─────────────────┬──────────────────┐
  │          │ redo log        │ binlog           │
  ├──────────┼─────────────────┼──────────────────┤
  │ 层级      │ InnoDB 独有      │ Server 层全引擎   │
  │ 物理逻辑  │ 物理日志(页改动)  │ 逻辑日志(SQL/行变)│
  │ 写入方式  │ 循环写(固定大小)  │ 追加写(写满换文件)│
  │ 用途      │ 崩溃恢复         │ 复制/时间点恢复    │
  └──────────┴─────────────────┴──────────────────┘

WAL（Write-Ahead Logging）一句话：
  先顺序写日志（快），再择机刷数据页（慢）——把随机写变顺序写

一条 UPDATE 的完整写盘时序（★镇场图，明天 day28 还会放大）：
  BEGIN
   ① 改 Buffer Pool 里的页（内存，脏页）
   ② 写 undo log（旧值入版本链）
   ③ 写 redo log buffer
  COMMIT 触发两阶段提交（2PC）：
   ④ redo log 写盘并标记【prepare】
   ⑤ 写 binlog（并按 sync_binlog 刷盘）
   ⑥ redo log 打上【commit】标记
  之后：后台线程择机把脏页刷盘（所以"已提交"≠"数据页已落盘"！）

为什么必须两阶段提交（反证法，面试就这么推）：
  先 redo 后 binlog，中间崩 → 恢复后主库有数据，binlog 没有 → 从库丢数据
  先 binlog 后 redo，中间崩 → 恢复后主库没数据，binlog 有 → 从库多数据
  ★2PC 保证两份日志的"原子性"——主从才不会精神分裂

双 1 配置（生产默认，性能与安全的档位）：
  innodb_flush_log_at_trx_commit = 1  每次 commit redo 刷盘
  sync_binlog = 1                     每次 commit binlog 刷盘
  （调成 0/2 可提性能但宕机可能丢事务——金融别碰）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Redo Log | 重做日志 | WAL 物理日志，循环写 |
| Undo Log | 回滚日志 | 旧版本，回滚+MVCC |
| Binlog | 归档日志 | 逻辑日志，复制/恢复 |
| Two-Phase Commit | 两阶段提交 | prepare→binlog→commit |
| Dirty Page | 脏页 | 内存已改未刷盘的页 |
| Crash-Safe | 崩溃安全 | 宕机后已提交数据不丢 |

## 3. 动手实操

### 3.1 三大日志的"现场勘查"

```sql
-- ① redo log 配置与写入进度
SHOW VARIABLES LIKE 'innodb_log_file%';        -- 文件与大小（8.4 支持 innodb_redo_log_capacity）
SHOW VARIABLES LIKE 'innodb_redo_log_capacity';
SHOW ENGINE INNODB STATUS\G
--   找 LOG 段：Log sequence number（当前 LSN）/ Last checkpoint——抄进笔记
--   redo 循环写：LSN 一直涨，checkpoint 追着它跑

-- ② binlog 状态与内容
SHOW VARIABLES LIKE 'log_bin';                 -- 容器里默认开（8.0+ 默认 ON）
SHOW BINARY LOGS;                              -- binlog 文件清单
CREATE TABLE t_log (id INT PRIMARY KEY, v INT);
INSERT INTO t_log VALUES (1, 10);
SHOW BINLOG EVENTS IN '容器里显示的文件名' LIMIT 10;
--   看到 Query:CREATE TABLE / Query:BEGIN / Table_map / Write_rows:INSERT
--   ★ROW 格式下 INSERT 记的是"行变化"不是 SQL 文本
```

### 3.2 用 mysqlbinlog 解剖一条 UPDATE（binlog 内容实证）

```powershell
# 从容器里把 binlog 拷出来看（ROW 格式要 -vv 才能翻译成伪 SQL）
docker exec mysql-learning ls -l /var/lib/mysql/ | Select-String binlog
docker exec mysql-learning mysqlbinlog --no-defaults -vv `
  /var/lib/mysql/binlog.00000N | Select-Object -Last 60
# 找到：### UPDATE learn.t_log / ### WHERE id=1 / ### SET v=20
# 记录：这就是从库要重放的内容——理解"复制=重放 binlog"的实体
```

```sql
-- ③ 制造一条可观察的 UPDATE
UPDATE t_log SET v = 20 WHERE id = 1;
```

### 3.3 crash 场景推演（纸面演练，写成推演记录）

```text
场景 A：commit 完成（redo 已 commit 标记），脏页还没刷盘，此刻断电
  → 重启：检查 redo，发现已 commit → 用 redo 重放 → 数据在 ✓（持久性成立）
场景 B：redo prepare 后、binlog 写前断电
  → 重启：redo 处于 prepare 无 binlog → 回滚 → 事务不存在 ✓（两库一致）
场景 C：binlog 写完、redo commit 标记前断电
  → 重启：redo prepare + binlog 完整 → 查 binlog 有完整事件 → 提交该事务 ✓
  （规则：prepare 状态时"binlog 完整就提交，不完整就回滚"）
结论记录：三种断电推演全部自洽 → 两阶段提交的价值 = 主从一致 + 崩溃不丢
```

### 3.4 双 1 与组提交（生产参数卡）

```sql
SHOW VARIABLES LIKE 'innodb_flush_log_at_trx_commit';   -- 1
SHOW VARIABLES LIKE 'sync_binlog';                       -- 1
-- 档位含义卡：
--   redo: 1=每 commit 刷盘 / 2=刷到 OS 缓存 / 0=每秒
--   binlog: 1=每 commit 刷盘 / N=攒 N 个事务 / 0=交给 OS
-- 性能与丢失窗口对照（面试常问"能调吗"）：
--   双 1 最安全；非金融可 (2,1) 或 (1,100) 换吞吐，宕机丢 1 秒内事务
-- 组提交一句话：并发事务的"刷盘动作"合并成一次 IO——高并发下双 1 也不慢的秘密
```

## 4. 面试连接

**Q：一条 UPDATE 语句在 MySQL 里是怎么执行的？（旧大纲指定博客题，day28 成文）**
> 连接器(鉴权)→分析器(词法语法)→优化器(选计划)→执行器(调引擎)。InnoDB 内部：读页进 Buffer Pool→写 undo→改内存页→写 redo buffer→commit 时两阶段提交（redo prepare→binlog→redo commit）→后台刷脏页。能按这个顺序画图就是一线水平；今天先吃透 InnoDB 内部半截，day28 补全 Server 层半截。

**Q：redo log 和 binlog 的区别？为什么有两份？**
> 先背四行对比表（层级/物理逻辑/写入方式/用途），再答"历史原因"：MySQL 多引擎架构下 binlog 是 Server 层公共件（MyISAM 时代就有），但 MyISAM 没有 crash-safe 能力，InnoDB 自带 redo 补上。两者不能互相替代：redo 循环写会被覆盖没法归档，binlog 逻辑日志重放恢复慢且无 prepare 语义。最后收在两阶段提交——它们是"主从一致"的双保险。

**Q：什么是 WAL？为什么能提升性能？**
> Write-Ahead Logging：变更先顺序写日志再延迟刷数据页。性能来源：磁盘（尤其机械盘/云盘）随机写贵、顺序写便宜；日志小而顺序，数据页大而随机——把昂贵的随机页写摊薄到后台异步完成。代价：崩溃恢复要重放 redo（秒级）。延伸：LSN、checkpoint 追赶、innodb_io_capacity 控制刷脏节奏（day27 参数）。

## 5. 今日验收清单

- [ ] redo/binlog 现场勘查记录（LSN、BINLOG EVENTS 截图）
- [ ] mysqlbinlog -vv 看到 UPDATE 的行级内容
- [ ] 三种断电场景推演写完并理解
- [ ] 双 1 参数卡 + 丢失窗口能讲
- [ ] `git add . && git commit -m "day25: 3 logs & 2pc"`

---
[← Day 24](day24-锁体系与加锁规则.md) | [本月目录](README.md) | [Day 26 · 主从复制与高可用 →](day26-主从复制与高可用.md)
