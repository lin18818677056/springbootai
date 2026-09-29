# Day 05 · 内存管理与八种淘汰策略（LRU vs LFU 实验）

> **今日目标**：Redis 是内存数据库，内存就是生命线。今天搞懂近似 LRU/LFU 的实现巧思、八种淘汰策略怎么选，并用 maxmemory 实验亲手触发淘汰——面试"你的缓存库配什么淘汰策略"从此有理有据。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：淘汰策略实验记录 + LRU/LFU 对比观察 + 策略选型卡

## 1. 知识地图

```
内存水位管理三件套：
  maxmemory      内存上限（生产必配！默认 0=不限制直到吃光系统内存）
  INFO memory    used_memory / maxmemory_human / mem_fragmentation_ratio
  淘汰策略 maxmemory-policy  超限后怎么办

八种淘汰策略（2 维 × 4 行，★背矩阵）：
  ┌────────────┬──────────────────────────────────┐
  │ 不淘汰       │ noeviction（默认）：写报错 OOM     │
  │ 全库范围     │ allkeys-lru / allkeys-lfu / allkeys-random │
  │ 仅有过期时间 │ volatile-lru / -lfu / -random / volatile-ttl │
  └────────────┴──────────────────────────────────┘
  记忆：[no] + [allkeys|volatile] × [lru|lfu|random] + [volatile-ttl]
  选型直觉：纯缓存=allkeys-lru/lfu；缓存+持久混存=volatile-*（只赶可丢的）

近似 LRU（Redis 不用真 LRU 的原因与巧思）：
  真 LRU 要全局双向链表——每读一个 key 都要动链表，太贵
  Redis 采样法：每个 key 的 redisObject 里藏 24bit 时钟（秒级）
    淘汰时随机采 N 个（maxmemory-samples 默认 5），挑 idle 最大的删
  结果：近似 LRU，效果接近真 LRU（论文级验证），成本 O(1)

LFU（4.0+，更贴"热度"语义）：
  redisObject 里 24bit 拆成：16bit 分钟级时间戳 + 8bit 对数计数器
  对数计数：访问一次 lfu 不一定 +1，概率 1/(lfu×lfu_factor+1) 递增
    → 计数范围 0~255 也能表示百万次访问（morris 近似计数）
  衰减：每过 lfu-decay-time(默认 1) 分钟 lfu-1 —— 历史热点会冷却
  LRU 的坑：偶发扫描把真热点挤掉（污染）；LFU 按频次扛扫描

内存碎片：
  mem_fragmentation_ratio = used_memory_rss / used_memory
    1.0~1.5 正常；>1.5 碎片严重；<1 说明用了 swap（危险！）
  activedefrag yes 主动整理（在线搬移，Jemalloc 特性）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Eviction | 淘汰 | 内存超限时的清理 |
| Approximated LRU | 近似 LRU | 采样 5 个挑最久未用 |
| LFU Counter | LFU 计数 | 对数计数 + 时间衰减 |
| Fragmentation | 内存碎片 | rss/used 比值判读 |
| Idle Time | 空闲时长 | LRU 的排序依据 |
| Swap | 交换分区 | 碎片比 <1 的危险信号 |

## 3. 动手实操

### 3.1 八种策略速览 + 配置实验台

```powershell
docker exec -it redis-learning redis-cli
#   CONFIG GET maxmemory-policy            # 当前：noeviction
#   CONFIG SET maxmemory 1mb               # 实验室：限 1MB 触发淘汰
#   CONFIG SET maxmemory-policy allkeys-lru
INFO memory
#   重点看：used_memory / maxmemory_human / mem_fragmentation_ratio / maxmemory_policy
```

### 3.2 触发 LRU 淘汰实验（亲眼看谁被赶走）

```powershell
# 灌 2MB 数据（每个 value 约 1KB）：
$pipe = (1..2000 | ForEach-Object { "SET lru:k$_ $('x'*1000)`r`n" }) -join ""
$pipe | docker exec -i redis-learning redis-cli
docker exec redis-learning redis-cli DBSIZE              # <2000：被淘汰了一部分
docker exec redis-learning redis-cli INFO stats | Select-String evicted
#   evicted_keys > 0 ★淘汰发生
# 先访问一部分 key 制造"冷热"再灌第二轮，观察被淘汰的是不是冷 key：
docker exec redis-learning redis-cli MGET lru:k1 lru:k2 lru:k3   # 热一下前几个
$pipe2 = (2001..4000 | ForEach-Object { "SET lru:k$_ $('x'*1000)`r`n" }) -join ""
$pipe2 | docker exec -i redis-learning redis-cli
docker exec redis-learning redis-cli EXISTS lru:k1 lru:k2 lru:k3   # 还在吗？
# 记录：热 key 存活情况（近似 LRU 大概率保住刚访问的）
```

### 3.3 LRU vs LFU 行为对比（一次扫描实验看差别）

```powershell
# 场景：k1~k10 是高频热 key；一次性灌入 2000 个"扫描型"冷 key
docker exec redis-learning redis-cli CONFIG SET maxmemory-policy allkeys-lru
#   灌热 key + 频繁访问，再灌 2000 个新的 → 观察 k1 存活率 ____%
docker exec redis-learning redis-cli CONFIG SET maxmemory-policy allkeys-lfu
#   重置实验（DEL 旧 key + FLUSHDB 后重来）：灌热 key 访问 20 次，再灌扫描 key
#   观察 k1 存活率 ____%（LFU 下应显著更高）
# 结论记录：LRU 怕"一次扫描"，LFU 认"频次"——写入笔记
# 实验完恢复正常配置：
docker exec redis-learning redis-cli CONFIG SET maxmemory 0
docker exec redis-learning redis-cli CONFIG SET maxmemory-policy noeviction
docker exec redis-learning redis-cli FLUSHDB
```

### 3.4 碎片率体检 + 策略选型卡

```powershell
docker exec redis-learning redis-cli INFO memory | Select-String "fragmentation"
#   mem_fragmentation_ratio:____  （对照 §1 判读标准写结论）
```

```text
策略选型卡（抄笔记）：
| 场景                | 推荐                   | 理由                    |
|---------------------|------------------------|-------------------------|
| 纯缓存库            | allkeys-lfu            | 热度导向扛扫描污染      |
| 缓存+不能丢的混存   | volatile-lru           | 只赶有过期时间的        |
| 持久化主库(慎用)    | noeviction             | 宁可报错不静默丢数据    |
| TTL 优先业务        | volatile-ttl           | 快过期的先走            |
红线：noeviction 报错要被监控捕获；volatile-* 若没人设 TTL = 永不淘汰=假死
```

## 4. 面试连接

**Q：Redis 的 LRU 是怎么实现的？为什么不用标准 LRU？**
> 先讲为什么不用：标准 LRU 需要全局双向链表，每次访问都要 O(1) 搬移节点+维护指针——单线程下这些开销累计可观，且多一倍指针内存。Redis 的近似法：key 结构里带 24bit 最近访问时钟，淘汰时随机采样 maxmemory-samples 个（默认 5），驱逐 idle 最大的。官方数据：样本 10 时接近真 LRU。加分句："我还做过 LRU vs LFU 的扫描污染对比实验，LFU 下热 key 存活率明显更高。"

**Q：LRU 和 LFU 怎么选？LFU 的计数器为什么要用对数+衰减？**
> 场景答：偶尔被批量扫一遍的（报表拉全量）用 LFU——它按频次不按最近；热点平稳的用 LRU 足够。LFU 设计两个巧思：① 对数计数器（8bit 表百万次访问，概率性递增 1/(count×factor+1)）——内存只花 8bit；② 分钟级衰减（每 lfu-decay-time 分钟 -1）——去年热点不占今年名额。"8bit 装下百万热度"这个设计句是亮点。

**Q：mem_fragmentation_ratio 是 2.3 说明什么？怎么治？**
> 碎片严重：操作系统分配给 Redis 的 RSS 是其逻辑用量的 2.3 倍——大量 free 过的内存滞留在 allocator 手里（常见于大量 DEL/过期后的库）。治法：① activedefrag yes 开在线整理（Jemalloc 搬移，需 CONFIG SET active-defrag 相关阈值）；② 业务侧避免大量变更长度的操作（append 大 value）；③ 低峰重启重建（从 RDB/AOF 恢复内存紧凑）。<1 则是 swap 报警，比碎片更紧急。

## 5. 今日验收清单

- [ ] 八种策略矩阵默写
- [ ] LRU 触发实验（evicted_keys > 0 截图）
- [ ] LRU vs LFU 扫描对比实验记录（两个存活率数字）
- [ ] 碎片率体检 + 选型卡默写
- [ ] `git add . && git commit -m "day05: eviction & memory"`

---
[← Day 04](day04-对象系统与编码.md) | [本月目录](README.md) | [Day 06 · 事务与 Lua →](day06-事务与Lua.md)
