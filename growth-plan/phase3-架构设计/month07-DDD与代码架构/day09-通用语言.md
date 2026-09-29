# Day 09 · 通用语言：让代码说业务的话

> **今日目标**：理解 Ubiquitous Language——业务/产品/研发使用同一套词汇且被代码命名采用；产出商城订单域中英术语表；用"命名一致性检查"验证语言即模型。
> **时长**：概念 1h / 术语表共建 2.5h（拉产品评审） / 命名检查 1.5h
> **今日产出**：订单域中英术语表 ≥30 条 + 代码命名不一致清单（改造项）

## 1. 知识地图

```
通用语言（Ubiquitous Language）：
  在一个限界上下文内，业务/产品/研发对同一概念用同一个词——而且是"英文单词级"的对齐
  三种人三种词的现状（反面）：
    产品说"核销" / 研发说 verify / 代码里叫 checkOrder —— 一次需求沟通三轮翻译
    PRD 说"订单作废" / 代码里是 deleteOrder —— 语义漂移：作废≠删除（审计还查吗？）
  语言即模型（Eric Evans 的核心洞察）：
    改语言=改模型：当团队开始说"核销"而不是"消费"，核销就应该是一个显式方法 verify()
    术语不在代码里出现 → 语言死了 → 模型腐化开始

术语表怎么"活"在代码里（三个落点）：
  ① 类/方法名：Order.pay()/cancel()/verify()——动词用术语表的
  ② 领域事件名：OrderCreated/OrderPaid——过去时+术语表词根（day18 详谈命名规范）
  ③ 日志与监控：log.info("order.verified")、指标 mall_order_verify_total
     ——M6 day23/25 的 RED 指标与 EFK 日志，排查时按术语表 grep 秒级召回
     （这就是为什么 M6 说"结构化日志是可观测的地基"——地基里埋的是语言）

翻译成本 = 通用语言的量化收益：
  无通用语言：需求评审 30min 翻译 + 代码 review 争论 + 测试用例对不齐 ≈ 每需求多 2h
  有通用语言：PRD 里直接写 OrderPaid 事件、术语表链接直达 —— 沟通协议压缩
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Ubiquitous Language | 通用语言（业务与技术统一词汇） |
| Glossary / Term Table | 术语表（语言的载体） |
| Model-Driven Design | 模型驱动设计（语言驱动模型） |
| Naming Consistency | 命名一致性（语言落到代码） |
| Semantic Drift | 语义漂移（同一词各自理解） |
| Verb / Noun Alignment | 动词/名词对齐（方法名/类名用术语表词根） |

## 3. 动手实操：商城订单域术语表 + 命名检查

```markdown
<!-- docs/glossary-order.md（节选，完整版≥30条，拉产品一起评审） -->
| 中文 | 英文（代码词根） | 定义（一句话） | 代码落点 |
|------|-----------------|---------------|---------|
| 下单 | place order | 创建订单并锁定库存的完整动作 | Order.place() |
| 支付 | pay | 买家完成付款，订单进入已支付 | Order.pay() / OrderPaid |
| 取消 | cancel | 买家或系统终止未支付订单 | Order.cancel() / OrderCancelled |
| 核销 | verify | 凭证类订单在门店被确认使用 | Order.verify() |
| 履约 | fulfill | 已支付订单的发货/服务交付过程 | Fulfillment 上下文（独立） |
| 拆单 | split | 一单多商品按仓库拆成多个子单 | OrderSplitPolicy |
| 超时关单 | auto-close | 30分钟未支付系统自动取消 | OrderAutoClosePolicy |
| 定金/尾款 | deposit / balance | 预售分两笔支付 | DepositPayment |
——规则：PRD 里中文词 → 链接本表；代码 review 检查英文词根是否被采用
```

```powershell
# 命名一致性检查（把"语言死了"找出来）：
cd D:\mywork\springbootai\practice-projects\02-microservice-mall\mall\mall-order
# ① 找出所有业务方法名，对照术语表：
Get-ChildItem -Recurse -Include *.java | Select-String -Pattern "public\s+\w+\s+(\w+)\(" | Measure-Object
# ② 找语义漂移嫌疑（这些词不在术语表里就要警惕）：
Get-ChildItem -Recurse -Include *.java | Select-String -Pattern "checkOrder|deleteOrder|doProcess|handleBiz"
# ③ 产出《命名不一致清单》：旧名→术语表名→改动点（SOLID 的"重构"纪律执行）
```

## 4. 面试连接

**Q：什么是通用语言？它解决什么问题？**
> 在一个限界上下文内，业务、产品、研发用同一套词汇，并且这套词汇被代码命名真实采用——产品说"核销"，代码里就有 Order.verify() 方法、OrderVerified 事件、mall_order_verify_total 指标。它解决的是"翻译成本"和"语义漂移"：没有它，需求评审一次、代码再翻译一次、排查日志还得猜词，一个"订单作废还是删除"能吵半小时。我落地的方式是《订单域中英术语表》——PRD 引用中文词、代码用英文词根、日志与监控指标埋同一词根，排查时按词 grep 秒级召回（M6 的 EFK 体系就是它的下游受益者）。语言不是文档，是活的协议。

**Q："语言即模型"怎么理解？**
> Evans 的洞察：词汇的演化就是模型的演化。当团队把"下单"从 create 说起 place，模型就发生了质变——place 意味着"占库存+算价+生成流水号"是一个完整动作，create 只是把行插进表里。我们重构订单模块时第一个动作就是把 OrderService.createOrder 改名聚合的 Order.place()，改名的过程中自然暴露出"占库存失败要不要回滚已生成号段"这个被掩盖了两年的问题——**改名改出来的 bug，说明语言不准的地方模型必有漏洞**。这就是为什么我把术语表评审放在重构之前：先统一语言，再动模型。

## 5. 今日验收清单

- [ ] 订单域术语表 ≥30 条（含产品评审记录）
- [ ] 命名不一致清单 ≥5 条并完成改名
- [ ] "作废≠删除"这类语义漂移案例 ≥1 个写进博客素材
- [ ] 术语表与日志/指标词根对齐抽查
- [ ] `git add . && git commit -m "day09: ubiquitous language"`

---
[← Day 08](day08-领域与子域分级.md) | [本月目录](README.md) | [Day 10 · 限界上下文 →](day10-限界上下文.md)
