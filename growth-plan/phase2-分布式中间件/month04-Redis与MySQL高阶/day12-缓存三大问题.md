# Day 12 · 缓存三大问题：穿透 / 击穿 / 雪崩（全套治理手段）

> **今日目标**：缓存架构最经典的三连问。今天不是背名词，而是每题先复现事故再上全套解药（事前/事中/事后），并手写两个核心组件：简化布隆过滤器（穿透）+ 互斥锁重建模板（击穿）——秒杀项目周直接复用。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：三问题复现记录 + 布隆过滤器 + 互斥锁重建代码 + 三问题对照卡

## 1. 知识地图

```
三大问题的本质区别（先分清再治）：
  穿透：查【不存在】的数据 → 缓存永远没有 → 全部打 DB（恶意攻击最爱）
  击穿：某个【热点 key】过期瞬间 → 并发全打到 DB（单点故障）
  雪崩：【大量 key】同时过期 或 Redis 整体宕机 → DB 洪峰（系统性故障）
  记忆：穿透=查无此人 / 击穿=明星塌房 / 雪崩=集体塌房

穿透治理（三层防线）：
  事前  参数校验：id<=0 直接打回（成本最低）
        布隆过滤器：把"存在的 id"预加载 → 拦在缓存之前（可能误判存在，绝不漏判不存在）
  事中  缓存空值：DB 查不到 → set null 60s（防同一个 key 反复穿透）
  事后  风控限流：同 IP/用户 QPS 限制（配合网关）

击穿治理（保热点单点）：
  方案A 互斥锁：只放 1 个线程去 DB 重建，其他线程自旋等待（保证 DB 单飞）
        代码模板见 3.3（分布式锁 day14 正式版）
  方案B 逻辑过期：value 里存过期时间，发现"逻辑过期"不删 key，
        拿锁异步重建，期间返回旧数据（牺牲一致性保可用，热点推荐）
  方案C 永不过期：纯靠后台刷新（极热 key 专用）
  选型：能容忍短暂旧数据→B（无阻塞）；必须新数据→A（有等待）

雪崩治理（系统级防灾）：
  事前  TTL 加随机抖动：ttl = base + random(0, 300s) → 错峰过期
        多级缓存：本地 Caffeine + Redis + DB（month02 缓存行概念的应用层版）
        高可用：哨兵/Cluster（day10/11）防 Redis 自身宕机
  事中  熔断限流降级：DB 前加 Sentinel 网关限流（day26 秒杀实战）
  事后  快速恢复：预热脚本 + 报警兜底

布隆过滤器原理（30 秒版）：
  写入：k 个哈希函数 → 置位 k 个 bit
  查询：k 个位全 1 → "可能存在"；任一位 0 → "一定不存在"
  特点：有误判率（说不存在绝不骗你；说存在可能骗你）→ 只能挡穿透不能当索引
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Cache Penetration | 缓存穿透 | 查不存在的数据打穿缓存 |
| Cache Breakdown | 缓存击穿 | 热 key 失效瞬间洪峰 |
| Cache Avalanche | 缓存雪崩 | 大批 key 同时失效 |
| Bloom Filter | 布隆过滤器 | 概率型集合，判"一定不在" |
| Logical Expiration | 逻辑过期 | 不删 key，value 内藏过期时间 |
| Mutex Rebuild | 互斥锁重建 | 单线程回源，其余等待 |

## 3. 动手实操

### 3.1 主菜：手写简化布隆过滤器（挡穿透）

```java
// SimpleBloomFilter.java —— JDK BitSet + 3 个哈希（教学版）
import java.util.BitSet;
import java.util.function.Function;

public class SimpleBloomFilter {
    private final BitSet bits = new BitSet(1 << 22);          // 400 万 bit ≈ 0.5MB
    // 3 个哈希函数：把同一字符串搅出 3 个不同下标
    private static final Function<String, Integer>[] HASHES = new Function[]{
            s -> (s.hashCode() & 0x7fffffff) % (1 << 22),
            s -> (s.hashCode() * 31 & 0x7fffffff) % (1 << 22),
            s -> (s.hashCode() * 131 & 0x7fffffff) % (1 << 22)
    };

    public void add(String key) {
        for (Function<String, Integer> h : HASHES) bits.set(h.apply(key));
    }
    public boolean mightContain(String key) {
        for (Function<String, Integer> h : HASHES)
            if (!bits.get(h.apply(key))) return false;        // 任一位 0 → 一定不存在
        return true;                                          // 全 1 → 可能存在
    }

