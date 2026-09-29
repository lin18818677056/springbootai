# Day 20 · CQRS 与读模型：写懂业务，读懂性能

> **今日目标**：理解 CQRS——写模型保不变量、读模型为查询优化；用事件驱动建订单列表 ES 宽表；明确"什么时候不需要 CQRS"的边界（与 day04 呼应）。
> **时长**：概念 1h / 读模型落地 3h / 边界思考 1h
> **今日产出**：订单列表读模型（事件驱动）+ CQRS 决策清单

## 1. 知识地图

```
CQRS（Command Query Responsibility Segregation）：
  写模型（命令侧）：day19 的充血聚合——为"保护不变量"优化（强一致/小事务/状态机）
  读模型（查询侧）：为"查询形态"优化的宽表——想怎么查就怎么存（反范式/冗余/预计算）
  为什么需要：聚合形态 ≠ 查询形态
    订单列表页要：订单号+商品图+状态+金额+物流摘要——横跨 Order/商品快照/物流三个聚合
    用聚合查 = 三次 Repository 组装 + 分页深翻慢 → 查询侧直接"一张宽表"喂给前端
  CQRS ≠ 必须两套数据库！分级落法：
    L1 同库读写分离视图（SQL 视图/只读 DTO 组装）——小系统够用
    L2 同库异表：读模型表 + 事件驱动更新（今天主做）
    L3 异构存储：ES 宽表/Redis 列表缓存（订单列表大促场景）——今天以 ES 演示

读模型怎么更新（事件驱动的三个接线方式）：
  ① 监听领域事件（OrderCreated/OrderPaid/...）逐事件更新宽表——day18 Outbox 出口直连
  ② binlog CDC（Canal/Debezium）——不改业务代码，但宽表逻辑藏在数据链路（可观测性差）
  ③ 定时全量校对——①②漏了的兜底（M6 day19 对账思想的读模型版）
  选型：核心链路走①（事件=事实，语义清晰），兜底挂③；②留给"不能动老代码"的场景

读模型的一致性真相：
  事件驱动更新 = 最终一致（毫秒~秒级延迟）——"下单成功列表里没看见"能不能容忍？
  电商答案是：下单后跳"订单详情"（写模型直查，强一致），列表页晚 1 秒出现无感
  ——一致性要求按"页面"定，不按系统定：详情页强一致走聚合，列表页最终一致走宽表

什么时候不需要 CQRS（day04 的"分层轻重"姊妹判断）：
  查询形态=聚合形态（按 ID 查单/查详情）→ 不需要，直接聚合查询
  后台配置类 CRUD → 不需要，两层架构
  判断句："写侧的不变量复杂度 × 读侧的查询形态偏离度"两个都高才上 CQRS
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| CQRS | 命令查询职责分离 |
| Write Model / Read Model | 写模型 / 读模型 |
| Denormalization | 反范式化（宽表冗余换查询性能） |
| CDC (Change Data Capture) | 变更数据捕获 |
| Projection | 投影（事件→读模型的转换器） |
| Eventual Consistency | 最终一致（读模型的代价） |

## 3. 动手实操：订单列表读模型

```java
// 读模型文档（ES 索引 mall_order_list——为列表页而生的宽表）
// 字段：orderId/userId/orderNo/status/statusText/totalAmount/payAmount
//       itemCount/firstProductImg(first of items)/shippingNo/shippingCompany/createdAt
// ——观察：firstProductImg 是"取第一个商品图"的预计算（查询时不用解 items 数组）
//    shippingXxx 是物流上下文的跨域字段（day10 的跨上下文，在读模型"合家欢"是合法的！）

// 投影器：事件→宽表更新（adapter/mq——它是读模型的 adapter，不在领域层）
package com.mall.order.adapter.projection;
@Component
public class OrderListProjection {
    private final ElasticsearchClient es;
    @RocketMQMessageListener(topic = "mall-order-events", consumerGroup = "order-list-proj")
    public class Listener { /* 按事件类型路由 */ }

