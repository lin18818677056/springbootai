# 第 7 月：DDD（Domain-Driven Design，领域驱动设计）与代码架构

> **本月一句话目标**：从"面向数据库表编程"升级为"面向领域建模编程"。DDD 是区分"熟练工"与"架构师"的第一道分水岭，P8 面试聊架构必问业务建模。本月底微服务商城完成 step03。

## 本月目标与验收标准

- [ ] 讲清演进式架构思想：分层架构 → 六边形架构（Hexagonal）→ 整洁架构（Clean Architecture）→ DDD
- [ ] 掌握战略设计（Strategic Design）：限界上下文（Bounded Context）、通用语言（Ubiquitous Language）、上下文映射（Context Mapping）
- [ ] 掌握战术设计（Tactical Design）：实体/值对象/聚合根/领域服务/领域事件/资源库
- [ ] 用事件风暴（Event Storming）完成商城订单域建模，输出领域模型图
- [ ] 把商城订单模块从贫血模型（Anemic Model）重构为充血模型（Rich Domain Model），落实 CQRS（Command Query Responsibility Segregation，命令查询职责分离）
- [ ] 微服务商城完成 step03（分布式事务），并输出 1 篇博客

---

## 每日计划

### 第 1 周：架构演进与设计原则

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 架构的定义 | 架构 = 重要决策的结构化表达；软件架构两大维度：逻辑架构（模块职责）与物理架构（部署拓扑）；康威定律（Conway's Law：系统结构映射组织结构） | 写下你司系统的架构图并标注问题 | 能用一句话回答"什么是好架构"（合适 + 演进友好） |
| D2 | 设计原则回顾 | SOLID：单一职责（SRP）/开闭（OCP）/里氏替换（LSP）/接口隔离（ISP）/依赖倒置（DIP）；高内聚低耦合（High Cohesion & Low Coupling） | 在自己代码里找出 3 处违反 SRP 的类并重构 | 每个原则能举出自己改过的例子 |
| D3 | 演进式架构 | 演进式架构（Evolutionary Architecture）、适配度函数（Fitness Function：可测试性/耦合度度量）、技术债（Technical Debt）管理 | 给商城模块定义 3 个适配度函数 | 能讲清"技术债如何量化与偿还" |
| D4 | 分层架构演进 | 传统三层（Controller/Service/DAO）问题：业务逻辑漏到各层、Service 巨石；领域分层（接口层/应用层/领域层/基础设施层） | 画出商城订单模块的两种分层对比图 | 能指出三层架构里业务逻辑散落的证据 |
| D5 | 六边形架构 | 端口与适配器（Ports and Adapters）：领域核心 + 驱动侧适配器（REST/MQ 入）+ 被驱动侧适配器（DB/MQ 出）、依赖倒置的落地 | 把一个服务改造成六边形目录结构 | 依赖方向全部指向领域层 |
| D6 | 整洁架构与 COLA | 整洁架构（Clean Architecture：同心圆依赖规则）、COLA（阿里应用架构：适配层/应用层/领域层/基础设施层 + 扩展点） | 阅读 COLA 架构源码（alibaba/COLA） | 能对比 COLA 与标准 DDD 分层差异 |
| D7 | 周复盘 | 架构风格串讲 | 周记 + 手绘整洁架构同心圆图 | 能脱稿讲三种架构的取舍 |

### 第 2 周：DDD 战略设计（Strategic Design，战略设计）

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 领域与子域 | 领域（Domain）、核心子域（Core Domain，护城河）、支撑子域（Supporting）、通用子域（Generic，可外购/开源）；问题空间 vs 方案空间 | 对商城划分子域并分级 | 能讲清"为什么支付是通用子域" |
| D2 | 通用语言 | 通用语言（Ubiquitous Language）：业务与研发统一术语表；语言即模型，模型即代码 | 为商城输出中英术语表（订单/履约/履约单/子单） | 术语表被代码命名采用 |
| D3 | 限界上下文 | 限界上下文（Bounded Context，模型适用的明确边界）、上下文即微服务边界候选；反模式：一个大模型（Shared Kernel 滥用） | 用"商品"一词在不同上下文的含义差异举例（商品详情 vs 库存商品 vs 营销商品） | 能讲清上下文边界如何指导微服务拆分 |
| D4 | 上下文映射 | 上下文映射（Context Mapping）九种关系：合作（Partnership）、共享内核（Shared Kernel）、客户-供应商（Customer-Supplier）、遵奉者（Conformist）、防腐层（ACL，Anti-Corruption Layer）、开放主机服务（OHS）、发布语言（PL）、各行其道（Separate Ways）、大泥球（Big Ball of Mud） | 画出商城全域上下文映射图 | 能指出哪里需要防腐层及理由 |
| D5 | 事件风暴(上) | 事件风暴（Event Storming，Alberto Brandolini）：橙色领域事件（Domain Event）→ 蓝色命令（Command）→ 黄色聚合（Aggregate）→ 紫色策略（Policy）→ 红色外部系统 | 组织一场（自导自演）订单域事件风暴 | 产出 ≥ 15 个领域事件与时间线 |
| D6 | 事件风暴(下) | 从事件时间线划分限界上下文、发现核心流程与异常流程（补偿/取消） | 完成订单域建模文档 | 建模文档含事件清单+上下文划分 |
| D7 | 周复盘 | 战略设计串讲 | 博客：《一次事件风暴工作坊实录：把业务聊成架构》发布 | 博客含完整产出图 |

