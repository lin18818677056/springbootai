# Step 2：商品详情与缓存体系（多级缓存 + 三大缓存问题防护）

> **本步目标**：实现秒杀商品详情接口，构建 Caffeine + Redis 两级缓存，做好缓存穿透/击穿/雪崩防护与缓存预热，把详情接口做到本机 3000+ QPS。

## 前置术语

| 英文 | 中文 |
|------|------|
| Multi-level Cache | 多级缓存 |
| Cache Penetration | 缓存穿透（查不存在的数据，每次都打到 DB） |
| Cache Breakdown | 缓存击穿（热点 key 过期瞬间，并发全打 DB） |
| Cache Avalanche | 缓存雪崩（大量 key 同时过期） |
| Warm-up | 预热（提前加载热点数据进缓存） |
| Logical Expiration | 逻辑过期（物理不过期，值里带过期时间） |

## 1. 多级缓存读取链路

```
请求 → Caffeine(本地, TTL 10s) → 命中?返回
      → Redis(TTL 30min+随机)  → 命中?回填Caffeine并返回
      → MySQL                  → 回填两级缓存
```

本地缓存选 Caffeine（Java 8+ 主流，W-TinyLFU 淘汰算法命中率高）：

```java
@Bean
public Cache<Long, GoodsVO> goodsCache() {
    return Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(10))   // 本地短TTL，保证最终一致
            .recordStats()                               // 开启命中率统计
            .build();
}
```

## 2. 读链路实现

```java
public GoodsVO getGoodsDetail(long goodsId) {
    // 1. 本地缓存
    GoodsVO vo = goodsCache.getIfPresent(goodsId);
    if (vo != null) return vo;

    // 2. Redis（穿透防护：缓存空对象 60s）
    String key = "seckill:goods:" + goodsId;
    String json = redisTemplate.opsForValue().get(key);
    if (json != null) {
        if ("".equals(json)) throw new BizException("商品不存在"); // 空值哨兵
        vo = JSON.parseObject(json, GoodsVO.class);
        goodsCache.put(goodsId, vo);
        return vo;
    }

    // 3. 击穿防护：互斥重建（只放一个线程去查库）
    String lockKey = "lock:goods:" + goodsId;
    Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofSeconds(3));
    try {
        if (Boolean.TRUE.equals(locked)) {
            Goods db = goodsMapper.selectById(goodsId);
            if (db == null) {
                redisTemplate.opsForValue().set(key, "", Duration.ofSeconds(60)); // 空值
                throw new BizException("商品不存在");
            }
            vo = GoodsVO.from(db);
            redisTemplate.opsForValue().set(key, JSON.toJSONString(vo),
                    Duration.ofSeconds(1800).plusSeconds(ThreadLocalRandom.current().nextLong(600))); // 随机TTL防雪崩
            goodsCache.put(goodsId, vo);
            return vo;
        }
        // 4. 没抢到锁：短暂自旋等别人建好
        Thread.sleep(50);
        return getGoodsDetail(goodsId);   // 递归重查（实际项目建议重试次数上限）
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new BizException("查询繁忙");
    } finally {
        if (Boolean.TRUE.equals(locked)) redisTemplate.delete(lockKey);
    }
}
```

## 3. 三大缓存问题与本项目对策（面试标准答案）

| 问题 | 本项目对策 | 原理 |
|------|-----------|------|
| 穿透 | 空值缓存 60s + （可选）布隆过滤器 | 不存在的数据也缓存，挡住恶意 id 扫描 |
| 击穿 | 互斥锁重建 + 本地缓存兜底 | 同一 key 只有一个线程查库 |
| 雪崩 | TTL 加随机 0~600s + 多级缓存 + Sentinel 兜底 | 过期时间打散，DB 压力可熔断 |

## 4. 缓存预热（Warm-up，秒杀开始前必做）

活动开始前 5 分钟，把所有秒杀商品加载进两级缓存：

```java
@Component
public class CacheWarmUpRunner implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) {
        // 生产环境应由调度任务在活动开始前触发，这里演示启动时预热
        goodsMapper.selectSeckillList().forEach(g -> {
            String key = "seckill:goods:" + g.getGoodsId();
            redisTemplate.opsForValue().set(key, JSON.toJSONString(GoodsVO.from(g)),
                    Duration.ofHours(2));
        });
        log.info("秒杀商品缓存预热完成, size={}", ...);
    }
}
```

## 5. 一致性说明（为什么详情缓存可以接受短暂不一致）

- 详情页数据（名称/图片/时间）变化频率低，10s 本地 + 30min Redis 的**最终一致**完全可接受。
- 但**库存字段绝不走详情缓存**！库存走 step03 的 Redis 预扣（实时扣减），详情里展示的"剩余件数"仅做展示，以扣减结果为准。**展示数据与交易数据分离**是重要架构决策。

## 验收清单

- [ ] 详情接口三级回源链路（本地→Redis→DB）逻辑正确
- [ ] 用 JMeter 压详情接口 3000 并发 30s：QPS ≥ 3000，DB QPS ≈ 0（看 MySQL 监控）
- [ ] 手工测试三种问题：查不存在的 id（穿透防护）、删除 Redis key 后并发查（击穿防护）
- [ ] 预热日志可见

## 压测数据记录表（示例格式，填你的真实数据）

| 压测项 | 并发 | QPS | P99 | DB 查询次数/秒 |
|--------|------|-----|-----|---------------|
| 无缓存直查 | 100 | | | |
| 仅 Redis | 100 | | | |
| 两级缓存 | 100 | | | |

## 常见坑

1. **Caffeine 缓存对象可变性**：缓存里的对象若被调用方修改，会污染缓存——返回前深拷贝或用不可变对象（record）。
2. **空值与正常值混淆**：空值哨兵建议用独立短 TTL，或值里加标记字段，避免业务 bug。
3. **递归重查无上限**：加最大重试 3 次，否则锁等待异常时可能栈深暴涨。

> 完成后进入 `step03-秒杀核心-防超卖与限流.md`。
