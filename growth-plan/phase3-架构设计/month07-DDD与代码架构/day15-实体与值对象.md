# Day 15 · 实体与值对象：建模的原子粒度

> **今日目标**：掌握实体（Entity）与值对象（Value Object）的判别标准；落地 Money/Address/商品快照三个值对象（兑现 step01 的 Money 伏笔）；理解"不可变"带来的工程收益。
> **时长**：概念 1.5h / 三个值对象落地 3h / 面试整理 0.5h
> **今日产出**：Money 完整版 + Address + OrderItem 快照化 commit

## 1. 知识地图

```
实体（Entity）：有唯一标识的对象——标识不变，属性可变，生命周期跨越事务
  例：订单（orderId 相同就是同一单，哪怕金额被改）、用户、商品
  equals/hashCode 基于 ID；状态可变但必须通过行为方法变（day19 充血主角）

值对象（Value Object）：无标识、靠属性值定义的对象——值相等即对象相等
  例：金额 Money(100,CNY)、地址 Address(省,市,区,详址)、日期区间、商品快照
  equals/hashCode 基于全部属性；不可变（immutable）——要"变"就 new 一个新的

判别三问（拿到一个名词按顺序问）：
  ① 它需要被单独追踪吗？（"这 100 块和那 100 块"需要区分吗？不需要→值对象）
  ② 它的属性变了还算"同一个"吗？（地址改了门牌还是"这个地址"吗？不再是→值对象）
  ③ 它有自己的生命周期吗？（会独立创建/归档吗？不会→值对象）
  ——三问全"否"→值对象；任何一问"是"→实体
  经验法则：领域概念 80% 是值对象——贫血模型的团队往往 80% 都建成实体（反着来的）

值对象不可变的三重工程收益：
  ① 天然线程安全（不用同步——M2 并发月的心智直接复用）
  ② 可自由共享与缓存（不变的东西才能安全地 cache）
  ③ equals 语义正确（Set/Map 里行为可预期；BigDecimal 的 equals 坑：2.0≠2.00 → Money 要重写比较"值"而非精度）

商品快照值对象化（订单建模的经典决策）：
  错误设计：OrderItem 持 productId 引用商品服务实时查——价格改了/商品删了，历史订单"变脸"
  正确设计：下单瞬间把 商品名/单价/图片URL 快照成值对象冻结进 OrderItem
  ——这也顺便回答了上下文问题：订单上下文不依赖详情上下文的实时数据（day10 私有数据原则）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Entity | 实体（有唯一标识与生命周期） |
| Value Object | 值对象（无标识、不可变、按值相等） |
| Identity | 标识（实体的唯一凭据） |
| Immutability | 不可变性 |
| Snapshot | 快照（下单时刻的事实冻结） |
| Self-Encapsulation | 自封装（值对象校验入参后构造） |

## 3. 动手实操：三个值对象落地

```java
// ① Money 完整版（step01 雏形的深化——course of values 对象的标准模板）
package com.mall.order.domain.model;
public final class Money {                       // final 类+final 字段=彻底不可变
    private final long cents;                    // 用最小单位整数（分）——浮点数是金额的天敌
    private final String currency;               // 币种是金额的一部分（100CNY≠100USD）

    private Money(long cents, String currency) { // 私有构造：入参校验后才能出生
        if (cents < 0) throw new IllegalArgumentException("negative money");
        this.cents = cents; this.currency = currency;
    }
    public static Money ofYuan(BigDecimal yuan) { return new Money(yuan.movePointRight(2).longValueExact(), "CNY"); }
    public static Money zero() { return new Money(0, "CNY"); }

    public Money add(Money o)      { same(o); return new Money(cents + o.cents, currency); }
    public Money subtract(Money o) { same(o); return new Money(cents - o.cents, currency); }
    public Money multiply(int qty) { return new Money(Math.multiplyHigh(cents, qty), currency); }
    public boolean isGreaterThanOrEqual(Money o) { same(o); return cents >= o.cents; }
    private void same(Money o) { if (!currency.equals(o.currency)) throw new IllegalArgumentException("currency mismatch"); }

