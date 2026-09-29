# Day 02 · 读多写少三板斧：多级缓存漏斗

> **今日目标**：设计五层缓存漏斗（浏览器→CDN→网关→本地 Caffeine→Redis→DB）并标注每层命中率；落地 Caffeine+Redis 两级缓存；用 M6 RED 指标监控漏斗。
> **时长**：漏斗推演 1.5h / 两级缓存落地 3h / 监控接入 0.5h
> **今日产出**：商品详情读链路图（含每层命中率）+ 两级缓存代码

## 1. 知识地图

```
读多写少的本质：让"绝大多数读请求"在离用户最近的地方结束——漏斗模型：
  ① 浏览器缓存（Cache-Control）：静态资源/JS/CSS——命中率 ~60%（回访用户）
  ② CDN：图片/视频/静态页——商品图 95% 命中（边缘节点就近返回）
  ③ 网关缓存（Gateway 局部）：热门商品页整页缓存——命中 ~30%（穿透部分）
  ④ 本地缓存 Caffeine：Top 热点商品详情 JSON——命中 ~70%（单机万级 QPS，零网络）
  ⑤ Redis：全量商品详情——命中 ~99%（集群百万 QPS）
  ⑥ DB：只剩 1% 的"冷数据/缓存击穿"流量——撑住即可
  漏斗乘法验证（DB 最终压力）：
  假设 1 万 QPS 打到后端（CDN 已挡住静态）：网关挡 30% → 7000
  Caffeine 挡 70% → 2100；Redis 挡 99% → 21 QPS 进 DB ——漏斗的价值直观呈现

两级缓存的工程要点（Caffeine+Redis 组合）：
  Caffeine 放什么：Top N 热点（热点探测：1 小时访问 Top100——day09 热 Key 治理联动）
  容量纪律：W-TinyLFU 驱逐 + maximumSize 上限（防 OOM）+ expireAfterWrite 5~10s（短 TTL 保新鲜）
  一致性：Redis 更新后"广播失效"本地缓存（MQ/Redis Pub-Sub——秒级一致足够）
  防击穿：Caffeine 天然挡掉对同一热点的并发重建（单机内互斥）；跨机用分布式锁（day09）

缓存容量规划（Redis 内存估算模板）：
  商品详情 JSON 平均 2KB × 100 万商品 × 1.3（碎片/开销系数）≈ 2.6GB
  → 4GB 实例单主即可；集群按 hash slot 水平扩（M4 dayXX 分片的场景化回收）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Cache Funnel | 缓存漏斗（逐层拦截读流量） |
| Hit Ratio | 命中率（每层拦截比例） |
| L1 / L2 Cache | 本地缓存 / 远程缓存 |
| W-TinyLFU | Caffeine 驱逐算法（频率+新鲜度） |
| Broadcast Invalidation | 广播失效（本地缓存一致性） |
| Capacity Planning | 容量规划（内存估算） |

## 3. 动手实操：两级缓存落地

```java
// ① 依赖：spring-boot-starter-cache + caffeine（Gradle 一行）
// implementation 'com.github.ben-manes.caffeine:caffeine:3.1.8'

// ② 两级缓存服务（adapter 层——领域零感知，day23 装饰器思想复用）
@Service
public class ProductCacheService {
    private final Cache<Long, String> local = Caffeine.newBuilder()
        .maximumSize(1_000)                                   // Top1000 热点，防 OOM 红线
        .expireAfterWrite(java.time.Duration.ofSeconds(10))   // 短 TTL：脏窗口 10s 内
        .recordStats()                                        // 命中率统计（接监控）
        .build();
    private final StringRedisTemplate redis;

