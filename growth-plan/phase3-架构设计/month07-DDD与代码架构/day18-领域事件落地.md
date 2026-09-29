# Day 18 · 领域事件落地：进程内与跨上下文

> **今日目标**：把 day12 的事件清单工程化——统一事件结构、进程内 Spring Event 与跨上下文 MQ 的选择标准、Outbox 保证不丢（打通 M5 事务消息伏笔）；跑通 OrderCreatedEvent 驱动积分。
> **时长**：概念 1h / 事件基础设施 2h / 积分链路联调 2h
> **今日产出**：事件基类 + Outbox 表 + 积分链路端到端跑通

## 1. 知识地图

```
领域事件工程化三件套：
  ① 统一结构（事件是公共契约，不是裸 POJO）：
     eventId(UUID 唯一)/eventType(mall.order.created)/occurredOn(发生时间)
     aggregateType+aggregateId(订单/123)/payload(业务载荷)/version(契约版本号)
     ——version 从第一天就加：消费者按版本兼容解析，避免加字段就全量升级
  ② 两级发布通道（选择标准：消费者是谁）：
     进程内（Spring Event）：同上下文同事务的事——积分如在本服务内、缓存失效、审计落库
       @DomainEvents 聚合攒事件 / @TransactionalEventListener(AFTER_COMMIT) 消费
     跨上下文（MQ）：微服务边界外——库存/营销/通知订阅 OrderCreated
       必须可靠：Outbox 模式（业务与事件同一本地事务落库→中继投递 MQ）
  ③ 不丢不重三板斧：
     不丢：Outbox 事件表与业务同事务 insert（原子）→ 定时中继/Debezium 投 MQ
     不重：eventId 幂等键，消费端建去重表（M6 day18 消费幂等的领域化表达）
     可追溯：事件表就是审计日志（事后补偿 day13 的底气）
     ——与 M5 day10 事务消息的关系：RocketMQ 半消息=MQ 侧的 Outbox，
       Outbox=DB 侧的半消息——两者选一即可，中小系统 Outbox 更通用（不绑 MQ 实现）

聚合攒事件的模式（day16 埋点收口）：
  Order.pay() 内 registerEvent(new OrderPaid(...)) → 事件是"行为的副产品"
  AppService 统一 pullEvents() 发布——聚合自己不碰 Spring（领域零框架红线不破）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Domain Event | 领域事件（过去时业务事实） |
| Event Envelope | 事件信封（统一结构契约） |
| Spring Event | 进程内事件（同上下文） |
| @TransactionalEventListener | 事务感知监听（AFTER_COMMIT） |
| Outbox Pattern | 发件箱模式（同事务落库事件） |
| Idempotent Consumer | 幂等消费（eventId 去重） |
| Event Versioning | 事件版本演进 |

## 3. 动手实操：事件基建 + 积分链路

```java
// ① 事件基类与领域事件（domain/event——零框架依赖）
package com.mall.order.domain.event;
public abstract class DomainEvent {
    public final String eventId = java.util.UUID.randomUUID().toString();
    public final java.time.Instant occurredOn = java.time.Instant.now();
    public abstract String eventType();      // mall.order.created
    public abstract String aggregateId();    // 订单号
}
public final class OrderCreated extends DomainEvent {
    public final Long orderId; public final Long userId; public final Money amount;
    public OrderCreated(Long orderId, Long userId, Money amount) { /* 赋值 */ }
    @Override public String eventType() { return "mall.order.created"; }
    @Override public String aggregateId() { return String.valueOf(orderId); }
}
// ② 聚合攒事件 + 应用服务统一发布
public class Order {
    private final transient List<DomainEvent> events = new java.util.ArrayList<>();
    protected void registerEvent(DomainEvent e) { events.add(e); }
    public List<DomainEvent> pullEvents() { var c = List.copyOf(events); events.clear(); return c; }
}
// ③ Outbox：与业务同一事务落库（adapter/persist）
@Transactional  // CreateOrderAppService 的 @Transactional 已覆盖：outbox insert 同事务
@Component
public class OutboxEventPublisher implements DomainEventPublisher {
    private final JdbcTemplate jdbc;
    public void publish(List<DomainEvent> events) {
        for (DomainEvent e : events)
            jdbc.update("INSERT INTO event_outbox(event_id,event_type,payload,created_at,status) VALUES(?,?,?,?,0)",
                e.eventId(), e.eventType(), Json.dumps(e), java.sql.Timestamp.from(e.occurredOn));
    }   // 同事务原子落库——回滚则事件消失，成功则必被中继扫到（不丢）
}
// 中继：定时扫 status=0 投 MQ，成功置 1（宕机重扫天然重投 → 靠 eventId 幂等兜底）
```

```powershell
# 积分链路端到端验证（ mall-points 订阅 mall.order.created ）：
docker exec -it rocketmq sh "mqadmin sendMessage -t mall-order-events -p '{...}'" 2>$null
# 或直接调 POST /api/order 触发全链路，观察：
# ① event_outbox 表新行 status 0→1（中继投递成功）
# ② points 服务日志出现消费行（eventId 去重表插入）
# ③ 重复投递实验：手动向 MQ 重发同 eventId → 积分不重复发放（幂等生效截图）
cd D:\mywork\springbootai\practice-projects\02-microservice-mall\mall
.\gradlew.bat :mall-order:build ; .\gradlew.bat :mall-points:build
git add . ; git commit -m "day18: domain events & outbox"
```

## 4. 面试连接

**Q：领域事件怎么落地？进程内和跨服务怎么选？**
> 结构上事件是带信封的公共契约：eventId/类型/发生时间/聚合标识/载荷/版本号——version 第一天就加，消费者按版本兼容。通道选择看消费者：同上下文同事务的副作用（审计、缓存失效、本服务内积分）走进程内 Spring Event，@TransactionalEventListener(AFTER_COMMIT) 保证业务提交后才消费，避免"事务回滚了事件却发出去了"的幽灵事件；跨上下文走 MQ，且必须配可靠性机制。我们订单域两级都用：OrderCreated 进程内通知本服务缓存失效，同时经 Outbox 中继到 RocketMQ 给库存/营销订阅——一套事件，两个出口，语义同源。

**Q：怎么保证事件不丢不重？**
> 不丢用 Outbox：事件与业务数据在同一个本地事务 insert 进 event_outbox 表——业务成功事件必然落库，定时中继扫表投 MQ 后置已发；中继宕机重扫天然重投。不重靠消费端幂等：eventId 做唯一键建去重表，重复消息直接跳过——这也是 M6 消费幂等的领域化表达。两条合起来："发送端同事务原子化 + 消费端幂等化"，中间的 MQ 只需至少一次语义。我们实测过故障注入：中继投递后 kill 进程，重启重投，积分服务因幂等表零重复发放。与 RocketMQ 事务消息的关系也想清楚了：半消息是 MQ 侧方案，Outbox 是 DB 侧方案，二选一，我们选 Outbox 因为不绑定具体 MQ、且有免费的事件审计表。

**Q：事件契约怎么演进？加字段怎么办？**
> 三条纪律：①只加不删不改名——旧消费者按 version 兼容解析，新字段可忽略；②载荷里放业务事实不放意图——OrderCreated 带 amount 不带"请发积分"，消费方各自解读（解耦的关键：生产者不知道消费者是谁）；③大版本变更（语义都变了）用新事件类型而非改旧事件——mall.order.created.v2 是新事件，老事件照发直至消费者下线。我们的去重表按 eventId 隔离，新事件类型天然是新的幂等通道，版本演进不影响去重逻辑。

## 5. 今日验收清单

- [ ] 事件基类（eventId/时间/类型/聚合标识）落地
- [ ] Outbox 表+中继跑通，status 0→1 可见
- [ ] 积分链路端到端 + 重复投递幂等实验截图
- [ ] AFTER_COMMIT 与 Outbox 的选择能讲
- [ ] `git add . && git commit -m "day18: events & outbox"`

---
[← Day 17](day17-领域服务与应用服务.md) | [本月目录](README.md) | [Day 19 · 贫血到充血重构 →](day19-贫血到充血重构.md)
