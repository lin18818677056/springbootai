# Day 05 · 六边形架构：端口与适配器落地 mall-order

> **今日目标**：理解六边形架构（Ports and Adapters）——依赖全部指向领域核心；把 mall-order 改造成六边形目录结构，用 day03 的检查器验证依赖方向 100% 正确。
> **时长**：原理 1.5h / 改造落地 3h / 验证 0.5h
> **今日产出**：mall-order 六边形改造 commit + 依赖检查器绿灯截图

## 1. 知识地图

```
六边形架构（Hexagonal / Ports and Adapters，Alistair Cockburn）：
  核心思想：领域（业务逻辑）在中心，一切外部世界通过"端口"进出
  两类端口（Port=接口）+ 两类适配器（Adapter=接口的实现）：

              ┌────── 驱动侧（左侧/入方向）──────┐
  HTTP 请求 → [驱动适配器: Controller] → [驱动端口: OrderUseCase 接口] ─┐
  MQ 消息   → [驱动适配器: Consumer]  ─┘                                ↓
                                                              ┌──────────────┐
                                                              │   领域核心    │
                                                              │ 实体/值对象/  │
                                                              │ 领域服务/事件 │
                                                              └──────────────┘
  DB 持久化 ← [被驱适配器: RepositoryImpl] ← [被驱端口: OrderRepository 接口] ─┘
  支付调用 ← [被驱适配器: WechatPayAdapter] ← [被驱端口: PaymentGateway 接口] ─┘

  关键规则（整张图就一句话）：源码依赖只能指向领域核心
   ——Controller 依赖 OrderUseCase 接口（不是反过来）
   ——RepositoryImpl 依赖 OrderRepository 接口（接口在领域层！这就是依赖倒置的落地）
   → 领域层零 import：不认识 Spring/MyBatis/任何 SDK（day03 检查器的验收标准）

  为什么值得（代价换什么）：
  ✓ 换存储/换消息队列/换支付渠道：只换适配器，领域零改动
  ✓ 测试：领域层纯 Java 单测，不需要 Spring 容器（毫秒级 vs 秒级）
  ✓ 与微服务解耦的呼应：六边形里"替换适配器"的思维=外部世界变了领域不变
     ——M6 day06 的 fallbackFactory、M7 day11 的防腐层，都是这个思想的外围应用

  mall-order 六边形目录（今天的改造目标）：
  com.mall.order
   ├── domain/            领域核心（零框架依赖）
   │    ├── model/        Order 聚合/Money 值对象/OrderStatus 枚举
   │    ├── service/      领域服务（跨实体规则）
   │    ├── event/        OrderCreatedEvent 等领域事件
   │    └── gateway/      PaymentGateway 等被驱端口（接口）
   ├── application/       OrderAppService（实现 OrderUseCase 用例接口）
   ├── adapter/
   │    ├── web/          OrderController + dto（驱动侧）
   │    ├── mq/           OrderEventConsumer（驱动侧）
   │    └── persist/      OrderRepositoryImpl（被驱侧，MyBatis 实现）
   └── OrderApplication.java
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Hexagonal Architecture | 六边形架构 |
| Port | 端口（领域定义的接口） |
| Adapter | 适配器（端口的技术实现） |
| Driving / Driven Side | 驱动侧（发起调用）/被驱动侧（被调用） |
| Use Case Interface | 用例接口（应用层的驱动端口） |
| Framework Isolation | 框架隔离（领域零框架依赖） |
| Testability | 可测试性（纯 POJO 单测） |

## 3. 动手实操：六边形改造

```java
// ① 被驱端口（领域层定义——注意：接口在这，实现在外）
package com.mall.order.domain.gateway;
public interface OrderRepository {
    Order find(Long id);
    void save(Order order);
}
package com.mall.order.domain.gateway;
public interface PaymentGateway {                 // 领域对"支付"的抽象词汇
    PayResult pay(PayOrder order);                // 领域语言，不是微信的语言！
}

// ② 领域核心（零框架 import——day03 检查器的红线）
package com.mall.order.domain.model;
public class Order { /* place/pay/cancel 行为，day19 充血完整版 */ }

