# Day 02 · SOLID 深挖：从"知道"到"举出自己改的例子"

> **今日目标**：五个原则每个都落一个"自己代码里的真实例子"——名词人人都背得出，例子才是分水岭；找出商城代码里 3 处违反 SRP 的类并重构。
> **时长**：原则对照 2h / 代码重构 2.5h / 总结 0.5h
> **今日产出**：SOLID 自查表（5 原则×自己例子）+ 3 处 SRP 重构 diff

## 1. 知识地图

```
为什么 SOLID 听了十年还是写不好：因为停留在"定义记忆"而不是"模式识别"
  ——今天的方法：每个原则配"违反时的代码味道"（Code Smell）+ 我改过的例子

S 单一职责（SRP）：一个类只有一个"变化的原因"
  味道：类名里有 And/Manager/Util；一个类的改动总是连带另一类需求
  商城实例：OrderService 同时干"下单编排+库存校验+金额计算+发通知"
    ——四个变化原因（流程变/库存规则变/计价变/通知方式变）
    → 重构：编排留 AppService，规则进 Order 领域（day19 充血），通知走事件（day18）
  ⚠ SRP 的"职责"=变化的原因（axiom of change），不是"功能数量"

O 开闭（OCP）：对扩展开放，对修改关闭
  味道：新增一种支付渠道要改 if-else 五处
  商城实例：PaymentService.create(type) 里 if(wechat) else if(alipay)...
    → 策略+工厂（day22 组合拳）；OCP 是目标，策略模式是手段——别为两个分支上策略
  ⚠ "消灭所有 if-else"是误导：分支稳定且少就直写，频繁扩展的分支才抽象

L 里氏替换（LSP）：子类必须能无损替换父类（契约不削弱）
  味道：子类重写方法抛 UnsupportedOperationException；子类加强前置条件
  经典反例：正方形 extends 长方形（setWidth 破坏独立性）——数学对，建模错
  商城实例：BasePayChannel.refund() 默认抛异常，微信渠道覆写支持、积分渠道不支持
    → 违反 LSP：调用方 base.refund() 随时可能炸 → 重构：接口拆分（ISP）或能力标记
  ⚠ LSP 是"行为子类型"问题——继承前问：is-a 在所有行为维度成立吗？

I 接口隔离（ISP）：客户端不依赖它不需要的方法
  味道：接口里一堆方法，实现类一半方法空着或抛异常
  商城实例：ProductService 接口 15 个方法，order 只用 getById——却编译依赖全部
    → M6 day02 预告的 API 模块在此落地：product-api 只含 order 需要的契约
  ⚠ ISP 与微服务：Feign 接口按消费者拆（order 的 ProductClient ≠ marketing 的）

D 依赖倒置（DIP）：高层依赖抽象，抽象不依赖细节
  味道：领域层直接 import MyBatis/JdbcTemplate/第三方 SDK 类名
  商城实例：OrderService 直接 new WechatPayClient()
    → 定义 PaymentGateway 接口（领域层），客户端做适配器（基础设施层）
  ⚠ DIP 是六边形架构的理论根基（day05）：依赖方向指向领域——本月最重要的一原则
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Single Responsibility Principle (SRP) | 单一职责（一个变化原因） |
| Open-Closed Principle (OCP) | 开闭原则（扩展开放修改关闭） |
| Liskov Substitution Principle (LSP) | 里氏替换（行为子类型） |
| Interface Segregation Principle (ISP) | 接口隔离（按消费者拆接口） |
| Dependency Inversion Principle (DIP) | 依赖倒置（依赖抽象） |
| Code Smell | 代码味道（坏味道的可识别特征） |
| Contract | 契约（方法的前置/后置条件） |

## 3. 动手实操：3 处 SRP 重构

```java
// 重构前（mall-order 遗留代码，M6 快速开发的债）：
@Service
public class OrderService {
    public Long createOrder(CreateOrderRequest req) {
        // 职责①编排 + ②计价规则 + ③库存校验 + ④通知 —— 四个变化原因！
        BigDecimal amount = req.getPrice().multiply(BigDecimal.valueOf(req.getCount()));
        if (req.getCount() > 100) throw new BizException(5001, "单笔上限");   // 计价规则
        if (!productClient.checkStock(req.getProductId())) ...                // 库存校验
        orderMapper.insert(...);
        smsClient.send(req.getUserId(), "下单成功");                          // 通知
        return order.getId();
    }
}

