# Day 17 · 流量防护四件套：限流、熔断、降级、隔离

> **今日目标**：掌握防护四件套的分布式升级（集群限流/熔断参数推导/降级分层/舱壁进阶）；Sentinel 集群流控落地——M6 day08 单机四算法的分布式答卷。
> **时长**：原理 1.5h / 集群限流落地 2h / 四件套联动演练 1.5h
> **今日产出**：集群限流架构 + 熔断参数表（有推导依据）+ 四件套联动压测报告

## 1. 知识地图

```
M6 day08 学的是"单机四算法"（计数器/滑窗/漏桶/令牌桶），今天的灵魂拷问：
  限流 1000 QPS 配在 3 个实例上——是 3×1000 还是 1000/3？——都不对，是没想清楚
  → 分布式限流 = 集中计数（独立 Token Server）or 本地预估（均分+加成）

四件套的分布式形态（今天的四道防线）：
① 集群限流（Cluster Flow Control）：
  方案 A：Sentinel Token Server——独立发号，全局精确，但 TS 本身是单点（要部署 2+）
  方案 B：Redis+Lua 集中计数——滑动窗口存 zset，INCR+EXPIRE，精度换简单
  方案 C：本地均分+动态加成——LB 均匀时近似全局（无中心依赖，兜底首选）
  选型：核心链路 A（精确）+C（TS 挂了本地兜底）；边缘接口 B 足够
② 熔断（参数有推导，不是默认值一把梭）：
  慢调用比例：RT>200ms 判慢（依据 day05 预算表库存 P99 120ms×1.5），
    比例>50% 且统计窗口≥20 请求 → 熔断 10s → 半开放 3 个探测
  异常比例/异常数：支付回调这类低频接口用异常数（次数阈值）而非比例（样本太小）
  推导原则：阈值=预算表的告警线，熔断时长=依赖恢复 P99×2，半开探测=小流量试探
③ 降级（分层清单，不是一句"降级"）：
  读降级：详情页营销位→静态兜底；库存→"查询中"；评价→隐藏
  写降级：积分发放→MQ 堆积延迟；日志→采样丢弃；通知→合批发
  不可降级：支付回调（宁可排队不可丢）→ 背压（限流保护自己）
④ 隔离（舱壁的分布式延伸）：
  线程池隔离（核心/非核心）→ 进程隔离（秒杀独立部署 mall-seckill）→ 集群隔离
  （秒杀挂了不影响常规购——day08 秒杀模块独立的架构理由）

联动推演（压测验证的剧本）：
  大促洪峰 → 网关集群限流 3000/s（第一道闸）→ 下游营销服务 RT 飙升
  → 慢调用熔断 10s（第二道闸）→ 降级规则接管（无优惠展示，详情页活着）
  → 积分写请求进 MQ 排队（第三道闸削峰）——四道闸各司其职，用户端"降级但可用"
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Cluster Flow Control | 集群限流（Token Server 发号） |
| Circuit Breaker | 熔断器（开/半开/闭三态） |
| Slow Call Ratio | 慢调用比例熔断 |
| Degradation Ladder | 降级分层（读/写/不可降级） |
| Bulkhead (Process Level) | 进程级舱壁（独立部署） |
| Backpressure | 背压（保护不可降级链路） |

## 3. 动手实操：集群限流与四件套联动

```java
// ① Redis+Lua 集中限流（方案 B——滑窗 zset，简单够用的集群限流）
private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>("""
    redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1] - ARGV[3])  -- 清窗外
    local n = redis.call('ZCARD', KEYS[1])
    if n >= tonumber(ARGV[2]) then return 0 end                     -- 超限拒绝
    redis.call('ZADD', KEYS[1], ARGV[1], ARGV[1] .. '-' .. math.random())
    redis.call('PEXPIRE', KEYS[1], ARGV[3])
    return 1
    """, Long.class);   // ARGV: now(ms), limit, windowMs —— 全局滑窗精确计数
