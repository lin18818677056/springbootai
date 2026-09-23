# Day 22 · 事务与隔离级别（三种读现象亲手复现）

> **今日目标**：进入 Week4「事务·锁·日志」。今天把 ACID 落到实验：开两个终端当两个并发用户，逐个复现脏读/不可重复读/幻读，验证四个隔离级别各自挡住什么——面试"隔离级别"题从此有实验背书。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：三现象复现记录 + 隔离级别×现象矩阵 + 快照读/当前读概念卡

## 1. 知识地图

```
事务 ACID（每个字母背后都有一个"实现者"——本周逐一揭晓）：
  Atomicity  原子性 ← undo log（回滚靠它，day25）
  Consistency 一致性 ← 其余三者的共同结果（目的不是手段）
  Isolation  隔离性 ← MVCC + 锁（day23/24）
  Durability 持久性 ← redo log（day25）

并发三现象（隔离性要挡的三个"坏读"）：
  脏读     读到别人【未提交】的数据（对方回滚=你拿着假数据）
  不可重复读 同一事务两次读【同一行】结果不同（别人 UPDATE 提交了）
  幻读     同一事务两次【同一范围】行数不同（别人 INSERT 提交了）
  区别记忆：脏读=未提交；不可重复读=行内容变；幻读=行数量变

四隔离级别 × 现象矩阵（★必背）：
  ┌────────────────┬──────┬──────────┬──────┐
  │ 级别            │ 脏读 │ 不可重复读│ 幻读  │
  ├────────────────┼──────┼──────────┼──────┤
  │ READ UNCOMMITTED│ 可能 │ 可能     │ 可能 │
  │ READ COMMITTED  │ 防住 │ 可能     │ 可能 │
  │ REPEATABLE READ │ 防住 │ 防住     │ InnoDB 基本防住*│
  │ SERIALIZABLE    │ 防住 │ 防住     │ 防住 │
  └────────────────┴──────┴──────────┴──────┘
  * SQL 标准说 RR 挡不住幻读；InnoDB 靠 MVCC(快照读)+间隙锁(当前读)
    基本挡住了——这半句是面试的区分度所在（day24 锁体系展开）
  MySQL 默认 RR；Oracle/PostgreSQL 默认 RC

快照读 vs 当前读（理解 RR 防幻读的钥匙）：
  快照读：普通 SELECT → 读 MVCC 版本链快照（day23）
  当前读：UPDATE/DELETE/SELECT...FOR UPDATE → 读最新已提交版本+加锁
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Transaction | 事务 | 一组要么全做要么全不做的操作 |
| Dirty Read | 脏读 | 读到未提交数据 |
| Non-Repeatable Read | 不可重复读 | 同行两次读不一致 |
| Phantom Read | 幻读 | 同范围行数变化 |
| Isolation Level | 隔离级别 | 四档防御矩阵 |
| Snapshot Read | 快照读 | MVCC 免锁读 |

## 3. 动手实操

### 3.1 实验台：两个终端 = 两个会话（标记 A/B）

```powershell
# 终端1（下文称 A）与 终端2（下文称 B）各开一个 mysql 交互会话
docker exec -it mysql-learning mysql -uroot -proot123 learn
docker exec -it mysql-learning mysql -uroot -proot123 learn