    public static void main(String[] args) {
        SimpleBloomFilter bf = new SimpleBloomFilter();
        // 预加载"合法商品 id"（生产：全量/增量同步进来）
        for (int i = 1; i <= 100_000; i++) bf.add("sku:" + i);

        // ① 正常请求：大概率命中（可能误判，但放过无害——顶多查一次 DB）
        System.out.println("sku:50000 → " + bf.mightContain("sku:50000"));    // true
        // ② 恶意穿透请求：负数 id/乱拼 id → 拦截！
        System.out.println("sku:-1 → " + bf.mightContain("sku:-1"));          // false 拦下
        System.out.println("sku:999999999 → " + bf.mightContain("sku:999999999")); // false 拦下
        // ③ 验证"绝不漏判"：已加入的 10 万个全部必须 true
        long miss = java.util.stream.IntStream.rangeClosed(1, 100_000)
                .filter(i -> !bf.mightContain("sku:" + i)).count();
        System.out.println("漏判数（必须=0）: " + miss);
    }
}
// 生产替代：Redis 4+ 的 RedisBloom 模块 / Guava BloomFilter（调参误判率）
```

```powershell
cd D:\mywork\springbootai\learning\month04-redis-mysql
javac -d out SimpleBloomFilter.java; java -cp out SimpleBloomFilter
```

### 3.2 复现击穿：热 key 过期瞬间的 DB 洪峰

```powershell
# 造热 key，TTL 3 秒，过期瞬间 20 个并发请求同时打 DB（观察计数）
docker exec -it redis-learning redis-cli set hot:sku1 "detail-json" EX 3
# 3 秒后立刻并发查询（用 redis-cli 循环模拟业务侧"缓存没有→查DB"）
Start-Sleep 3
1..20 | ForEach-Object -Parallel {
  $v = docker exec redis-learning redis-cli get hot:sku1
  if ($v -eq "(nil)") { "MISS→查DB" }
} -ThrottleLimit 20
# 期望：20 个 MISS 同时出现 → 20 个请求全部打向 DB（击穿现场！）
# （生产里这 20 个 MISS 就是 20 条 SELECT——DB 直接被打疼）
```

### 3.3 主菜②：互斥锁重建模板（治击穿，秒杀周复用）

```java
// HotPointCache.java —— 击穿治理模板：锁重建 + 双重检查
import java.util.concurrent.*;

public class HotPointCache {
    // 用 ConcurrentHashMap 模拟"分布式锁"（真分布式锁 day14 上 Redis）
    static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();
    static int dbQueryCount = 0;                                  // 观察 DB 被打次数

    static String getWithRebuild(String key) throws Exception {
        String v = CACHE.get(key);
        if (v != null) return v;                                  // ① 缓存命中，直接回
        Object lock = LOCKS.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {                                     // ② 同 key 排队
            v = CACHE.get(key);                                   // ③ 双重检查！
            if (v != null) return v;                              //    前人已重建，白排队但没打 DB
            v = "detail-of-" + key + "-from-DB";                  // ④ 只有第一名查 DB
            dbQueryCount++;
            Thread.sleep(50);                                     // 模拟 DB 慢查
            CACHE.put(key, v);
            return v;
        }
    }

    public static void main(String[] args) throws Exception {
        CACHE.put("hot:sku1", "x"); CACHE.remove("hot:sku1");     // 模拟刚过期
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(20);
        for (int i = 0; i < 20; i++) {
            pool.execute(() -> {
                try { getWithRebuild("hot:sku1"); }
                catch (Exception ignored) {}
                finally { latch.countDown(); }
            });
        }
        latch.await();
        System.out.println("20 并发下 DB 查询次数（无治理=20，锁重建=1）: " + dbQueryCount);
        pool.shutdown();
    }
}
// 输出：dbQueryCount=1 → 击穿被互斥锁消灭（对照 3.2 的 20 连击）
// 秒杀项目将把 synchronized 换成 Redis SETNX 锁（day14），模板不变
```

```powershell
javac -d out HotPointCache.java; java -cp out HotPointCache
```

### 3.4 雪崩治理演示（TTL 抖动一行代码）

```powershell
# 基础 TTL 3600s + 随机 0~300s 抖动 → 大批 key 天然错峰
1..5 | ForEach-Object {
  $ttl = 3600 + (Get-Random -Maximum 300)
  docker exec redis-learning redis-cli set "batch:$_" v EX $ttl
  docker exec redis-learning redis-cli ttl "batch:$_"
}
# 观察：5 个 key 的 TTL 各不相同 → 不会同一秒集体过期
# 配套：Redis 自身高可用（day10 哨兵）+ DB 前限流（day26 Sentinel）双保险
```

## 4. 面试连接

**Q：缓存穿透/击穿/雪崩的区别和解决方案？**
> 三个词各配一句根因+三层方案。穿透：查不存在的数据；方案=参数校验→布隆过滤器→缓存空值（短 TTL）。击穿：单热 key 过期；方案=互斥锁重建（强一致）或逻辑过期（高可用），加分句"我写过双重检查模板，20 并发实测 DB 只被打 1 次"。雪崩：大批 key 同失效或 Redis 宕机；方案=TTL 随机抖动、多级缓存、哨兵/Cluster 高可用、熔断限流。最后总结一句："三题共同的底层逻辑是——不要让缓存失效的代价瞬间集中转移到 DB。"

**Q：布隆过滤器为什么能防穿透？有什么代价？**
> 它把"查无此人"提前到缓存之前：不存在的 key 一定返回"不存在"（绝不漏判），恶意随机 id 直接拦截。代价：① 误判——存在的可能被判不存在（放过无害，顶多多查一次 DB），误判率随容量/哈希数可调；② 不支持删除（计数布隆可解）；③ 需要预加载数据集并同步更新。生产用 RedisBloom 模块或 Guava。

**Q：逻辑过期和互斥锁怎么选？**
> 看业务对"旧数据"的容忍度。逻辑过期：请求永不阻塞，返回旧值+异步重建——适合资讯/推荐类（旧几秒无所谓）；互斥锁：等待重建完成拿新值——适合库存/价格类（必须新）。秒杀里两者混用：商品详情逻辑过期，库存扣减走互斥锁+Lua 原子操作。

## 5. 今日验收清单

- [ ] 布隆过滤器跑通（漏判数=0）
- [ ] 击穿复现（20 连击 MISS）+ 锁重建修复（DB=1）对照记录
- [ ] TTL 抖动脚本执行记录
- [ ] 三问题对照卡默写（事前/事中/事后）
- [ ] `git add . && git commit -m "day12: cache trinity"`

---
[← Day 11](day11-集群Cluster.md) | [本月目录](README.md) | [Day 13 · 缓存一致性 →](day13-缓存一致性.md)
