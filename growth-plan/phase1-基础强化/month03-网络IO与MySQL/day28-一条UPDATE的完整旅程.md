# Day 28 · 综合演练①：一条 UPDATE 的完整旅程（端到端串珠）

> **今日目标**：把三周的 MySQL 知识焊成一条链：`UPDATE t SET v=20 WHERE id=1` 从客户端敲下回车，到主从两端数据一致——Server 层四件套、InnoDB 内部、两阶段提交、复制传播全程串联（旧大纲指定博客②的成文日）。
> **时长**：理论 1h / 实操 2h / 博客 1.5h
> **今日产出**：端到端旅程图 + 《一条 UPDATE 语句在 MySQL 里的完整旅程》博客发布

## 1. 知识地图

```
一条 UPDATE 的完整旅程（★全月镇场图，每站都是前面某一天）：

 客户端: UPDATE t_log SET v=20 WHERE id=1;
   ↓ TCP(day01-04) → 连接器：鉴权/权限/连接池(month02 连接池对号)
   ↓ 分析器：词法语法解析（识别这是 UPDATE）
   ↓ 优化器：WHERE id=1 → 选 PRIMARY 索引 const 访问(day18)
   ↓ 执行器：调用 InnoDB 接口，逐行执行
 ──────────────── 进入 InnoDB(day15-25) ────────────────
   ① 按 id=1 的 B+ 树定位数据页 → Buffer Pool 不在则从磁盘读(day27)
   ② 写 undo log：旧值 v=10 入版本链(day23 MVCC 的原料)
   ③ 修改内存页：v=10→20（该页变"脏页"）(day27)
   ④ 当前读加锁：id=1 的 X 记录锁(day24)
   ⑤ 写 redo log buffer
   ↓ COMMIT 触发两阶段提交(day25)
   ⑥ redo 落盘【prepare】
   ⑦ binlog 写入并刷盘(ROW 格式记行变化 day25)
   ⑧ redo 打【commit】→ 事务返回成功
 ──────────────── 复制传播(day26) ────────────────────────
   ⑨ 主库 dump 线程推送 binlog → 从库 IO 线程写 relay log
   ⑩ 从库 SQL 线程重放 → 同样的 B+ 树定位+undo+改页（从库视角）
   ⑪ 从库数据一致，Seconds_Behind_Source=0
 之后：主库后台把脏页刷盘（checkpoint 推进）——旅程才算真正落袋

 Blog 成文结构（今天产出②的骨架）：
   ① 开头：这条 SQL 看着平平无奇，背后 11 站旅程
   ② Server 层四件套（连接/分析/优化/执行）
   ③ InnoDB 六步（图解：页/undo/redo/锁）
   ④ 2PC 反证法（先 redo 崩/先 binlog 崩）
   ⑤ 复制三线程（主从一致）
   ⑥ 附：性能视角——这一路哪些是顺序写哪些随机写
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| End-to-End | 端到端 | 从客户端到从库的全链路 |
| Connector | 连接器 | 鉴权与连接管理 |
| Parser | 分析器 | 词法/语法树 |
| Optimizer | 优化器 | 选执行计划 |
| Executor | 执行器 | 调引擎接口逐行干 |
| Replication Path | 复制路径 | dump→IO→SQL 三线程 |

## 3. 动手实操

### 3.1 逐站取证：把 11 站各拍一张"现场照"

```sql
-- 站点1-2：连接与线程
SHOW PROCESSLIST;                          -- 看到自己这个连接（Command=Query/Sleep）
SHOW STATUS LIKE 'Threads_connected';

-- 站点3：优化器的选择
EXPLAIN UPDATE t_log SET v=20 WHERE id=1;  -- 8.0 支持 EXPLAIN DML
--   type=const, key=PRIMARY —— 优化器选了主键精准访问

