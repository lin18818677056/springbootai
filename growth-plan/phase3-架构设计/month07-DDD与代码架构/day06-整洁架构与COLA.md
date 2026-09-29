# Day 06 · 整洁架构与 COLA：同心圆依赖规则

> **今日目标**：理解整洁架构（Clean Architecture）的同心圆与唯一依赖规则；研读阿里 COLA 架构的分层与扩展点设计；输出"三种架构对比取舍表"。
> **时长**：整洁架构 1.5h / COLA 源码研读 2.5h / 对比总结 1h
> **今日产出**：整洁架构同心圆手绘图 + COLA 研读笔记 + 三架构取舍表

## 1. 知识地图

```
整洁架构（Clean Architecture，Robert C. Martin）——多种架构的"最大公约数"：
  同心圆四层（由内向外）：
  ① Entities（实体）：最内层——企业级业务规则（领域模型）
  ② Use Cases（用例）：应用级业务规则（编排）——对应应用层
  ③ Interface Adapters（接口适配器）：Controller/Presenter/Gateway 转换层
  ④ Frameworks & Drivers（框架与驱动）：Web/DB/UI——最外层的细节

  唯一规则（The Dependency Rule）：源码依赖只能由外圈指向内圈，永不向内
   ——内圈完全不知道外圈的存在（不知道有 HTTP、不知道有 MySQL）
   ——跨边界的数据用简单的 DTO（不传 ORM 实体进来）
   ——其实和六边形是同一思想的不同画法：六边形横着画，整洁竖着画（同心圆）

  整洁架构的金句（面试引用率极高）：
  "框架是细节，数据库是细节，Web 是细节——把它们推迟到外圈，
   让业务规则不依赖任何细节"（A detail is something that can be postponed）

COLA 架构（阿里张建飞，Clean Object-oriented and Layered Architecture）：
  中国本土化的整洁架构落地，四层+扩展点：
  ┌─ 适配层（Adapter）：Web/Mobile/Wireless 各端接入——controller 所在
  ├─ 应用层（App）：CommandExecutor/QueryExecutor（CQRS 风格的用例类！）
  │    + spring-demo 的 Executor 每个用例一个类（OrderCreateExecutor）
  ├─ 领域层（Domain）：实体/领域服务/Gateway 接口（与六边形同构）
  └─ 基础设施层（Infrastructure）：GatewayImpl/DB/MQ/缓存实现
  COLA 特色三件：
  ① 命令查询分离的用例类：每个用例一个 Executor 类（不是大 Service 方法）
  ② 扩展点（Extension Point）：业务身份（bizCode）+ 扩展点接口——"不同租户不同逻辑"
     的标准化打法（与 day25 策略模式互补：扩展点是架构级，策略是代码级）
  ③ DTO 与领域对象严格分离（Cmd/DTO/CO 与 Entity 分层传递）

三种架构对比取舍（今天的核心输出表）：
  维度       | 三层架构   | 六边形      | 整洁/COLA
  依赖方向   | 向下       | 向心        | 向内（同心圆）
  业务规则住 | Service    | 领域核心    | Entities+UseCases
  框架隔离   | 无         | 适配器隔离  | 外圈隔离
  学习成本   | 低         | 中          | 中高（COLA 有组件规范）
  适用       | 简单 CRUD  | 中型领域系统| 大型多租户/多端
  ——本质同源：都是"领域不依赖细节"；差异在落地规范详细程度
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Clean Architecture | 整洁架构（同心圆） |
| The Dependency Rule | 依赖规则（源码依赖只能向内） |
| Entity / Use Case | 实体层/用例层（内两圈） |
| Interface Adapter | 接口适配层 |
| COLA | 阿里分层架构（Adapter/App/Domain/Infra） |
| Command Executor | 命令执行器（COLA 的用例类） |
| Extension Point | 扩展点（业务身份定制） |
| DTO (Data Transfer Object) | 数据传输对象（跨层数据隔离） |

## 3. 动手实操：COLA 研读与对照

```powershell
# COLA 源码研读（github alibaba/COLA）——带着三个问题读：
# ① cola-components 的分层是怎么用 Maven/Gradle 模块强制的？
#    （cola-component-domain-starter 等——模块化=物理边界，M6 day02 的 BOM 思想同源）
# ② CommandExecutor 的注册与路由机制？（每个用例一个类+统一入口——对比我们 AppService）
# ③ 扩展点 Extension 的 bizCode 匹配逻辑？（业务身份树：bizId→useCase→scenario 三级定位）
# 产出《COLA 研读笔记》：三个问题的答案+与我们 mall-order 的差异表
```

```java
// COLA 风格的用例类（对照 day04 的 OrderAppService 编排法）：
// 传统：一个 AppService 注入 10 个依赖，20 个方法
// COLA：每个用例一个 Executor，依赖收敛、职责单一（SRP 的架构级应用）
@Component
public class OrderCreateCmdExe {                 // 一个用例一个类
    private final OrderGateway orderGateway;     // 领域网关接口（基础设施实现）
    private final StockGateway stockGateway;
    public Long execute(OrderCreateCmd cmd) {    // 命令对象进，结果出——纯函数式编排
        // 编排逻辑（无规则——规则在 domain 的聚合里）
        return 0L;
    }
}
// 对照结论写进笔记：
// ① Executor 拆分 vs AppService 多方法：小用例爆炸 vs 大类聚合——按用例数量取舍
// ② 我们的六边形 + COLA 的 Executor 拆分 = 本月最终形态（day27 收口时合并）
// ③ 扩展点思想先记下：营销优惠计算（day25）的多租户场景可以借鉴 bizCode 路由
```

## 4. 面试连接

**Q：整洁架构的核心是什么？跟六边形什么关系？**
> 核心就一条规则：源码依赖只能由外向内，内圈不知道外圈——框架、数据库、Web 全是"细节"，可以被推迟到最外圈（Uncle Bob 的原话：detail 是可以延后决定的东西）。它和六边形本质同源：都是"领域不依赖细节"，只是画法不同——六边形横向画端口/适配器，整洁纵向画同心圆，DDD 分层是第三种表述。面试表达观点："我不纠结'哪张图更正统'，三张图说的是同一件事：给业务逻辑一个不依赖任何技术的家。工程上我落地的是六边形目录+COLA 的 Executor 用例拆分——图是思想，目录和依赖检查器才是落地物。"（用工具和验证器说话，不是用名词）

**Q：了解 COLA 吗？它有什么特色？**
> COLA 是阿里的整洁架构本土化：适配层/应用层/领域层/基础设施四层，特色三个：①用例类化——每个用例一个 CommandExecutor/QueryExecutor，替代大 Service 多方法，依赖自然收敛（CQRS 思想在代码组织的体现，day20 会展开）；②扩展点机制——用"业务身份"（bizCode 三级定位）路由不同实现，多租户/多行业定制的标准打法；③组件化——架构约束用独立 Gradle 模块强制，不是文档约定。我的实践："我在 mall-order 借鉴了它的 Executor 拆分和 DTO 严格分层，扩展点机制用在了营销优惠计算的原型上——COLA 的价值是把'整洁'从理念变成可 copy 的工程规范。"

## 5. 今日验收清单

- [ ] 同心圆四层手绘图（标注依赖规则箭头）
- [ ] COLA 三问研读笔记完成
- [ ] Executor 用例类对照代码跑通
- [ ] 三架构取舍表完成（含适用场景）
- [ ] "框架是细节"金句能引用并解释
- [ ] `git add . && git commit -m "day06: clean & cola"`

---
[← Day 05](day05-六边形架构.md) | [本月目录](README.md) | [Day 07 · 第一周复盘 →](day07-第一周复盘.md)
