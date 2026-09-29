# Day 09 · 热点Key治理：发现、拆解与本地承接

> **今日目标**：掌握 Redis 四大问题（热点 key/大 key/穿透/雪崩）的发现与治理；本地缓存承接热点读；key 分片打散写热点——商城商品详情接入实测。
> **时长**：原理 1.5h / 发现工具实操 1.5h / 治理改造 2h
> **今日产出**：热点 key 治理方案（发现→定性→三选一治理）+ 大 key 清理报告

## 1. 知识地图

```
Redis 不是"上了就稳"：单 key 也能打爆单节点。四大问题一张图：

热点 key（读/写集中单 key → 单节点 CPU/带宽打满）：
  发现三招：①redis-cli --hotkeys（LFU 模式）②客户端埋点统计 TopN key
            ③monitor 采样（生产慎用，开销大）
  定性：读热点 or 写热点——治理路线完全不同
  读热点 → 本地缓存承接（day02 Caffeine 在前，热点 99% 不出 JVM）
  写热点 → key 分片打散（库存分桶，day08 已落地）：stock:{id}:{0..7}
  极端兜底 → 读写都热（如首页秒杀价）→ 多副本 key + 客户端随机读

大 key（单 key 值过大：string>10KB / hash>5000 field / list>5000 元素）：
  危害：慢查询阻塞单线程（del 百万元素 key 卡 2s）、主从同步风暴、内存倾斜
  发现：redis-cli --bigkeys（采样）/ RDB 离线分析（redis-rdb-tools）
  治理：①拆分（hash 按 field hash 拆多 key）②压缩（值 gzip/protobuf）
        ③删除用 UNLINK（异步）绝不 DEL——大 key 删除是事故高发区

穿透（查不存在的 key，缓存与 DB 都没有 → 每次都打 DB）：
  day02 已落两招：空值缓存（60s）+ 布隆过滤器（100 万 uid 误判率 1% 仅 1.2MB）

雪崩（大量 key 同时失效 or Redis 整体不可用）：
  同时失效 → TTL 加随机抖动（10min ± 60s）；整体不可用 → 熔断降级读本地/旧值（day17 深入）

治理优先级口诀：先发现（没有度量就没有治理）→ 再定性（读/写/大小）→ 后选刀
  ——最便宜的是本地缓存，最贵的是架构改分片，能用便宜的绝不上贵的
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Hot Key | 热点 key（访问集中） |
| Big Key | 大 key（值体积/元素过大） |
| Key Sharding | key 分片（后缀打散） |
| Local Cache Offload | 本地缓存承接 |
| Bloom Filter | 布隆过滤器（防穿透） |
| UNLINK vs DEL | 异步删 vs 同步删 |
| LFU | 最不经常使用淘汰（--hotkeys 依赖） |

## 3. 动手实操：发现与治理落地

```powershell
# 发现三招实操（docker 环境直接跑）：
docker exec -it redis redis-cli CONFIG SET maxmemory-policy allkeys-lfu   # 开 LFU
docker exec -it redis redis-cli --hotkeys                                 # TopN 热点 key
docker exec -it redis redis-cli --bigkeys -i 0.01                         # 大 key 采样（间隔 10ms）
# 商城实测记录（docs/redis/hotkey-report.md）：
#   热点：product:detail:1001（活动页入口商品，8 万 QPS 单 key）
#   大 key：user:coupon:95533（hash 3.2 万 field，某羊毛党囤券）——UNLINK 治理
```

```java
// 读热点治理：本地缓存前置承接（day02 ProductCacheService 的热点增强）
public ProductVO getProduct(Long id) {
    ProductVO v = caffeine.getIfPresent(id);        // L1：热点商品 99% 命中于此
    if (v != null) return v;
    return loadAndFill(id);                          // L2：Redis → DB（带互斥重建）
}
// 写热点治理：key 分片（与 day08 库存分桶同一把刀，通用化）
public boolean tryDeduct(String skuId, Long uid) {
    int bucket = uid.hashCode() & 7;                 // 8 桶：写压力 /8
    return Boolean.TRUE.equals(redis.opsForValue()
        .decrement("stock:" + skuId + ":" + bucket) >= 0);  // 简化示意，生产走 Lua
}
// 大 key 删除纪律：
// redis.unlink("user:coupon:95533")   -- 异步删，主线程不阻塞
// 拆分改造：coupon:{uid}:{hash(couponId) & 15} → 16 个小 hash
```

```text
热点 key 治理决策树（今天的产出，进 docs/redis/hotkey-runbook.md）：
发现热点 → 是读热点吗？
  ├─ 是 → 本地缓存能承接吗（数据量小+可容忍秒级不一致）？
  │        ├─ 能 → Caffeine 前置（首选，零额外组件）
  │        └─ 不能（一致性要求高）→ key 多副本（replica 0..N 随机读）
  └─ 是写热点 → 可分片吗（各分片独立语义：库存/计数）？
           ├─ 能 → 分桶（day08 模式），聚合读取方负责 Σ
           └─ 不能（全局唯一语义：全局库存）→ Redis 分片集群按 hash slot 天然分
              或上"预扣+回流"（排队器异步回写，牺牲实时一致性）
