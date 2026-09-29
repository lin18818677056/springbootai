# Day 08 · 秒杀抢购全景：五层流量漏斗

> **今日目标**：掌握秒杀系统五层漏斗（答题→网关限流→Redis 预扣→MQ 削峰→DB 兜底）；Redis+Lua 原子预扣防超卖；库存分桶抗热点 key——**兑现 M7 day30 思考题**（Order.place() 在 10 万 QPS 下先崩在哪）。
> **时长**：全景推演 1.5h / Lua 预扣落地 2h / 分桶实验 1.5h
> **今日产出**：秒杀链路漏斗图（含逐层放行数字）+ SeckillLuaService + mall-seckill 模块骨架

## 1. 知识地图

```
秒杀 = 高并发技术的"全明星赛"：W1 的估算/缓存/削峰/预算在这里合体，
本质一句话——把"10 万人抢 100 件"拆成"10 万人被五层漏斗逐层拦截，最后 100 件安全落库"

五层流量漏斗（从入口到落库，逐层放行数字对账 day01 估算）：
L1 产品层：答题/滑块验证码——削掉脚本机器人 + 把瞬时洪峰摊平 2~3s（10万→6万）
L2 网关层：Sentinel 令牌桶限流 3000/s——超出的直接"手慢了"，不进后端（6万→3000）
L3 预扣层：Redis+Lua 原子预扣——库存 100 件，前 100 个请求成功拿到"资格票"，
  其余毫秒级拒绝；Lua 保证"查库存+扣库存"原子，防超卖（3000→≤100）
L4 削峰层：MQ 蓄水池——预扣成功才发消息，消费端 2000/s 匀速落库（day03 复用）
L5 兜底层：MySQL 行锁 update stock set = stock-1 where stock > 0 ——最终一致性闸门
对账：DB 全程只见 ~100 行写——"DB 活着、用户有反馈、不超卖"三同时成立

先兑现 M7 day30 思考题："10 万 QPS 打到 Order.place()，哪个环节先崩？"
答案：号段生成与锁库存网关——聚合本身不是瓶颈。三层论证：
  ① Order 聚合内存操作（校验规则/事件登记/PricePipeline）微秒级，CPU 永远追得上
  ② 先崩的是订单号生成：号段服务每次拉号段打同一 DB 行，行锁竞争堆积（M6 思考题伏笔）
  ③ 再崩的是库存网关：update stock 热点行——10 万并发在 InnoDB 行锁上排队，
     lock wait 超时雪崩（day03 写瓶颈三查的第一查）
秒杀架构 = 把这两个瓶颈提前消化：库存预扣上移到 Redis，订单号预取缓存批量号段
——"瓶颈不会消失，只能被移动到更便宜的地方"（Redis 比 MySQL 便宜两个数量级）

库存分桶（热点 key 治理的秒杀特化——day09 铺垫）：
  10 万/s 打同一个 stock key → 单 Redis 节点 CPU/带宽打满（单 key 热点）
  分桶：stock:1001 → stock:1001:0~7 八个桶，按 uid hash 路由；总库存 = Σ桶
  代价：某桶先空=变相"该用户没抢到"（可接受）；消费端汇总落库兜底
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Flash Sale / Seckill | 秒杀（瞬时高并发+有限库存） |
| Traffic Funnel | 流量漏斗（逐层拦截，越往后量越小） |
| Redis Pre-deduction | Redis 预扣库存（挡在 DB 之前） |
| Lua Atomicity | Lua 脚本原子性（判断+扣减不可分割） |
| Stock Bucketing | 库存分桶（拆热点 key） |
| Oversell Protection | 防超卖（预扣原子 + DB 条件更新双保险） |
| Qualification Token | 资格 token（抢到=拿到入场券） |

## 3. 动手实操：SeckillLuaService 与模块骨架

```java
// mall-seckill 模块骨架（六边形，M7 标准落地）：
// application/SeckillAppService + domain/SeckillOrder(聚合) + adapter/(web|mq|redis)
// ① Redis+Lua 预扣（判断+扣减原子完成——应用层先 get 再 decr 是两步，并发下超卖）
public class SeckillLuaService {
    private static final DefaultRedisScript<Long> DEDUCT = new DefaultRedisScript<>("""
        local stock = tonumber(redis.call('GET', KEYS[1]))
        if stock == nil then return -1 end      -- 未预热，视为未开始
        if stock <= 0 then return 0 end          -- 售罄，快速拒绝（大多数请求死在这）
        return redis.call('DECR', KEYS[1])       -- 扣减并返回剩余
        """, Long.class);

