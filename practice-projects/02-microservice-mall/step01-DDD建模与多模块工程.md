# Step 1：DDD 建模与多模块工程

> **本步目标**：完成订单域事件风暴建模，搭建多模块工程，用充血模型实现"创建订单"用例——体验"业务规则内聚在聚合里"的正确姿势。

## 前置术语

| 英文 | 中文 |
|------|------|
| Event Storming | 事件风暴（领域建模工作坊方法） |
| Aggregate Root | 聚合根 |
| Value Object | 值对象 |
| Domain Event | 领域事件 |
| Repository | 资源库（聚合的持久化抽象） |
| Bounded Context | 限界上下文 |

## 1. 订单域事件风暴（产出物示例）

**领域事件时间线**（过去时命名）：
```
商品已浏览 → 订单已提交 → 库存已锁定 → 订单已创建 → 余额已扣减 → 订单已支付
                                                        ↘ 积分已发放 / 优惠券已核销
        （15分钟超时）→ 订单已关闭 → 库存已释放
```

**命令 → 聚合归属**：

| 命令 | 执行聚合 | 所属上下文 |
|------|---------|-----------|
| 提交订单 | Order（订单聚合） | order |
| 锁定库存 | Stock（库存聚合） | product |
| 扣减余额 | Account（账户聚合） | user |
| 发放积分 | PointsAccount | marketing |

**关键建模决策**（写进你的建模文档）：
1. **订单聚合 = Order + OrderItem 列表 + 收货地址快照（值对象）**；支付单不进订单聚合（独立上下文，跨聚合走领域事件）。
2. OrderItem 只存**商品快照**（名称/价格当时值），不引用商品实体——防腐层思想：商品上下文变更不破坏历史订单。
3. 一个事务只改一个聚合；"扣库存"跨聚合 → 通过应用层编排 + 分布式事务/消息（step03）。

## 2. 订单聚合（充血模型核心代码）

```java
// 领域层：纯 Java，无框架依赖
public class Order {
    private OrderId id;                 // 标识
    private Long userId;
    private Money totalAmount;          // 值对象：金额+币种，不可变
    private OrderStatus status;         // 枚举状态机
    private List<OrderItem> items;      // 订单项
    private AddressSnapshot address;    // 值对象快照

    // 工厂方法：创建即校验（所有不变量在创建时保证）
    public static Order place(OrderId id, Long userId, List<OrderItem> items,
                              AddressSnapshot address, OrderPolicy policy) {
        if (items == null || items.isEmpty()) throw new OrderException("订单项不能为空");
        Order order = new Order(id, userId, items, address);
        order.totalAmount = items.stream().map(OrderItem::subtotal)
                .reduce(Money.ZERO, Money::add);
        policy.checkPayLimit(order);     // 业务规则委托领域策略（如单笔限额）
        order.status = OrderStatus.CREATED;
        order.registerEvent(new OrderPlacedEvent(id.value(), userId, order.totalAmount));
        return order;
    }

    // 行为内聚：状态机校验在聚合内（防非法流转的单一入口）
    public void pay(PaymentReceipt receipt) {
        assertStatus(OrderStatus.CREATED, "仅待支付订单可支付");
        this.status = OrderStatus.PAID;
        this.registerEvent(new OrderPaidEvent(id.value(), receipt));
    }

    public void cancel(String reason) {
        if (status == OrderStatus.PAID) throw new OrderException("已支付订单需走退款流程");
        this.status = OrderStatus.CLOSED;
        this.registerEvent(new OrderCancelledEvent(id.value(), reason));
    }

    private void assertStatus(OrderStatus expected, String msg) {
        if (this.status != expected) throw new OrderException(msg + "，当前状态：" + status);
    }
}

// 值对象：不可变 + 相等性基于属性
public record Money(BigDecimal amount, Currency currency) {
    public static final Money ZERO = new Money(BigDecimal.ZERO, Currency.CNY);
    public Money add(Money other) {
        if (currency != other.currency) throw new IllegalArgumentException("币种不同");
        return new Money(amount.add(other.amount), currency);
    }
}
```

对比贫血版（Order 只有 setter，逻辑在 OrderService 里散落）——你重构后应能说清三个收益：**规则集中、状态安全、可单测**（不需要起 Spring 容器就能测业务规则）。

## 3. 应用层用例编排（薄，无业务规则）

```java
@Service
public class OrderAppService {
    // 创建订单：编排 = 取参数对象 → 调聚合 → 持久化 → 发事件
    @Transactional
    public OrderId createOrder(CreateOrderCmd cmd) {
        List<OrderItem> items = inventoryFacade.lockStocks(cmd.items()); // 防腐层：调商品上下文
        Order order = Order.place(OrderId.newId(), cmd.userId(), items,
                cmd.address(), orderPolicy);
        orderRepository.save(order);                       // 仓储实现聚合级持久化
        domainEventPublisher.publish(order.pullEvents());  // 领域事件出站
        return order.id();
    }
}
```

## 4. 多模块工程（Gradle）

```
mall/
├── settings.gradle        # include 各服务与 common
├── build.gradle           # 公共依赖版本管理（用 platform 或 dependency-management）
├── mall-common/           # Result/异常/工具（保持薄！不要变成垃圾场）
├── mall-gateway/
├── mall-user/ mall-product/ mall-order/ mall-marketing/
└── infra/docker-compose.yml   # nacos/mysql/redis/sentinel-dashboard
```

settings.gradle 示例：

```groovy
rootProject.name = 'mall'
include 'mall-common','mall-gateway','mall-user','mall-product','mall-order','mall-marketing'
```

## 验收清单

- [ ] 建模文档：事件清单 + 聚合边界说明 + 3 条关键建模决策及理由
- [ ] 订单聚合充血实现：`Order.place/pay/cancel` 状态机完备，非法流转被拦截
- [ ] 纯单元测试：不起 Spring 容器直测 Order 行为（≥ 6 个用例）
- [ ] 多模块工程编译通过，mall-order 可独立启动
- [ ] 能回答："为什么 OrderItem 存快照不存引用？"

## 常见坑

1. **假充血**：把 Service 逻辑原样搬进实体但实体依赖了 Spring/MyBatis 注解——domain 层保持纯净。
2. **聚合过大**：把支付、物流都塞进 Order——一事务一聚合，跨聚合用事件。
3. **Common 模块腐化**：所有类都往 mall-common 扔——按"被 ≥2 模块复用"标准准入。

> 完成后进入 `step02-注册配置中心与网关.md`。
