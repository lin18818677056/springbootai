# 第 16 月：源码精读（Source Code Reading）与性能优化体系

> **本月一句话目标**：用"源码三遍法"精读 Spring/Spring Boot/RocketMQ 三大框架主线源码，并建立"性能优化方法论体系"（不是零散技巧，而是可复用的排查框架）。这是把 P7 硬技能"讲出深度"的关键一月。

## 本月目标与验收标准

- [ ] 精读 Spring IoC（Inversion of Control 控制反转）容器启动与 Bean 生命周期主线，讲清三级缓存解决循环依赖
- [ ] 精读 Spring AOP（Aspect Oriented Programming 面向切面编程）动态代理与事务失效八种场景原理
- [ ] 精读 Spring Boot 自动装配（Auto-Configuration）机制：@EnableAutoConfiguration → spring.factories/AutoConfiguration.imports → 条件注解
- [ ] 精读 RocketMQ 事务消息与存储主线（month05 基础上加深）
- [ ] 建立性能优化体系：指标先行 → 分层定位 → 手段库 → 验证闭环，产出《性能优化手册》
- [ ] 产出 2 篇源码博客

---

## 每日计划

### 第 1 周：Spring 框架源码（IoC 与 AOP）

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 源码阅读方法论 | 源码三遍法应用、读主线不读支线（主线 = 一次请求/一次启动的完整路径）、断点 + 调用栈（Call Stack）追踪法、画时序图固化理解 | 制定本月源码阅读计划表 | 计划精确到每天读哪个类 |
| D2 | IoC 容器启动 | refresh() 十二大步骤主线（invokeBeanFactoryPostProcessors/finishBeanFactoryInitialization 等）、BeanDefinition（Bean 定义）加载、BeanFactoryPostProcessor（BFPP 工厂后置处理器）扩展点 | 调试跟踪 Spring Boot 启动的 refresh() 全过程 | 能脱稿讲出 12 步中 6 步以上 |
| D3 | Bean 生命周期 | 实例化→属性填充 populateBean→Aware 回调→BeanPostProcessor 前置→初始化（InitializingBean/init-method）→BPP 后置→销毁；扩展点全景（面试高频） | 手写 5 个扩展点插桩打印顺序 | 打印顺序与理论一致并解释 |
| D4 | 循环依赖与三级缓存 | 三级缓存（singletonObjects/earlySingletonObjects/singletonFactories）、为什么需要三级而不是二级（AOP 代理提前暴露）、哪些循环依赖解不了（构造器/原型） | 复现循环依赖 → 打开三级缓存源码逐行走读 | 能画出 getSingleton 三级查找流程 |
| D5 | AOP 原理 | 动态代理双实现：JDK Proxy（接口）vs CGLIB（子类）、ProxyFactory 与 Advisor 匹配、拦截器链（Interceptor Chain：ReflectiveMethodInvocation 递归推进） | 写 AOP 切面并调试拦截器链推进过程 | 能讲清 proceed() 递归链 |
| D6 | 事务原理与失效 | @Transactional 本质（AOP + ThreadLocal 连接绑定）、传播行为（Propagation：REQUIRED/REQUIRES_NEW/NESTED 嵌套保存点）、失效八股：自调用/非 public/异常被吞/多线程/rollbackFor 等 | 复现 5 种事务失效并逐一修复 | 每种失效能讲到源码原因 |
| D7 | 周复盘 | Spring 串讲 | 博客：《三级缓存：Spring 如何优雅解决循环依赖》发布 | 博客含 3 张手绘图 |

### 第 2 周：Spring Boot 与 MyBatis 源码

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | Spring Boot 启动流程 | SpringApplication.run() 主线（环境准备 Environment/上下文创建/refresh/afterRefresh）、ApplicationRunner 与 CommandLineRunner、启动耗时分析 | 用 Arthas trace 启动耗时分布 | 能找出启动最慢的 3 个 Bean |
| D2 | 自动装配机制 | @SpringBootApplication = @Configuration + @EnableAutoConfiguration + @ComponentScan、AutoConfigurationImportSelector、条件注解家族（@ConditionalOnClass/OnMissingBean/OnProperty）、2.7+ 新配置文件 AutoConfiguration.imports | 手写一个自定义 Starter（含自动配置 + 条件装配） | Starter 被另一项目引入即生效 |
| D3 | 内嵌容器与 Filter | 内嵌 Tomcat（TomcatServletWebServerFactory）、DispatcherServlet 初始化（九大组件 HandlerMapping/HandlerAdapter 等）、一次请求的完整旅程（Filter→Servlet→Interceptor→AOP→Controller） | 断点跟踪一次 HTTP 请求全链路 | 能画出请求旅程图 |
| D4 | MyBatis 核心原理 | SqlSessionFactory 构建、Mapper 接口代理（MapperProxy 动态代理）、SqlSession 执行主线（Executor：SIMPLE/REUSE/BATCH）、缓存（一级 SqlSession 级/二级 namespace 级与坑） | 手写简版 Mapper 代理（50 行） | 理解"接口为什么不用实现类" |
| D5 | MyBatis-Plus 与插件 | 插件机制（Interceptor：分页/乐观锁/防全表更新）、分页原理（ThreadLocal + 拦截器改写 SQL）、条件构造器（Wrapper）实现 | 手写一个打印慢 SQL 的 MyBatis 插件 | 插件在生产配置可用 |
| D6 | 配置与 Profile | 配置加载优先级（命令行 > 环境变量 > application.yml > 默认）、多环境 Profile、配置加密（Jasypt）、外部化配置最佳实践 | 输出《配置管理规范》 | 规范覆盖优先级/加密/审计 |
| D7 | 周复盘 | SpringBoot/MyBatis 串讲 | 周记 + 手写 Starter 代码归档 | 达成周验收 |