// 重构后（SRP 切开 + 为 day17/19 的 DDD 演进打前站）：
@Service
public class OrderAppService {                          // 只剩职责①：用例编排（无规则）
    private final OrderPricer pricer;                   // ②计价规则独立类
    private final StockChecker stockChecker;            // ③库存校验独立类（领域服务候选）
    private final OrderNotifier notifier;               // ④通知（day18 将换成事件）
    public Long createOrder(CreateOrderRequest req) {
        OrderAmount amount = pricer.price(req);          // 规则藏在 Pricer 里
        stockChecker.check(req);
        Order order = Order.create(req, amount);
        orderMapper.insert(order);
        notifier.onCreated(order);
        return order.getId();
    }
}
// 重构验收：每个类回答"你因为什么会变"——答案必须唯一
// 逆向检查：重构后 createOrder 有没有"漏规则"？——对照重构前逐行 diff（day26 安全网预习）
```

```powershell
# 自查流程（在你司代码或商城里执行）：
# ① 列出 3 个类名带 Manager/Service/Util 的类 → 逐方法问"变化原因"
# ② 统计一个"最胖类"的 import：出现 ≥3 个不相关领域的关键词 → SRP 违规实锤
# ③ 用 git log 看改动历史：一个类经常被"不相干的两类 commit"同时改动 → 变化原因不止一个
git log --oneline -- mall/order/service/OrderService.java | Select-Object -First 20
# ④ 重构三处 → 每处写一句"违反/味道/重构手法/效果"进笔记（面试的例子就攒出来了）
```

## 4. 面试连接

**Q：讲讲 SOLID，最好举你改过的代码。**
> 我不复述定义，按"味道→例子→重构"讲三个：SRP——OrderService 曾同时管编排/计价/库存/通知四个变化原因，git 历史里计价需求和通知需求总是改同一个类；拆成 AppService+Pricer+Checker+Notifier 后，改动互不连带。OCP——支付渠道 if-else 五处扩展点，策略+工厂后新渠道只加一个类（这是 day22 的实战，今天先立目标）。DIP——OrderService 直接 new WechatPayClient，领域层被第三方细节污染；抽 PaymentGateway 接口后依赖指向抽象，这是六边形架构的理论根基。收尾观点："SOLID 不是教条是味觉——闻到味道才动手，两个分支的 if-else 没必要上策略模式，过度设计比坏味道更贵。"

**Q：LSP 说的是"里氏替换"，实际开发里怎么用？**
> 一句话：子类不能在任何行为维度上破坏父类契约——包括前置条件不能更严、后置条件不能更松、不变量要维持。我踩过的实例：支付渠道基类有 refund()，积分渠道不支持就抛 UnsupportedOperationException——调用方拿着 BaseChannel 引用随时可能炸，这是典型 LSP 违规。修复两条路：接口隔离（把 refund 拆到 Refundable 接口，渠道按能力实现）或能力标记（supportsRefund() 先问再调）。判断口诀：建模时问"is-a 在所有行为上成立吗"，正方形/长方形是数学真、建模假的经典陷阱——继承的代价是行为契约，不是字段复用，字段复用请用组合。

## 5. 今日验收清单

- [ ] SOLID 自查表：5 原则 × 各 1 个自己的例子
- [ ] 3 处 SRP 重构完成（git diff 留档）
- [ ] "变化原因"判别法能讲（不是功能数量）
- [ ] LSP 的支付渠道反例能白板画
- [ ] DIP 与六边形的关系一句话说清
- [ ] `git add . && git commit -m "day02: solid in action"`

---
[← Day 01](day01-什么是好架构.md) | [本月目录](README.md) | [Day 03 · 演进式架构与适配度函数 →](day03-演进式架构与适配度函数.md)
