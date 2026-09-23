# Day 24 · 锁体系与加锁规则（记录锁/间隙锁/临键锁）

> **今日目标**：MVCC 管"读不堵"，锁管"写安全"。今天建立完整锁地图：全局/表/行三级，行锁三兄弟（记录/间隙/临键），并亲手复现两个经典事故——"等锁超时"和"死锁"，以及间隙锁在什么条件下触发。
> **时长**：理论 1.5h / 实操 2h / 输出 0.5h
> **今日产出**：锁分类地图 + 两个并发事故复现记录 + 加锁规则速查表

## 1. 知识地图

```
锁的分层地图（从粗到细）：
全局锁  FTWRL（Flush Tables With Read Lock）
        → 全库只读：逻辑备份的历史手段（现在 mysqldump --single-transaction）
表级锁  表锁（读锁共享/写锁独占）—— MyISAM 主场，InnoDB 少用
        元数据锁 MDL：访问表自动加（CRUD→MDL读；DDL→MDL写）
        ★事故：长事务占 MDL 读锁 → DDL 排队 → 后续所有 CRUD 全排队！
        意向锁 IS/IX：表级"路标"，表意"表里某行加了行锁"（不用管，自动）
行级锁  （InnoDB 主场，加在【索引记录】上！）
  ① Record Lock 记录锁：锁单条索引记录（S/X 两种）
  ② Gap Lock 间隙锁：锁开区间 (a,b)——锁的是"缝隙"，防 INSERT（防幻读）
  ③ Next-Key Lock 临键锁：记录+前缝隙 [a,b) —— RR 默认加锁单位
  ④ Insert Intention 插入意向锁：INSERT 前的排队号（被 gap 挡住时）

加锁规则速查（RR 下当前读，★必背简化版）：
  唯一索引等值命中      → 记录锁（1 条）
  唯一索引等值未命中    → 命中位置附近的间隙锁
  唯一索引范围          → 范围内的临键锁 + 右端点处理（8.0 优化过）
  普通索引等值          → 临键锁 + 下一处间隙（防重复值插入）
  无索引当前读          → 全表所有行+所有间隙！≈锁全表（★事故之源）
  RC 级别              → 只有记录锁，无间隙锁（死锁少的根源）

死锁四板斧（今天的实验 2）：
  互相等待 + 不可剥夺 → InnoDB 自动检测，回滚代价小的一方
  参数：innodb_deadlock_detect=ON（默认）；SHOW ENGINE INNODB STATUS 看死锁日志
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Record Lock | 记录锁 | 锁单条索引记录 |
| Gap Lock | 间隙锁 | 锁开区间防插入（防幻读） |
| Next-Key Lock | 临键锁 | 记录+左开区间，RR 默认 |
| Intention Lock | 意向锁 | 表级路标（自动） |
| Deadlock | 死锁 | 循环等待，InnoDB 自动回滚一方 |
| Lock Wait Timeout | 锁等待超时 | innodb_lock_wait_timeout 默认 50s |

## 3. 动手实操

### 3.1 实验台重建 + 锁观察窗

```powershell
# 终端 A、B 各开一个会话（day22 同款）
docker exec -it mysql-learning mysql -uroot -proot123 learn
docker exec -it mysql-learning mysql -uroot -proot123 learn
```

```sql
-- 两边执行
SET autocommit = 0;
DROP TABLE IF EXISTS t_lock;
CREATE TABLE t_lock (id INT PRIMARY KEY, v INT) ENGINE=InnoDB;
INSERT INTO t_lock VALUES (1,10),(5,50),(10,100),(15,150);
-- 第三会话（或新窗口 C）作为"锁观察窗"：
SELECT ENGINE_TRANSACTION_ID, INDEX_NAME, LOCK_TYPE, LOCK_MODE,
       LOCK_DATA, LOCK_STATUS
