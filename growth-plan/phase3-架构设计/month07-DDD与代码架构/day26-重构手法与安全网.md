# Day 26 · 重构手法与安全网：500 行上帝方法手术

> **今日目标**：掌握《重构》核心手法（提取方法/以多态取代条件/搬移方法）与特征化测试安全网；对商城 500 行上帝方法做一次全程记录的重构手术（README 验收③"重构前后等价证明"的预演）。
> **时长**：手法学习 1h / 安全网建设 1h / 手术 3h
> **今日产出**：重构实录文档（7 步骤+前后 diff）+ 等价性证明

## 1. 知识地图

```
重构的纪律（Fowler 定义）：在不改变可观察行为的前提下，调整内部结构
  两个前提：①有安全网（测试）②小步走（每步可编译可提交——day03 演进式思想的代码版）

安全网建设（重构前的第一小时，比手术本身重要）：
  特征化测试（Characterization Test）：不测"应该怎样"，先测"现在是怎样"
    ——对上帝方法构造 10 组典型输入，记录当前输出作为"黄金样本"
    ——重构后输出必须逐字节一致（包括那些"看起来是 bug 的行为"——先保持再修！）
  三层测试金字塔（与 day05 测试收益呼应）：
    领域单测（毫秒级，不起容器）——重构的主安全网
    应用层测试（mock 网关，验证编排顺序）
    契约测试（Postman 集合回放——M6 day23 的存量资产复用）

核心手法四件（今天的手术刀）：
  ① 提取方法（Extract Method）：按"一段话一个意图"切——上帝方法的第一刀
  ② 以查询取代临时变量：tmp 变量藏计算逻辑，抽成方法让意图显形
  ③ 以多态取代条件表达式：switch(类型) → 策略/工厂（day22 的语法化）
  ④ 搬移方法（Move Method）：方法老用别的类的数据 → 搬去那个类（充血的方向！day19 的手法学名）

500 行上帝方法手术实录（createOrder 的七步，全程 git 小步提交）：
  Step1 特征化测试 10 组黄金样本（1h）
  Step2 提取方法×8：查商品快照/算价/锁库存/生成号段/装配明细/存单/发事件/发通知——500→8 个 60 行
  Step3 识别规则 vs 编排：算价/限购/超时是规则，其余是步骤（day04 的"产品提的还是架构师提的"）
  Step4 规则搬移：3 条规则 Move 进 Order.place()（day19 的正式手法化）
  Step5 switch(渠道) → 策略工厂（day22 成果接线）
  Step6 编排收口：CreateOrderAppService 三行调聚合（day17 纪律）
  Step7 等价证明：黄金样本 diff=0 + 契约回放 diff=0 + 依赖检查器绿灯
  ——手术前后对照：500 行 1 方法 → 8 个协作单元；业务 if 13→0（全在领域）；圈复杂度 47→6
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Refactoring | 重构（行为保持的结构调整） |
| Characterization Test | 特征化测试（黄金样本） |
| Extract Method | 提取方法 |
| Replace Conditional with Polymorphism | 以多态取代条件 |
| Move Method | 搬移方法（充血的手法学名） |
| Code Smell | 代码坏味道 |
| God Method | 上帝方法（圈复杂度爆表） |

## 3. 动手实操：手术关键步骤代码

```java
// ===== 术前：上帝方法（节选——500 行里的前 40 行已经能看出病）=====
public synchronized Long createOrder(CreateOrderRequest req) {
    // 80 行参数校验 if 嵌套……
    List<ProductDTO> products = new ArrayList<>();
    for (OrderItemRequest item : req.getItems()) {                 // 查商品
        ProductDTO p = productClient.get(item.getProductId());     // 无批量 N 次远程调用！
        if (p == null) throw new BizException(4004, "商品不存在");
        products.add(p);
    }
    BigDecimal total = BigDecimal.ZERO;                            // 算价（内嵌 30 行促销 if）
    for (int i = 0; i < products.size(); i++) {
        total = total.add(products.get(i).getPrice().multiply(new BigDecimal(req.getItems().get(i).getQuantity())));
        if (req.getCouponId() != null) { /* 35 行券规则 */ }
    }
    // ……锁库存(60 行)/生成单号(20 行)/装配 DO(80 行)/存库(40 行)/发 MQ(30 行)/通知(40 行)
}

