# Day 19 · 贫血到充血重构：把规则搬回家

> **今日目标**：兑现 M6 day28 伏笔——把 step01 的 DDD 骨架展开为完整重构；3 条业务规则从 OrderService 搬进 Order；cancel() 状态机内聚；复杂创建用 Factory 收口。
> **时长**：方法准备 0.5h / 重构 3.5h / 等价验证 1h
> **今日产出**：充血重构 diff（前后对照）+ 规则搬移清单 + 单测绿灯

## 1. 知识地图

```
贫血模型的病灶（day04 已取证，今天动刀）：
  ① 规则散落：OrderService 里 13 条规则，改一条要通读 500 行
  ② 状态裸奔：order.setStatus("PAID") 谁都能调，非法流转靠 review 人肉拦截
  ③ 测试困难：测一条规则要 mock 4 个依赖+起 Spring 容器（秒级）vs 领域单测毫秒级

充血重构三步法（每天 3 小时的节奏，规则一次搬 3 条，不贪多）：
  第一步 选规则（从 day04《散落地图》挑最高频变更的 3 条）：
    R1 限购规则：同一商品单笔限 5 件（营销月月改）
    R2 超时窗口：下单 30 分钟未付自动关（运营会调）
    R3 金额校验：支付金额≥应付（资金安全）
  第二步 搬家（方法级三原则：常量随行/校验随方法/异常说业务话）：
    魔法数 → private static final 常量（30min 窗口）
    散落的 if → 聚合方法内的 assertXxx 私有校验
    BizException(5004) → DomainException("仅待支付订单可取消")（错误码在 adapter 翻译）
  第三步 封状态：setter 全删，状态变更只能走 pay()/cancel()/refund() 行为方法
    ——编译器成为第一道防线：想绕过状态机？先过不了编译

Factory 收口复杂创建：
  Order.place() 不是简单 new：要生成号段/装配快照/算初始金额/注册事件——4 步组装逻辑
  放构造函数太重、放 AppService 又是规则泄漏 → 静态工厂 Order.place(...)（day13 热点①的落点）
  复杂聚合（订单+items+地址快照）的"整体出生"由工厂保证：不存在出生即残缺的聚合

等价性验证（重构的底线）：搬移前后对外行为完全一致
  旧测试全绿 + 新增领域单测覆盖 3 条规则 + 接口契约测试（M6 的 Postman 集合回放）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Anemic Model | 贫血模型（规则与数据分离） |
| Rich Model | 充血模型（规则住在对象上） |
| Tell, Don't Ask | 告诉不要问（调行为而非取状态判断） |
| Law of Demeter | 迪米特法则（少链式取值） |
| Factory Method | 工厂方法（复杂创建收口） |
| Behavior-Preserving Refactor | 行为保持重构 |

## 3. 动手实操：3 条规则的搬移 diff

```java
// ========== 重构前（贫血）：规则散落在 OrderService ==========
public void cancel(Long orderId) {
    OrderDO o = orderMapper.selectById(orderId);
    if (!"CREATED".equals(o.getStatus()))                    // R-状态规则在 Service
        throw new BizException(5004, "仅待支付订单可取消");
    if (System.currentTimeMillis() - o.getCreatedAt().getTime() > 30*60*1000)  // R2 魔法数
        throw new BizException(5005, "超30分钟不可自助取消");
    o.setStatus("CANCELLED");                                // 状态裸奔
    orderMapper.update(o);
}
public void pay(Long orderId, BigDecimal paid) { /* R3 金额校验同样散落... */ }

// ========== 重构后（充血）：规则回家 ==========
public class Order {                                          // 领域层，零框架 import
    private static final Duration SELF_SERVICE_WINDOW = Duration.ofMinutes(30); // R2 常量随行
    private static final int PER_ITEM_LIMIT = 5;              // R1 营销月月改→就改这一行