商城决策实例：product:detail:1001 读热点 → Caffeine 承接（10s TTL 可容忍）
             stock:1001 写热点 → 8 桶分片 + MQ 汇总落库（day08 已做）
```

## 4. 面试连接

**Q：线上发现 Redis 某个 key 特别热，怎么处理？**
> 先定性再选刀。读热点——首选本地缓存承接：热点数据通常量小且可容忍秒级不一致，我们商品详情用 Caffeine 10 秒 TTL，8 万 QPS 的热点商品 99% 在 JVM 内消化；一致性要求高的场景退而求其次用 key 多副本（replica:0..N 随机读，写时同步 N 份）。写热点——能分片语义的就分桶，比如库存按 uid hash 分 8 桶（day08 的秒杀做法），聚合语义交给读取方；不能分的全局语义就靠 Redis Cluster 的 hash slot 或预扣+异步回流。发现工具日常三件套：--hotkeys 看读热点、--bigkeys 看体积、客户端埋点看业务维度 TopN。原则是治理成本递增：本地缓存→多副本→分片→架构改造，能用便宜的绝不上贵的。

**Q：大 key 有什么危害？怎么治理？**
> 三个危害：①Redis 单线程处理命令，DEL 一个百万元素的 key 能阻塞 2 秒，期间所有请求排队；②主从同步和迁移时大 key 造成网络风暴与内存倾斜；③业务侧反序列化慢放大 RT。治理三步：发现（--bigkeys 采样 + RDB 离线分析）→ 拆分或压缩（hash 按 field 二次散列拆多 key，值用 gzip/protobuf）→ 删除必须 UNLINK 异步化。我们清过一例：羊毛党囤券把 user:coupon hash 撑到 3.2 万 field——先 UNLINK 掉存量，再把写入侧改成 16 个子 hash 的取模路由，写入读出都按规则路由，问题不再复发。这条"禁止 DEL 大 key"我们写进了 Redis 编码规范，code review 会查。

**Q：缓存穿透、击穿、雪崩的区别和方案？**
> 三个词一句话区分：穿透是"查不存在的数据"（缓存和 DB 都没有），攻击者用随机 id 打穿缓存——方案是空值缓存（60 秒）+ 布隆过滤器（把"不存在"挡在缓存之前，100 万 key 误判 1% 仅 1.2MB 内存）；击穿是"单个热点 key 过期瞬间"万千请求同时打 DB——方案是互斥重建（setIfAbsent 抢锁只放一个请求回源）或逻辑过期（物理永不过期+异步刷新）；雪崩是"大量 key 同时失效或 Redis 整体挂"——前者 TTL 加随机抖动错峰，后者靠高可用（哨兵/集群）+ 熔断降级（读本地缓存旧值兜底）。三者共同思想：DB 是最贵的资源，任何路径都要保证 DB 的请求量有上界。

## 5. 今日验收清单

- [ ] --hotkeys/--bigkeys 实操截图，热点与大 key 各 1 例
- [ ] 商品详情热点接入 Caffeine 承接（压测单 key 8 万 QPS 不打 Redis）
- [ ] 大 key UNLINK+拆分改造（3.2 万 field→16 子 hash）
- [ ] 治理决策树进 runbook（读热/写热/大key 三分支）
- [ ] `git add . && git commit -m "day08-09: hotkey & bigkey"`

---
[← Day 08](day08-秒杀抢购全景.md) | [本月目录](README.md) | [Day 10 · Feed流设计 →](day10-Feed流设计.md)