    public String getProduct(Long id) {
        String hit = local.getIfPresent(id);                  // L1：零网络，纳秒级
        if (hit != null) return hit;
        String json = redis.opsForValue().get("p:detail:" + id);   // L2：毫秒级
        if (json != null) { local.put(id, json); return json; }
        return loadAndFill(id);                               // 回源 DB（带互斥，见下）
    }
    private String loadAndFill(Long id) {
        // 互斥重建：分布式锁防缓存击穿（同 id 并发回源只有 1 个线程）
        Boolean locked = redis.opsForValue().setIfAbsent("lock:p:" + id, "1", java.time.Duration.ofSeconds(3));
        try {
            if (Boolean.TRUE.equals(locked)) {
                var detail = productGateway.findDetail(id);   // 领域端口（M7 六边形）
                if (detail == null) {                         // 防穿透：空值缓存 60s
                    redis.opsForValue().set("p:detail:" + id, "", java.time.Duration.ofSeconds(60));
                    return "";
                }
                String json = Json.dumps(detail);
                redis.opsForValue().set("p:detail:" + id, json, java.time.Duration.ofHours(1));
                local.put(id, json);
                return json;
            }
            Thread.sleep(20);                                 // 未抢到锁：等一下读别人填的缓存
            return java.util.Optional.ofNullable(redis.opsForValue().get("p:detail:" + id)).orElse("");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return ""; }
        finally { if (Boolean.TRUE.equals(locked)) redis.delete("lock:p:" + id); }
    }
    // 失效广播：Redis Pub-Sub 订阅 "cache-invalidate" → local.invalidate(id)
    // （写链路更新 Redis 后 publish；10s TTL 兜底，最终一致窗口 ≤10s）
}
```

```powershell
# 漏斗验证与监控（复用 M6 Prometheus——RED 的 Cache 扩展）：
# ① 命中率暴露：Caffeine recordStats → hitRate() 注册 Gauge 指标 cache_local_hit_ratio
# ② 压测观察漏斗：ab/wrk 打 1000 QPS → Grafana 看：
#    local 命中率（~70%）→ Redis QPS（~300）→ DB QPS（~5）三层指标同屏
docker run --rm -d --name wrk -w /wrk williamyeh/wrk -t4 -c200 -d60s http://host.docker.internal:8080/api/product/1
# ③ 防击穿演练：Redis 删 key 后并发 200 请求 → DB 回源日志只出现 1 次（互斥生效截图）
```

## 4. 面试连接

**Q：商品详情页怎么设计缓存方案？**
> 五层漏斗逐层设防：静态资源走浏览器 Cache-Control 和 CDN（命中率 95%+）；网关层可选整页缓存；应用内 Caffeine 本地缓存 Top1000 热点（maximumSize 上限+10 秒短 TTL+W-TinyLFU 驱逐），单机命中 70%，零网络开销扛万级 QPS；Redis 存全量详情（2KB×100 万≈2.6GB 容量算过），命中 99%；DB 只剩 1% 冷流量。三道防护配套：穿透用空值缓存 60 秒，击穿用分布式锁互斥重建，雪崩用 TTL 加随机抖动。一致性用"更新 Redis 后 Pub-Sub 广播失效本地缓存"，加上 10 秒 TTL 兜底，脏窗口可控在秒级——商品详情这个业务完全接受。整个漏斗我有 Grafana 三层指标同屏：local 命中率、Redis QPS、DB QPS，压测验证 1000 QPS 进来 DB 只有 5。

**Q：本地缓存和 Redis 怎么分工？本地缓存最大的坑是什么？**
> 分工看"热度和一致性容忍度"：Caffeine 只放 Top 热点（热点探测取 1 小时 Top100），短 TTL，吃"零网络+万级 QPS"的红利；Redis 放全量，承担准实时一致性。最大的坑是多实例本地缓存不一致——8 个实例各自为政，同一商品可能展示 8 种价格。三个解法按成本递增：短 TTL 自然过期（脏窗口= TTL）；更新时 Pub-Sub/MQ 广播失效（秒级一致）；或热点 key 挂版本号。我们的选择是广播失效+10 秒 TTL 双保险。另一个隐性的坑是容量——Caffeine 不设 maximumSize 就是 OOM 定时炸弹，这道红线我们写进了 code review checklist。

**Q：缓存命中率怎么提？到多少算够？**
> 先测再提：命中率指标分层暴露（Caffeine recordStats、Redis keyspace hits/misses），Grafana 同屏看漏斗。提升三板斧：①TTL 调优——命中率低先看是不是过期太频繁，商品详情 1 小时合理；②预热——大促前把 Top 商品提前灌进 Redis 和本地缓存（day19 预热阶段的动作），避免冷启动打穿；③热点加码——探测到的 Top100 进 Caffeine，命中一次本地就省一次 Redis 网络往返。至于"多少算够"没有绝对数：我们商品读链路 Redis 层 99% 是及格线，DB QPS 绝对值才是最终裁判——命中率 90% 但 DB 剩 500 QPS 撑不住，比 99% 剩 20 QPS 差得多。看绝对值，不看虚荣指标。

## 5. 今日验收清单

- [ ] 商品详情读链路图（五层漏斗+每层命中率标注）
- [ ] Caffeine+Redis 两级缓存落地（含互斥重建+空值缓存）
- [ ] 击穿演练截图（DB 回源仅 1 次）
- [ ] 三层指标 Grafana 同屏（local/Redis/DB）
- [ ] Redis 容量估算写进估算文档
- [ ] `git add . && git commit -m "day08-02: cache funnel"`

---
[← Day 01](day01-并发度估算.md) | [本月目录](README.md) | [Day 03 · 写多读少三板斧 →](day03-写多读少三板斧.md)
