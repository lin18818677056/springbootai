# Day 11 · 计数与排行榜：高并发计数一致性

> **今日目标**：掌握高并发计数（点赞/浏览/收藏）的分层方案；zset 排行榜与更新风暴治理；精确与模糊计数的选型（INCR vs HyperLogLog）——商城商品点赞与热销榜落地。
> **时长**：计数分层 1.5h / 排行榜实现 2h / 选型对比 1.5h
> **今日产出**：计数链路设计图 + zset 榜单服务 + 计数选型表（精确/模糊/近似）

## 1. 知识地图

```
计数是"最简单的业务 + 最难的并发"：like:{id} 一个 key 被 10 万/s INCR——day09 写热点的典型病例

分层计数方案（写热点三板斧在计数场景的完整应用）：
L1 本地聚合：LongAdder 累加（JVM 内无锁），每 500ms 批量 flush 一次 Redis
  ——10 万/s 的 INCR 变成 2 次/s × 并发实例数，Redis 压力除以 50000
L2 Redis 计数分桶（极端热点才用）：like:{id}:{0..3} 四桶各自 INCR，读时 Σ
L3 异步回写 DB：消费计数消息攒批 → UPDATE product SET like_cnt = like_cnt + N
  ——DB 里的是"准实时值"，展示容忍 1 分钟误差；关账时对账修正
取舍：计数的价值在"趋势与排序"，不在"分毫不差"——容忍误差换吞吐是本 day 主线

排行榜（zset，M4 学过的跳表结构这里大规模用）：
  board:hot → zset，member=productId，score=热度分（浏览×1+点赞×3+下单×10）
  ZINCRBY 增分 / ZREVRANGE 取 TopN / ZREVRANK 查名次（我的排第几）
  三种榜的工程差异：
  实时榜（热销 Top100）：zset 直读，成员 10 万级无压力
  周期榜（日榜/周榜）：key 带日期 board:hot:20260923，整点/零点切换
    ——切换风暴：零点所有读请求涌向新 key（缓存 miss）→ 提前 5 分钟预热
  大成员榜（亿级用户积分榜）：单 zset 撑不住（内存+ZRANK 变慢）
    → 分片榜：按用户尾数 100 片，片内排名 + Σ前面片的大小 = 全局名次

精确 vs 模糊选型（今日选型表）：
  点赞数/库存 → 精确（INCR/分桶）：影响用户决策与资金
  浏览量/PV → 近似（HyperLogLog：亿级 UV 仅 12KB，误差 0.81%）
  在线人数/日活 → UV 用 HLL，DAU 精确值走离线统计（对账用）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Counter Aggregation | 计数聚合（本地攒+批量 flush） |
| INCR / ZINCRBY | 原子自增 / 有序集增分 |
| Leaderboard | 排行榜（zset 实现） |
| Rank Sharding | 榜单分片（亿级成员拆片） |
| HyperLogLog | 基数估算（0.81% 误差/12KB） |
| Eventual Accuracy | 最终精确（实时近似+关账对账） |

## 3. 动手实操：点赞分层计数与热销榜

```java
// ① 点赞：L1 本地聚合 + L3 异步回写（10 万/s 点赞的商城落地）
@Service
public class LikeCounter {
    private final ConcurrentHashMap<Long, LongAdder> buffer = new ConcurrentHashMap<>();
    private final ScheduledExecutorService flusher =
        Executors.newSingleThreadScheduledExecutor();      // 单线程 flush，天然合并

    public void like(long productId) {
        buffer.computeIfAbsent(productId, k -> new LongAdder()).increment();  // JVM 内无锁
        return;                                            // 用户侧立即成功（202）
    }
    @PostConstruct void start() { flusher.scheduleWithFixedDelay(this::flush, 500, 500, MILLISECONDS); }
    void flush() {
        buffer.forEach((id, adder) -> {
            long n = adder.sumThenReset();
            if (n > 0) redis.opsForValue().increment("like:" + id, n);   // 500ms 攒一次
        });
    }
    // ② 热销榜：zset 周期榜 + 零点预热（防切换风暴）
    public List<String> topHot(int n, LocalDate day) {
        String key = "board:hot:" + day;                    // 日榜 key 带日期
        return redis.opsForZSet().reverseRange(key, 0, n - 1);
    }
    @Scheduled(cron = "0 55 23 * * ?")                       // 提前 5 分钟预热明日榜
    public void warmup() {
        redis.opsForZSet().add("board:hot:" + LocalDate.now().plusDays(1), "-1", 0);
    }   // 新 key 有数据后，零点切换的读请求全部命中（无穿透风暴）
}
// ③ 浏览量（模糊）：HLL 每日 UV
// redis.opsForHyperLogLog().add("uv:" + today, uid);  →  pfCount 误差 0.81%，12KB 扛亿级
```

```text
计数链路图（docs/counter/design.md）：
点赞请求 → AppService(202 立即返回) → LongAdder(JVM)
  → 500ms flush → Redis INCR(带 N) → MQ/定时任务 → DB 攒批 UPDATE(+N)
  → 每小时对账 job：ΣRedis 各商品计数 vs DB like_cnt，差值>阈值告警修正
