# Day 16 · 冗余与故障转移：从单点到多活的地基

> **今日目标**：掌握冗余三级（实例/组件/机房）与故障转移机制（RTO/RPO/脑裂）；商城全组件冗余架构落地；故障转移演练（kill 主节点实测切换时间）。
> **时长**：原理 1.5h / 商城冗余落地 2h / 切换演练 1.5h
> **今日产出**：商城冗余架构图（含 RTO/RPO 标注）+ 三组件故障转移演练报告

## 1. 知识地图

```
冗余是高可用的第一性原理：任何"单点"都是定时炸弹——按爆炸半径分三级治理：

第一级 实例冗余（无状态服务，day04 的直接延伸）：
  N+1 实例 + LB 四层/七层——kill 任意实例用户无感知（已演练过三无感知）
  关键认知：无状态是"能冗余"的前提——session 在实例上，冗余个寂寞

第二级 组件冗余（有状态组件的复制机制，M4/M5 存量的深化）：
  Redis：哨兵（Sentinel，3 节点仲裁，主挂自动切从，RTO ~30s）
         / Cluster（分片+副本，容量与可用一起解决）
  MySQL：主从半同步复制（M4 day16）——主挂切从（VIP/MHA/Orchestrator），RTO ~1min
  RocketMQ：NameServer 无状态多节点，Broker 主从（Dledger RAFT 自动选主）
  ES/其他：分片+副本天然冗余
  每个组件都要回答两个数：RTO（多久恢复服务）RPO（丢多少数据）

第三级 机房/地域冗余（day18 异地多活的前置认知）：
  同城双机房：RTT<2ms，同步复制，可防机房级故障（光缆被挖/火灾）
  异地多活：跨城 RTT 30ms+，异步复制，要面对数据一致性难题（day18 深入）

故障转移的核心矛盾——脑裂（Split-Brain）：
  场景：主从网络分区，两边都认为对方挂了 → 双主双写 → 数据分叉
  防线：仲裁多数派（哨兵 3 节点选主需 2 票）+ fencing（旧主降级：
    MySQL super_read_only / Redis slaveof 重定向 / 应用侧分布式锁挡写）
  铁律：任何自动切换都必须有"防双主"设计，否则切换=事故放大器

RTO/RPO 的业务映射（商城目标值，今天的产出依据）：
  订单/支付：RTO<1min RPO=0（半同步+对账补偿）——资损红线
  商品/营销：RTO<5min RPO<1min（异步复制可接受，缓存可重建）
  日志/统计：RTO 随意 RPO 随意——别为非核心付核心的成本
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Redundancy | 冗余（实例/组件/机房三级） |
| Failover | 故障转移（自动切换） |
| RTO / RPO | 恢复时间/数据丢失目标 |
| Split-Brain | 脑裂（双主双写） |
| Quorum / Fencing | 多数派仲裁 / 旧主隔离 |
| Semi-sync Replication | 半同步复制（至少一从确认） |

## 3. 动手实操：商城冗余架构与切换演练

```text
商城冗余架构图（docs/ha/mall-redundancy.md，标注 RTO/RPO）：
  Gateway ×2（LB 后）→ 订单×3/商品×3/秒杀×2（N+1，无状态，RTO=秒级 LB 摘除）
  Redis：1主2从+3哨兵（RTO~30s，RPO=异步丢秒级→预扣数据可由 DB 桶重建）
  MySQL：1主2从半同步（RTO~1min via Orchestrator，RPO=0 交易库/异步 库存快照库）
  RocketMQ：2 NameServer + Broker 2主2从 Dledger（RTO~10s 自动选主）
  Nacos：3 节点集群（AP 模式，注册中心挂了客户端本地缓存兜底）
  ——每个箭头都写上"挂了会怎样、多久回来、丢什么"才算架构图，否则是组件清单

