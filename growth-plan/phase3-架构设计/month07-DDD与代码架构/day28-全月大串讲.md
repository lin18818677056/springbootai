# Day 28 · 全月大串讲：四线合一与资产盘点

> **今日目标**：把架构演进/战略设计/战术设计/模式重构四条线合成一张全景图；盘点本月代码与文档资产；核对伏笔兑现清单；输出 M5/M6→M7 跨月连线表。
> **时长**：串讲成文 2.5h / 资产盘点 1.5h / 连线核对 1h
> **今日产出**：M7 全景图 + 资产清单 + 跨月连线表

## 1. 知识地图

```
四线合一（本月全景——面试自我介绍技术段的骨架）：
  架构演进线（W1）：好架构标准→SOLID→适配度函数→分层堕落→六边形→整洁/COLA
    主线："业务逻辑要有家，且不依赖技术细节"
  战略设计线（W2）：子域分级→通用语言→限界上下文→映射+ACL→事件风暴
    主线："先看清问题与边界，再动手建模"
  战术设计线（W3）：实体值对象→聚合→服务分工→领域事件→充血→CQRS
    主线："边界之内，规则住进对象，状态有闸口"
  模式重构线（W4）：创建型→结构型→行为型→组合拳→重构手法
    主线："模式是语法，DDD 是语义，安全网是底气"
  四线汇合点：day27 总实战——六项机械验收全绿的 mall-order

代码资产盘点（learning/month07-ddd-architecture + mall-order）：
  手写工具：LayerDependencyChecker（分层违规扫描，CI 可用）
  领域模型：Money/AddressSnapshot/OrderItemSnapshot（record）/OrderStatus 状态机
  实验类：SingletonLab（反射破坏）/OrderStateMachineLab（穷举矩阵）/MoneyTest（不可变）
  商城改造：六边形目录/聚合充血/Outbox 事件/CQRS 宽表/策略工厂适配器/营销管道
  文档资产：《订单域建模文档》/《DDD 代码规范》v1/3 则热点 ADR/重构实录/验收报告 6 图

M5→M7、M6→M7 跨月连线表（10 条——本月伏笔清单的兑现核对）：
  M5 day10 事务消息 → day18 Outbox：半消息=MQ 侧 Outbox，二选一 ✅
  M6 day01 微服务拆分 → day10 上下文即服务边界：理论根基补齐 ✅
  M6 day04 Nacos 配置 → day25 营销规则 L2 热更：@RefreshScope 复用 ✅
  M6 day06 fallbackFactory → day11 ACL 降级姿势：一体两面 ✅
  M6 day19 对账 → day08 支付子域之辩+day20 读模型兜底校对：对账思想三处开花 ✅
  M6 day23 RED 指标 → day09 术语表词根进指标名+day20 宽表延迟告警 ✅
  M6 day28 伏笔(step01 DDD 骨架) → day19/27 充血重构+总实战：✅ 本月主线闭环
  M6 day30 思考题(一事务一聚合 vs AT) → day16 正式回答（乘法关系）✅
  M2 day05 状态模式 → day24 订单状态机双重拦截：✅
  M3 day12 动态代理 → day23 AOP 代理本质：原理到框架产品化 ✅
  ——10/10 兑现，本月没有悬空伏笔
```

## 2. 核心概念（全月名词总表·快检）

| 周次 | 必须脱口而出的五个词 |
|------|---------------------|
| W1 | 适配度函数 / 依赖倒置 / 端口与适配器 / 依赖规则 / COLA 扩展点 |
| W2 | 核心域 / 通用语言 / 限界上下文 / 防腐层 / 领域事件（风暴贴纸语义） |
| W3 | 聚合根 / 真不变量 / 领域服务 / Outbox / CQRS 投影 |
| W4 | 策略工厂 / 防腐适配器 / 流转表 / 特征化测试 / 组合拳 |

## 3. 动手实操：全景图绘制与资产归档