    public void on(OrderCreated e) {
        es.index(i -> i.index("mall_order_list").id(e.orderId().toString()).document(Map.of(
            "orderId", e.orderId(), "status", "CREATED",
            "totalAmount", e.amount().toString(), "createdAt", e.occurredOn().toString())));
    }
    public void on(OrderPaid e)      { patch(e.orderId(), Map.of("status", "PAID", "payAmount", e.paid().toString())); }
    public void on(OrderShipped e)   { patch(e.orderId(), Map.of("status", "SHIPPED", "shippingNo", e.shippingNo())); }
    // 注意：投影器只做"事件→宽表字段"的映射，零业务规则（规则在领域层，投影是技术装配）
}

// 查询侧：Controller 直接查 ES（不经聚合/Repository——读侧有自己的通道）
@GetMapping("/api/orders")
public PageResult<OrderListVO> list(@RequestParam Long userId, @RequestParam String status) {
    return orderQueryService.search(userId, status);   // OrderQueryService→ES，绕过写模型
}
// 定时兜底校对（③）：每小时对比 count(order) vs count(ES)，差异走事件重放
```

```powershell
# 端到端验证：
# ① 下单 → ES 出现宽表文档；支付 → status 变 PAID（事件驱动生效）
docker exec -it elasticsearch curl -s "localhost:9200/mall_order_list/_doc/1?pretty"
# ② 一致性延迟实测：下单后立刻查列表（写模型直查有，ES 宽表 200ms 后才有）——记录延迟
# ③ 兜底校对：手动删 ES 文档 → 等小时级 Job 或手动触发 → 文档恢复（③ 生效截图）
cd D:\mywork\springbootai\practice-projects\02-microservice-mall\mall
.\gradlew.bat :mall-order:build
git add . ; git commit -m "day20: cqrs read model"
```

## 4. 面试连接

**Q：什么是 CQRS？为什么需要？**
> CQRS 把写和读分成两个模型：写模型是充血聚合，为保护不变量优化（强一致/事务边界/状态机）；读模型是为查询形态优化的宽表，想怎么查就怎么存（反范式冗余/预计算）。需要的根因是"聚合形态≠查询形态"：订单列表页要订单+商品图+物流摘要三个聚合的数据，用聚合组装就是三次查库加深分页噩梦，而读模型一张 ES 宽表直出。分级落法要讲清：不是一上来就两套库——同库视图是一级、事件驱动读模型表是二级、ES/缓存异构是三级，按查询压力逐级升级。我们订单列表落在第三级（大促 QPS 高），详情页坚持写模型直查保强一致。

**Q：读模型的最终一致怎么处理？业务能接受吗？**
> 先回答一致性要求按"页面"定：下单后跳订单详情页——写模型直查，强一致，用户刚花的钱必须立刻看见；列表页晚 1-2 秒出现新订单，用户无感——这是可容忍区。技术上三道保障：事件驱动更新通常毫秒级；监控侧挂"宽表延迟"指标（事件时间 vs 投影完成的差值，P99 报警——M6 RED 方法的直接复用）；兜底每小时 count 校对差异重放（day19 对账思想的读模型版）。被追问"用户刷新发现列表没有详情页有会不会投诉"——真实数据是没有：列表入口本来就是"稍后回来看"的心智，把强一致给对的地方，把性能给对的地方，CQRS 的取舍就成立了。

**Q：什么时候不该用 CQRS？**
> 两个条件同时高才值得：写侧不变量复杂（有状态机/聚合规则）×读侧查询形态偏离（跨聚合/复杂筛选/高并发列表）。反面清单：按 ID 查详情——聚合直查就行，建宽表纯属浪费；后台配置 CRUD——两层架构（day04 的判断复用）；小系统日订单几千——L1 视图甚至直接 SQL join 足够。再加一条经验：CQRS 的隐形成本是"投影逻辑的维护+最终一致的沟通成本"（产品要理解列表有延迟），团队没有事件基建时先补基建再上 CQRS，顺序反了就是双倍痛苦。

## 5. 今日验收清单

- [ ] 订单列表 ES 宽表（含预计算字段设计说明）
- [ ] 三个事件投影 + 查询侧绕过写模型
- [ ] 一致性延迟实测记录 + 兜底校对跑通
- [ ] "什么时候不用 CQRS"清单能举例
- [ ] `git add . && git commit -m "day20: cqrs"`

---
[← Day 19](day19-贫血到充血重构.md) | [本月目录](README.md) | [Day 21 · 第三周复盘 →](day21-第三周复盘.md)