# 演练三连（今天的核心实操——每个组件 kill 主实测）：
```

```powershell
# ① Redis 哨兵切换演练（预期：~30s 内完成切主，写中断窗口<30s）：
docker exec -it redis-master redis-cli DEBUG sleep 0        # 先确认当前主
docker stop redis-master                                    # 模拟主宕机
# 观察：docker logs -f redis-sentinel-1 → "+sdown master"→"+odown"→"+switch-master"
Start-Sleep -Seconds 45 ; docker exec -it redis-sentinel-1 redis-cli INFO sentinel | Select-String "master0"
# 记录：检测 10s + 仲裁 5s + 切换 15s ≈ 30s（写入 mall-redundancy.md）
docker start redis-master          # 旧主回来：确认自动变 slave（哨兵下发 slaveof）
# ② MySQL 主从切换（Orchestrator 演练，或手动 stop 主观察从库提升）：
docker stop mysql-master ; # 观察 orchestrator UI / 日志：topology recovery 事件与耗时
# 记录 RTO；验证新主可写后，应用连接池自动重连（HikariCP 重试）
# ③ 应用层自愈验证：kill 订单实例（docker stop mall-order-2）→ Nacos 摘除→LB 流量切走
#    → 用户无感知（重试策略 day04 已配）→ 重启实例后流量均匀回归
git add . ; git commit -m "day08-16: failover drill"
```

```java
// 应用侧对"切换窗口"的韧性配合（day17 深入，今天先立骨架）：
// ① Redis 切换 30s 窗口内：业务降级读——缓存 miss 不打挂 DB（舱壁+快速失败）
// ② 切换后连接池重连：Lettuce 拓扑刷新 + HikariCP 重试（maxLifetime 已配 29min）
// ③ MySQL 切换 RPO=0 的保证：半同步（rpl_semi_sync_master_wait_for_slave_count=1）
//    ——代价：写延迟 +1 次网络 RT（~1ms），交易库付得起，库存快照库付不起（用异步）
```

## 4. 面试连接

**Q：讲讲你们的 Redis 高可用方案？主挂了会发生什么？**
> 一主二从三哨兵。切换过程拆开讲：主宕机后，哨兵先 sdown（主观下线，单哨兵 ping 不通），达到 quorum 后升级 odown（客观下线），然后哨兵间选 leader 执行 failover：按优先级+offset 挑新主（数据最新的从），其余从 slaveof 新主，最后改写客户端路由。实测整个窗口约 30 秒。两个关键补充：①防脑裂——三哨兵仲裁多数派，网络分区时少数派无法单方面切换；应用侧再加分布式锁挡写，旧主回归被强制降为 slave（哨兵下发 slaveof）。②切换窗口的业务韧性——30 秒写不可用，我们的秒杀预扣会失败重试到从库读+降级提示，核心是这 30 秒不能雪崩到 DB（舱壁隔离+快速失败）。这个回答的关键是把"组件自动切"和"应用侧配合"讲成一套组合拳，而不是只背哨兵原理。

**Q：什么是脑裂？怎么防？**
> 脑裂是网络分区下主从失联、双方都认为对方挂了，各自接受写入，网络恢复后出现双主数据分叉——数据层面不可自动合并，是灾难级故障。防线三层：①仲裁多数派——哨兵/RAFT/Dledger 都要求 quorum（3 节点拿 2 票）才能执行切换，分区内少数派永远凑不齐票，从机制上保证"同时只有一个新主"；②fencing 隔离旧主——切换完成后强制旧主降级（Redis 哨兵下发 slaveof、MySQL 设 super_read_only），旧主的残余写入被物理拒绝；③应用侧兜底——关键写操作带分布式锁/版本号，双主极端场景下脏写被业务层拦截。一个容易被追问的点：为什么哨兵要 3 个不是 2 个——2 个节点分区后各 1 票都不到多数派，谁都切不了，形同虚设；奇数+≥3 是仲裁集群的通用配置法。

**Q：RTO 和 RPO 是什么？你们怎么定的？**
> RTO 是故障后多久恢复服务（时间维度），RPO 是最多丢多少数据（数据维度），两者独立且常常冲突——要 RPO=0 就得同步复制（写延迟高），要 RTO 极短就得自动化切换（误切换风险）。我们按业务分级定：交易链路（订单/支付）RPO=0 不可妥协（半同步复制，写多付 1ms 延迟），RTO<1 分钟（Orchestrator 自动切）；商品营销类 RPO<1 分钟可接受（异步复制），RTO<5 分钟；日志统计类随意。定级的依据是资损与舆情影响，不是技术炫技——为日志库配半同步和为交易库配异步，都是错的。演练是这套数字可信的唯一方式：我们 kill 主实测过 Redis 30 秒、MySQL 1 分钟，演练报告里每级的实测值与目标值的差距就是下一步改进项。

## 5. 今日验收清单

- [ ] 冗余架构图（每组件标注 RTO/RPO 与故障行为）
- [ ] Redis 哨兵切换演练（实测 ~30s，旧主自动降级验证）
- [ ] MySQL 主从切换演练（RTO 记录+RPO 口径）
- [ ] 脑裂三层防线能脱稿讲（仲裁/fencing/应用兜底）
- [ ] `git add . && git commit -m "day08-16: redundancy drill"`

---
[← Day 15](day15-可用性度量.md) | [本月目录](README.md) | [Day 17 · 流量防护四件套 →](day17-流量防护四件套.md)
