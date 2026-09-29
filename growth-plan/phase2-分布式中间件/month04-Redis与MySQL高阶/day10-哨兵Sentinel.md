# Day 10 · 哨兵 Sentinel（自动故障转移全过程实录）

> **今日目标**：给昨天的一主二从装上 3 个哨兵，然后亲手 kill 主库，完整记录"主观下线→客观下线→选 Leader→故障转移"的时间线——高可用不再是名词，而是一段你能背出来的事故时间线。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：3 哨兵 + 一主二从拓扑跑通 + kill 主库故障转移时间线 + 脑裂防护配置卡

## 1. 知识地图

```
哨兵解决什么：主库挂了，谁来"扶正"从库？（人肉运维 → 自动化）

哨兵三大职责：
  ① 监控：每秒 ping 主/从/其他哨兵
  ② 通知：故障时通知管理员/客户端（Pub/Sub 频道 +sentinel-switch-master）
  ③ 故障转移：挑一个从库提升为新主，其他从库改跟新主

两个"下线"（★必考辨析）：
  主观下线 SDOWN  单个哨兵觉得：ping 超过 down-after-milliseconds 没回
                  → 可能是我网络不好，先报告大家
  客观下线 ODOWN  ≥quorum 个哨兵都认为主库失联 → 集体确认：真挂了
                  （quorum=2：3 个哨兵中 2 个同意才 ODOWN，防单点误判）

故障转移四步（Leader 哨兵主导）：
  ① 选举 Leader 哨兵：哨兵之间 Raft 式投票（先到先得+多数派）
     规则：每哨兵一票、超半数当选、超时重新发起
  ② 挑新主：从库筛选打分（从库优先级 replica-priority →
     复制偏移量 offset 最大（数据最全）→ runid 最小）
  ③ 执行切换：新主 replicaof no one；其余从库 replicaof 新主；
     旧主回来发现天变了 → 自动降级为从
  ④ 通知客户端：改写自身配置 + Pub/Sub 广播新主地址

脑裂（split-brain）防护：
  场景：主库"假死"（网络分区），哨兵已切新主，旧主还收着客户端写
        → 网络恢复，旧主降级为从，假死期间的写入全部丢失！
  解药（主库侧，等于半同步下限）：
    min-replicas-to-write 1    至少 1 个从库连着才可写
    min-replicas-max-lag 10    且延迟 ≤10 秒
  效果：假死的主库失去所有从库 → 拒绝写入 → 脑裂数据丢失窗口被掐死

客户端感知方案（面试加分）：
  方案A：定时查 SENTINEL get-master-addr-by-name mymaster
  方案B：订阅 +switch-master 频道，事件驱动切换
  生产连接池（Lettuce/JedisSentinelPool）都内置了哨兵支持
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Sentinel | 哨兵 | 独立进程（redis-sentinel），分布式架构 |
| SDOWN | 主观下线 | 单哨兵判断，可能误判 |
| ODOWN | 客观下线 | quorum 个哨兵共同确认 |
| Quorum | 法定票数 | 判定 ODOWN 的门槛 |
| Failover | 故障转移 | 提升从库为新主的全过程 |
| Split-Brain | 脑裂 | 两个主同时接受写入 |

## 3. 动手实操

### 3.1 主菜：3 哨兵压上（复用 day09 的 redis-net）

```powershell
cd D:\mywork\springbootai\learning\month04-redis-mysql
mkdir s1, s2, s3

# ① 哨兵配置（三份一样，端口都映射 26379，靠容器隔离）
@"
port 26379
sentinel monitor mymaster redis-master 6379 2
sentinel down-after-milliseconds mymaster 5000
sentinel failover-timeout mymaster 10000
sentinel parallel-syncs mymaster 1
"@ | Set-Content -Encoding ASCII s1\sentinel.conf
Copy-Item s1\sentinel.conf s2\sentinel.conf
Copy-Item s1\sentinel.conf s3\sentinel.conf

# ② 起 3 个哨兵容器
1..3 | ForEach-Object {
  docker run -d --name "sentinel$_" --network redis-net `
    -v "$PWD\s$_\sentinel.conf:/etc/redis/sentinel.conf" `
    redis:7 redis-sentinel /etc/redis/sentinel.conf
}
# ③ 确认哨兵已发现 1 主 2 从
docker exec -it sentinel1 redis-cli -p 26379 sentinel master mymaster | Select-Object -First 30
docker exec -it sentinel1 redis-cli -p 26379 sentinel replicas mymaster
# 期望：num-slaves:2，num-other-sentinels:2（互发现成功）

