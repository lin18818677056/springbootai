# Day 23 · MVCC：版本链与 ReadView（快照读的底层引擎）

> **今日目标**：昨天"快照读" magic 的拆机课：一行数据怎么带出多条历史版本（DB_TRX_ID/DB_ROLL_PTR 版本链）、ReadView 四要素、可见性判断算法，以及 RC 与 RR 生成 ReadView 的时机差异——这是 MySQL 面试深度题的必争之地。
> **时长**：理论 1.5h / 实操 1.5h / 输出 0.5h
> **今日产出**：版本链手绘图 + ReadView 可见性判断流程图 + RC/RR 差异实验记录

## 1. 知识地图

```
MVCC = Multi-Version Concurrency Control（多版本并发控制）
思路：写不阻塞读——每次 UPDATE 留旧版本，读的人挑一个自己"看得见"的版本

一行的隐藏三列（每行都有，肉眼不可见）：
  ┌────┬────────────┬───────────┬──────────────┐
  │ id │ 业务列      │ DB_TRX_ID │ DB_ROLL_PTR   │
  │    │            │ 最近改它的事务ID │ 回滚段指针 → undo log 旧版本 │
  └────┴────────────┴───────────┴──────────────┘
版本链（顺着 roll_ptr 串起来）：
  [balance=50, trx=300] → [balance=100, trx=200] → [balance=88, trx=100]
     最新版本                历史版本1              历史版本2(在undo里)

ReadView 四要素（读时刻的"可见性裁判卡"）：
  m_ids        生成视图时，所有【活跃(未提交)】事务 id 列表
  min_trx_id   m_ids 里最小的（活跃最早者）
  max_trx_id   下一个将分配的事务 id（不是最大活跃！易错）
  creator_trx_id  我自己的事务 id

可见性判断（对版本链上每个版本的 trx_id 逐个判，★背流程）：
  trx_id == creator            → 自己改的，可见 ✓
  trx_id <  min_trx_id         → 生成视图前已提交，可见 ✓
  trx_id >= max_trx_id         → 生成视图后才开启的事务，不可见 ✗
  min ≤ trx_id < max：
      在 m_ids 里（还活跃着）    → 不可见 ✗（防脏读的关键）
      不在 m_ids（已提交）      → 可见 ✓
  不可见 → 顺 roll_ptr 找上一版本再判，直到找到可见版本

RC 与 RR 的唯一差别（★面试题眼）：
  RC：每条 SELECT 都新生成一个 ReadView（总能看到最新已提交）
  RR：事务里【第一次】SELECT 生成，之后复用（=可重复读的本质）
  一句话："RC 是'每读一拍新照'，RR 是'进店拍一张看到底'"
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| MVCC | 多版本并发控制 | 写不阻塞读的实现 |
| Version Chain | 版本链 | roll_ptr 串起的 undo 历史 |
| Read View | 读视图 | 可见性判断的四要素快照 |
| Active Transaction | 活跃事务 | 已开始未提交（在 m_ids 里） |
| Undo Log | 回滚日志 | 旧版本存放地（day25 细讲） |
| Long Transaction | 长事务 | 让 undo 无法清理的元凶 |

## 3. 动手实操

### 3.1 亲眼看版本链（information_schema 侧面观察）

```sql
-- 版本链本身在 undo 里不可直读，但可以观察它的"因"与"果"：
-- ① 事务 id 自增观察
CREATE TABLE t_mvcc (id INT PRIMARY KEY, v INT) ENGINE=InnoDB;
INSERT INTO t_mvcc VALUES (1, 88);           -- 记下此时 TRX 状态
SELECT trx_id, trx_state, trx_started, trx_rows_modified
FROM information_schema.innodb_trx;          -- 应该看不到（短事务已结束）

-- ② 开一个长事务，看它出现在活跃列表
BEGIN;
UPDATE t_mvcc SET v = 100 WHERE id = 1;      -- 不提交！
SELECT trx_id, trx_state, trx_started, trx_rows_modified
FROM information_schema.innodb_trx;          -- ★看到它了：trx_id 抄进笔记
-- 这个 trx_id 就是刚才那行 UPDATE 的 DB_TRX_ID（最新版本上的印记）

-- ③ undo 链增长观察
UPDATE t_mvcc SET v = 101 WHERE id = 1;
UPDATE t_mvcc SET v = 102 WHERE id = 1;      -- 同事务内连改，链上已有 88→100→101→102
SELECT trx_rows_modified FROM information_schema.innodb_trx;  -- 3
-- 先别提交！下一节用另一个会话来看"别人眼中的世界"
```

### 3.2 实验：RR 快照的"出生时刻"（RC/RR 差异实证）

```sql
-- 终端2（B）：确认 RR 级别，然后开始读
-- B: SET autocommit=0; BEGIN;
-- B: SELECT v FROM t_mvcc WHERE id=1;   -- 读到 88 ★（A 的 100/101/102 未提交）

