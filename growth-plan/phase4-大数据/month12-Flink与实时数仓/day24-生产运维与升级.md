# Day 24 · 生产运维与升级：作业怎么安全地"改版上线"

> **今日目标**：学会生产的"作业保养手册"——Savepoint（手动存档）与升级恢复全流程、监控四件套、告警阈值；理解为什么改代码前要给算子上"身份证"（UID）。
> **时长**：概念 1h / 实操 2.5h / 输出 0.5h
> **今日产出**：savepoint 停止/恢复实验记录 + 《监控巡检表》+ 《升级 SOP》

## 1. 知识地图（先讲人话）

```
游戏存档类比收官（day08 的家族 reunion）：
  Checkpoint = 自动存档（系统定时拍，作业挂了自己读最近的档续命）
  Savepoint  = 手动存档（升级前主动拍一个"正式档"，格式稳定、跨版本可读）

作业改版的标准三步（生产版"行驶中换轮胎"）：
  ① 拍档：flink stop --savepointPath（先拍存档、拍成功才优雅停止）
  ② 换胎：提交新代码（停旧的、上新的）
  ③ 读档：新作业指定 savepoint 路径启动 → 状态无缝接管
  —— day08 的 kill TM 是"事故读档"（被动、慌张）；
     今天是"计划读档"（主动、档是新的、人是清醒的）

状态兼容性（读档失败的头号原因）：
  算子 UID = 每个算子的身份证号；不指定时按代码结构自动生成
  —— 改了代码结构 → 身份证变了 → 旧档对不上号 → 恢复失败！
  —— 生产纪律：关键算子显式指定 UID（DataStream 的 .uid("agg-v1")）
  状态结构：只调参数（TTL、并行度）→ 兼容可恢复；
            改 key / 改聚合逻辑 → 不兼容（新作业，或接受状态清零）

监控四件套（前两周老朋友的"运维视角集结"）：
  Lag：上游积压多少（没跟上进度）
  busy%：工人忙不忙（>80% 报警）
  CKPT：存档灵不灵（连续失败必须报）
  State Size：小本本是否只涨不跌（day22 慢性病）
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| Savepoint | 保存点 | 手动存档：格式标准化，跨版本/跨集群可恢复 |
| stop-with-savepoint | 带档停止 | 触发 savepoint 成功后才停作业（拍完照才关机） |
| Operator UID | 算子标识 | 算子的身份证号，恢复时靠它对号入座 |
| State Compatibility | 状态兼容性 | 新代码能不能读旧档（调参兼容，改结构不兼容） |
| Rollback | 回滚 | 新版出问题 → 旧代码读同一个档退回去 |
| Restart Strategy | 重启策略 | 挂了自动重试的节奏（默认按 checkpoint 重启） |

## 3. 动手实操

### 3.1 准备：重建集群带"共享柜"

```powershell
# savepoint 必须写进 JM/TM 都能读的目录（生产=HDFS/S3；容器演示=共享卷）
docker stop flink-jm flink-tm; docker rm flink-jm flink-tm
docker volume create flink-sp
docker run -d --name flink-jm --network bigdata -p 8081:8081 -v flink-sp:/tmp/flink-sp `
  -e FLINK_PROPERTIES="jobmanager.rpc.address: flink-jm" flink:1.18 jobmanager
docker run -d --name flink-tm --network bigdata -v flink-sp:/tmp/flink-sp `
  -e FLINK_PROPERTIES="jobmanager.rpc.address: flink-jm`ntaskmanager.numberOfTaskSlots: 4" flink:1.18 taskmanager