-- 站点4-8：InnoDB 与 2PC（day25 已做，快速重演）
SHOW ENGINE INNODB STATUS\G                -- 记下 LSN
UPDATE t_log SET v = 30 WHERE id = 1;      -- 执行
SHOW ENGINE INNODB STATUS\G                -- LSN 涨了（redo 写入的证据）
SHOW BINLOG EVENTS IN '你的binlog文件' LIMIT 5;  -- 看到 UPDATE 的行级事件
```

### 3.2 站点9-11：复制端取证（day26 主从还在跑的话）

```sql
-- 主库：SHOW MASTER STATUS;   记下 binlog 位点/GTID
-- 从库：
SHOW REPLICA STATUS\G
--   Retrieved_Gtid_Set / Executed_Gtid_Set 增长 → IO/SQL 线程都在干活
SELECT * FROM learn2.t_rep LIMIT 1;        -- 数据已是最新（若刚才改的是复制库）
-- 若没搭主从：回到 day26 §3.1 半小时搭起来，这个取证值回票价
```

### 3.3 性能视角：这条路上什么贵什么便宜（博客素材）

```text
写路径成本清单（从便宜到贵）：
  ✔ redo log buffer（内存顺序写）         —— 几乎免费
  ✔ redo 刷盘（顺序写 + 组提交合并）      —— 便宜
  ✔ binlog 刷盘（顺序写）                 —— 便宜
  ✘ 数据页读盘（随机读，BP 未命中才发生）  —— 贵
  ✘ 脏页刷盘（随机写，后台摊薄）          —— 贵但延迟付
  ✘ 锁等待（别人持有行锁时）              —— 无上限的贵
  结论卡：MySQL 写性能=日志吞吐决定上限（顺序写），
          读性能=Buffer Pool 决定下限（命中率）
          ——呼应 day25 WAL 与 day27 参数章
```

### 3.4 博客发布：《一条 UPDATE 语句在 MySQL 里的完整旅程》

```text
发布渠道：CSDN/掘金/博客园/知乎（旧大纲指定产出②）
写作配方（素材全部现成）：
  §1 开场：11 站旅程总览图（把 §1 的图重画成博客版，箭头+编号）
  §2 Server 层四件套：每个 3 句话 + 一张小图（连接器/分析器/优化器/执行器）
  §3 InnoDB 内部：B+ 树定位图 + undo 版本链图 + 脏页/redo 图（day15/23/27 的图复用）
  §4 两阶段提交：2PC 时序图 + 反证法表格（day25 §1 直接改写）
  §5 复制传播：三线程图（day26）+ 你的 SHOW REPLICA STATUS 截图
  §6 收尾：性能视角清单（§3.3）——工程师味十足的结尾
  加分项：贴 2 张你自己的取证截图（EXPLAIN / REPLICA STATUS）
  标题备选：《一条 UPDATE 的奇幻漂流：MySQL 11 站全解》
发布后链接贴入本文件 + learning 仓库 NOTES.md
```

## 4. 面试连接

**Q：一条 UPDATE 在 MySQL 里怎么执行？（本题就是答案的彩排）**
> 两段式回答：Server 层（连接器鉴权→分析器解析→优化器选主键 const 计划→执行器调 InnoDB）；InnoDB 内部（读页进 BP→写 undo→改内存页变脏页→行锁→redo buffer→2PC：redo prepare→binlog→redo commit）。收尾补复制传播与脏页异步刷盘。全程可画图——提前在纸上把 §1 的图练到 3 分钟画完。

**Q：为什么提交成功了，数据页可能还没写进磁盘？**
> 因为 WAL：提交的 durability 由 redo log 保证（顺序写+组提交，便宜且快），脏页刷盘是后台异步的事（贵且随机，交给 checkpoint 慢慢来）。崩溃恢复时 redo 重放即可还原所有已提交事务。这是"把贵的事延后、把保命的事先做"的经典设计——和 Redis AOF/RDB 的取舍逻辑可以对照着聊（month04 预告）。

**Q：主从数据不一致可能出在哪一环？**
> 按 11 站排查：⑦ binlog 没落盘（sync_binlog≠1 主库崩丢事件）→ 从库少数据；⑨-⑩ IO/SQL 线程断（Last_IO_Error/Last_SQL_Error，常见 1062 主键冲突/1032 行不存在——从库被手工改过）；②从库被写入（read_only 没开）导致冲突。修复：pt-table-checksum 校验 + pt-table-sync 修复，或重建从库。答出"检查表+常见错误码"是实战派标志。

## 5. 今日验收清单

- [ ] 11 站取证全部完成（每站一行记录）
- [ ] 端到端旅程图能 3 分钟脱稿画完
- [ ] 性能成本清单能讲（顺序写 vs 随机写）
- [ ] 博客②已发布，链接贴入本文件
- [ ] `git add . && git commit -m "day28: update journey e2e + blog"`

---
[← Day 27](day27-参数与容量评估.md) | [本月目录](README.md) | [Day 29 · 慢 SQL 门诊与模拟验收 →](day29-慢SQL门诊与模拟验收.md)