// ③ 被驱适配器（基础设施实现，依赖领域接口——方向"倒"过来了）
package com.mall.order.adapter.persist;
@Repository
public class OrderRepositoryImpl implements OrderRepository {   // implements 领域接口
    private final OrderMapper mapper;             // MyBatis 细节被封死在这一层
    public Order find(Long id) { return toDomain(mapper.selectById(id)); }
    public void save(Order o) { mapper.insert(toDO(o)); }
    private Order toDomain(OrderDO d) { ... }     // DO↔领域对象转换（防腐的最后一道）
}

// ④ 驱动端口与适配器
package com.mall.order.application;               // 用例接口（可选，也可直接注入 AppService）
public interface CreateOrderUseCase { Long handle(CreateOrderCommand cmd); }
package com.mall.order.adapter.web;
@RestController
public class OrderController {
    private final CreateOrderUseCase createOrder;  // 依赖用例抽象，不依赖实现
    @PostMapping("/api/order")
    public Result<Long> create(@RequestBody OrderRequest req) {
        return Result.ok(createOrder.handle(req.toCommand()));   // DTO→Command 转换
    }
}
```

```powershell
# 改造步骤（渐进式，每步可编译可提交）：
# ① 建目录骨架（domain/application/adapter 三层）
# ② 搬领域对象进 domain/model（先搬 Order/OrderStatus/Money）
# ③ OrderRepository 接口提到 domain/gateway，实现类改名搬进 adapter/persist
# ④ Controller 注入改用接口，DTO→Command 转换写在 adapter/web
# ⑤ 跑依赖检查器：
cd D:\mywork\springbootai\learning\month07-ddd-architecture
javac -encoding UTF-8 -d out src\LayerDependencyChecker.java
java -cp out LayerDependencyChecker D:\mywork\springbootai\practice-projects\02-microservice-mall\mall\mall-order\src\main\java
# 验收：violations=0（截图）；如果有 Spring 的 import 出现在 domain → 回炉搬
# ⑥ 单测速度验证：Order 的纯单测不起 Spring 容器（对比改造前 @SpringBootTest 秒级→毫秒级）
```

## 4. 面试连接

**Q：讲讲六边形架构？它解决了什么问题？**
> 六边形=端口与适配器：领域核心在中心定义所有端口（用例接口+仓储/网关接口），外部世界全部以适配器身份接入——HTTP 是驱动适配器、DB 实现是被驱适配器。唯一规则是源码依赖只能指向领域：RepositoryImpl 反过来 implements 领域层的接口，这就是 DIP 的工程落地。解决的问题三个：技术替换零伤及领域（换 ORM/换支付渠道只动适配器）；领域层纯 POJO 可毫秒级单测（不启容器）；领域语言不被外部词汇污染（领域说 pay(PayOrder)，适配器层才翻译成微信 API）。收尾："我在 mall-order 落地过完整改造，用自写的依赖检查器把'domain 零框架 import'变成 CI 红线——改造后换了一次消息队列实现，领域层零改动，这就是投资回报。"

**Q：六边形和传统三层最本质的区别？**
> 依赖方向：三层的依赖是"一路向下"——Controller→Service→DAO→框架，业务在中间被技术细节上下夹击（Service 直接 import MyBatis）；六边形的依赖是"向心"——所有外围依赖领域，领域不依赖任何人。这个方向差带来一个质变：三层里"业务逻辑依赖技术实现"，六边形里"技术实现服务于业务抽象"。实践中最大的体感是测试：三层的 Service 测试要起容器+连库，六边形的领域测试是纯内存对象交互——测试速度从秒级到毫秒级，才养得起 day26"安全网测试先行"的重构习惯。

## 5. 今日验收清单

- [ ] 六边形目录结构改造完成（domain/application/adapter）
- [ ] OrderRepository/PaymentGateway 端口在领域层定义
- [ ] 依赖检查器绿灯（violations=0 截图）
- [ ] 领域纯单测跑通（不起 Spring）
- [ ] 端口/适配器两类划分能画图讲清
- [ ] `git add . && git commit -m "day05: hexagonal mall-order"`

---
[← Day 04](day04-分层架构的堕落.md) | [本月目录](README.md) | [Day 06 · 整洁架构与COLA →](day06-整洁架构与COLA.md)
