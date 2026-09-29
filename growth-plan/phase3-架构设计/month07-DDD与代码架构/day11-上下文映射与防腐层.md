# Day 11 · 上下文映射与防腐层：九种关系一张图

> **今日目标**：掌握上下文映射图的九种关系类型；重点落地 ACL 防腐层（兑现 M6 day06 fallbackFactory 伏笔）；产出商城全域上下文映射图并标注每条线的类型与理由。
> **时长**：概念 1.5h / 映射图绘制 2h / ACL 代码 1.5h
> **今日产出**：全域上下文映射图（9+ 条线含类型标注）+ 营销 SDK 防腐层代码

## 1. 知识地图

```
上下文映射（Context Mapping）：把上下文之间的协作关系显式画出来——九种关系速查：
  ① Partnership 合作：两个团队共同进退（同步发布）——如订单与库存
  ② Shared Kernel 共享内核：共享一小块模型（谨慎！昨天刚自查过滥用）
  ③ Customer-Supplier 客户-供应商：上游照顾下游需求（库存供订单）
  ④ Conformist 遵奉者：下游直接follow上游模型（不改不抗）——如对接集团统一会员
  ⑤ ACL 防腐层：下游加一层翻译，绝不让外部模型渗进来 ★今天的主角
  ⑥ OHS 开放主机服务：上游把服务开放为标准协议（我们的 OpenAPI）
  ⑦ Published Language 发布语言：OHS 配套的公开标准语言（如订单事件 JSON Schema）
  ⑧ Separate Ways 各行其道：不集成（砍需求也是一种架构决策）
  ⑨ Big Ball of Mud 大泥球：混合边界别硬建模，圈出来隔离它（遗留系统）

ACL 防腐层的完整心智（今天代码落地）：
  位置：本上下文的 adapter 里（六边形的被驱适配器——day05 的 PaymentGateway 实现就是 ACL！）
  职责三件事：翻译（外部 DTO→本域模型）/ 隔离（外部字段污染止步于此）/ 稳定（外部改版只改这层）
  与旧知识连线：
  · M6 day06 fallbackFactory = ACL 的降级姿势（外部挂了也不能污染本域——返回兜底模型）
  · M2 day06 适配器模式 = ACL 的实现手法（day23 结构型模式再系统化）
  · SkyWalking day26 的链路里，ACL 是"外部依赖 span"的边界——超时/重试策略都挂在它身上
  判断要不要 ACL 的一句话：
  "这个外部系统的模型，我们愿意让它出现在领域层的 import 里吗？"——不愿意，就建 ACL
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Context Map | 上下文映射图 |
| Partnership / Customer-Supplier | 合作 / 客户-供应商 |
| Conformist | 遵奉者（下游跟随上游模型） |
| Anti-Corruption Layer (ACL) | 防腐层（翻译+隔离外部模型） |
| Open Host Service (OHS) | 开放主机服务 |
| Published Language | 发布语言（公开数据契约） |
| Separate Ways | 各行其道（放弃集成） |

## 3. 动手实操：全域映射图 + 营销 ACL

```text
商城全域上下文映射图（今天的产出，每条线标类型+一句理由）：
  [订单]─Partnership─[库存]      （大促共同发布，锁库存协议共担）
  [订单]─C-S─[营销]              （订单是客户：优惠券核销找营销，营销尽量配合订单节奏）
  [订单]─ACL──[支付渠道]          （微信/支付宝模型绝不渗透进订单域——day05 PaymentGateway）
  [订单]─ACL──[三方营销中台SDK]    （今天代码：外部券模型→本域 Coupon 模型）
  [会员]─OHS+PL─全系统           （统一会员 OpenAPI + 用户事件 Schema）
  [订单]─Conformist─[集团审计]    （审计模型集团统一定，我们照传不翻译）
  [遗留ERP]─大泥球圈出─ACL─[库存] （老系统不重构，防腐层隔离）
  画图纪律：先画关系类型，再问"为什么是这个类型"——答不出理由的线=没有决策的线
