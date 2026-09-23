# Day 26 · 主从复制与高可用（Docker 一主一从实战）

> **今日目标**：用 Docker 搭一套 GTID 一主一从，亲眼看到"主库写入→从库出现"的复制链路；理解 binlog dump/IO/SQL 三线程与复制延迟的成因——高可用（MHA/MGR/读写分离）的一切都建立在这条链路上。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：一主一从跑通截图 + 复制延迟实验记录 + 三线程流程图

## 1. 知识地图

```
复制链路三线程（★必背图）：
  主库                                    从库
  ┌───────────────┐  binlog dump   ┌──────────────────┐
  │ 事务提交→binlog │ ────────────→ │ IO 线程            │
  │ (dump 线程推送) │               │   ↓ 写入 relay log │
  └───────────────┘               │ SQL 线程(重放)      │
                                   │   ↓               │
                                   │ 数据与主库一致      │
                                   └──────────────────┘
  relay log = 从库的"待重放队列"（重放完自动清理）

GTID = 全局事务标识（source_uuid:序号）
  旧模式靠"文件名+偏移量"对位，主从切换要人工算点位
  GTID 让每个事务全库唯一 → 从库只需说"我做到 GTID x" → 自动对齐
  生产标配：gtid_mode=ON + enforce_gtid_consistency=ON

复制方式三档：
  异步（默认）   主提交就返回，从库何时追上不管——快但可能丢
  半同步 semisync  至少 1 个从库收到 binlog 才返回——折中
  组复制 MGR      Paxos 多数派——MySQL 8 高可用终极形态（month06 再展开）

复制延迟（ReadWriteSplit 之痛）三因：
  ① 从库单 SQL 线程重放（主库并发写）—— 大事务瞬间放大延迟
  ② 从库机器/负载弱  ③ 网络抖动
  治理：并行复制(MTA, replica_parallel_workers) + 拆大事务 + 半读策略
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Replication | 复制 | 主库变更传播到从库 |
| Relay Log | 中继日志 | 从库的待重放队列 |
| Dump / IO / SQL Thread | 三线程 | 推送/接收/重放 |
| GTID | 全局事务标识 | source_uuid:n |
| Semi-Sync | 半同步 | 至少一从收到才返回 |
| Replica Lag | 复制延迟 | Seconds_Behind_Source |

## 3. 动手实操

### 3.1 起一主一从（Docker 自定义网络，容器名互通）

```powershell
# ① 建专用网络（默认 bridge 不支持容器名 DNS，必须自定义！）
docker network create mysql-net

# ② 主库 3307（命令行参数代替 my.cnf，免挂载）
docker run -d --name mysql-master --network mysql-net `
  -p 3307:3306 -e MYSQL_ROOT_PASSWORD=root123 mysql:8.4 `
  --server-id=1 --log-bin=binlog --gtid-mode=ON --enforce-gtid-consistency=ON

# ③ 从库 3308
docker run -d --name mysql-slave --network mysql-net `
  -p 3308:3306 -e MYSQL_ROOT_PASSWORD=root123 mysql:8.4 `
  --server-id=2 --gtid-mode=ON --enforce-gtid-consistency=ON --relay-log=relay

docker ps --filter "name=mysql-"        # 两个都 Up 再往下
```

### 3.2 配置复制链路

```sql
-- ① 主库上：建复制专用账号（进主库：见下行 PowerShell）
CREATE USER 'repl'@'%' IDENTIFIED WITH caching_sha2_password BY 'repl123';
GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';
SHOW VARIABLES LIKE 'gtid_mode';     -- 确认 ON
```

```powershell
docker exec -it mysql-master mysql -uroot -proot123
docker exec -it mysql-slave   mysql -uroot -proot123   # 另一终端
```

```sql
-- ② 从库上：指路 + 启动复制（8.4 新词 Source；GET_SOURCE_PUBLIC_KEY 免密钥坑）
CHANGE REPLICATION SOURCE TO
  SOURCE_HOST='mysql-master', SOURCE_PORT=3306,
  SOURCE_USER='repl', SOURCE_PASSWORD='repl123',
  SOURCE_AUTO_POSITION=1, GET_SOURCE_PUBLIC_KEY=1;