# 教学点：存档要放"所有工人和工头都看得见的柜子"——这就是为什么生产必须共享存储
```

### 3.2 实验①：拍档 → 换代码 → 读档（全流程走一遍）

```powershell
# 第 1 步：先提交一个跑着的作业（day06 的 TTL 作业即可），拿作业 id
docker exec flink-jm ./bin/flink list
# 第 2 步：带档停止
docker exec flink-jm ./bin/flink stop --savepointPath /tmp/flink-sp <jobId>
# 输出里记下 savepoint 路径：/tmp/flink-sp/savepoint-xxxxxx
# 第 3 步：改一个兼容参数（TTL 60s → 120s），从档恢复
docker exec -it flink-jm ./bin/sql-client.sh
```

```sql
SET 'execution.savepoint.path' = '/tmp/flink-sp/savepoint-xxxxxx';
SET 'table.exec.state.ttl' = '120 s';
-- 重新 INSERT 同一个作业
-- 验收：UI Overview 恢复运行；Exceptions 无报错；
-- Checkpoints 页 State Size 与停机前衔接（状态没丢的证据，截图）
```

### 3.3 实验②：故意弄坏一次（不兼容长什么样）

```
把 GROUP BY 的 key 从 uid 改成别的字段（状态结构变了），仍指向旧 savepoint 启动：
预期：恢复失败，报错包含 savepoint/状态不匹配信息
诚实记录：报错原文 + 原因归档（key 变了=档案里的账本格式对不上）
→ 这就是生产"大改逻辑要么新作业、要么状态迁移"的实证原因
```

### 3.4 实验③：一分钟巡检（Rest API）

```powershell
curl.exe -s http://localhost:8081/jobs/overview
# 看什么：state 是否 RUNNING（不是=偷偷挂了/重启中）
curl.exe -s http://localhost:8081/jobs/<jobId>/checkpoints
# 看什么：counts.failed（失败次数）、latest.completed 的 duration
```

```
《监控巡检表》（四指标×阈值×动作）：
  Lag         > 10 分钟且持续上涨 → 立即排查（day10 五步法）
  busy%       > 80% 持续 15 分钟  → 扩并行/查慢算子
  CKPT        连续失败 3 次       → 按 day22 决策树诊断
  State Size  连续 7 天只涨不跌   → 三板斧瘦身
《升级 SOP》（贴墙）：
  低峰执行 → 拍 savepoint 并确认 completed → 测试环境读档演练 →
  生产三步走 → 观察 10 分钟四件套 → 异常则旧代码读同档回滚
```

## 4. 面试连接

**Q：Savepoint 和 Checkpoint 有什么区别？（送分题，答全=加分）**
> 四个维度。触发方式：checkpoint 按 interval 自动触发，savepoint 由人手动触发。目的：checkpoint 是容错用的——作业挂了自己读最近的档续命；savepoint 是有计划的操作——升级、扩缩容、迁移前的正式存档。格式：checkpoint 的存储格式和状态后端实现绑定（比如 RocksDB 增量档离开原集群就没法读），savepoint 是标准化格式，跨版本跨集群可恢复。生命周期：checkpoint 默认随作业删除而清理，savepoint 必须人显式删。生产纪律随之而来：升级前必拍 savepoint；关键算子显式 uid，否则改了代码读档就对不上号。

**Q：生产上 Flink 作业怎么升级不丢状态？（追问链）**
> 三步加一条纪律。三步：低峰期 stop-with-savepoint（拍完档才停，状态是最新）；新代码指定 savepoint 路径启动；观察四件套——Lag 回落、busy 正常、checkpoint 恢复、State Size 与停机前衔接。纪律：动生产前先在测试环境拿生产 savepoint 的副本演练读档，确认状态兼容；如果改了 key 或聚合逻辑这种大改，档读不了就老实新作业加双跑切换，硬恢复等于把状态清零重来。万一新版有问题，旧代码读同一个 savepoint 秒级回滚——"计划读档"永远比"事故读档"从容。

## 5. 今日验收清单

- [ ] Savepoint vs Checkpoint 四维度对比脱口而出
- [ ] 实验①跑通（恢复耗时 + State Size 衔接截图）
- [ ] 实验②的不兼容报错见过一次（知道疼才记得住）
- [ ] 算子 UID 的"身份证"类比能讲
- [ ] 《监控巡检表》+《升级 SOP》成文
- [ ] `git add . && git commit -m "day12-24: ops-upgrade"`

---
[← Day 23](day23-并行度与资源.md) | [本月目录](README.md) | [Day 25 · 流批一体对照 →](day25-流批一体对照.md)
