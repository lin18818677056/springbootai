# Day 25 · 秒杀模块总实战：mall-seckill 收口

> **今日目标**：把 W2-W4 的秒杀零件（Lua 预扣/分桶/集群限流/对账/影子压测）装配成完整 mall-seckill 模块；全量验收（正确性/防超卖/压测达标/预案可触发）——本月项目的总装日。
> **时长**：模块装配 2.5h / 全量验收 2.5h / 复盘沉淀 1h
> **今日产出**：mall-seckill v1.0（可跑通+压测达标）+ 验收报告 + 一图流架构定稿

## 1. 知识地图

```
总实战 = 零件总装：本月每天产出的是零件，今天装配并验收——工程能力的分水岭在"收口"

mall-seckill v1.0 全景（六边形架构，M7 规范落地）：
  adapter/web：SeckillController（答题校验+限流注解+染色透传）
  application：SeckillAppService（编排：校验→预扣→发 MQ→返回排队号）
  domain：SeckillActivity（聚合：活动/库存水位/状态机）
          SeckillPolicy（策略：分桶数/限流阈值/答题开关——配置驱动）
  adapter/redis：SeckillLuaService（day08 预扣+分桶）
  adapter/mq：OrderCreateConsumer（day03 攒批落库）+ RefundConsumer（day13 退回）
  adapter/db：SeckillRepository（影子表路由 day22）

本月伏笔的集中兑现（跨月连线，M6/M7 存量技能的总集成）：
  M7 day16 一事务一聚合 → 库存扣减不该在 Order 聚合事务里 → Redis 预扣+MQ 异步落库
  M7 day24 状态机 → 活动状态机（NOT_STARTED→ACTIVE→SOLD_OUT→ENDED）双重拦截
  M6 day17 TCC → 预扣=Try（Redis 弹珠）/确认=消费落库/取消=24h 退回——天然 TCC 骨架
  M6 day08 限流四算法 → day17 集群限流的网关闸
  M6 day19 对账 → day13 三方对账 job
  M4 Redis/>M5 MQ → 预扣/削峰全链路
全量验收六项（每项有数字，附录报告格式）：
  ① 正确性：100 并发抢 10 份 → 领取人数=10、无重复、总额守恒（day13 三查）
  ② 防超卖：预扣数+DB 扣减数对账平账（对账 job 绿）
  ③ 性能：预扣 9.8万/s（day24 复压数据）、下单链路 P99 140ms（day24 治理后）
  ④ 稳定性：kill 预扣实例→流量切走无感知（day04 三无感知）
  ⑤ 预案：限流/降级/队列页三个开关彩排触发（day19 清单）
  ⑥ 可观测：大盘四信号+对账 job+压测报告归档
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Module Integration | 模块总装（零件→系统） |
| Acceptance Test | 验收测试（六项数字达标） |
| Natural TCC | 天然 TCC（Try=预扣/Confirm=落库/Cancel=退回） |
| Config-driven Policy | 配置驱动策略（分桶/阈值热更） |
| Tag: mall-seckill-v1 | 版本封板（git tag） |

## 3. 动手实操：装配与验收

```java
// application 层编排（全部零件的最终合体——读一遍就能看到本月全部知识点）：
@Service
public class SeckillAppService {
    public QueueTicket seckill(long uid, long skuId) {
        activityGuard.check(skuId);                        // 状态机闸（M7 day24 双重拦截）
        if (!captchaService.verify(uid)) return QueueTicket.captchaFail();   // L1 答题
        if (!clusterLimiter.allow("seckill", 6000)) return QueueTicket.busy(); // L2 集群限流（day17）
        long bucket = bucketRouter.route(uid);             // 分桶路由（day08/09）
        long r = luaService.preDeduct(skuId, bucket);      // L3 Redis Lua 预扣（原子）
        if (r < 0) return QueueTicket.soldOut();
        var msg = new CreateOrderMsg(uid, skuId, StressContext.snapshot());  // 染色透传（day22）
        rocketMQTemplate.syncSend("seckill-order" + StressContext.topicSuffix(), msg); // L4 削峰
        refundScheduler.schedule(skuId, uid);              // 24h 兜底（day13 延迟退回）
        return QueueTicket.queued(msg.orderNo());          // 排队号→前端轮询状态
    }
}
// domain 层状态机（M7 day24 模式复用，枚举级 canTransferTo + 聚合级 transition 闸口）：
public enum ActivityStatus { NOT_STARTED, ACTIVE, SOLD_OUT, ENDED;
    private static final Map<ActivityStatus, Set<ActivityStatus>> ALLOWED = Map.of(
        NOT_STARTED, EnumSet.of(ACTIVE), ACTIVE, EnumSet.of(SOLD_OUT, ENDED), SOLD_OUT, EnumSet.of(ENDED));
    public boolean canTransferTo(ActivityStatus t) { return ALLOWED.getOrDefault(this, Set.of()).contains(t); }
}
```

```text
验收报告骨架（docs/seckill/acceptance-v1.md，六项全绿才封版）：
① 正确性：100 抢 10 → takers=10 ✓ LLEN=0 ✓ Σ金额=总额 ✓（截图三张）
② 防超卖：对账 job 输出 balance=0 ✓（Redis 剩余+DB 扣减=总库存）
③ 性能：预扣 9.8万/s（8 桶均衡<1.3）✓ 下单 P99 140ms ✓（day23/24 数据引用）
④ 稳定性：kill 1/2 实例 → 0 请求失败（LB 摘除+重试）✓
⑤ 预案：sw-seckill-queue / sw-promo-fallback / 网关限流 彩排触发 ✓
⑥ 可观测：Grafana 大盘+对账 job+压测报告归档 ✓
git tag mall-seckill-v1 && git push origin mall-seckill-v1   # 封版
```

```powershell
# 总装与验收执行：
cd D:\mywork\springbootai\practice-projects\02-microservice-mall\mall
.\gradlew.bat :mall-seckill:build :mall-seckill:test      # 单测（正确性/幂等/状态机）
# 端到端演练（防黄牛→抢到→落库→对账 全链路）：
for ($i=1; $i -le 100; $i++) { Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/seckill/1001?uid=$i" }
# 六项验收逐项执行（对应 docs/seckill/acceptance-v1.md 清单）
git add . ; git commit -m "day08-25: mall-seckill v1" ; git tag mall-seckill-v1
```

## 4. 面试连接

**Q：讲讲你的秒杀模块，从设计到验收。（项目深挖题——本月的核心弹药）**
> 按五层漏斗+总装验收讲。设计：五层流量漏斗每层有放行数字（答题摊峰→集群限流 6000/s→Redis Lua 预扣 9.8 万/s→MQ 削峰→DB 条件更新兜底）；库存分桶 8 份抗热点 key；工程上沿用六边形架构，策略（分桶数/阈值）配置驱动。关键技术决策三个：①为什么预扣放 Redis——把 MySQL 行锁瓶颈移到更便宜的地方，Lua 保证原子防超卖；②为什么说它天然是 TCC——Try 是 Redis 弹珠、Confirm 是消费落库、Cancel 是 24h 延迟退回，资金安全靠三方对账兜底；③为什么模块独立部署——进程级舱壁，秒杀挂了常规购不受影响。验收六项全数字：正确性（100 抢 10 三查）、对账平账、性能（9.8 万/s/P99 140ms）、稳定性（kill 实例无感）、预案彩排、可观测归档。踩过的坑：预扣曾有两跳 RTT 导致网络瓶颈（day24 案例），Lua 合并后达标——压测驱动优化的实证。

**Q：这个模块如果流量再涨 10 倍，你会怎么演进？**
> 逐层算账再动手（day01 方法论）。预扣层：9.8 万/s×10=100 万/s，8 桶不够——Redis Cluster 分片按 sku hash 天然水平扩，桶数从 8 到 64，同时答题层加强（L1 摊平比例从 40% 提到 70%）；落库层：消费端 2000/s×5 实例=1 万/s，10 倍量要用 Flink 或分库后多消费组并行；数据层：单库 8000 TPS 见顶，启用 day03 的 16 库 64 表分片（订单号基因法已预留分片键）；可用性：单元化从 v0 走真部署（day18 双 Set）；成本：压测驱动逐层扩，不一步到位——先压出真实拐点（day23）再按 ×0.75 水位规划。这个回答展示的是"演进路线图"思维：当前架构的每一层都写了"下一级杠杆是什么、触发阈值是多少"（day04 扩容路线图），10 倍流量只是按图施工，不是推倒重来。

## 5. 今日验收清单

- [ ] mall-seckill v1.0 装配完成（六边形目录+状态机+配置驱动）
- [ ] 验收六项全绿（acceptance-v1.md 每项有数字截图）
- [ ] git tag mall-seckill-v1 封版
- [ ] "流量涨 10 倍怎么演进"能按路线图回答
- [ ] `git add . && git commit -m "day08-25: seckill v1"`

---
[← Day 24](day24-瓶颈定位三板斧.md) | [本月目录](README.md) | [Day 26 · 容量规划 →](day26-容量规划.md)