实测：10 万/s 点赞，Redis 命令量 = 实例数×2 次/s（原 10 万次/s），DB 每小时 1 次批量
选型表（docs/counter/selection.md）：
| 指标   | 方案          | 精度     | 理由                    |
|-------|--------------|---------|------------------------|
| 点赞数 | LongAdder+INCR | 精确     | 影响用户决策            |
| 浏览PV | HLL          | 0.81%   | 展示用，成本优先        |
| 库存   | Lua 预扣(day08)| 精确     | 资金安全，绝不模糊      |
| 热度分 | zset 权重合成  | 近似     | 排序用途，误差不敏感    |
```

```powershell
# 验证三连：
docker exec -it redis redis-cli ZADD board:hot:20260924 99 "1001"
docker exec -it redis redis-cli ZREVRANGE board:hot:20260924 0 9 WITHSCORES
docker exec -it redis redis-cli PFADD uv:20260924 "u1" "u2" "u1" ; docker exec -it redis redis-cli PFCOUNT uv:20260924   # 2（去重）
# 压测点赞接口（10 万/s 目标，观察 Redis 命令量在 monitor 中是否骤减）：
docker run --rm williamyeh/wrk -t8 -c500 -d30s http://host.docker.internal:8080/api/like/1001
git add . ; git commit -m "day08-11: counter & leaderboard"
```

## 4. 面试连接

**Q：10 万 QPS 的点赞怎么设计？**
> 分层漏斗思路和读缓存同构，只是方向在写侧。第一层 JVM 内聚合：LongAdder 无锁累加，接口立即返回 202，10 万/s 在单机内部消化；第二层定时 flush：500 毫秒把各商品增量一次性 INCR 到 Redis——Redis 看到的命令量从 10 万/s 变成实例数×2 次/s，这就是 day09 写热点治理的"聚合回写"变体；第三层异步落库：计数变更进 MQ 攒批更新 DB，展示容忍分钟级误差；最后每小时对账修正，保证最终精确。关键决策是"哪些计数必须精确"：点赞展示精确（影响购买决策），浏览量用 HyperLogLog 近似（0.81% 误差，12KB 扛亿级），库存绝不能模糊（资金安全，走 day08 的 Lua 预扣）。设计计数系统先问精度要求，再谈吞吐。

**Q：排行榜怎么做？亿级用户的排行榜呢？**
> 常规榜 Redis zset 一把梭：member 是商品/用户 ID，score 是热度分（多指标加权合成：浏览×1+点赞×3+下单×10），ZINCRBY 增分、ZREVRANGE 取 TopN、ZREVRANK 查"我排第几"，10 万成员内性能无忧。两个进阶问题：①周期榜切换风暴——日榜 key 带日期，零点新 key 冷启动全部读穿透，方案是提前 5 分钟定时预热（我们 23:55 给明天的 key 写种子数据）；②亿级成员单 zset 撑不住——内存几十 GB 且 ZRANK 变慢，方案是分片榜：按用户尾数拆 100 片，先查所在片排名，再 Σ 前面所有片的成员数得全局名次，读全局 Top100 则是各片 Top100 归并。本质还是那句话：热点不会消失，只能被移动——这里是把"一个 zset"移到"一百个小 zset"。

**Q：为什么浏览量不用 INCR 精确计数？**
> 成本收益不匹配。浏览量的业务价值是"趋势展示"（月销 1 万+），0.81% 的误差用户无法感知，但精确计数要付出三层代价：每次浏览一次 Redis INCR（10 万/s 打热点 key）、持久化成本、回写 DB 的写入放大。HyperLogLog 用 12KB 固定内存扛任意基数，pfadd 幂等去重天然适配 UV 场景。当然边界要清楚：HLL 只能"估算基数"（有多少不同的人来过），不能"判定某人是否来过"（那是布隆过滤器，day09 防穿透用的）也不能"精确统计总数"（对账口径的精确 PV 走日志离线统计）。三种结构三件事：zset 排序、HLL 估算基数、布隆判存在——选型先问业务问的是哪个问题。

## 5. 今日验收清单

- [ ] 点赞三层计数落地（LongAdder→500ms flush→异步落库+对账）
- [ ] 热销日榜（zset+零点预热防切换风暴）
- [ ] HLL UV 实操（pfadd/pfcount 去重验证）
- [ ] 计数选型表（精确/模糊/资金三类对号入座）
- [ ] `git add . && git commit -m "day08-11: counter design"`

---
[← Day 10](day10-Feed流设计.md) | [本月目录](README.md) | [Day 12 · 签到与位图 →](day12-签到与位图.md)