### 第 3 周：RocketMQ 源码 + 性能优化体系

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 消息发送源码 | DefaultMQProducerImpl.send 主线（路由查找 TopicRouteData→消息队列选择→重试（Retry Times）→发送结果）；同步/异步/单向三通道线程模型 | 断点跟踪一次同步发送全过程 | 能画出发送时序图 |
| D2 | 消息存储源码 | putMessage 主线（CommitLog 追加写→刷盘服务 FlushRealTimeService→复制 HAService）、MappedFileQueue（内存映射文件队列）与预热（warmMappedFile：预写零值缺页优化） | 阅读 MappedFile 关键路径 | 能讲清顺序写为什么快 |
| D3 | 消费源码 | Pull vs Push 本质（Push = 长轮询封装 Pull）、PullMessageService→ProcessQueue→ConsumeMessageThread（消费线程池）、位移提交（offsetStore） | 断点跟踪一次并发消费 | 能画出消费内部分工 |
| D4 | 性能优化方法论(1) | 性能三问：慢在哪（定位）、为什么慢（根因）、改了会怎样（收益预估）；分层模型：客户端→网络→网关→服务→缓存→DB→OS/硬件；指标先行（RT/QPS/错误率/资源四类） | 输出《性能优化方法论 v1》（流程图 + 手段库索引） | 方法论能套用到任意系统 |
| D5 | 性能优化方法论(2) | 手段库分类：缓存类/异步类/批量类/并行类/连接复用类/数据结构类/OS 类（零拷贝/页缓存）、每类手段的适用与代价；反模式（过早优化 Premature Optimization） | 给手段库每类补 1 个自己做过的实例 | 手段库 ≥ 20 条且各有案例 |
| D6 | 综合性能案例 | 案例复现：一个"500ms 接口"的完整优化（串行改并行→加缓存→SQL 优化→连接池调优→GC 调参），记录每步收益 | 在 springbootai 复现该案例并记录 | 每步有量化收益表 |
| D7 | 周复盘 | 源码+性能串讲 | 博客：《一个接口从 500ms 到 50ms：我的性能优化全过程》发布 | 博客含完整优化链路 |

### 第 4 周：源码自选精读 + 月度收官

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 选定自读目标 | 从下面选一个：Redisson 锁实现/Sentinel 滑动窗口/Seata AT 全局锁/Redisson 延迟队列；用三遍法规划 | 完成阅读路线图 | 路线图含每日目标 |
| D2 | 自读第一遍 | 跑通示例 + 熟悉结构（包结构/核心类职责） | 完成结构笔记 | 核心类职责表完成 |
| D3 | 自读第二遍 | 主线走读（断点+调用栈） | 完成主线时序图 | 时序图完整 |
| D4 | 自读第三遍 | 支线与细节（异常/并发控制/扩展点） | 记录 5 个"精妙设计" | 每个能讲清设计动机 |
| D5 | 输出 | 博客：《XX 框架 XX 机制源码解析》 | 博客发布 | 含图 ≥ 3 张 |
| D6 | 知识体系整合 | 把 16 个月知识整合为个人知识库（分类归档：原理/中间件/架构/数据/AI/工程） | 完成《个人技术知识地图 v2》 | 地图可指导后续复习 |
| D7 | 月度总复盘 | 源码月收官 | 月记 + 《性能优化手册》终版归档 | 达成本月全部验收标准 |

---

## 本月产出清单

1. 博客 3 篇（三级缓存/性能优化全过程/自选源码解析）
2. 手写自定义 Starter + MyBatis 慢 SQL 插件 + 简版 Mapper 代理
3. 《性能优化方法论 v1》《性能优化手册》终版（含手段库 20+ 条）
4. 《个人技术知识地图 v2》

## 本月术语表

| 英文 | 中文 |
|------|------|
| IoC (Inversion of Control) | 控制反转（对象创建与依赖管理交给容器） |
| BeanDefinition | Bean 定义（对象的"图纸"） |
| Three-level Cache | 三级缓存（解决循环依赖的三个 Map） |
| Interceptor Chain | 拦截器链（AOP/MyBatis 插件的执行链） |
| Auto-Configuration | 自动装配（按条件自动注册 Bean） |
| MappedFile | 内存映射文件（mmap，RocketMQ 存储核心） |
| Propagation (REQUIRED/NESTED) | 事务传播行为 |
| Premature Optimization | 过早优化（无据调优的反模式） |

> 下月：`../month17-技术管理与业务.md`——P8 的另一半能力：带团队、推事情、懂业务。