```powershell
# 资产归档（learning 仓库文档区）：
cd D:\mywork\springbootai\learning\month07-ddd-architecture
javac -encoding UTF-8 -d out src\*.java          # 全量编译通过性检查
java -cp out LayerDependencyChecker D:\mywork\springbootai\practice-projects\02-microservice-mall\mall\mall-order\src\main\java
# 最后一遍绿灯（day27 的⑥项之一，收尾复查）

# 商城里程碑复核：
cd D:\mywork\springbootai\practice-projects\02-microservice-mall\mall
git log --oneline -20          # 回看本月的 commit 串（讲故事的时间线）
git tag                        # mall-order-ddd-v1 在列

# 全月文档核对：
cd D:\mywork\springbootai\growth-plan\phase3-架构设计\month07-DDD与代码架构
Get-ChildItem docs -Recurse | Format-Table Name -AutoSize
# 应有：event-storming/(贴纸墙图+事件清单+ADR×3+建模文档) / ddd-code-standard.md / acceptance/day27/×6图
git add . ; git commit -m "day28: month retrospective & assets"
```

## 4. 面试连接

**Q：（面试官）你这几个月技术成长的主线是什么？（月度级叙事）**
> 三个月三级跳，我用商城一个项目贯穿：M5 打地基——把消息队列和分布式一致性吃透，事务消息/对账/补偿这些"跨系统信任机制"全部手撸过；M6 上微服务——从拆分做到稳定性全链路，限流熔断灰度可观测，混沌演练修复了三个真实隐患；M7 炼内功——用 DDD 把订单模块从贫血巨石重构成六边形充血模型，六项机械验收全绿，营销需求的变更半径缩小一个数量级。串起来一句话：**先让系统跨得出去（分布式），再让系统稳得住（稳定性），最后让系统改得动（领域建模）**——改得动这一步，是前两步复杂度的终极解药：M6 的 AT 用量降 70% 就是模型收敛的直接回报。

**Q：DDD 听起来概念很多，你们团队怎么推广？**
> 我的原则是"机械先行，名词靠后"：不先教战略设计术语，先上依赖检查器和 grep 门禁——红灯一亮，同事自然问"为什么我的 Service 不能 import MyBatis 到 domain"，这时候六边形和 DIP 的讲解才有人听。第二步是拿订单模块当样板间——建模文档、状态机、验收报告全部开源在组内，别人照着抄目录结构就能起步。第三步规范落地成 MR checklist（day21 的五节规范），六项验收变成合并门禁。三个月后团队状态：新服务默认六边形目录、事件必带信封、聚合评审看四原则——没人再讨论"要不要 DDD"，因为检查器和样板间已经替我们回答了。

**Q：如果重新来一遍，你会在哪些地方做得不一样？**
> 三个复盘：①术语表应该更早建——我先动了模型后补语言，导致"核销/作废"两个词在代码和 PRD 漂移了两周，返工了一次命名（day09 的教训：先统一语言再动模型）；②CQRS 该晚一周上——事件基建（Outbox/幂等）还没稳就接了宽表投影，排查过一次投影丢数据其实是 Outbox 中继的坑，基建先行才是正确顺序；③事件风暴应该拉产品做完整一场——自导自演版省了协调成本，但热点①"拆单时机"如果有产品现场拍板，ADR 不会返工第二次。这三个"不一样"比成功经验更能说明我真的踩过、改过。

## 5. 今日验收清单

- [ ] 全景图（四线合一）手绘+成文
- [ ] 代码/文档资产清单归档（learning 编译通过+商城 tag 在列）
- [ ] 跨月连线表 10/10 核对（无悬空伏笔）
- [ ] "三个月主线"叙事能 3 分钟脱稿讲
- [ ] `git add . && git commit -m "day28: month retrospective"`

---
[← Day 27](day27-DDD重构订单模块总实战.md) | [本月目录](README.md) | [Day 29 · M7模拟验收 →](day29-M7模拟验收.md)