```

```java
// 防腐层落地：三方营销中台 SDK 的模型翻译（adapter 层）
package com.mall.order.adapter.promotion;
// 外部世界的词汇（SDK 的模型——绝不让它越过这一层）
import com.thirdparty.promo.sdk.model.PromoResult;      // 外部：promoType/discountRate/rawJson
import com.thirdparty.promo.sdk.MarketingClient;

// 本域的词汇（领域层定义的端口，day05 同款手法）
// domain/gateway/PromotionGateway: Coupon apply(CouponCommand cmd)

@Component
public class PromotionGatewayImpl implements PromotionGateway {   // implements 领域接口
    private final MarketingClient client;                          // 三方 SDK 封死在这
    private final FallbackCache fallbackCache;                     // M6 降级思想延续

    @Override
    public Coupon apply(CouponCommand cmd) {
        PromoResult r;
        try {
            r = client.tryPromo(toSdkReq(cmd));                    // ① 翻译出去：本域→外部语言
        } catch (Exception e) {
            return fallbackCache.lastGood(cmd);                    // ② 外部挂了：返回兜底，不让异常污染
        }
        return toCoupon(r);                                        // ③ 翻译回来：外部→本域模型
    }
    private Coupon toCoupon(PromoResult r) {                       // 字段级翻译+语义校正
        // 外部 discountRate 是"折扣率0.85"，本域 Coupon 是"减免金额"——语义都不同！
        return new Coupon(r.getPromoId(), cmd.amount().multiply(BigDecimal.valueOf(1 - r.getDiscountRate())));
    }
}
// 验收红线：grep 领域层目录，不得出现 com.thirdparty 的 import
```

## 4. 面试连接

**Q：什么是防腐层？为什么需要它？**
> ACL 是上下文之间的一道翻译隔离层：外部系统的模型只能到达这层，被翻译成本域的模型后才放行。需要它的原因：外部模型的变更频率和语义都不受我们控制，直接渗入领域层，等于把领域模型的主权交给外部——三方 SDK 升个版，领域层跟着改。我落地的两处：支付网关（day05 的 PaymentGateway，微信/支付宝模型止步于 adapter）和营销中台（PromotionGatewayImpl，外部"折扣率"语义翻译成本域"减免金额"，连语义偏差都在这层修正）。它和 M6 的 fallbackFactory 是一体两面：防腐层管"进来的模型干净"，降级兜底管"外部挂了领域不脏"。验收手段是机械的：领域层 grep 不到第三方包的 import。

**Q：九种上下文关系能说几种？各自什么时候用？**
> 常用的六种：Partnership（团队共同进退，如订单库存大促联动）、Customer-Supplier（上游照顾下游，库存供订单）、Conformist（跟随上游模型，对接集团统一审计——翻译成本大于收益时认了）、ACL（外部模型必须隔离）、OHS+Published Language（我们把会员服务开放成 OpenAPI+事件 Schema）、Separate Ways（不集成，砍需求）。两个"负向"的也认识：Shared Kernel 滥用是耦合之源（day10 自查过），Big Ball of Mud 不硬建模、圈出来用 ACL 隔离。我画商城映射图时要求每条线写理由——画图的过程就是把集成决策显式化的过程，这比图本身值钱。

**Q：什么时候会选择 Conformist 而不是 ACL？**
> 成本收益决策：上游模型稳定、语义和我基本一致、翻译收益低时，Conformist 更划算——比如集团统一审计系统，它的模型就是集团标准，我翻译一遍反而制造偏差，照传即 compliance。三个条件下转向 ACL：外部模型脏（字段语义和本域冲突）、外部变更频繁（需要隔离震动）、或外部是三方商业产品（永远不受控）。一句话："翻译是要花钱的，但污染是要命的一一权衡要用在每条线上，而不是全公司一刀切。"

## 5. 今日验收清单

- [ ] 全域上下文映射图 ≥7 条线（每条含类型+理由）
- [ ] 九种关系能说清 ≥6 种及适用条件
- [ ] PromotionGatewayImpl 落地，领域层无三方 import（grep 验证）
- [ ] ACL 与 fallbackFactory/适配器模式的连线能讲
- [ ] `git add . && git commit -m "day11: context map & acl"`

---
[← Day 10](day10-限界上下文.md) | [本月目录](README.md) | [Day 12 · 事件风暴实战(上) →](day12-事件风暴实战上.md)
