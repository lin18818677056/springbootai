# Day 17 · 领域服务与应用服务：两种服务不混淆

> **今日目标**：分清应用服务（用例编排）与领域服务（跨实体规则）；重构 OrderAppService 到六边形 application 层；设计按根进出的 Repository；实现"规则 100% 在领域层"验收。
> **时长**：概念 1h / 重构 3h / 验收 1h
> **今日产出**：OrderAppService 重构 diff + PriceCalculator 领域服务 + 依赖检查器绿灯

## 1. 知识地图

```
两种服务（名字像，职责天差地别）：
  应用服务（Application Service）——"指挥家"：
    职责：接收命令 → 加载聚合 → 调聚合行为 → 保存 → 发布事件 → 事务包裹
    红线：不写业务规则（没有 if 判断业务条件）、不管协议（HTTP/MQ 在 adapter）
    对应六边形：application 层，实现驱动端口接口（day05 的 CreateOrderUseCase）
  领域服务（Domain Service）——"客座专家"：
    职责：承载"不属于任何单一实体"的业务规则（跨实体/跨聚合的计算）
    例：PriceCalculator（要碰 items+优惠券+运费三个概念，放 Order 还是 Coupon 都别扭）
    特征：无状态、输入输出都是领域对象、可以被聚合调用
  判断优先级（放哪里？从上往下试）：
    ① 规则只涉及一个聚合自己的数据 → 实体行为（pay 归 Order）
    ② 规则涉及多个领域对象但都是纯计算 → 领域服务（PriceCalculator）
    ③ 都不涉及，只是"做一件事的步骤" → 应用服务编排
    ——反模式：图省事全堆应用服务 = 贫血模型卷土重来（day19 的靶子）

Repository（仓储）：聚合的"内存集合"抽象——按根进出：
  接口在领域层（domain/gateway，day05 已定）；实现带 ORM 细节在 adapter/persist
  设计纪律：
  · 只为聚合根建仓储（OrderRepository 有，OrderItemRepository 不该存在——day16 红线②）
  · 方法说领域语言：findPendingPayment(userId) 而不是 selectByStatusAndUserId(0,...)
  · save(Order) 一个方法管增改（聚合是整体保存——区别于 DAO 的 insert/update 拆分）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Application Service | 应用服务（用例编排，无规则） |
| Domain Service | 领域服务（跨实体业务规则） |
| Orchestration | 编排（指挥家职责） |
| Stateless | 无状态（领域服务特征） |
| Repository | 仓储（聚合的集合抽象） |
| Aggregate-Whole Save | 聚合整体保存 |

## 3. 动手实操：OrderAppService 重构

```java
// ① 应用服务：六边形 application 层——找一遍，必须没有一个业务 if
package com.mall.order.application;
public class CreateOrderAppService implements CreateOrderUseCase {
    private final OrderRepository orderRepo;        // 领域接口（day05）
    private final StockGateway stockGateway;        // 跨上下文端口（ACL 实现在 adapter）
    private final PromotionGateway promotionGateway;
    private final DomainEventPublisher events;      // day18 的事件出口

    @Transactional                                   // 事务边界=一个聚合（day16 原则④）
    public Long handle(CreateOrderCommand cmd) {
        Order order = Order.place(cmd.userId(), cmd.items(), cmd.address());
        //                    ↑ 规则全在这：非空/限购/计价/号段——应用服务零规则
        Coupon coupon = promotionGateway.apply(cmd.couponCommand(order.totalAmount()));
        PriceCalculator.applyCoupon(order, coupon);  // 领域服务：跨 Order+Coupon 的计价规则
        orderRepo.save(order);
        stockGateway.lock(order.stockCommands());    // 跨上下文：事件/补偿由 M6 机制兜底
        events.publish(order.pullEvents());          // 聚合攒的事件统一发布（day18）
        return order.id();
    }
}

// ② 领域服务：跨实体规则（无状态、纯领域对象进出）
package com.mall.order.domain.service;
public final class PriceCalculator {
    public static void applyCoupon(Order order, Coupon coupon) {
        Money discount = coupon.applicableDiscount(order.items());  // 圈品规则在 Coupon 上
        Money after = order.totalAmount().subtract(discount);
        order.reprice(after);             // 校验（折后≥0）也封在 reprice 里，这里不写 if
    }
    private PriceCalculator() {}          // 工具型领域服务：私有构造+静态方法（或注册为领域 Bean）
}