### 第 3 周：DDD 战术设计（Tactical Design，战术设计）与代码落地

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 实体与值对象 | 实体（Entity：有唯一标识、有生命周期）、值对象（Value Object：无标识、不可变、可整体替换）；判别三问 | 把地址/金额/商品快照建模为值对象 | 能讲清"订单地址为什么不是实体" |
| D2 | 聚合与聚合根 | 聚合（Aggregate：一致性边界）、聚合根（Aggregate Root：外部只能通过根访问）；聚合设计四原则（小聚合/通过 ID 引用/一事务一聚合/最终一致跨聚合） | 审查"订单聚合"边界：订单项/地址/支付单归属 | 能讲清"为什么支付单不进订单聚合" |
| D3 | 领域服务与应用服务 | 领域服务（Domain Service：跨实体逻辑）、应用服务（Application Service：用例编排、无业务规则）、仓储（Repository：聚合持久化抽象） | 重构订单创建用例：AppService 编排 + DomainService 承载规则 | 业务规则 100% 在领域层 |
| D4 | 领域事件落地 | 领域事件（Domain Event：已发生的既成事实，过去时命名）、进程内发布（Spring Event）vs 跨上下文（MQ）、事件溯源（Event Sourcing）概念 | 下单成功发布"订单已创建"事件驱动积分/通知 | 事件命名与结构符合规范 |
| D5 | 贫血 vs 充血 | 贫血模型（Anemic Model：只有 getter/setter，逻辑在 Service）的问题（封装缺失、规则散落）、充血模型（Rich Model：行为内聚）、Factory 工厂负责复杂创建 | 把 OrderService 里的 3 条业务规则搬进 Order 聚合 | Order.cancel() 内聚状态机校验 |
| D6 | CQRS 与查询优化 | CQRS（命令查询职责分离）、读模型（Read Model：ES 宽表/物化视图）、写模型归一化；什么时候不需要 CQRS（简单 CRUD 是过度设计） | 为订单列表页建读模型（ES 或宽表） | 列表查询不再扫订单主表 |
| D7 | 周复盘 | 战术设计串讲 | 周记 + 《DDD 代码规范》（聚合/事件/仓储命名与分层规则） | 规范可评审通过 |

### 第 4 周：设计模式（Design Pattern）在架构中的应用 + 项目推进

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 创建型模式 | 单例（Singleton：枚举/双检锁）、工厂（Factory/Abstract Factory）、建造者（Builder）；在框架里的应用（Spring Bean 作用域、Builder 链式配置） | 重构支付渠道创建逻辑为策略+工厂 | 消灭 if-else 渠道分支 |
| D2 | 结构型模式 | 适配器（Adapter：防腐层落地）、装饰器（Decorator：Java IO）、代理（Proxy：AOP 本质）、门面（Facade：网关聚合接口） | 用适配器封装第三方支付 SDK | 第三方变更不影响领域层 |
| D3 | 行为型模式 | 策略（Strategy）、模板方法（Template Method）、责任链（Chain of Responsibility：网关过滤器/审批流）、观察者（Observer：事件驱动）、状态机（State：订单状态流转） | 订单状态机：用枚举+事件实现合法流转矩阵 | 非法流转编译期/运行期双重拦截 |
| D4 | 组合拳实战 | 策略+工厂+模板方法消除业务分支；规则引擎（Rule Engine：LiteFlow/QLExpress）适用边界 | 重构营销优惠计算（满减/折扣/叠加互斥规则） | 规则可配置，新增玩法不改主流程 |
| D5 | 重构手法 | 重构（Refactoring）清单：提取方法/内联/搬移/以多态取代条件、机械式重构与安全网（测试先行）、IDE 重构快捷键肌肉记忆 | 对商城一段 500 行"上帝方法"完成重构 | 重构前后行为一致（测试证明） |
| D6 | 项目推进 | 微服务商城 step03：Seata 分布式事务落地（订单-库存-账户） | 完成 `practice-projects/02-microservice-mall/step03` | 异常注入自动回滚通过 |
| D7 | 月度总复盘 | DDD+设计模式串讲 | 月记 + 博客：《我用 DDD 重构了订单模块：从贫血到充血》发布 | 达成本月全部验收标准 |

---

## 本月产出清单

1. 商城领域建模文档（事件风暴产出 + 上下文映射图 + 术语表）
2. 《DDD 代码规范》与《重构手法清单》
3. 博客 2 篇（事件风暴实录 + 充血模型重构）
4. 微服务商城 step03 交付

## 本月术语表

| 英文 | 中文 |
|------|------|
| Bounded Context | 限界上下文（模型的适用边界） |
| Ubiquitous Language | 通用语言（业务与技术统一词汇） |
| Aggregate Root | 聚合根（一致性与事务边界入口） |
| Anti-Corruption Layer (ACL) | 防腐层（隔离外部模型污染） |
| Anemic / Rich Domain Model | 贫血 / 充血领域模型 |
| Event Storming | 事件风暴（工作坊式领域建模方法） |
| Command Query Responsibility Segregation (CQRS) | 命令查询职责分离 |
| Event Sourcing | 事件溯源（以事件序列作为唯一事实来源） |
| God Object / God Method | 上帝对象 / 上帝方法（职责过多的反模式） |

> 下月：`../month08-高并发高可用设计.md`——P8 核心战场：扛住流量的系统设计方法论。
