# Day 16 · 聚合与聚合根：一致性的边界

> **今日目标**：掌握聚合四原则；用 day13 的聚合候选表审查订单聚合边界；正式回答 M6 day30 思考题（一事务一聚合 vs Seata AT 是否矛盾）。
> **时长**：概念 1.5h / 边界审查 2.5h / 思考题整理 1h
> **今日产出**：订单聚合边界图 + 聚合审查结论表 + 思考题答案

## 1. 知识地图

```
聚合（Aggregate）：一组必须共同满足业务不变量的对象——一致性的边界
聚合根（Aggregate Root）：聚合对外的唯一入口——外部只能持有根的引用
  边界内：对象之间直接引用（OrderItem 挂在 Order 里）
  边界外：只能通过 ID 引用其他聚合（Order 存 paymentId，不持有 Payment 对象）

聚合设计四原则（Vaughn Vernon 的有效聚合设计）：
  ① 保护真不变量（True Invariant）：边界由"必须同时成立"的规则决定
     订单：sum(OrderItem.amount)==totalAmount、items 非空——必须同生共死→一个聚合
  ② 聚合尽量小（Design Small）：聚合越大，事务冲突概率越高（M6 AT 全局锁的领域视角！）
  ③ 聚合间通过 ID 引用（Reference By ID）：持有对象引用=把别人的不变量拖进自己的事务
  ④ 一事务只改一个聚合（One Transaction One Aggregate）：跨聚合一致性用领域事件最终一致

订单聚合边界审查（day13 聚合候选表的正式裁决）：
  进聚合：Order(根) + OrderItem(快照列表) + Money(值对象) + 收货地址快照
  不进：Payment（支付单）——支付有自己的生命周期与状态机，塞进来=双根冲突+事务面扩大
  不进：Refund（退款）——一个订单 N 笔退款，订单只记 refundedAmount 投影（day13 热点②）
  不进：Stock（库存）——跨上下文，本来就不能进（day10 私有数据）
  不进：UserInfo（用户）——ID 引用 + 收货人快照进聚合（信息按需快照，不拉活对象）

正式回答 M6 day30 思考题：一事务一聚合 与 Seata AT 全局锁矛盾吗？
  不矛盾——是同一"缩小锁范围"思想在两个层面的表达：
  · 聚合层面（建模时）：一事务一聚合，把本地事务的锁面缩到最小（行数少/时间短）
  · 分布式层面（运行时）：跨聚合不可避免的调用（下单扣库存），AT 的全局锁只在
    "跨服务写同一行"时介入，且锁持有时间=本地事务时长（M6 day16 的结论）
  → 聚合设计得越小，进入分布式事务的概率和锁持有时间就越小——两个原则是乘法关系
  一句话："聚合四原则是'少打架'，AT 是'打架时的裁判'——前者降低打架频率，后者保证公平。"
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Aggregate | 聚合（一致性边界） |
| Aggregate Root | 聚合根（外部唯一入口） |
| True Invariant | 真不变量（必须同时成立的规则） |
| Reference by Identity | 按 ID 引用（跨聚合松耦合） |
| Consistency Boundary | 一致性边界 |
| Transactional Boundary | 事务边界（≈聚合边界） |
| Eventual Consistency | 最终一致（跨聚合的让步） |

## 3. 动手实操：聚合边界图 + 封装验证

```text
订单聚合边界图（今天的产出）：
  ┌──────────────── Order（聚合根）────────────────┐
  │ orderId: OrderId          status: OrderStatus   │
  │ items: List<OrderItem>  ← 快照值对象（day15）   │
  │ totalAmount: Money      refundedAmount: Money   │
  │ address: AddressSnapshot                        │
  │   行为：place()/pay()/split()/cancel()/refund() │
  └─────┬───────────────┬──────────────┬───────────┘
     userId(引用)   paymentId(引用)  stock 无引用
        ↓               ↓              ↓（事件 StockLocked 通知库存上下文）
     [用户聚合]      [支付单聚合]      [库存上下文]
  审查问题清单（每个聚合过一遍）：
  ① 这条不变量离开根还能被保护吗？（不能→进聚合）
  ② 这个对象有独立生命周期吗？（有→出聚合，ID 引用）
  ③ 聚合加载会不会拖出大数据量？（items 上限：一般订单 ≤50 行，可接受）
