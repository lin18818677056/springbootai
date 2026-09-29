# Day 27 · DDD 重构订单模块总实战：验收日

> **今日目标**：兑现 M6 day28 伏笔——把 day05-26 的所有成果整合收口，mall-order 达成"重构完成版"：六边形+充血+状态机+CQRS 四件全绿，逐条对照 README 验收标准①③⑤。
> **时长**：整合查漏 3h / 全量验收 1.5h / 收口提交 0.5h
> **今日产出**：重构完成版 mall-order + 全量验收报告（6 项绿灯）

## 1. 知识地图

```
总实战 = 把散落三周的"招式"串成"一次完整落地流水线"（面试终极题的答案骨架）：
  第1步 战略先行：子域分级(day08)→通用语言(day09)→限界上下文(day10)→映射+ACL(day11)
         →事件风暴(day12/13)——产出：建模文档+聚合候选+3 则 ADR
  第2步 框架落地：六边形目录(day05)——domain/application/adapter+依赖检查器(day03)设卡
  第3步 模型建设：值对象(day15)→聚合裁决(day16)→服务分工(day17)
         →充血重构(day19)+Factory——规则 100% 进领域
  第4步 事件与读写：领域事件+Outbox(day18)→CQRS 读模型(day20)
  第5步 模式精修：状态机双重拦截(day24)+策略工厂适配器组合拳(day22/23/25)
  第6步 手术与等价：特征化安全网+七步手术(day26)——黄金样本 diff=0
  ——今天：把六步的遗留尾巴全部清零，跑全量验收

最终目录结构（mall-order 完成版——面试白板可直接默写）：
  com.mall.order
   ├── domain/                     零框架（检查器红线①）
   │   ├── model/     Order(聚合根,place/pay/cancel/transition)
   │   │              Money/AddressSnapshot/OrderItemSnapshot(record)
   │   │              OrderStatus(流转表状态机)
   │   ├── service/   PriceCalculator(领域服务)
   │   ├── event/     DomainEvent 基类+OrderCreated/OrderPaid/...
   │   └── gateway/   OrderRepository/PaymentGateway/PromotionGateway(接口)
   ├── application/   CreateOrderAppService/PayAppService/CancelAppService(零业务 if)
   └── adapter/
       ├── web/       OrderController(DTO→Command 转换)
       ├── mq/        OutboxEventPublisher/OrderListProjection
       ├── persist/   OrderRepositoryImpl(整聚合存取)/CachingOrderRepository(装饰)
       └── pay/       WechatPayChannel/AlipayChannel(SDK 防腐)

全量验收六项（对照 README 验收标准①③⑤，今天的机械检查清单）：
  ① 依赖检查器 violations=0（domain 零框架 import）
  ② application 层业务 if=0（grep 证明）
  ③ 状态机全矩阵穷举通过+脏数据拦截实验
  ④ SDK 零渗透（wxpay/alipay import 只在 adapter/pay）
  ⑤ CQRS 读模型延迟<1s+兜底校对恢复
  ⑥ 等价三件套：黄金样本/契约回放/Postman 全 diff=0
```

## 2. 核心概念（收口视角）

| 成果 | 验收手段（全部机械化） |
|------|----------------------|
| 六边形依赖 | LayerDependencyChecker violations=0 |
| 规则 100% 领域层 | application/domain grep 业务 if |
| 状态机双重拦截 | 穷举矩阵+脏数据实验 |
| SDK 防腐 | 三方包 import 范围 grep |
| 事件不丢不重 | Outbox status 流转+幂等重放 |
| 行为等价 | 黄金样本+契约回放 diff=0 |

## 3. 动手实操：整合查漏与全量验收