    @Override public boolean equals(Object o) {   // 按"值"相等：只比 cents 不比精度表示
        return o instanceof Money m && m.cents == cents && m.currency.equals(currency);
    }
    @Override public int hashCode() { return java.util.Objects.hash(cents, currency); }
    @Override public String toString() { return currency + (cents / 100) + "." + String.format("%02d", cents % 100); }
}
// ② Address：三个 String + 校验（非空），equals 按全字段——练习自写
// ③ OrderItem 快照：record OrderItemSnapshot(String productName, Money unitPrice, String imageUrl) {}
//    ——record 是 JDK 值对象语法的原生形态（自动 equals/hashCode/不可变）
```

```powershell
# 验证不可变性收益（并发读安全 + equals 正确性）：
cd D:\mywork\springbootai\learning\month07-ddd-architecture
# 写 MoneyTest：HashSet 去重（new Money(10000,CNY) 两个实例→去重成 1 个）
# 写并发读：20 线程读同一 Money 实例无锁无竞态（对比可变对象要 synchronized）
javac -encoding UTF-8 -d out src\*.java ; java -cp out MoneyTest
git add . ; git commit -m "day15: value objects"
```

## 4. 面试连接

**Q：实体和值对象的区别？怎么判别？**
> 实体有唯一标识、生命周期跨越事务、属性可变但标识不变——订单换了状态还是那一单；值对象没有标识、按属性值定义相等、不可变——100 块就是 100 块，不存在"哪一个"100 块。判别我用三问：要不要单独追踪？属性变了还算同一个吗？有独立生命周期吗？全否则是值对象。经验上领域概念 80% 应该建成值对象，而贫血团队 80% 建成了实体——因为他们只有"表"没有"模型"，表列的容器天然都是可变实体。工程上值对象的不可变带来三重收益：线程安全、可缓存共享、equals 语义正确（我们 Money 用分+币种双字段并重写 equals，绕开了 BigDecimal 的精度坑）。

**Q：为什么订单里要存商品快照，而不是引用商品？**
> 两层原因：业务上订单是"成交时刻的历史事实"——价格改了、商品下架了，历史订单不能跟着变脸，快照把事实冻结在下单瞬间；架构上这是上下文私有数据原则的落地——订单上下文若实时查详情上下文，等于把详情的可用性变成了下单的可用性（M6 熔断里最痛的就是这种跨域强依赖），快照化后下单链路对详情零依赖。实现上 OrderItemSnapshot 用 record（productName/unitPrice/imageUrl），构造时从详情 DTO 翻译——DTO→领域值对象的转换发生在 adapter，防腐层纪律一致。追问"快照占存储怎么办"：一张单几个字段，行级膨胀 <5%，对比每次联查的 RT 和可用性收益，这笔账稳赚。

**Q：值对象放领域层还是公共包？**
> 领域层。通用值对象（Money/Address）看似"谁都用"，一旦放公共包就成了 Shared Kernel（day10 的反模式）——所有上下文被同一份 Money 绑架，营销要加"积分抵扣混合金额"就得动公共包，全公司回归。正确做法：各上下文定义自己的 Money（订单的 Money 带"实付/应付"语义，营销的有"券后价"语义），哪怕代码看起来重复——**重复好过错误的耦合**（上下文间的翻译通过 API/事件，day11 已定）。只有确实稳定多年且语义全一致的基础类型才考虑提升公共包，且要过 ADR。

## 5. 今日验收清单

- [ ] Money 完整版（不可变/运算/equals/hashCode/toString）
- [ ] Address 与 OrderItemSnapshot（record）落地
- [ ] 判别三问能脱口而出
- [ ] 快照化理由（历史事实+私有数据原则）能讲
- [ ] HashSet 去重与并发读实验跑通
- [ ] `git add . && git commit -m "day15: entity vs vo"`

---
[← Day 14](day14-第二周复盘.md) | [本月目录](README.md) | [Day 16 · 聚合与聚合根 →](day16-聚合与聚合根.md)