START REPLICA;
SHOW REPLICA STATUS\G
--   三个关键行（截图存档，这就是今天的验收）：
--   Replica_IO_Running: Yes
--   Replica_SQL_Running: Yes
--   Seconds_Behind_Source: 0
```

### 3.3 复制验证：主写从读

```sql
-- 主库：
CREATE DATABASE learn2;
USE learn2;
CREATE TABLE t_rep (id INT PRIMARY KEY, v VARCHAR(20));
INSERT INTO t_rep VALUES (1, 'from-master');

-- 从库（切到从库终端）：
SHOW DATABASES;                     -- learn2 出现了
SELECT * FROM learn2.t_rep;         -- 1, from-master ★复制实证
-- 再观察 GTID 对齐：
SHOW REPLICA STATUS\G               -- Executed_Gtid_Set 与主库 SHOW MASTER STATUS 对比
```

### 3.4 延迟实验：亲手制造 Seconds_Behind_Source

```sql
-- 从库：暂停 SQL 线程（IO 线程继续收货）
STOP REPLICA SQL_THREAD;
-- 主库：批量灌数据（1 万行）
--   USE learn2; INSERT INTO t_rep SELECT seq, CONCAT('v', seq) FROM (
--     WITH RECURSIVE seq AS (SELECT 2 n UNION ALL SELECT n+1 FROM seq WHERE n<10000) SELECT n, CONCAT('v',n) FROM seq) s;

-- 从库：观察积压（IO 线程收完，SQL 线程没跑）
SELECT COUNT(*) FROM learn2.t_rep;            -- 少于主库
-- 主库再 INSERT 几条 → 从库完全看不到（SQL 线程停着）
START REPLICA SQL_THREAD;
-- 从库再看：瞬间追平 + COUNT 一致 + Seconds_Behind_Source 回 0
-- 记录：追平 1 万行耗时 ____ 秒 → 体会"大事务=延迟放大器"
```

### 3.5 三线程流程图手绘 + 读写分离提示卡

```text
图：§1 的三线程图画一遍，标注"GTID 从 binlog → relay log → 重放"全程传递
读写分离速记（month06 服务化还会回来）：
  □ 写走主，读走从（业务框架/中间件路由）
  □ 坑1 复制延迟：写后立刻读 → 读到旧数据（"下单后查不到订单"事故）
     药方：关键读强制走主 / 半同步 / GTID 等待（/*+ gtids */）
  □ 坑2 主从切换脑裂：靠中间件(MHA/Orchestrator/MGR)裁决
```

## 4. 面试连接

**Q：MySQL 主从复制的原理？**
> 三线程答起：主库 dump 线程推送 binlog 事件；从库 IO 线程接收写入 relay log；SQL 线程重放 relay log。补 GTID：事务全局唯一标识，AUTO_POSITION 模式从库自动比对缺口，主从切换免人工对位。加分句："我 Docker 里搭过一主一从并制造过延迟——STOP SQL_THREAD 灌 1 万行再放行，亲眼看 Seconds_Behind_Source 从积压回 0。"

**Q：复制延迟怎么监控、怎么治理？**
> 监控：SHOW REPLICA STATUS 的 Seconds_Behind_Source（原理：SQL 线程重放事件的时间戳与当前时间差）+ relay log 积压量。治理四板斧：① 拆大事务（今天实验证明大事务=延迟放大器）；② 并行复制 replica_parallel_workers（按库/写集合并行重放）；③ 半同步降低丢失窗口；④ 业务侧读写分离的"关键读走主"。能讲清"延迟的根子在重放模型单线程化"才算懂原理。

**Q：主库宕机怎么办？（高可用引子）**
> 手工版：从库升主（STOP REPLICA; RESET REPLICA ALL; 应用切连接串）——人工慢且易错。自动版：MHA/Orchestrator 监控+选主+切换；MySQL 8 更推荐 MGR（组复制，多数派协议选主，RPO=0）。要点补充：半同步防数据丢失、GTID 让切换可靠、VIP/代理让应用无感。收尾说"month06 微服务稳定性会把这些串成完整高可用方案"——埋下个月的钩子。

## 5. 今日验收清单

- [ ] 一主一从跑通（SHOW REPLICA STATUS 三关键行截图）
- [ ] 主写从读验证记录 + GTID 对齐观察
- [ ] 延迟实验：STOP/START SQL_THREAD 全程记录
- [ ] 三线程流程图默画
- [ ] `git add . && git commit -m "day26: master-slave replication (gtid)"`

---
[← Day 25](day25-三大日志与两阶段提交.md) | [本月目录](README.md) | [Day 27 · MySQL 参数与容量评估 →](day27-参数与容量评估.md)