```powershell
# ===== 整合查漏（把三周遗留的 TODO 清一遍）=====
cd D:\mywork\springbootai\practice-projects\02-microservice-mall\mall
# 查漏点1：day16 的 itemRepository 残留？（按根红线）
Get-ChildItem -Recurse -Include *.java | Select-String -Pattern 'OrderItemRepository' 
# 查漏点2：setter 残留？（充血红线）
Get-ChildItem -Recurse -Path .\mall-order\src -Include *.java | Select-String -Pattern '\.set(Status|Amount)\('
# 查漏点3：BizException 在领域层残留？（异常翻译在 adapter 的纪律）
Get-ChildItem -Recurse -Path .\mall-order\src\main\java\com\mall\order\domain -Include *.java |
  Select-String -Pattern 'BizException'

# ===== 全量验收六连（今天的核心动作）=====
cd D:\mywork\springbootai\learning\month07-ddd-architecture
javac -encoding UTF-8 -d out src\LayerDependencyChecker.java
java -cp out LayerDependencyChecker D:\mywork\springbootai\practice-projects\02-microservice-mall\mall\mall-order\src\main\java   # ①
# ②-⑥ 按知识地图清单逐项执行并截图（六张图存 docs/acceptance/day27/）
# 全链路冒烟：下单→支付→拆单→发货→完成 五连发 + 状态机事件链核对（ES 宽表 5 状态流转）

git add . ; git commit -m "day27: ddd refactor complete - all green"
git tag mall-order-ddd-v1                       # 里程碑 tag：重构完成版
```

## 4. 面试连接

**Q：完整讲讲你用 DDD 重构订单模块的过程？（终极开放题）**
> 我按六步流水线讲：战略先行——先给商城做了子域分级（订单营销是核心域）、统一术语表、画出五个限界上下文与映射关系（支付走防腐层），然后对订单域跑了一场事件风暴，20 多个领域事件按时间线排开，三个热点争论（拆单时机/退款建模/完成触发）全部落成 ADR。框架落地——mall-order 改六边形目录，领域层零框架依赖，自写依赖检查器进 CI 设卡。模型建设——Money/快照 record 值对象、聚合裁决支付单退款单出聚合、应用服务零业务 if、setter 清零规则搬进聚合行为。事件与读写——领域事件带信封结构，Outbox 保证不丢，消费端幂等；订单列表上 ES 宽表走 CQRS。模式精修——状态机枚举流转表双重拦截、支付渠道策略工厂适配器组合拳。最后用特征化测试做了一次 500 行上帝方法的等价手术。整个过程六项机械验收全绿——DDD 对我不是名词，是一套有检查器的工作流。

**Q：DDD 落地你踩过什么坑？**
> 三个真实的坑：①一开始想把支付单塞进订单聚合——被"生命周期错位"教育后才懂聚合四原则，现在订单只存 paymentId 加 OrderPaid 事件回流；②领域事件一开始直接在聚合方法里发 MQ——领域层 import 了 Spring 和 MQ 客户端，检查器立刻红灯，改成聚合攒事件+应用服务统一发布+Outbox 出站，红线保住了纯度；③CQRS 上头——一度想给详情页也建宽表，被"按 ID 直查聚合就行"劝退，最终只有列表页走读模型。总结坑的共同点是"过度与不及"：聚合贪大、事件直发、CQRS 滥用都是没让机械验收先说话——先设检查器再写代码，偏差当场就红。

**Q：重构后最大的收益是什么？用数字说话。**
> 四个数字：①变更半径——营销限购规则改动从"通读 500 行"缩到"一行常量+一个单测"，三次真实需求平均 diff 20 行以内；②测试速度——领域单测毫秒级 vs 原来的 @SpringBootTest 8 秒，安全网从"没人愿意跑"变成"每次保存都跑"；③稳定性——业务 if 13→0 且非法流转双重拦截后，状态类线上事故零复发（此前半年 3 起）；④分布式事务量——单事务只碰订单聚合后，AT 分支用量降约 70%。最重要的是第四个：模型层的收敛直接反映到了 M6 那些分布式机制的负担上——好的领域设计是分布式复杂度的第一道减法。

## 5. 今日验收清单

- [ ] 三项查漏（item 仓储/setter/领域层 BizException）清零
- [ ] 全量验收六项全绿（六张截图归档 docs/acceptance/day27/）
- [ ] 全链路冒烟：下单→完成五状态流转+事件链核对
- [ ] `git tag mall-order-ddd-v1`（里程碑打标）
- [ ] 终极面试题答案录音自评一遍（6 分钟内讲完）

---
[← Day 26](day26-重构手法与安全网.md) | [本月目录](README.md) | [Day 28 · 全月大串讲 →](day28-全月大串讲.md)