```

```java
// 聚合封装的两个红线验证：
// 红线①：外部不能绕过根拿到内部对象的可变引用
public class Order {
    private final List<OrderItem> items;                       // 内部可变列表
    public List<OrderItem> items() {
        return List.copyOf(items);                             // 防御性拷贝——只读视图出边界
    }
    public void pay(Money paid) {                              // 状态只能经行为变更
        assertStatus(OrderStatus.CREATED);
        if (!paid.isGreaterThanOrEqual(totalAmount))
            throw new DomainException("支付金额不足");
        this.status = OrderStatus.PAID;
        registerEvent(new OrderPaid(orderId, paid));           // 事件随聚合产生（day18 发布）
    }
}
// 红线②：Repository 只按根进出（day17 实现），禁止 itemRepository 直查——查行必先过根
// orderItemRepository.findByOrderId(x)  ← 这种接口出现即聚合封装被击穿
```

## 4. 面试连接

**Q：聚合怎么设计？说说四原则。**
> 聚合是一致性边界，根是外部唯一入口。四原则：①保护真不变量——必须同时成立的规则决定边界，订单的"明细合计等于总额"让 items 必须和根同生共死；②聚合尽量小——大聚合事务冲突概率高，我们的裁决是支付单/退款/库存全部出聚合，订单只留 items 和快照值对象；③跨聚合按 ID 引用——持有对象引用等于把别人的不变量拖进自己事务；④一事务只改一个聚合——跨聚合一致性靠领域事件最终一致。这四条在我们订单域全部用过：8 个命令的候选聚合审查后收敛为 3 进 4 出，事务面缩小一半。

**Q：支付单为什么不放进订单聚合？（M6 思考题正式回答）**
> 三个理由：生命周期错位——订单超时取消时支付单可能在渠道侧还在处理中，两者状态机节奏不同，放一起会互相拖住；不变量不同——订单保护"明细=总额"，支付单保护"支付金额=应付金额且渠道流水唯一"，两套真不变量强行同住，任何一方变更都要重审对方；事务面扩大——合并后一次支付动作要锁整个订单聚合（items 大列表），AT 全局锁持有时间被拉长，热点场景吞吐直接受损。所以订单存 paymentId 做 ID 引用，支付结果通过 OrderPaid 事件回流——"知道发生了什么，不管理它怎么发生"。这句是我想留给面试官的记忆点。

**Q：一事务一聚合和分布式事务（Seata AT）冲突吗？**
> 不冲突，是同一思想的两端。聚合原则说"建模时把强一致的圈子画小"，AT 说"运行时不得已跨圈时，把锁的时间缩到最短（M6 实测：锁持有≈本地事务时长）"。两者是乘法关系：聚合越小→跨聚合写越少→进入 AT 分支的概率越低→全局锁竞争越少。我们订单域改造前后对比：贫血巨石时代一个事务改 5 张表，改造后单事务只碰 order+items 两表，AT 用量降了约 70%，剩下跨服务的扣库存走 TCC（M6 day17）。所以答案是一句话："聚合设计是减少打架，分布式事务是打架时的裁判——裁判再好，不如少打。"

## 5. 今日验收清单

- [ ] 聚合四原则能结合订单实例讲
- [ ] 订单聚合边界图（3 进 4 出裁决+理由）
- [ ] 防御性拷贝与 Repository 按根红线落地
- [ ] M6 思考题答案成文（三层论证）
- [ ] `git add . && git commit -m "day16: aggregate design"`

---
[← Day 15](day15-实体与值对象.md) | [本月目录](README.md) | [Day 17 · 领域服务与应用服务 →](day17-领域服务与应用服务.md)