    public static Order place(UserId uid, List<ItemDraft> drafts, AddressSnapshot addr) {
        if (drafts == null || drafts.isEmpty()) throw new DomainException("订单不能为空");
        drafts.forEach(d -> { if (d.quantity() > PER_ITEM_LIMIT)              // R1 限购
            throw new DomainException("单笔限购" + PER_ITEM_LIMIT + "件"); });
        var o = new Order(OrderId.next(), uid, assemble(drafts), addr);
        o.registerEvent(new OrderCreated(o.id.value(), uid.value(), o.totalAmount));
        return o;                                             // 工厂保证整体出生
    }
    public void cancel() {                                    // 状态机内聚
        assertStatus(OrderStatus.CREATED);                    // 非法流转第一道拦截（day24 第二道）
        if (Duration.between(createdAt, Instant.now()).compareTo(SELF_SERVICE_WINDOW) > 0)
            throw new DomainException("超30分钟不可自助取消");  // R2 规则回家
        this.status = OrderStatus.CANCELLED;
        registerEvent(new OrderCancelled(id.value()));
    }
    public void pay(Money paid) {
        assertStatus(OrderStatus.CREATED);
        if (!paid.isGreaterThanOrEqual(totalAmount))          // R3 资金规则回家
            throw new DomainException("支付金额不足");
        this.status = OrderStatus.PAID;
        registerEvent(new OrderPaid(id.value(), paid));
    }
    private void assertStatus(OrderStatus expect) {           // 私有校验：外部看不见
        if (this.status != expect) throw new DomainException("订单状态不允许该操作");
    }
}
// AppService 收口后：OrderAppService.cancel(id) → orderRepo.find→order.cancel()→save→publish
// ——三行编排，零规则（day17 纪律保持）
```

```powershell
# 等价性验证三连：
cd D:\mywork\springbootai\practice-projects\02-microservice-mall\mall
.\gradlew.bat :mall-order:test          # ① 旧测试全绿（行为保持）
# ② 新领域单测：OrderTest 不起 Spring，6 个用例 <100ms（对比 @SpringBootTest 8s）
# ③ 接口契约回放：M6 day23 的 Postman 集合重放，响应体逐字段 diff
git add . ; git commit -m "day19: anemic to rich"
```

## 4. 面试连接

**Q：贫血模型有什么问题？怎么重构到充血？**
> 三个病灶：规则散落（我们订单 Service 500 行藏 13 条规则，改限购要通读全文）、状态裸奔（setStatus 谁都能调，非法流转靠 review 拦）、测试昂贵（一条规则要 mock 四个依赖起容器）。重构三步法：从散落地图挑变更最频繁的 3 条规则先搬（限购/超时窗口/金额校验）——常量随行、校验随方法、异常说业务话；然后删光 setter 封状态，状态变更只能走 pay/cancel 行为方法，编译器成第一道防线；复杂创建用静态工厂 place() 收口保证聚合整体出生。等价性底线：旧测试全绿+契约回放 diff 为空。搬完最直观的收益：营销月月改的限购数，现在只动一行 PER_ITEM_LIMIT，改动半径从"通读 500 行"缩到"一行常量"。

**Q：充血之后，事务和编排放哪？**
> 事务在应用服务的 @Transactional，编排也在应用服务——充血不等于聚合管一切：Order.cancel() 只管"我能不能取消、取消后状态和事件"，至于"从哪加载、存哪、事件发到哪、要不要跨服务补偿"，是 AppService 的编排职责。这正是 day17 的两种服务分工：聚合是规则的家，应用服务是用例的指挥家。一个常见的追问"聚合方法里能调 Repository 吗"——不能：聚合方法只碰自己的数据和传入的领域对象，需要外部数据（如优惠券）由 AppService 取好"喂"进来（或领域服务封装），这样聚合才保持纯内存可测。

**Q：什么时候不该充血？**
> 三种情形让步：纯报表/查询逻辑——读路径没有不变量要保护，硬建充血模型是给查询套枷锁（day20 用 CQRS 直接把读侧拆出去）；稳定 CRUD 的支撑域——day08 分级为支撑的模块，规则 5 条以内，两层架构够用，充血是过度投资；批量数据管道——ETL 类逻辑的领域是"数据流"不是"实体生命周期"，函数式管道更自然。我的口径："充血是为'有状态机、有不变量、规则高频变'的核心域准备的——先分级再选型，不给通用域上重模型。"

## 5. 今日验收清单

- [ ] 3 条规则搬移 diff（常量随行/校验随方法/异常说业务话）
- [ ] setter 清零，状态只能走行为方法
- [ ] Order.place() 工厂收口（整体出生）
- [ ] 旧测试全绿 + 领域单测 <100ms + 契约回放 diff 空
- [ ] `git add . && git commit -m "day19: rich model refactor"`

---
[← Day 18](day18-领域事件落地.md) | [本月目录](README.md) | [Day 20 · CQRS与读模型 →](day20-CQRS与读模型.md)