// ===== Step2 提取方法（意图显形，行为未变——先切后搬）=====
public Long createOrder(CreateOrderRequest req) {
    validate(req);                                                 // ① 校验
    var snapshots = loadSnapshots(req.getItems());                 // ② 查快照（顺手改批量接口）
    var ctx = PricingContext.of(snapshots, req);                   // ③ 装配计价上下文
    var priced = priceCalculator.calculate(ctx);                   // ④ 计价（Step4 搬领域）
    lockStock(priced.stockCommands());                             // ⑤ 锁库存
    var order = Order.place(req.userId(), priced.toDrafts(), req.toAddress());  // Step4/6
    orderRepository.save(order);
    events.publish(order.pullEvents());
    return order.id();
}
// ===== Step4 搬移示例：券规则 35 行 → Coupon.applicableDiscount()（Move Method）=====
// 判断依据：那段代码只用 Coupon 和 items 的数据，从不碰 Service 的其他状态——它属于 Coupon！

// ===== 黄金样本（特征化测试骨架——重构前就写好）=====
// sample_01: 普通单1件 → {orderNo, total=1590, status=CREATED}   （金额单位：分）
// sample_02: 券+满减叠加 → {total=1280, discountDetail=[...]}
// sample_05: 超限购 6 件 → BizException 5006  ←连错误码都是样本！
// ……10 组覆盖：正常/叠券/互斥/限购/超时/空参/商品下架/库存不足/重复提交/并发同品
```

```powershell
# 手术流程（git 小步提交——每步一个 commit，出问题按步回退）：
cd D:\mywork\springbootai\practice-projects\02-microservice-mall\mall
.\gradlew.bat :mall-order:test            # 每步之后必跑（绿灯才允许下一步）
# Step 提交节奏示例：
# git commit -m "refactor: step2 extract methods (golden diff=0)"
# 等价证明脚本：回放 10 组黄金样本 diff 为空 + Postman 集合 diff 为空
git add . ; git commit -m "day26: refactoring kata"
```

## 4. 面试连接

**Q：讲一次你做过最有价值的前重构经历？（STAR）**
> 场景：商城订单 createOrder 方法 500 行、圈复杂度 47，每次营销改规则全组心惊肉跳。任务：让"改规则"变成低风险动作。行动：先花一小时建安全网——10 组特征化测试钉住当前行为（连一个已知的小怪癖：券叠加时四舍五入方向，样本也照录）；然后七步手术：提取方法切成 8 个意图单元、识别出 3 条真规则搬进 Order 聚合、渠道 switch 换策略工厂、编排收口进 AppService，每步一个 commit 且测试必绿才走下一步。结果：业务 if 从 13 降到 0（全在领域层），圈复杂度 47→6，之后三次营销需求平均改动半径 20 行以内、零回归。这段经历让我确信：重构的价值不是"代码好看"，是"变更成本降了一个数量级"。

**Q：怎么保证重构不出错？**
> 三道保险：①安全网先行——特征化测试不测"对错"只测"现状"，10 组黄金样本把行为钉死，包括错误码和边界值；②小步提交——每个重构步骤独立 commit，提取方法/搬移方法各是一步，任何一步出问题 git revert 到上一步，绝不"大爆炸式重构"；③机械验证收尾——黄金样本 diff=0、契约回放 diff=0、依赖检查器绿灯三件套，不靠肉眼 review 判断等价。还有一个心态要点：重构时忍住"顺手修 bug"的冲动——特征化测试连怪癖行为都保护，先让结构与昨天完全一致，bug 修复是重构完成后的独立 commit（带着新测试），混在一起出问题时你分不清是重构引入的还是修复引入的。

**Q：什么是特征化测试？和 TDD 有什么区别？**
> 特征化测试（Feathers《修改代码的艺术》）是把"代码现在行为"固化成样本——不管它对不对，先钉住再动刀；TDD 是先写"期望行为"的红灯测试再实现。方向相反：特征化是"如实记录过去"，TDD 是"定义想要的未来"。特征化的关键纪律是覆盖怪癖：我们发现老代码券计算用 BigDecimal.ROUND_HALF_UP 而新规范是 HALF_EVEN，特征化样本照录旧行为——重构保持旧怪癖，新怪癖作为独立需求带测试切换。没有特征化安全网就动 500 行上帝方法，等于蒙眼做手术；这就是我坚持"重构第一步永远是建安全网，不是动刀"的原因。

## 5. 今日验收清单

- [ ] 10 组黄金样本建好（含怪癖行为与错误码）
- [ ] 七步手术完成，每步 commit+测试绿灯
- [ ] 等价证明三件套（黄金 diff=0/契约 diff=0/检查器绿灯）
- [ ] 术前术后指标对照表（行数/业务 if/圈复杂度）
- [ ] `git add . && git commit -m "day26: refactoring kata"`

---
[← Day 25](day25-组合拳与规则引擎.md) | [本月目录](README.md) | [Day 27 · DDD重构订单模块总实战 →](day27-DDD重构订单模块总实战.md)