-- 两边都执行：建实验表 + 关自动提交（手动 BEGIN/COMMIT 才有并发窗口）
DROP TABLE IF EXISTS t_txn;
CREATE TABLE t_txn (id INT PRIMARY KEY, balance INT NOT NULL) ENGINE=InnoDB;
INSERT INTO t_txn VALUES (1, 100), (2, 200);
SET autocommit = 0;
-- 确认两边当前隔离级别（8.4 默认 REPEATABLE-READ）
SELECT @@transaction_isolation;
```

### 3.2 实验 1：脏读（RU 级别才能看到）

```sql
-- 两边都执行：SET SESSION TRANSACTION ISOLATION LEVEL READ UNCOMMITTED;
-- A: BEGIN; UPDATE t_txn SET balance=999 WHERE id=1;   -- 改了但不提交
-- B: BEGIN; SELECT balance FROM t_txn WHERE id=1;
--    → 读到 999 ★脏读：这是 A 没提交的数据
-- A: ROLLBACK;                                          -- A 反悔了
-- B: SELECT ... WHERE id=1;   → 又变回 100：B 刚才基于假数据做了决策
-- 记录：截图两边的查询结果对比
```

### 3.3 实验 2：不可重复读（RC 级别可见，RR 级别消失）

```sql
-- 两边：SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
-- A: BEGIN; SELECT balance FROM t_txn WHERE id=1;      -- 读到 100（快照建立）
-- B: BEGIN; UPDATE t_txn SET balance=50 WHERE id=1; COMMIT;  -- B 提交了修改
-- A: SELECT balance FROM t_txn WHERE id=1;
--    RC 下读到 50 ★不可重复读：同事务两次读不一致
--    切回 RR 再做一遍 → A 两次都读 100（MVCC 快照兜底）→ 对比记录！
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE-READ;
```

### 3.4 实验 3：幻读与它的两个面孔

```sql
-- 两边 RR 级别。先测【快照读的幻读】：
-- A: BEGIN; SELECT COUNT(*) FROM t_txn;                -- 2 行
-- B: BEGIN; INSERT INTO t_txn VALUES (3, 300); COMMIT;
-- A: SELECT COUNT(*) FROM t_txn;                       -- 还是 2（快照读看不到）
--     → RR 下普通 SELECT 靠 MVCC 防住了幻读的一半
-- 再测【当前读的幻读边界】：
-- A: UPDATE t_txn SET balance=balance+1 WHERE id>0;    -- 当前读！
-- A: SELECT COUNT(*) FROM t_txn;                       -- 3 行了（更新"吵醒"快照）
--     → 结论卡：RR 的防幻读 = 快照读走 MVCC；
--       一旦混用当前读（UPDATE/FOR UPDATE）就看到最新世界
--       "完全防幻读要么全程当前读+间隙锁(day24)，要么 SERIALIZABLE"
```

### 3.5 概念卡收尾（抄笔记）

```text
| 读法     | 触发语句                    | 读什么         | 加锁吗 |
|----------|---------------------------|----------------|--------|
| 快照读   | 普通 SELECT                | MVCC 历史版本  | 不加   |
| 当前读   | UPDATE/DELETE/FOR UPDATE   | 最新已提交     | 加锁   |
| 共享读   | SELECT ... LOCK IN SHARE MODE (8.0: FOR SHARE) | 最新 | S 锁 |
面试一句话："MySQL 的 RR 是'快照读防幻 + 当前读加锁'的组合拳，
不是 SQL 标准里那个裸 RR——这是 MySQL 特有的实现升级。"
```

## 4. 面试连接

**Q：四种隔离级别分别解决什么问题？MySQL 默认哪个，为什么？**
> 先给三现象定义（脏读/不可重复读/幻读），再对矩阵逐格回答：RU 全裸、RC 防脏读、RR 再防不可重复读、SERIALIZABLE 全防但并发暴跌。MySQL 默认 RR：主从复制早期依赖 RR 下的语句级 binlog 安全（day26 细讲），且 InnoDB 的 MVCC 让 RR 防幻读升级。生产互联网公司常改 RC（锁更少、语义简单）——讲出这个取舍是加分项。

**Q：RR 能完全防幻读吗？**
> 标准答案分两面：快照读防（MVCC 只看事务开始时的快照）；当前读之间靠间隙锁防（范围锁定防止 INSERT，day24 实验）。但快照读与当前读【混用】会"破功"（今天的 UPDATE 吵醒快照实验）。所以严谨表述："InnoDB RR 把幻读挡到'基本不可见'，完全免疫需要事务内统一读法。"能画出"快照读/当前读两条时间线"就是满分。

**Q：RC 为什么很多公司反而喜欢用？**
> 三点：① 锁粒度代价低——RC 无间隙锁（除外键/唯一检查），死锁率与锁冲突显著低；② 语义简单——每条语句读最新已提交，少"历史快照迷惑"；③ binlog ROW 格式普及后，RR 的复制安全优势消失。代价：不可重复读需业务容忍（多数互联网场景无感）。答出"默认值是历史，选型是当下"的态度很加分。

## 5. 今日验收清单

- [ ] 三现象实验全部复现（A/B 终端截图）
- [ ] RR 下 UPDATE"吵醒快照"实验记录
- [ ] 隔离级别×现象矩阵默写
- [ ] 快照读/当前读概念卡能脱稿讲
- [ ] `git add . && git commit -m "day22: isolation levels in action"`

---
[← Day 21](day21-第三周复盘与SQL清单.md) | [本月目录](README.md) | [Day 23 · MVCC 版本链与 ReadView →](day23-MVCC版本链与ReadView.md)