FROM performance_schema.data_locks;        -- 8.0 的锁观察表（替代 5.7 的 innodb_locks）
-- 现在应该为空——没有人在持锁
```

### 3.2 实验 1：等锁与超时（一行引发的排队）

```sql
-- A: BEGIN; UPDATE t_lock SET v=v+1 WHERE id=5;        -- X 记录锁在 id=5
-- C 窗口查询 data_locks → 看到 A 的 X 锁（LOCK_DATA=5）★截图
-- B: BEGIN; UPDATE t_lock SET v=v+1 WHERE id=5;        -- B 排队……
-- C: SELECT * FROM performance_schema.data_lock_waits; -- 看到阻塞关系
-- A: ROLLBACK;                                         -- 释放 → B 立刻通过
-- 变式：A 不提交一直挂 → B 等 50 秒报 ERROR 1205 (lock wait timeout)
--   （演示可调小：SET innodb_lock_wait_timeout=5; 再试 5 秒超时）
```

### 3.3 实验 2：死锁复现（互相握住对方想要的）

```sql
-- 全新表且两边都先锁到"对方的下一站"
-- A: BEGIN; UPDATE t_lock SET v=v+1 WHERE id=1;    -- A 持 id=1
-- B: BEGIN; UPDATE t_lock SET v=v+1 WHERE id=10;   -- B 持 id=10
-- A: UPDATE t_lock SET v=v+1 WHERE id=10;          -- A 等 B……
-- B: UPDATE t_lock SET v=v+1 WHERE id=1;           -- B 等 A → 死锁！
-- 现象：B 的语句立刻报 ERROR 1213 (Deadlock found)，A 的事务还在
--   （InnoDB 回滚了代价小的 B，保留 A —— A 再 ROLLBACK 清场）
-- 查死锁现场（面试必提）：
SHOW ENGINE INNODB STATUS\G
--   找 LATEST DETECTED DEADLOCK 段：两个事务各持什么、各等什么
```

### 3.4 实验 3：间隙锁现场（RR 下未命中等值的后果）

```sql
-- A: BEGIN; SELECT * FROM t_lock WHERE id = 7 FOR UPDATE;   -- id=7 不存在！
-- C 窗口：SELECT ... FROM data_locks;
--   → LOCK_TYPE=RECORD, LOCK_MODE=X,GAP, LOCK_DATA=10
--   ★锁住了 (5,10) 这条缝——不是任何一行，是"7 应该在的位置"
-- B: BEGIN; INSERT INTO t_lock VALUES (7, 70);   -- 卡住！缝被 A 锁了
-- A: COMMIT;                                      -- B 立刻插入成功
-- 对照实验：两边都切 RC（SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED）
--   重做上面 → B 的 INSERT 直接成功——RC 无间隙锁的实证！
-- 危险对照（无索引当前读 = 锁全表）：
--   CREATE TABLE t_noidx (v INT); INSERT ...; 在其上 A: SELECT * FROM t_noidx WHERE v=5 FOR UPDATE;
--   C 看 data_locks：一堆记录锁+间隙锁 ≈ 全表被锁 —— 无索引当前读事故原理
```

### 3.5 加锁速查表（抄笔记）

```text
| 场景（RR 当前读）        | 实际加锁                  | 记忆点 |
|--------------------------|--------------------------|--------|
| 唯一索引等值命中          | 记录锁 ×1                | 最小代价 |
| 唯一索引等值未命中        | 间隙锁(命中位附近)        | 防"补位"插入 |
| 普通索引等值              | 临键锁+下一个间隙         | 防重复值 |
| 范围查询                  | 范围临键锁(8.0右端优化)   | 区间安全 |
| 无索引条件                | 全表行+全表缝             | 事故之源 |
| RC 隔离级别               | 只有记录锁               | 死锁少 |
```

## 4. 面试连接

**Q：InnoDB 有哪些锁？RR 下一条 UPDATE 加什么锁？**
> 分层答：全局/表(MDL/意向)/行(记录/间隙/临键/插入意向)。RR 下 UPDATE 走当前读：唯一键等值命中=记录锁；普通索引=临键锁+下缝；无索引=锁全表（事故之源）。补一句"锁加在索引记录上，二级索引也要加锁"，再带出 RC 无间隙锁所以死锁少——立刻显出体系感。

**Q：生产遇到死锁怎么处理？怎么预防？**
> 应急：ERROR 1213 自动回滚一方，应用重试即可（死锁不是 bug 是并发常态）；SHOW ENGINE INNODB STATUS 看最后死锁现场，定位两条 SQL。预防四招：① 固定加锁顺序（都按 id 升序更新）；② 小事务快提交；③ 索引齐全（防无索引锁全表）；④ 必要时降 RC 或用乐观锁（version 字段，month06 会展开）。加分句："我在两终端复现过标准交叉死锁，能读懂 LATEST DETECTED DEADLOCK 日志。"

**Q：什么是 MDL 锁事故？**
> 现象：一条 ALTER 挂起，随后整表 CRUD 全部排队。原理：长事务持 MDL 读锁 → DDL 请求 MDL 写锁排队 → DDL 之后的读锁请求全被堵（队列公平性）。处置：查 information_schema.schema_object_overview / performance_schema.metadata_locks 找元凶长事务 kill；预防：DDL 前查长事务 + 上 online DDL/gh-ost + 业务低峰。这是"一个锁引发雪崩"的经典案例，答出完整链路很出彩。

## 5. 今日验收清单

- [ ] 等锁超时实验（含 5 秒变式）记录
- [ ] 死锁复现 + 能读懂 INNODB STATUS 死锁段
- [ ] 间隙锁实验：RR 卡 INSERT vs RC 直接成功
- [ ] 无索引当前读≈锁全表的现象记录
- [ ] 加锁速查表默写；`git add . && git commit -m "day24: locks & deadlock"`

---
[← Day 23](day23-MVCC版本链与ReadView.md) | [本月目录](README.md) | [Day 25 · 三大日志与两阶段提交 →](day25-三大日志与两阶段提交.md)
