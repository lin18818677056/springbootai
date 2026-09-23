# Step 3：秒杀核心 —— Lua 原子扣减防超卖 + Sentinel 限流防刷

> **本步目标**：实现秒杀最核心的两道防线：Redis Lua 原子扣减（防超卖+一人一单）与 Sentinel 接口限流（防系统被打垮），并完成防刷（令牌机制）。

## 前置术语

| 英文 | 中文 |
|------|------|
| Lua Script | Lua 脚本（Redis 端原子执行的脚本） |
| Oversell / Undersell | 超卖 / 少卖 |
| Rate Limiter | 限流器 |
| Token Bucket | 令牌桶 |
| Risk Control | 风控（防刷） |
| TPS (Transactions Per Second) | 每秒事务数 |

## 1. 为什么需要 Lua 原子扣减（先把原理讲透）

"查库存 → 判断 → 扣减"三步在并发下必然超卖：

```java
// 错误示范（非原子）
int stock = Integer.parseInt(redis.get("stock"));  // T1 读到 1
if (stock > 0) {                                    // T2 也读到 1
    redis.set("stock", String.valueOf(stock - 1));  // 两个都写 0 → 卖 2 件！
}
```

Lua 脚本在 Redis 单线程中**一次性执行完**，天然原子。核心脚本：

```lua
-- seckill.lua：校验活动时间 → 校验一人一单 → 原子扣库存
-- KEYS[1]=库存key  KEYS[2]=用户限购set  ARGV[1]=userId
if redis.call('EXISTS', KEYS[3]) == 0 then
    return -3                      -- 活动未初始化
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -2                      -- 重复下单（一人一单）
end
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock <= 0 then
    return -1                      -- 库存不足
end
redis.call('DECR', KEYS[1])        -- 原子扣减
redis.call('SADD', KEYS[2], ARGV[1])  -- 记录用户已购
return 1                           -- 成功
```

Java 侧执行（Spring Data Redis）：

```java
private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
static {
    SECKILL_SCRIPT = new DefaultRedisScript<>();
    SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
    SECKILL_SCRIPT.setResultType(Long.class);
}

public SeckillResult trySeckill(long goodsId, long userId) {
    Long r = redisTemplate.execute(SECKILL_SCRIPT,
            List.of(stockKey(goodsId), boughtKey(goodsId), initKey(goodsId)),
            String.valueOf(userId));
    return switch (r.intValue()) {
        case 1  -> SeckillResult.SUCCESS;
        case -1 -> SeckillResult.SOLD_OUT;
        case -2 -> SeckillResult.DUPLICATE;
        default -> SeckillError.NOT_STARTED;
    };
}
```

> **库存 key 的初始化**：活动开始时 `SET seckill:stock:{id} 1000`，与 DB 库存同值。Redis 扣的是"预扣库存"，真正的账以 DB 为准（step04 落库）。

## 2. 数据库兜底扣减（防超卖的第二道保险）

异步落库时的 DB 扣减 SQL（乐观锁思想，靠 where 条件保证不为负）：

```sql
UPDATE seckill_goods
SET stock_count = stock_count - 1
WHERE goods_id = #{goodsId} AND stock_count > 0
```

若返回影响行数 = 0（理论不该发生），说明 Redis 与 DB 出现偏差 → 告警 + 人工对账。**两级扣减互为保险**，这就是"不超卖"的双保险设计。

## 3. Sentinel 限流接入

```groovy
implementation 'com.alibaba.csp:sentinel-annotation-aspectj:1.8.8'
implementation 'com.alibaba.csp:sentinel-transport-simple-http:1.8.8'
```

注解式限流（QPS 模式 + 预热 Warm Up，秒杀开始流量陡增场景最适合预热）：

```java
@SentinelResource(value = "seckillOrder",
        blockHandler = "orderBlocked",
        blockHandlerClass = SeckillBlockHandler.class)
@PostMapping("/seckill/order")
public Result<OrderVO> order(@Valid @RequestBody OrderReq req) { ... }

// 拒绝时的友好返回（限流 ≠ 报错，要引导用户）
public static Result<OrderVO> orderBlocked(OrderReq req, BlockException ex) {
    return Result.fail(429, "当前排队人数过多，请稍后重试");
}
```

规则建议（压测后校准）：

| 资源 | 阈值 | 流控效果 | 理由 |
|------|------|---------|------|
| /seckill/order | 单机 1000 QPS | Warm Up 10s | 冷启动给 JVM/Redis 预热时间 |
| /seckill/result | 单机 2000 QPS | 快速失败 | 轮询接口要顶住 |
| /seckill/token | 单机 500 QPS | 排队等待 | 令牌接口匀速放行 |

## 4. 防刷设计（令牌机制 + 基础风控）

下单前必须先拿**一次性令牌**（Token），脚本无法直接刷下单接口：

```
1. GET /seckill/token → 生成 UUID 存 Redis（TTL 30s，一次性）
2. POST /seckill/order 携带 token → Lua 原子校验并删除 token（无效则拒绝）
3. 同一 userId 获取 token 频率限制：每秒 1 次（INCR + EXPIRE）
```

补充风控三板斧（面试可讲）：
- **设备指纹**（Device Fingerprint）：前端采集设备特征，黑名单拦截
- **行为验证**：答题/滑块验证码，削掉纯脚本 + 拉平手速
- **名单分层**：IP 级限流（网关）+ 用户级限购（Redis）+ 黑名单（风控库）

## 5. 降级预案（Redis 挂了怎么办）

| 故障 | 动作 | 说明 |
|------|------|------|
| Redis 超时率 > 10% | 切换"DB 直扣降级模式" | 限流阈值降到 500，直接走 DB 乐观锁扣减（吞吐低但正确） |
| Redis 完全不可用 | 活动熔断 | 返回"系统繁忙"，保住 DB |
| 恢复后 | 库存对账 Job | 比对 Redis 预扣 vs DB 实扣，偏差告警 |

> 降级开关放配置中心（Nacos），一键切换不重启——**限流防的是过载，降级保的是核心**。

## 验收清单

- [ ] 并发测试：JMeter 5000 线程抢 1000 库存，结果**精确卖出 1000 件、0 重复用户**
- [ ] Redis `GET stock` = 0 且 DB `stock_count` = 0，两边一致
- [ ] Sentinel 控制台可见限流曲线，被拒请求收到友好提示（非 500）
- [ ] 无 token 直刷下单接口被 100% 拒绝
- [ ] 能脱稿解释 Lua 脚本每一行的并发意义

## 常见坑

1. **Lua 里的类型**：`tonumber` 忘了写导致字符串比较错误；返回值统一 Long。
2. **KEYS 顺序**：集群模式下同一 Lua 的所有 key 必须同一 slot（本单机版无此问题，集群要用 hash tag `{goodsId}`）。
3. **令牌删除非原子**：校验+删除必须在一个 Lua 里，否则脚本可复用 token。
4. **限流阈值拍脑袋**：必须压测校准（单机极限 × 安全系数 0.8）。

> 完成后进入 `step04-消息队列削峰与订单.md`。
