# Day 11 · Redis Cluster（3 主 3 从 + 16384 槽分片实录）

> **今日目标**：哨兵只保高可用不扩容量，Cluster 才是"分片+高可用"合体。今天搭 3 主 3 从，理解 16384 槽路由、MOVED 重定向、hash tag 解决跨槽——面试题"为什么是 16384 个槽"现场就能答。
> **时长**：理论 1.5h / 实操 2h / 输出 0.5h
> **今日产出**：3 主 3 从集群跑通 + MOVED/hash tag 实验记录 + 槽路由图

## 1. 知识地图

```
什么时候上 Cluster（对照哨兵）：
  哨兵模式：数据量单机放得下，只要高可用 → 读写分离
  Cluster：  数据量超单机内存（如 100GB）/ 写入 QPS 单机扛不住 → 分片扩容
  ★分片怎么做：把 key 空间切成 16384 份（槽 slot），每节点管一部分

槽路由公式（★必背）：
  slot = CRC16(key) mod 16384
  节点0: 槽 0~5460    节点1: 槽 5461~10922    节点2: 槽 10923~16383
  （每个主节点各配一个从节点 = 6 节点，主挂从顶，哨兵逻辑内置）

客户端请求的两条路径：
  老办法 MOVED：client → 任意节点 → "这 key 不归我，去节点X"（返回 MOVED + 正确地址）
              → 客户端重发（多一次往返）→ 并更新本地槽表
  新办法 smart client：启动时拉全量槽位表（CLUSTER SLOTS）
              → 本地计算槽号 → 直连正确节点（主流客户端都这样）
  ASK（了解）：槽迁移中的临时重定向（MOVED 是永久的，ASK 是临时的）

为什么是 16384 不是 65536（★高频题，作者原话提炼）：
  ① 心跳包携带槽位图：16384 bit = 2KB；65536 bit = 8KB——心跳太胖浪费带宽
  ② 集群规模上限约 1000 节点，16384 个槽足够分（平均每节点 16+ 槽）
  ③ CRC16 本身只产生 16 位（65536），但没必要用满

跨槽问题（多 key 命令的限制）：
  mget k1 k2 → 若 k1/k2 不同槽 → CROSSSLOT 报错
  解药 hash tag：{user1000}.name 与 {user1000}.age
    CRC16 只算花括号里的部分 → 同 tag 必同槽 → mget/mset/事务可用
  （设计数据 key 时就用 tag 规划，是 Cluster 的开发纪律）

Gossip 协议（节点间怎么认识彼此）：
  meet：新节点握手入网
  ping/pong：随机挑节点交换"我知道的节点+槽"状态（指数扩散）
  fail：多数主认为某主挂 → 广播下线（对比哨兵的 ODOWN，内置了）
  优点：去中心化无单点；代价：最终一致（状态收敛有延迟）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Slot | 槽 | 16384 个，key 的分片单位 |
| Hash Tag | 哈希标签 | {} 内参与哈希，控制同槽 |
| MOVED | 永久重定向 | 槽已归别家，客户端更新槽表 |
| ASK | 临时重定向 | 迁移中借用，不更新槽表 |
| Gossip | 流言协议 | 去中心化状态传播 |
| Smart Client | 智能客户端 | 本地缓存槽表直连 |

## 3. 动手实操

### 3.1 主菜：Docker 3 主 3 从

```powershell
cd D:\mywork\springbootai\learning\month04-redis-mysql
# ① 起 6 个节点（同网络，容器内端口都 6379）
1..6 | ForEach-Object {
  docker run -d --name "redis-cn$_" --network redis-net `
    redis:7 redis-server --cluster-enabled yes `
    --cluster-config-file nodes.conf --cluster-node-timeout 5000
}
# ② 收集 6 个容器 IP
$ips = 1..6 | ForEach-Object {
  docker inspect "redis-cn$_" --format "{{.NetworkSettings.Networks.`"redis-net`".IPAddress}}"
}
$ips
# ③ 组建集群：每 1 个主配 1 个从（--cluster-replicas 1）
$addr = ($ips | ForEach-Object { "$_:6379" }) -join " "
docker exec -it redis-cn1 redis-cli --cluster create $addr --cluster-replicas 1
# 输出确认：[OK] All 16384 slots covered.（槽全覆盖）
# ④ 集群体检
docker exec -it redis-cn1 redis-cli cluster info
#   期望：cluster_state:ok / cluster_slots_assigned:16384 / cluster_known_nodes:6
docker exec -it redis-cn1 redis-cli cluster nodes
#   每行：节点id ip:port flags(master/slave) master节点id 槽范围
```

### 3.2 实验①：槽路由与 MOVED 重定向

```powershell
# 不加 -c：直连模式，key 不归该节点 → 看原始 MOVED
docker exec -it redis-cn1 redis-cli set foo bar
#   期望：(error) MOVED 12182 172.x.x.1:6379   （告诉你真身在谁家）
# 加 -c：cluster 模式，客户端自动跟随重定向（模拟 smart client）
docker exec -it redis-cn1 redis-cli -c set foo bar
docker exec -it redis-cn1 redis-cli -c get foo
#   日志显示 Redirected to slot [12182]（自动跳转成功）
# 手动验证公式：slot = CRC16(key) mod 16384
docker exec -it redis-cn1 redis-cli cluster keyslot foo
#   期望：12182（与 MOVED 报错里的槽号一致——公式闭环）
```

### 3.3 实验②：跨槽限制与 hash tag 解药

```powershell
# ① 跨槽 mget 报错
docker exec -it redis-cn1 redis-cli -c mset name jack age 20
#   期望：CROSSSLOT Keys in request don't hash to the same slot
# ② hash tag：花括号内容参与哈希 → 强制同槽
docker exec -it redis-cn1 redis-cli -c mset {u100}.name jack {u100}.age 20
#   成功（{u100} 同 tag → 同槽）
docker exec -it redis-cn1 redis-cli -c mget {u100}.name {u100}.age
# ③ 证明同 tag 同槽
docker exec -it redis-cn1 redis-cli cluster keyslot "{u100}.name"
docker exec -it redis-cn1 redis-cli cluster keyslot "{u100}.age"
#   两个槽号相同 → 同一个节点保管
```

### 3.4 实验③：主节点挂掉，集群自愈（可选）

```powershell
# 找一个从库多的主挂掉它（从 cluster nodes 里挑一个 master）
docker stop redis-cn2
Start-Sleep 12
docker exec -it redis-cn1 redis-cli cluster info | Select-String "state|fail"
# 期望：cluster_state:ok（从库已自动上位——内置哨兵生效）
# 恢复并重新入网：docker start redis-cn2（它回来会当原主的从）
```

## 4. 面试连接

**Q：Redis Cluster 的原理？为什么是 16384 个槽？**
> 路由：slot=CRC16(key) mod 16384，每个主节点负责一段槽区间；客户端两种模式——朴素重定向（MOVED 后重发）与 smart client（拉槽表本地路由）。一致性：Gossip 协议 ping/pong 交换节点与槽状态，故障判定内置多数派投票。16384 的原因（作者回答）：① 心跳携带槽位图 2KB 够小（65536 要 8KB）；② 集群上限约 1000 节点，16384 足够；③ 压缩效果好。加分句："我用 Docker 搭了 3 主 3 从，cluster keyslot 验证过 CRC16 公式，也复现过 CROSSSLOT 报错并用 hash tag 解决。"

**Q：Cluster、哨兵、客户端分片怎么选？**
> 数据单机放得下、只要可用性 → 哨兵（运维简单，多 key 命令随便用）；数据超单机内存或写入瓶颈 → Cluster（官方分片+内置高可用，代价：多 key 操作受槽约束、开发要守 hash tag 纪律）；客户端分片/代理（Twemproxy）→ 历史方案，扩容要停机重分布，已被 Cluster 取代。一句话："先哨兵后 Cluster，按数据量升级。"

**Q：Cluster 模式下事务和 Lua 还能用吗？**
> 有限制：所有 key 必须同一槽——用 hash tag 把事务/Lua 涉及的 key 规划进同一 tag（如 {order123}.stock、{order123}.user）。跨槽的用 Pipeline 拆发（不保证原子）。设计 key 命名规范时就内置 tag 规则，是 Cluster 项目的第一课。

## 5. 今日验收清单

- [ ] 3 主 3 从跑通（cluster_info: slots 16384 全覆盖）
- [ ] MOVED 重定向与 -c 自动跟随实验记录
- [ ] CROSSSLOT 报错 + hash tag 解药验证
- [ ] "为什么 16384"三个理由能脱稿讲
- [ ] `git add . && git commit -m "day11: redis cluster"`

---
[← Day 10](day10-哨兵Sentinel.md) | [本月目录](README.md) | [Day 12 · 缓存三大问题 →](day12-缓存三大问题.md)
