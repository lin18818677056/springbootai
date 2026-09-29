# Day 25 · 组合拳与规则引擎：营销计算重构

> **今日目标**：把设计模式组合成"拳法"——策略+工厂+模板方法+责任链重构营销优惠计算（README 验收⑤"营销规则可配置"）；判断 LiteFlow 类规则引擎的适用边界（何时上引擎，何时代码组合拳）。
> **时长**：组合拳设计 2h / 营销链落地 2.5h / 规则引擎调研 0.5h
> **今日产出**：营销优惠责任链 + 规则可配置 Demo + 引擎选型结论

## 1. 知识地图

```
单模式是招式，组合才是拳法——营销优惠计算的三层叠加难题：
  业务：一笔订单可同时命中 券( coupon )+满减( fullCut )+会员折扣( vip )+秒杀价( seckill )
  难点：①叠加顺序影响结果（先券后折扣 vs 先折扣后券）②互斥规则（秒杀价不参与叠加）
       ③新玩法月月加——if-else 地狱的发源地（day02 找到的 SRP 违反大户）

组合拳设计（四模式各就各位）：
  Promotion 接口 = 策略抽象：Money discount(OrderSnapshot ctx) + int order() + boolean support(ctx)
  VipPromotion/CouponPromotion/FullCutPromotion = 具体策略（各自封装各自的规则）
  Spring List<Promotion> 注入 + order() 排序 = 责任链（顺序可配——解决叠加顺序）
  PricePipeline.calculate() = 模板方法：过滤(support)→排序→逐个叠加→校验底价→出明细
  与 day06 COLA 扩展点连线：渠道差异化玩法（A 渠道专属券）= 扩展点 bizCode 路由的另一实例
  与 day24 状态机对照：状态机管"订单是谁"，营销链管"价格怎么算"——两条正交的规则轴

规则可配置的分级（今天的 Demo 按 L2 做）：
  L1 硬编码：规则写死在策略类里（当前默认——大部分玩法够用）
  L2 参数配置：规则常量进配置中心（Nacos——M6 day04 的存量技能），监听刷新
     例：满减阈值/会员折扣率/叠加上限——运营可调，无需发版
  L3 规则引擎（LiteFlow/QLExpress/Drools）：规则表达式外置热更新
     适用：规则由非研发维护/一天多次变更/组合爆炸（千人千面玩法）

LiteFlow 适用边界（今天的调研结论）：
  ✓ 上：组件编排型流程（ chains: THEN(a,b,WHEN(c,d)) ），EL 表达式编排已有 Bean
  ✗ 不上：规则少于 10 条且稳定——引擎的学习成本/调试成本/表达式 bug 风险 > 收益
  判断句（面试金句）："规则引擎解决的是'规则变更频率'问题，不是'代码质量'问题
   ——if-else 乱不是上引擎的理由，重构成组合拳才是；引擎是把组合拳的'出招顺序'外置。"
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Pattern Composition | 模式组合拳（多模式协同） |
| Promotion Pipeline | 营销计算管道（责任链+模板） |
| Mutually Exclusive | 互斥规则（秒杀不叠加） |
| Stacking Order | 叠加顺序（可配置化） |
| Rule Engine | 规则引擎（LiteFlow/QLExpress） |
| Hot Reload | 热更新（Nacos 配置刷新） |

## 3. 动手实操：营销优惠责任链

```java
// ① 策略抽象：一个营销活动一个类（领域层——营销上下文）
package com.mall.promotion.domain.pipeline;
public interface Promotion {
    String name();
    int order();                                        // 叠加顺序（可配置覆盖）
    boolean support(PromoContext ctx);                  // 圈品/互斥判断（规则住自己家）
    Money discount(PromoContext ctx);                   // 本环节优惠额（纯计算无副作用）
}

// ② 具体策略：满减（常量进 Nacos = L2 可配置）
@Component
public class FullCutPromotion implements Promotion {
    private final PromoConfig config;                   // Nacos @RefreshScope 配置（M6 day04）
    public int order() { return 20; }
    public boolean support(PromoContext ctx) {
        return !ctx.hasTag("seckill")                   // 互斥：秒杀不参与
            && ctx.amount().isGreaterThanOrEqual(config.fullCutThreshold());
    }
    public Money discount(PromoContext ctx) { return config.fullCutOff(); }
}
// VipPromotion(order=10 先打折)/CouponPromotion(order=30 后抵券) 同构——各 20 行内