-- 终端1（A）：提交
-- A: COMMIT;

-- B: SELECT v FROM t_mvcc WHERE id=1;   -- 还是 88！RR 快照在 BEGIN 后第一读就定版
-- B: COMMIT;                            -- 结束事务
-- B: BEGIN; SELECT v FROM ...;          -- 新事务：读到 102（新 ReadView）

-- 复做一遍但 B 用 RC：SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
-- B: BEGIN; SELECT ...;                 -- 88
-- A: BEGIN; UPDATE ... v=200; COMMIT;   -- A 提交 200
-- B: SELECT ...;                        -- 200！RC 每条语句新 ReadView
-- 记录：两个实验的四次读值时间线（RR:88,88,102 / RC:88,200）
```

### 3.3 手绘任务：两张图

```text
图① 版本链：一行 4 个版本的链（标注 DB_TRX_ID 变化 + undo log 归宿）
图② 可见性判断流程图（§1 的四分支画成流程图）：
   拿 trx_id → 是自己? → <min? 可见 → ≥max? 不可见 → 在 m_ids? 不可见:可见
   → 不可见则 roll_ptr 前进，循环
配一道自测题（先做再看答案）：
  ReadView{m_ids=[300], min=300, max=400, creator=100}
  版本链: trx=350 → trx=250 → trx=100，各版本可见性？
  答：350 不可见(在 m_ids 活跃) → 250 可见(<min) ✓  → 读到 250 版本
```

### 3.4 长事务危害观察（生产事故高频根因）

```sql
-- 3.1 里那个不提交的事务就是"长事务"雏形。危害三连：
-- ① undo 链只能一直保留（有活跃事务引用着）→ undo 表空间膨胀
-- ② 任何新事务的 ReadView 都要包含它 → 可见性判断链变长
-- ③ 它持有的行锁不释放（day24 展开）→ 别人堵门
-- 观察命令（生产巡检脚本的原型）：
SELECT trx_id, trx_started,
       TIMESTAMPDIFF(SECOND, trx_started, NOW()) AS alive_seconds,
       trx_rows_modified
FROM information_schema.innodb_trx
WHERE TIMESTAMPDIFF(SECOND, trx_started, NOW()) > 60;   -- 超过 1 分钟的都报警
-- 记得回终端1 ROLLBACK 清场！
```

## 4. 面试连接

**Q：讲讲 MVCC 的实现原理？**
> 三件套：隐藏列（DB_TRX_ID/DB_ROLL_PTR）+ undo log 版本链 + ReadView。流程：每次修改把旧版本压进 undo 链；读取时按 ReadView 四要素从新到旧逐版本判可见性，读到第一个可见版本。最后补 RC/RR 差异（ReadView 生成时机）——这是题眼。加分句："我能现场画版本链和可见性流程图，还能设计一道 trx_id 判断题讲给别人。"

**Q：为什么 RR 下第一次 SELECT 生成 ReadView 就能可重复读？**
> 因为后续 SELECT 复用同一张"裁判卡"：事务开始后其他事务无论提交与否，要么在卡生成前已提交（可见，但读到的是同一历史版本因为卡不变），要么活跃/未来（不可见）。于是整个事务看到一个固定世界。RC 每语句刷新卡，所以总能看到最新提交。一句话版本："可重复读 = 裁判卡不换。"

**Q：长事务有什么危害？怎么发现和治理？**
> 危害四连：undo 无法清理（膨胀+历史链变长）、锁不释放、主从延迟（大事务 binlog 一次性下发）、回滚巨慢。发现：innodb_trx 查 trx_started 超 60s；治理：① 业务拆小事务；② 交互式会话禁开事务后去干别的（大忌）；③ 大批量删除改分批；④ 监控告警+kill 超时事务。加分句："我把巡检 SQL 贴出来过：SELECT ... FROM innodb_trx WHERE alive_seconds > 60。"（今天的 3.4 就是原型）

## 5. 今日验收清单

- [ ] innodb_trx 观察实验完成（抄下 trx_id）
- [ ] RR/RC 四次读值时间线记录（88,88,102 vs 88,200）
- [ ] 版本链 + 可见性流程图默画；自测题做对
- [ ] 长事务巡检 SQL 收藏，能讲三大危害
- [ ] `git add . && git commit -m "day23: mvcc version chain & readview"`

---
[← Day 22](day22-事务与隔离级别.md) | [本月目录](README.md) | [Day 24 · 锁体系与加锁规则 →](day24-锁体系与加锁规则.md)
