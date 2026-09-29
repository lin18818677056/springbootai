# Day 09 · Redis 主从复制（一主二从 + 部分重同步实验）

> **今日目标**：用 Docker 搭一主二从，亲眼看复制链路：replid/backlog/偏移量三个核心概念，亲手触发"部分重同步"（backlog 命中）与"全量复制"（backlog 溢出）——与 month03 day26 的 MySQL 复制对照着学，思想同源细节不同。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：一主二从跑通 + 部分重同步/全量复制的切换实验记录 + 复制流程图

## 1. 知识地图

```
为什么主从（三大理由）：
  ① 读写分离：读多写少（8:2），从库分担读压力
  ② 数据冗余：一份数据多处副本（配合哨兵做高可用）
  ③ 故障转移基础：主挂了从可上位（day10）

复制全量流程（首次连接，五步背下来）：
  从库                              主库
   │ ── PSYNC ? -1 ──────────────→  │  （我第一次来，没有 replid）
   │ ←──── +FULLRESYNC replid off ── │  （给你身份和起点）
   │ ←──── RDB 全量快照 ───────────── │  fork 生成 RDB 发送（day08 的 RDB！）
   │ 载入 RDB                        │  期间新写入 → 复制缓冲区
   │ ←──── 缓冲区增量命令流 ────────── │
   │ 之后：命令传播（长连接实时推送）      │

增量复制靠三要素（★面试核心）：
  replication id (replid)   主库身份 ID，从库跟着记
  offset 偏移量             复制流写到哪了（两边各记一份，可对比延迟）
  repl_backlog 环形缓冲区   主库保留最近 1MB（默认）写命令
  断线重连时：offset 还在 backlog 窗口内 → PSYNC replid offset → +CONTINUE 只补增量
            offset 已被冲掉           → 退化为全量复制（代价：fork+RDB+网络）

对照 MySQL（month03 day26）：
  MySQL: binlog(dump线程) → relay log → SQL 线程重放   【日志回放式】
  Redis: 命令流实时推送，从库直接执行                    【命令传播式】
  共同思想：主库记"我做了什么"，从库照着做一遍

主从延迟的本质：命令传播是异步的 → 主库写完即返回，从库稍后到达
  应用端补偿：写后立刻读的请求强制走主（或等 offset 追平）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Replication | 复制/主从同步 | 异步命令传播 |
| Replication ID | 复制 ID | 主库身份指纹，断线重连凭据 |
| Backlog | 复制积压缓冲区 | 环形缓冲，增量复制的窗口 |
| Offset | 复制偏移量 | 复制流进度（主从各记一份） |
| Full Resync | 全量重同步 | fork+RDB，代价大 |
| Partial Resync | 部分重同步 | backlog 命中，只补增量 |
| Read Replica | 只读副本 | replica-read-only=yes |

## 3. 动手实操

### 3.1 主菜：Docker 一主二从

```powershell
# ① 专用网络（容器间用容器名互访）
docker network create redis-net
# ② 主库
docker run -d --name redis-master --network redis-net -p 6380:6379 redis:7
# ③ 两个从库（--replicaof 直接声明跟随谁）
docker run -d --name redis-replica1 --network redis-net -p 6381:6379 `
  redis:7 redis-server --replicaof redis-master 6379
docker run -d --name redis-replica2 --network redis-net -p 6382:6379 `
  redis:7 redis-server --replicaof redis-master 6379
# ④ 主库视角看复制拓扑
docker exec -it redis-master redis-cli info replication
#   期望：role:master / connected_slaves:2
#         slave0:ip=... port=6379 state=online offset=xxx lag=0
# ⑤ 从库视角
docker exec -it redis-replica1 redis-cli info replication | Select-String "role|master"
#   期望：role:slave / master_link_status:up