// ③ 模板方法：管道骨架（顺序+底价校验统一收口）
@Component
public class PricePipeline {
    private final List<Promotion> chain;                // Spring 注入全部策略
    public PricePipeline(List<Promotion> list) {
        this.chain = list.stream().sorted(Comparator.comparingInt(Promotion::order)).toList();
    }
    public PriceDetail calculate(PromoContext ctx) {
        var applied = new java.util.ArrayList<AppliedRule>();
        var payable = ctx.amount();
        for (var p : chain) {                            // 责任链遍历：不命中跳过（可断链）
            if (!p.support(ctx)) continue;
            var cut = p.discount(ctx);
            payable = payable.subtract(cut);
            applied.add(new AppliedRule(p.name(), cut)); // 明细：前端展示"省在哪"
        }
        if (payable.cents() < 0) payable = Money.zero(); // 底价保护（唯一全局规则）
        return new PriceDetail(ctx.amount(), payable, List.copyOf(applied));
    }
}
// 新增玩法演练：SeckillPromotion 一个类 + support 里互斥 tag → 其他类零改动（OCP 全开）
```

```powershell
# 验证：
# ① 组合叠加：vip(9折)+满减(减20)+券(减10) 顺序=10→20→30（order 排序生效）
# ② 互斥：带 seckill tag 的 ctx → FullCut/Coupon 全部 support=false（只剩秒杀价）
# ③ 可配置：Nacos 改满减阈值 100→50 → @RefreshScope 生效（无重启，日志验证）
# ④ 明细输出：PriceDetail.appliedRules 前端可展示"会员折扣-10.00/满减-20.00/券-10.00"
cd D:\mywork\springbootai\practice-projects\02-microservice-mall\mall ; .\gradlew.bat :mall-promotion:test
git add . ; git commit -m "day25: promotion pipeline"
```

## 4. 面试连接

**Q：代码里一堆 if-else，你怎么优化？**
> 先分类再下拳：①类型分支（渠道/支付方式）——策略+工厂+Map 路由，加实现不改主流程（day22-24 支付渠道三连改造：40 行 switch 变 3 行）；②流程分支（校验步骤/管道步骤）——责任链，节点可插拔可断链（下单校验链）；③规则分支（阈值/折扣率）——常量外置配置中心热更（L2）；④复杂组合规则且高频变——才考虑规则引擎（L3）。我特别想强调一个误区：if-else 的问题不是"if 这个语法"，是"变化点没有隔离"——同一个方法里十种变化原因才是病（SRP）。重构成组合拳后代码量未必减少，但每加一个玩法只碰一个新类，回归范围从"全方法"缩到"新类"——这才是优化的本质。

**Q：什么时候需要上规则引擎（LiteFlow/Drools）？**
> 我的标准三条占二：规则变更频率高（一天多次/大促期间运营自助调）、规则维护者不是研发（运营/产品要界面化）、规则组合爆炸（千人千面标签圈品）。商城营销我们最终没上引擎：玩法十来条、变更周级、维护者就是研发——L2 配置化（Nacos 热更阈值）+组合拳已覆盖，上引擎反而引入表达式调试难、规则与代码割裂的成本。LiteFlow 我调研过的正确场景是"流程编排"：理赔审核的多级审批流，组件已有、顺序常变，EL 表达式编排很香。一句话总结："规则引擎解决变更频率问题，不解决代码质量问题——先重构出干净的组件，引擎才编排得动。"

**Q：多种营销活动叠加怎么设计？顺序和互斥怎么管？**
> 三个机制：叠加顺序用策略的 order() 值排序（先会员折扣→再满减→后抵券，这个顺序本身就是业务规则，和运营一起定死并写进术语表）；互斥用 support() 的上下文判断（秒杀订单带 seckill tag，其他活动自己 support 里检查该 tag 拒绝参与——互斥规则住在每个活动自己的类里，而不是管道里的集中 if）；底价保护是管道模板方法里唯一的全局规则（优惠后不为负）。输出必须是明细（AppliedRule 列表）而不是一个总数——客服对账、风控审计、前端"已优惠"展示都靠它。这套设计上线后新增"囤货满减"玩法：一个类 20 行 + Nacos 配阈值，半天上线零回归。

## 5. 今日验收清单

- [ ] Promotion 三策略+管道落地（排序/互斥/底价三机制）
- [ ] Nacos 配置热更验证（阈值改 100→50 无重启生效）
- [ ] 新玩法 OCP 演练（一个类接入零改动旧码）
- [ ] LiteFlow 适用边界三条判据+不上理由能讲
- [ ] `git add . && git commit -m "day25: pattern composition"`

---
[← Day 24](day24-行为型模式.md) | [本月目录](README.md) | [Day 26 · 重构手法与安全网 →](day26-重构手法与安全网.md)