# 配置说明（抄进笔记）：
#   mymaster          主库别名（客户端用这个名字找主库）
#   redis-master 6379 主库地址（用容器名 = 网络内 DNS）
#   2                 quorum=2（3 哨兵中的 2 票才 ODOWN）
#   down-after 5000   5 秒无响应 → SDOWN
#   parallel-syncs 1  转移后从库逐个跟新主（1=平滑，多=快但抖）
```

### 3.2 实验：kill 主库，记录完整时间线

```powershell
# ① 记录当前主库地址
docker exec -it sentinel1 redis-cli -p 26379 sentinel get-master-addr-by-name mymaster
#   期望：redis-master 6379

# ② 停主库（Windows 上 stop 即模拟宕机，效果同 kill -9）
docker stop redis-master
Get-Date -Format "HH:mm:ss.fff"   # 记下停库时刻 T0

# ③ 追哨兵日志（时间线证据）
docker logs sentinel1 --tail 30
# 期望时间线（对照你的 T0 换算）：
#   +sdown master mymaster ...           T0+5s   主观下线
#   +odown master mymaster #quorum 2/2   T0+5s   客观下线
#   +vote-for-leader ...                 立即    哨兵选 Leader
#   +elected-leader ...                  当选
#   +selected-slave redis-replica2 ...   挑中新主（offset 最大者优先）
#   +switch-master mymaster redis-master 6379 redis-replica2 6379
#                                        T0+约8-12s  ★切换完成

# ④ 验证新主
docker exec -it sentinel1 redis-cli -p 26379 sentinel get-master-addr-by-name mymaster
#   期望：redis-replica2 6379
docker exec -it redis-replica1 redis-cli info replication | Select-String "master"
#   期望：master_host:redis-replica2（旧从已改跟新主）

# ⑤ 旧主回归测试：自动降级为从
docker start redis-master
Start-Sleep 5
docker exec -it redis-master redis-cli info replication | Select-String "role"
#   期望：role:slave（哨兵已把它收编为从库——自动化闭环）
```

### 3.3 脑裂防护实验（可选加深）

```powershell
# 给主库加脑裂保护后，单独 kill 它的所有从库 → 主库应拒绝写
docker exec -it redis-replica2 redis-cli config set min-replicas-to-write 1
docker exec -it redis-replica2 redis-cli config set min-replicas-max-lag 10
docker stop redis-replica1; docker stop redis-master
Start-Sleep 12
docker exec -it redis-replica2 redis-cli set hacked 1
# 期望：NOREPLICAS Not enough good replicas to write.（写入被拒——数据丢失窗口被锁死）
docker start redis-replica1; docker start redis-master
```

## 4. 面试连接

**Q：哨兵的架构与工作原理？**
> 分层答：① 监控层：3 个哨兵进程，每秒 ping，down-after-milliseconds 超时 → SDOWN；② 确认层：quorum 个哨兵同意 → ODOWN（防单哨兵网络抖动误判）；③ 决策层：哨兵间 Raft 式投票选 Leader，Leader 执行 failover；④ 挑选层：replica-priority → offset 最大 → runid 最小三轮打分；⑤ 收尾层：切换从库指向 + Pub/Sub 通知客户端。强调"我搭过 3 哨兵并 kill 主库记录了完整时间线，+sdown 到 +switch-master 约 10 秒"。

**Q：为什么哨兵至少 3 个？quorum 怎么设？**
> 奇数个（3/5）：Leader 选举需要多数派，2 个哨兵挂 1 个就凑不齐多数；3 个容忍 1 个故障。quorum 通常设 N/2+1（3 哨兵设 2）：只影响 ODOWN 判定门槛，Leader 选举永远要多数派——所以"quorum=1 也不能单哨兵部署"。

**Q：哨兵模式会丢数据吗？怎么防脑裂？**
> 会：复制异步（day09），且旧主网络分区时可能还在收写。防脑裂三板斧：主库 min-replicas-to-write=1 + min-replicas-max-lag=10（失去从库就拒写）；down-after-milliseconds 别太短（误切）；客户端订阅 +switch-master 及时切流。我做过实验：掐掉所有从库后主库返回 NOREPLICAS 拒写——脑裂窗口被物理消灭。

## 5. 今日验收清单

- [ ] 3 哨兵 + 一主二从拓扑跑通（num-other-sentinels:2）
- [ ] kill 主库完整时间线记录（sdown→odown→leader→switch）
- [ ] 旧主回归自动降级为从（日志/role 证据）
- [ ] 脑裂防护配置与 NOREPLICAS 实验记录
- [ ] `git add . && git commit -m "day10: sentinel failover"`

---
[← Day 09](day09-Redis主从复制.md) | [本月目录](README.md) | [Day 11 · 集群 Cluster →](day11-集群Cluster.md)