// ③ Repository 按根设计（接口在 domain/gateway）
public interface OrderRepository {
    Order find(OrderId id);                          // 整聚合加载（items 一起回来）
    void save(Order order);                          // 整聚合保存（增改一体）
    List<Order> findPendingPayment(UserId userId);   // 领域语言命名
}
```

```powershell
# 验收三连（机械可验证）：
cd D:\mywork\springbootai\learning\month07-ddd-architecture
javac -encoding UTF-8 -d out src\LayerDependencyChecker.java
java -cp out LayerDependencyChecker D:\mywork\springbootai\practice-projects\02-microservice-mall\mall\mall-order\src\main\java
# ① 依赖检查器绿灯（violations=0）
Get-ChildItem -Recurse -Include *.java -Path D:\mywork\springbootai\practice-projects\02-microservice-mall\mall\mall-order\src\main\java\com\mall\order\application |
  Select-String -Pattern 'if\s*\(' | Format-Table LineNumber, Line -AutoSize -Wrap
# ② application 层 grep "if(" 应只剩参数判空（业务 if 计数=0）
# ③ 领域层 grep "com.thirdparty|org.springframework" = 0（day11/05 红线复查）
git add . ; git commit -m "day17: app & domain service"
```

## 4. 面试连接

**Q：应用服务和领域服务的区别？**
> 应用服务是用例的编排者：接收命令、加载聚合、调用聚合行为、保存、发事件、管事务——它回答"做这件事分几步"，自身零业务规则；领域服务是规则的客座专家：承载不属于任何单一实体的跨对象业务逻辑，无状态、纯领域对象进出，它回答"这条规则住在哪"。判别优先级我从上到下试：规则只碰一个聚合→实体行为（pay 归 Order）；跨对象纯计算→领域服务（PriceCalculator 碰 Order+Coupon）；只是步骤→应用服务。最大的反模式是全堆应用服务——我们的 OrderAppService 重构后业务 if 计数为零（用 grep 机械验证），这就是"规则 100% 在领域层"的验收方式：不靠自觉靠检查器。

**Q：什么情况下需要领域服务？直接放实体不行吗？**
> 放实体优先——规则能用聚合自己的数据表达就内聚进实体（充血的第一选择）。需要领域服务的三个信号：①规则涉及两个以上聚合的协作（计价要碰订单明细和优惠券）；②规则属于"全家共享"的计算（风控分计算，订单/售后都要用）；③需要其他无状态领域知识（汇率换算）。反例警示：领域服务不是贫血的遮羞布——如果把 pay 的金额校验也抽进 OrderPayService，参数传裸 Money 进出，实体被掏空成数据袋，这比三层架构还糟（三层至少 Service 在管事）。口诀："实体能干的不外包，外包的必须是真跨域。"

**Q：Repository 和 DAO 有什么区别？**
> 三个层面：抽象对象不同——DAO 面向表（OrderDAO 对应 t_order），Repository 面向聚合（OrderRepository 管 order+items 整体进出）；方法语言不同——DAO 是 selectByStatus(0)，Repository 是 findPendingPayment（day09 通用语言的落点）；归属不同——DAO 通常和 Service 同层互相裸调，Repository 接口在领域层、实现在基础设施层，领域只见抽象不见 MyBatis（DIP 落地）。一个实用判别：出现 orderDAO.insert 和 orderItemDAO.batchInsert 被同一个 Service 顺序调用时，聚合一致性靠程序员自觉；换成 orderRepository.save(order)，items 随根整体保存，一致性由模型结构保证。

## 5. 今日验收清单

- [ ] OrderAppService 重构：业务 if=0（grep 证明）
- [ ] PriceCalculator 领域服务落地
- [ ] Repository 按根三纪律（无 item 仓储/领域命名/整体保存）
- [ ] 依赖检查器绿灯截图
- [ ] `git add . && git commit -m "day17: services split"`

---
[← Day 16](day16-聚合与聚合根.md) | [本月目录](README.md) | [Day 18 · 领域事件落地 →](day18-领域事件落地.md)