public boolean allow(String api, int limit, long windowMs) {
    Long r = redis.execute(LIMIT, List.of("rl:" + api),
        String.valueOf(System.currentTimeMillis()), String.valueOf(limit), String.valueOf(windowMs));
    return r != null && r == 1;
}
// ② Sentinel 慢调用熔断（参数从预算表推导，注释即依据）
// DegradeRule: 资源=stock-query, RT=200ms（预算表 P99 120ms×1.5 缓冲）
//   ratio=0.5, minRequest=20（样本太小的比例无意义）, timeWindow=10s（恢复 P99×2）
// ③ 降级分层清单落在 Feign fallback（M6 day06 骨架，今天补分层语义）：
@FeignClient(name = "mall-promotion", fallback = PromotionFallback.class)
public interface PromotionClient {
    Product get(Long id);   // fallback: 返回 PromoDTO.noPromo()（读降级：无优惠展示）
}
// 积分发放不走 Feign——直接发 MQ（写降级：延迟而非失败，M5 削峰语义）
```

```text
四件套联动压测剧本（docs/ha/four-shields-report.md）：
脚本：wrk 5000 并发打详情页 + 人为让营销服务 RT 注入 800ms
预期与实测：
  T+0s   网关集群限流生效：5000 并发中 3000/s 放行（Redis 计数命中）
  T+5s   营销 RT 全线超 200ms：慢调用比例破 50%，minRequest 20 满足 → 熔断开启
  T+5s~  降级接管：详情页营销位展示兜底（"优惠查询中"），成功率回到 99.9%
  T+15s  半开放 3 探测：若注入撤除 → 逐步放量恢复；未撤 → 回到熔断
  全程 DB QPS 曲线平缓（限流闸在前）——用户视角"慢但可用"，系统视角"有界降级"
结论写进报告：每道闸的触发时间点截图 + 闸门顺序合理性分析
```

```powershell
# 压测联动演练：
docker run --rm williamyeh/wrk -t8 -c500 -d60s http://host.docker.internal:8080/api/product/detail/1001
# 营销服务注入延迟（调试端点或 Sentinel 规则）后重复——观察熔断-降级时序
# Redis 限流计数验证：
docker exec -it redis redis-cli ZCARD rl:/api/product/detail    # ≈ limit（窗口内请求数）
git add . ; git commit -m "day08-17: four shields"
```

## 4. 面试连接

**Q：单机限流和分布式限流的区别？怎么做集群限流？**
> 单机限流（Sentinel 默认）计数在 JVM 内，3 个实例配 1000 QPS 实际放行 3000——LB 均匀时误差可控，但 LB 倾斜或突发不均时保护失效。分布式限流三种做法按成本排：①Sentinel Token Server 独立发号——全局精确计数，适合核心链路，代价是 TS 部署与可用性（至少 2 台互备）；②Redis+Lua 集中计数——zset 滑窗，INCR 判断原子完成，简单可靠，我们商城详情页用的这个，代价是每请求多一次 Redis RT（100ms 预算内）且 Redis 挂了要定策略（放行还是拒绝，我们选择"放行+本地兜底阈值"）；③本地均分近似——limit/实例数 各自单机限流，零依赖但依赖 LB 均匀，作为 TS/Redis 全挂的兜底。生产答案从来不是三选一，是核心精确、边缘从简、兜底常备的分层组合。

**Q：熔断参数怎么定？拍脑袋吗？**
> 不是，全部从 day05 的超时预算表和压测数据推导。慢调用阈值 RT=200ms——依据是预算表里库存 P99 120ms，留 1.5 倍缓冲，超过就说明下游异常；比例 50% 且 minRequest 20——样本小于 20 的比例是噪声（20 个请求 11 个慢就熔断会误杀），统计窗口要覆盖这个样本量；熔断时长 10s——依据依赖服务恢复的 P99（从故障到恢复的实测分布），太短反复熔断震荡、太长浪费可恢复期；半开放 3 个探测请求——小流量试探，成功才逐步放量。低频接口（支付回调每秒几个请求）用异常数阈值而非比例——样本太少比例失真。一句话：熔断参数是预算表的衍生品，预算表改了熔断参数要跟着 review，这也是把 M6 的"对账"思想用到配置上。

**Q：降级的边界怎么定？什么不能降级？**
> 分三层清单管理。读降级最宽松：营销位给静态兜底、库存展示"查询中"、评价直接隐藏——用户拿到的页面少个模块但能用。写降级次之：积分发放改 MQ 排队（延迟而非失败）、日志采样丢弃、通知合批——数据不丢，只是慢。不可降级层：支付回调宁可排队背压也不能丢（丢了就是对账灾难），秒杀预扣失败宁可明示"抢购失败"不能静默吞掉。判据是"用户诉求是否达成+数据是否可追"：读降级用户还能买到东西，写降级数据最终会到，不可降级层两个都不满足。还有一条常被忽略：降级要有开关演练——M6 day13 的预案不是文档是肌肉记忆，我们每月把每条降级规则手动触发一遍，验证兜底内容真的可用。

## 5. 今日验收清单

- [ ] Redis+Lua 集群限流落地（全局滑窗 zset）
- [ ] 熔断参数表（每参数标注推导依据）
- [ ] 降级三层清单（读/写/不可降级）进 code review 规范
- [ ] 四件套联动压测报告（时序截图+闸门顺序分析）
- [ ] `git add . && git commit -m "day08-17: cluster flowctl"`

---
[← Day 16](day16-冗余与故障转移.md) | [本月目录](README.md) | [Day 18 · 异地多活 →](day18-异地多活.md)
