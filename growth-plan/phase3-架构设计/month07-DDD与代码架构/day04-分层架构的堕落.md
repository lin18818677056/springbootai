# Day 04 · 分层架构的堕落：三层架构的业务逻辑去哪了

> **今日目标**：用"找证据"的方式证明三层架构的业务逻辑散落；画出订单模块"三层 vs 领域四层"对比图；理解各层职责的精确边界——为 day05 六边形改造定框架。
> **时长**：证据收集 1.5h / 对比重构 2.5h / 总结 1h
> **今日产出**：订单模块双分层对比图 + 领域四层职责清单 + 逻辑散落证据集

## 1. 知识地图

```
传统三层（Controller → Service → DAO）的问题——不是"错"，是"管不住业务逻辑"：
  ① 业务逻辑漏进 Controller：参数校验/权限/转换/甚至 if 状态判断
     （"下单人数超限直接 return"——这是业务规则还是接口细节？分不清）
  ② Service 层变"巨石"：所有规则堆在 XxxService.create/update/delete 里
     ——day02 重构前的 OrderService 就是（编排+计价+库存+通知四合一）
  ③ DAO 层开始"懂业务"：SQL 里写 CASE WHEN 状态、WHERE 里埋业务条件
     （"SELECT * FROM stock WHERE available - frozen >= 3"——3 是业务规则！）
  ④ 模型贫血：Entity 只有 getter/setter（M6 day30 埋的"贫血模型"伏笔本周兑现）
  诊断法：把三层代码里的"if/公式/魔法数字"全部标黄——数一数业务规则散落在几层
    → 证据：订单模块 23 条业务规则，13 条在 Service、6 条在 Controller、4 条在 SQL

领域四层（DDD 标准分层）——给业务逻辑一个"唯一的家"：
  ┌────────────────────────────────────────────┐
  │ 接口层（User Interface）：协议适配/参数转换/鉴权——不含业务规则          │
  │ 应用层（Application）：用例编排（做什么步骤）——不含业务规则              │
  │ 领域层（Domain）：业务规则全在这（实体/值对象/领域服务/领域事件）★核心    │
  │ 基础设施层（Infrastructure）：DB/MQ/第三方 SDK 的技术实现——为领域提供能力 │
  └────────────────────────────────────────────┘
  判断规则（面试金句）：
  "这条逻辑变了，是产品经理提的还是架构师提的？"
   ——产品提的（满减规则/状态流转/计价）→ 领域层；技术提的（重试/缓存/协议）→ 外围
  依赖方向：接口层→应用层→领域层←基础设施层（注意基础设施"倒着"依赖领域——day05 详谈）

商城订单模块的两种分层对比（今天的图）：
  三层版：OrderController(校验+规则) → OrderService(编排+规则+SQL) → OrderMapper(规则)
  四层版：OrderController(转换) → OrderAppService(编排) → Order聚合.place()/pay()/cancel()
         → OrderRepository(接口) ← OrderRepositoryImpl(MyBatis 实现)
  对比验收：同一条规则（"取消订单必须是待支付状态"）在两版的落点：
   三层：Service 里 if(order.status==待支付) else throw
   四层：Order.cancel() 内 assertStatus(CREATED)——规则住在对象身上
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Three-Tier Architecture | 传统三层（表现/业务/持久） |
| Layered Architecture | 分层架构（领域四层） |
| Anemic Domain Model | 贫血领域模型（规则不在对象上） |
| Business Logic Leakage | 业务逻辑泄漏（散落到外围层） |
| Use Case | 用例（应用层编排的单位） |
| Domain Layer | 领域层（业务规则的唯一居所） |
| Repository Interface | 仓储接口（领域定义，基础设施实现） |

## 3. 动手实操：证据收集与对比重构

```powershell
# 证据收集（对商城 mall-order 执行）：
# ① 提取所有业务 if：Select-String -Path src\**\*.java -Pattern "if\s*\(" | 统计分布
# ② 找魔法数字：Select-String -Pattern "\d{2,}" --include=*.java 过滤时间/端口后剩余的
# ③ 找 SQL 里的业务：mapper XML 里搜 CASE WHEN / 业务列名条件
# ④ 输出《业务逻辑散落地图》：规则编号/内容/当前所在层/应在层 —— 5 条以上
```

```java
// 对比重构：同一条规则的两种住法
// 【三层版】规则住在 Service：
public void cancel(Long orderId) {
    OrderDO order = orderMapper.selectById(orderId);
    if (!"CREATED".equals(order.getStatus()))           // 规则散落在外
        throw new BizException(5004, "仅待支付订单可取消");
    if (System.currentTimeMillis() - order.getCreatedAt().getTime() > 30 * 60 * 1000)
        throw new BizException(5005, "超30分钟不可自助取消");   // 30/60/1000 全是魔法数
    order.setStatus("CANCELLED");
    orderMapper.update(order);
}
// 【四层版】规则住在聚合（day15/16 将进一步把时间窗变成值对象）：
public class Order {                                    // 领域层：充血的聚合根
    private OrderStatus status;
    private Instant createdAt;
    private static final Duration SELF_SERVICE_WINDOW = Duration.ofMinutes(30);
    public void cancel() {
        assertStatus(OrderStatus.CREATED);              // 状态机校验内聚（day19 充血主角）
        if (Duration.between(createdAt, Instant.now()).compareTo(SELF_SERVICE_WINDOW) > 0)
            throw new DomainException("超30分钟不可自助取消");   // 规则+常量都住在对象里
        this.status = OrderStatus.CANCELLED;            // 只能通过行为改状态（封装！）
    }
}
// 对比验收三问：规则在哪层？状态能不能被绕过（order.setStatus 裸调）？测试写在哪更自然？
```

## 4. 面试连接

**Q：三层架构有什么问题？DDD 四层怎么解决？**
> 先讲证据再讲方案："我们订单模块盘过一次，23 条业务规则里 13 条在 Service、6 条漏在 Controller、4 条埋在 SQL 的 CASE WHEN 里——三层架构不是错了，是'没给规则一个唯一的家'，谁都能往里塞。"四层的解法：接口层只做协议转换、应用层只做用例编排、领域层收容全部业务规则、基础设施层提供技术能力。判断口诀："这条规则变了是产品提的还是架构师提的？"——产品提的进领域层。最大的收益是状态不再能被绕过：三层版任何人都能 order.setStatus('PAID')，四层版状态只能通过 pay()/cancel() 行为变更，非法流转在编译期就被挡住（day24 状态机双重拦截）。

**Q：四层会不会太重？小项目也要这么分吗？**
> 分层是复杂度的投资，按"规则数量×变化频率"决定：5 条以内规则且稳定的 CRUD 模块，两层（接口+Service）完全够——上了四层才是过度设计（本月 day20 讲 CQRS 时同样观点）；规则超过 15 条或每周变（营销/计价/风控），四层的收益立刻覆盖成本。我的实践节奏："商城订单模块先跑三层攒了 23 条规则，散落问题暴露后按四层重构——先长肉再塑形，比一开始就画完美架构更符合演进式思想（day03）。分层的正确姿势是'从乱到治'，不是'从治到治'。"

## 5. 今日验收清单

- [ ] 《业务逻辑散落地图》≥5 条规则（含所在层证据）
- [ ] 双分层对比图（同一规则两种住法）
- [ ] Order.cancel() 四层版重构完成（魔法数消灭）
- [ ] "产品提的还是架构师提的"口诀能讲
- [ ] 分层轻重判断标准能举例
- [ ] `git add . && git commit -m "day04: layered evolution"`

---
[← Day 03](day03-演进式架构与适配度函数.md) | [本月目录](README.md) | [Day 05 · 六边形架构 →](day05-六边形架构.md)
