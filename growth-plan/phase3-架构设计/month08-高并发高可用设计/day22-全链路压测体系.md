# Day 22 · 全链路压测体系：影子库与流量染色

> **今日目标**：掌握全链路压测三要素（流量染色/影子存储/数据构造）；压测模型的构建（生产镜像 vs 等比缩放）；商城压测平台选型——W4 压测周的第一天。
> **时长**：体系设计 1.5h / 影子链路搭建 2.5h / 数据构造 1h
> **今日产出**：压测方案文档（染色/影子/数据三设计）+ 影子表+影子 Redis 落地

## 1. 知识地图

```
单接口压测（wrk 打一个 URL）≠ 全链路压测：真实流量穿过网关→服务→缓存→MQ→DB 的完整拓扑，
瓶颈往往藏在"单接口测不出来"的地方（消费积压/连接池耗尽/缓存击穿连锁）——W4 建立体系

全链路压测三要素：
① 流量染色（压测标记贯穿全链路）：
  HTTP 头 X-Stress-Flag: 1 → RPC 透传（SkyWalking/Feign 拦截器）→ MQ 消息属性 → 日志 MDC
  ——所有中间件按染色路由到影子资源；丢一处就污染生产（最大风险）
② 影子存储（压测数据与生产数据物理/逻辑隔离三档）：
  影子表（同库 t_order_shadow）：成本低，风险中（同库资源竞争）——中小公司主流
  影子库（独立 schema）：隔离彻底，成本中——商城 v1 方案
  影子集群（独立中间件全套）：最安全最贵——大厂核心链路
  影子 Redis：key 前缀 stress:* + 独立 DB index；影子 MQ：topic 加后缀 _stress
③ 数据构造（压测数据从哪来）：
  流量录制回放（GoReplay/tcpcopy）：最真实，需脱敏
  数据工厂：按生产画像造数（订单分布/商品热度二八分布——day01 方法论复用）
  模型原则：压测流量分布 ≈ 生产流量分布（读:写、热点商品分布、用户行为路径）

压测环境两流派：
  等比缩放（1:4 环境压出 ×4 结论）：便宜，线性假设有偏差（缓存命中不可线性放大）
  生产镜像/生产环境压（流量染色直接压生产影子链路）：最准，风险最高需预案
  商城路线：本地 docker 等比练手 → day23 阶梯加压 → 结论标注缩放系数置信度

与 M6 的衔接：M6 day13 学过"压测拐点×0.75 定水位"——当时是单接口，本月是全链路
  新增观测维度：MQ 消费滞后/连接池 pending/缓存命中率衰减/影子表写入延迟——四维联合判断
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Full-link Stress Test | 全链路压测 |
| Traffic Tainting | 流量染色（压测标透传） |
| Shadow Table / DB | 影子表/影子库（数据隔离） |
| Traffic Recording | 流量录制回放 |
| Data Factory | 数据工厂（画像造数） |
| Scaled-down Environment | 等比缩放环境 |

## 3. 动手实操：影子链路搭建

```java
// ① 染色透传（Web → Feign → MQ → 日志 四跳）：
@Component
public class StressFilter extends OncePerRequestFilter {          // Web 入口染色
    protected void doFilterInternal(HttpServletRequest q, HttpServletResponse s, FilterChain c) {
        if ("1".equals(q.getHeader("X-Stress-Flag"))) {
            StressContext.mark();                                  // ThreadLocal 打标
            MDC.put("stress", "1");                                // 日志可检索
        }
        try { c.doFilter(q, s); } finally { StressContext.clear(); MDC.remove("stress"); }
    }
}
@Component
public class StressFeignInterceptor implements RequestInterceptor {  // Feign 透传
    public void apply(RequestTemplate t) {
        if (StressContext.isStress()) t.header("X-Stress-Flag", "1");
    }
}
// MQ 透传：rocketMQTemplate 发送时 msg.putUserProperty("stress","1")；消费端检查后路由影子
// ② 存储路由（MyBatis 拦截器按染色切影子表）：
@Intercepts(@Signature(type = StatementHandler.class, method = "prepare"))
public class ShadowTableInterceptor implements Interceptor {
    public Object intercept(Invocation inv) {
        if (StressContext.isStress()) {                            // SQL 改写 t_order→t_order_shadow
            rewriteSql(inv, Map.of("t_order", "t_order_shadow", "t_stock", "t_stock_shadow"));
        }
        return inv.proceed();
    }
}
// ③ Redis/MQ 影子路由：key 加前缀 stress: / topic 加后缀 _stress（工具类统一封装）
```

```markdown
<!-- docs/stress/mall-stress-plan.md —— 压测方案文档核心章节 -->
## 商城压测方案 v1
### 链路拓扑与压测目标
链路：JMeter → Gateway → order/promo/stock → Redis/MySQL/MQ(消费落库)
目标：详情读 5万/s、下单写 5000/s、秒杀预扣 10万/s（day19 容量表口径）×1.5 验证
### 三设计决策
染色：X-Stress-Flag 全链路透传（Web/Feign/MQ/日志四跳），丢失即熔断压测（防御式）
影子：MySQL 影子表（t_order_shadow 等 5 张）+ Redis stress: 前缀 + MQ topic _stress 后缀
数据：数据工厂造 10 万商品（热度二八分布）+ 1 万测试 uid；流量模型 读:写=10:1（day01 口径）
### 风险清单（压测前置检查项）
- [ ] 染色透传全链路验证（故意丢一跳→确认影子隔离仍成立）
- [ ] 影子表自动清理 job（压后 24h 清，防膨胀）
- [ ] 熔断压测开关（异常时一键停压：网关拒绝 X-Stress-Flag 请求）
```

```powershell
# 影子链路验证（压测前的隔离性检查）：
docker exec -it mysql mysql -uroot -p -e "SHOW TABLES LIKE 't_%_shadow'"          # 影子表就位
# 带染色头压一轮小流量：
docker run --rm williamyeh/wrk -H "X-Stress-Flag: 1" -t2 -c50 -d30s http://host.docker.internal:8080/api/order/create
docker exec -it mysql mysql -uroot -p -e "SELECT COUNT(*) FROM t_order_shadow"    # 影子表有数
docker exec -it mysql mysql -uroot -p -e "SELECT MAX(id) FROM t_order"            # 生产表无增长 ✓
git add . ; git commit -m "day08-22: shadow link"
```

## 4. 面试连接

**Q：全链路压测怎么做？和单接口压测的区别？**
> 单接口压测只能回答"这个接口能扛多少"，全链路压测回答"这套系统在真实流量形态下瓶颈在哪"——瓶颈常在单接口测不出的地方：消费端攒批的背压、连接池的排队连锁、缓存命中率衰减后的 DB 放大。落地三要素：①流量染色——X-Stress-Flag 从 Web 到 Feign 到 MQ 到日志四跳透传，压测流量全程可识别；②影子存储——MySQL 影子表、Redis 前缀隔离、MQ 独立 topic，压测数据物理不进生产；③数据构造——数据工厂按生产画像造数（商品热度二八分布、读写比 10:1），流量分布失真则压测结论失真。最大风险是染色丢失导致污染生产，所以防御式设计：透传缺失即熔断压测+压前隔离性检查清单。我们商城本地等比环境练手，结论标注缩放系数；生产级压测需要影子集群+录制回放，那是成本阶梯的另一档。

**Q：为什么压测数据要按生产分布造？均匀造不行吗？**
> 均匀数据会系统性高估容量。三个失真点：①缓存命中率——热点二八分布下命中率 99%（day02 漏斗），均匀分布可能只有 60%，DB 压力差 40 倍，压出来的"容量"上线即破；②锁竞争——库存热点商品集中扣减（day08 分桶解决的正是这个），均匀造数会掩盖热点行锁瓶颈；③队列倾斜——MQ 按 shard 路由，热点商品全进同一队列。所以数据工厂按生产画像造：商品热度二八分布、用户行为路径还原（浏览→加购→下单的漏斗比例）、读写比按 day01 口径。进阶做法是流量录制回放（GoReplay 录生产流量脱敏回放），分布最真实但要解决数据依赖问题。一句话：压测的第一性原理是"用真实的压力形态检验系统"，均匀造数等于考卷和考试内容完全脱节。

**Q：压测怎么保证不搞挂生产？**
> 五道防线：①物理隔离——影子表/影子 Redis/独立 topic，压测数据写不进生产存储，这是最硬的一道；②染色防御——透传链路缺失即熔断压测（压测工具端校验响应头的染色标记），防半截染色污染；③一键停压——网关层压测开关（拒绝所有 X-Stress-Flag 请求），异常时秒级止血，比逐台杀压测机快；④环境档位分级——本地等比环境先跑通、预发影子链路验证隔离性、最后才是生产低比例（5%起）探底，每级有准入检查清单；⑤预案联动——压测期间 day19 的预案开关处于就绪状态，压出问题按预案走而不是现场救火。加上事后两条：影子表 24h 自动清理、压测报告归档（数字沉淀到容量文档，day05 预算表回填实测列）。

## 5. 今日验收清单

- [ ] 染色四跳透传（Web/Feign/MQ/日志）+ 缺失熔断验证
- [ ] 影子三件套落地（影子表/Redis 前缀/MQ 后缀）+ 隔离性检查通过
- [ ] 压测方案文档（目标×1.5/三设计/风险清单）
- [ ] 数据工厂产出画像数据（二八分布验证）
- [ ] `git add . && git commit -m "day08-22: stress system"`

---
[← Day 21](day21-第三周复盘.md) | [本月目录](README.md) | [Day 23 · 压测执行 →](day23-压测执行.md)