# ⑥ 读写分离实证：从库只读
docker exec -it redis-replica1 redis-cli set k1 v1
#   期望报错：READONLY You can't write against a read only replica.
```

### 3.2 实验①：部分重同步（backlog 命中）

```powershell
# 1. 主库写入，记下 offset
docker exec -it redis-master redis-cli set a 1
docker exec -it redis-master redis-cli info replication | Select-String "master_repl_offset"
# 2. 手动断开从库（模拟网络闪断）
docker exec -it redis-replica1 redis-cli replicaof no one   # 摆脱主库
docker exec -it redis-replica1 redis-cli replicaof redis-master 6379  # 重新跟上
# 3. 在主库日志观察（关键证据）
docker logs redis-master --tail 20
#   期望看到：Accepting PSYNC from ... replid=xx offset=xx → +CONTINUE（部分重同步成功！）
```

### 3.3 实验②：全量复制（backlog 溢出退化）

```powershell
# 1. 把 backlog 缩到极小（生产默认 1MB，实验用 1KB 制造溢出）
docker exec -it redis-master redis-cli config set repl-backlog-size 1024
# 2. 断开从库 → 主库狂写 5 万条 → 重连
docker exec -it redis-replica2 redis-cli replicaof no one
1..50000 | ForEach-Object { docker exec redis-master redis-cli set "k$_" v > $null }
#   ↑ 太慢？改用管道一次性灌入（更快）：
#   1..50000 | ForEach-Object { "set k$_ v" } | docker exec -i redis-master redis-cli
docker exec -it redis-replica2 redis-cli replicaof redis-master 6379
docker logs redis-master --tail 5
#   期望：Starting BGSAVE for SYNC ... +FULLRESYNC（offset 掉出 backlog → 全量复制）
# 结论一句话：断线越久/写入越猛，backlog 越容易被冲掉 → 全量复制的代价就在这
```

### 3.4 配置生产化清单（抄进笔记）

```text
repl-backlog-size      1MB → 按"断线时长×写入速率"调大（如 64MB）
repl-diskless-sync     yes 无盘复制：RDB 直接走网络不落盘（磁盘慢时用）
min-replicas-to-write  1   至少 1 从在线才可写（防脑裂写入丢失，day10 再讲）
min-replicas-max-lag   10  从库延迟超 10 秒视为不在线
replica-read-only      yes 从库只读（默认）
```

## 4. 面试连接

**Q：Redis 主从复制的原理？**
> 两段式：首次/掉出 backlog 窗口时全量复制（PSYNC → 主库 fork 生成 RDB 发送 → 期间增量走缓冲区 → 命令传播）；断线窗口内部分重同步（replid+offset 对上 → +CONTINUE 只补 backlog 增量）。三个核心参数：replid（身份）、offset（进度）、repl_backlog（环形缓冲窗口）。加分句："我做过 backlog 溢出实验，1024 字节的 backlog 灌 5 万条后重连，日志里从 CONTINUE 变成了 FULLRESYNC——所以生产要按断线时长×写速率调 repl-backlog-size。"

**Q：主从复制是同步还是异步？丢数据怎么办？**
> 异步：主库写完不等从库 ACK。主库宕机时，还没传播到从库的数据会丢。缓解：min-replicas-to-write=1 + min-replicas-max-lag=10——至少 1 个从库 10 秒内在线才接受写（等价于"半同步"的下限保护），配合哨兵迁移把损失压到秒级窗口。

**Q：为什么 Redis 用命令传播而不是像 MySQL 传 binlog？**
> MySQL 的 binlog 是持久化日志，要支持崩溃恢复与从库重放（crash-safe，month03 day25）；Redis 的复制目标是"实时跟读"，内存态命令直接推送最简单，持久化交给 RDB/AOF 独立解决——关注点分离。

## 5. 今日验收清单

- [ ] 一主二从跑通（info replication 两个从库 online）
- [ ] 部分重同步 +CONTINUE 日志截图
- [ ] 全量复制 FULLRESYNC 触发记录（backlog 溢出）
- [ ] 复制流程图手绘（PSYNC 两分支）
- [ ] `git add . && git commit -m "day09: redis replication"`

---
[← Day 08](day08-RDB与AOF持久化.md) | [本月目录](README.md) | [Day 10 · 哨兵 Sentinel →](day10-哨兵Sentinel.md)