    /** @return 1=抢到资格 0=售罄 -1=未开始 */
    public long preDeduct(String skuId) {
        Long r = redisTemplate.execute(DEDUCT, List.of("stock:" + skuId));
        return r == null ? -1 : r;
    }
}
// ② 分桶路由：bucket = hash(uid) % 8，预热时把总库存均分进 8 个桶
public String bucketKey(String skuId, Long uid) {
    return "stock:" + skuId + ":" + (uid.hashCode() & 7);
}
// ③ 预扣成功 → 发 MQ（rocketMQTemplate.syncSend("seckill-order", msg)）
//    → 消费端走 day03 攒批落库 → L5 兜底 update stock where stock > 0（对不上即告警）
```

```powershell
# 环境与骨架（本月项目 mall-seckill 从今天起逐日长出）：
cd D:\mywork\springbootai\practice-projects\02-microservice-mall\mall
.\gradlew.bat :mall-seckill:build     # 新模块（build.gradle 参照 mall-order 复制）
docker exec -it redis redis-cli SET stock:1001:0 12   # 预热 8 桶共 100 件
docker exec -it redis redis-cli --eval src/main/resources/lua/seckill.lua , 1  # 手动验证
# 压测预扣层（wrk 模拟 3000 并发，观察命中率与拒绝速率）：
docker run --rm williamyeh/wrk -t4 -c300 -d20s http://host.docker.internal:8080/api/seckill/1001
git add . ; git commit -m "day08-08: seckill funnel & lua"
```

## 4. 面试连接

**Q：设计一个秒杀系统？**
> 我用五层漏斗讲，每层放行多少都有数。L1 产品层：答题或滑块，把 10 万 QPS 的洪峰摊平 2~3 秒并砍掉脚本流量；L2 网关层：Sentinel 令牌桶 3000/s，超出直接友好拒绝，保证后端有界；L3 预扣层是核心：Redis+Lua 原子预扣库存，脚本里"判空、判零、扣减"三步不可分割，库存 100 件就让 100 个请求拿到资格、其余毫秒级售罄拒绝；L4 削峰层：预扣成功才发 MQ，消费端按 DB 能力匀速落库；L5 兜底层：DB 条件更新 where stock>0 做最终闸门，和 Redis 预扣数对账，对不上告警补偿。整个链路 DB 只见百行写。设计原则一句话：瓶颈不会消失，只能被移动——把 MySQL 行锁瓶颈移到 Redis，再让 Redis 也别成为单点（分桶）。（追问：为什么不在 DB 里扣？答：热点行锁排队，10 万并发直接 lock wait 雪崩，这正是 M7 思考题的答案——先崩的是号段生成和锁库存网关，不是聚合逻辑本身。）

**Q：Redis 预扣成功但 MQ 丢了/消费失败，用户抢到了却没订单，怎么办？**
> 三层兜底：①发送侧——RocketMQ 同步发送+重试，或事务消息/Outbox（M5/M6 存量技能）保证"预扣成功"和"消息发出"尽量同成功；②消费侧——消费幂等（uid+skuId 唯一索引）防重，消费失败重投到死信后人工/定时任务介入；③对账兜底——活动结束跑对账任务：Redis 各桶剩余之和 + 已落库订单数 ≠ 总库存即触发差异处理（超卖则回滚补偿、少卖则退库存）。关键认知：秒杀允许"少卖几件"（用户无感知），不允许"超卖一件"（资损+舆情）——所以预扣宁可"事后补发"也不"事后取消"。

**Q：怎么防黄牛和脚本？**
> 四道关卡递进：①答题/滑块——提高脚本成本，同时把瞬时请求摊平成 2~3 秒的坡；②UID 限频——Sentinel 热点参数限流，同一 uid 5 秒 1 次，设备指纹重复直接拉黑；③资格 token——活动开始前对预约用户发放一次性 token，无 token 网关直接拒（把"开放接口"变"邀请制"）；④风控黑名单——历史行为模型前置到网关。注意防黄牛不是无限加严：正常用户误伤率要监控，我们商城的预算是误伤 <0.1%，否则活动口碑崩盘比超卖更伤。

## 5. 今日验收清单

- [ ] 五层漏斗图（含逐层放行数字，与 day01 估算对账）
- [ ] M7 day30 思考题正式回答入文档（号段+锁库存网关，非聚合）
- [ ] SeckillLuaService 落地（Lua 原子预扣 + 分桶路由）
- [ ] 300 并发压测预扣层（无超卖：Σ桶扣减数 = 资格数）
- [ ] `git add . && git commit -m "day08-08: seckill funnel"`

---
[← Day 07](day07-第一周复盘.md) | [本月目录](README.md) | [Day 09 · 热点Key治理 →](day09-热点Key治理.md)
