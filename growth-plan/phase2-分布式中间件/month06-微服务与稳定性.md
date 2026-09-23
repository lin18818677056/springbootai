# 第 6 月：微服务（Microservices）与服务稳定性保障

> **本月一句话目标**：掌握 Spring Cloud Alibaba 全链路（注册配置中心、网关、RPC、限流熔断、分布式事务）+ 可观测性三支柱（日志/指标/链路），并引入混沌工程思维。本月结束时你具备 P7 完整硬技能，进入阶段三学"架构思维"。

## 本月目标与验收标准

- [ ] 讲清 Nacos 注册与配置原理（Distro/AP、Raft/CP、长轮询配置推送）
- [ ] Gateway 路由与过滤器链、OpenFeign 超时重试与负载均衡策略配置
- [ ] Sentinel 流控（QPS/线程数、直接/关联/链路）、熔断降级（慢调用/异常比例/异常数）、热点参数限流
- [ ] Seata AT 模式原理（两阶段、全局锁、undo_log）+ TCC 模式落地
- [ ] 搭建可观测性体系：Prometheus + Grafana（指标）、ELK/EFK（日志）、SkyWalking（链路追踪 Tracing）
- [ ] 微服务商城项目启动（`practice-projects/02-microservice-mall/`），完成 step01-step02

---

## 每日计划

### 第 1 周：服务治理三件套（Nacos / Gateway / OpenFeign）

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 微服务演进与拆分 | 单体→SOA→微服务→云原生（Cloud Native）；服务拆分原则（按业务能力/团队/数据）、拆分粒度反模式（分布式单体 Distributed Monolith） | 对商城业务做拆分设计（用户/商品/订单/营销/支付） | 拆分方案能回答"为什么这么拆" |
| D2 | Nacos 注册中心 | 服务注册/发现/心跳（Heartbeat）、Distro 协议（AP，最终一致）、临时实例 vs 持久实例、推空保护与优雅上下线 | 阅读客户端注册源码主线 | 能讲清 AP/CP 实例的区别与选型 |
| D3 | Nacos 配置中心 | 长轮询（Long Polling：客户端 30s 挂起 + 变更即时返回）、配置灰度发布、命名空间（Namespace）与分组（Group）隔离、配置加密 | 实现配置热更新 + @RefreshScope 动态线程池 | 配置变更 1 秒内生效 |
| D4 | Gateway 网关 | 路由（Route）断言（Predicate）与过滤器（Filter）、GlobalFilter 执行顺序、鉴权/限流/灰度三大自定义过滤器 | 实现网关统一鉴权（JWT 校验）+ 灰度路由头 | 请求经网关携带链路头到达下游 |
| D5 | OpenFeign 与负载均衡 | 声明式调用原理（动态代理 + 编解码）、超时（连接/读取）与重试（Retryer）配置、负载均衡策略（轮询/随机/权重/Nacos 权重）、连接池（OkHttp/HttpClient） | 封装统一 Feign 模板：超时+重试+降级+日志 | 能讲清"重试风暴"风险与幂等前提 |
| D6 | Dubbo 认知 | Dubbo3 应用级服务发现（Application-Level Discovery）、Triple 协议（gRPC 兼容）、与 Spring Cloud 生态融合 | 用 Dubbo3 写一个双协议 Demo | 能对比 Feign vs Dubbo 的适用场景 |
| D7 | 周复盘 | 服务治理串讲 | 周记 + 《微服务治理配置清单》（超时/重试/熔断/日志标准值） | 清单可直接用于新服务初始化 |

### 第 2 周：Sentinel 限流熔断与服务韧性

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 限流算法全景 | 计数器（固定窗口）→ 滑动窗口（Sliding Window）→ 漏桶（Leaky Bucket）→ 令牌桶（Token Bucket）→ 滑动日志；单机 vs 集群限流 | 手写四种算法并压测对比精度 | 能讲清 Sentinel 滑动窗口的实现（LeapArray） |
| D2 | Sentinel 流控规则 | QPS/并发线程数两种阈值、流控效果（快速失败/预热 Warm Up/排队等待）、流控模式（直接/关联/链路） | 给秒杀接口配置预热 + 排队规则 | 压测观察平滑通过曲线 |
| D3 | 熔断降级 | 熔断器状态机（Closed→Open→Half-Open 半开）、三种策略（慢调用比例/异常比例/异常数）、降级返回（Fallback）与 Bloc kHandler 区分 | 对下游接口注入故障，观察熔断恢复 | 半开试探行为可观测 |
| D4 | 热点与系统保护 | 热点参数限流（Hot Param：参数级 QPS）、系统自适应保护（Load/CPU/RT/入口 QPS）、来源控制（授权规则） | 对"商品ID"维度做热点限流 | 突发单商品热点不影响其他商品 |
| D5 | 集群流控与规则持久化 | Token Server 集群流控、规则持久化到 Nacos（动态数据源 DataSource）、Sentinel Dashboard 原理 | 完成规则 Nacos 持久化 | 重启应用规则不丢失 |
| D6 | 服务韧性设计 | 舱壁隔离（Bulkhead：信号量/线程池隔离）、超时预算（Timeout Budget）逐级递减、重试预算、优雅降级（默认值/缓存兜底/功能开关 Feature Toggle） | 为商城核心链路设计韧性方案并落地 | 输出《核心链路韧性矩阵表》 |
| D7 | 周复盘 | Sentinel 串讲 | 周记 + 博客：《限流熔断降级：一套高并发防护网的设计》发布 | 博客含四种限流算法对比数据 |

### 第 3 周：Seata 分布式事务与数据一致性

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | Seata AT 模式 | 一阶段（业务 SQL + undo_log + 前后镜像）、二阶段（异步删除 or 反向补偿）、全局锁（Global Lock）防脏写、@GlobalTransactional | 商城下单：订单+库存+账户 AT 事务 | 异常注入后数据全自动回滚 |
| D2 | AT 深入与局限 | 全局锁性能影响与隔离级别（读未提交默认）、undo_log 序列化、空回滚与悬挂场景 | 压测 AT 模式记录 TPS 损耗 | 能讲清 AT 为什么不默认可重复读 |
| D3 | TCC 模式 | Try 预留资源/Confirm 确认/Cancel 释放、三大问题：空回滚（Empty Rollback）、悬挂（Hang）、幂等（Idempotency）及解决（事务状态表） | 把扣库存改造成 TCC 并压测 | 三大问题的防御代码齐全 |
| D4 | Saga 与消息最终一致 | Saga 状态机（长事务编排）、对比 At/MQ 事务消息方案、选型决策（强一致少数、最终一致多数） | 把非核心链路（积分/通知）改为消息最终一致 | 输出《商城事务一致性总方案》 |
| D5 | 数据一致性校验 | 对账（Reconciliation）体系：准实时对账 + T+1 对账、差异处理（自动补偿/人工工单）、资损防控（Fund Loss Prevention）思想 | 写一个双写对账 Job | 能发现人为制造的不一致数据 |
| D6 | 灰度发布与全链路 | 灰度发布（Gray Release：金丝雀 Canary）、全链路灰度（染色标记经网关透传）、标签路由（Label Routing） | 实现按用户尾号的全链路灰度 Demo | 灰度请求全程走灰度实例 |
| D7 | 周复盘 | 一致性全景串讲 | 周记 + 一致性方案评审（模拟评审人视角自评） | 评审意见 ≥ 5 条并修订 |

### 第 4 周：可观测性（Observability）与混沌工程

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 可观测性三支柱 | 指标（Metrics）、日志（Logging）、链路（Tracing）；OpenTelemetry（OTel 统一标准：SDK + Collector + 协议 OTLP） | 画出三支柱数据流架构图 | 能讲清三者关联定位问题的路径 |
| D2 | 指标体系 | Micrometer + Prometheus（时序数据库：Exporter/抓取/PromQL）、Grafana 看板；RED 方法（Rate/Errors/Duration）与 USE 方法 | 为商城配置 RED 指标 + JVM/线程池指标大盘 | 一个 Grafana 大盘覆盖核心指标 |
| D3 | 告警体系 | 告警规则（Prometheus Alertmanager）、分级（P0-P3）、收敛与静默、值班（On-call）与升级机制（Escalation） | 配置 5 条分级告警规则 | 告警含 runbook（处置手册）链接 |
| D4 | 日志体系 | 结构化日志（JSON）、TraceId 全链路贯穿、ELK/EFK（Elasticsearch + Logstash/Filebeat + Kibana）架构、日志采样与成本治理 | Docker 搭 EFK，日志按 TraceId 聚合检索 | 一个请求全链路日志一键召回 |
| D5 | 链路追踪 | OpenTracing/OpenTelemetry 概念（Span/Trace/Context 传播）、SkyWalking（字节码增强探针 Agent 无侵入）架构与存储 | 接入 SkyWalking，看到完整调用拓扑 | 能定位一个跨 3 服务的慢调用根因 |
| D6 | 混沌工程 | Chaos Engineering（混沌工程）、稳态假设（Steady State Hypothesis）、故障注入（ChaosBlade/Chaos Mesh）、故障演练流程与爆炸半径（Blast Radius）控制 | 用 ChaosBlade 对商城注入：CPU 满/网络延迟/依赖挂 | 每次演练输出《发现-修复-复盘》记录 |
| D7 | 月度总复盘 + 阶段毕业 | 阶段二全回顾 | 微服务商城完成 step02（网关+治理）；整理《P7 硬技能清单自评表》 | 达成月度与阶段里程碑 M2 |

---

## 本月产出清单

1. 《微服务治理配置清单》《核心链路韧性矩阵》《商城事务一致性总方案》
2. 博客 1 篇（限流熔断设计）
3. 可运行工程：Sentinel 全套规则 + Seata AT/TCC + 全链路灰度 + 可观测性大盘
4. 混沌演练记录 3 份

## 本月术语表

| 英文 | 中文 |
|------|------|
| Distro Protocol | Nacos 自研 AP 协议（最终一致分片复制） |
| Bulkhead Isolation | 舱壁隔离（资源隔离防雪崩蔓延） |
| Half-Open | 半开状态（熔断后放行探测请求） |
| Idempotency | 幂等（同一操作执行多次结果一致） |
| Hang / Suspension | 悬挂（Cancel 先于 Try 到达） |
| Canary Release | 金丝雀发布（小流量验证后全量） |
| RED Method | Rate/Errors/Duration 三指标法 |
| Blast Radius | 爆炸半径（故障影响范围，演练需控制） |
| SLO Error Budget | 错误预算（SLO 允许的故障额度） |

## 阶段二毕业检查（对照 README 里程碑 M2）

- [ ] 秒杀项目 5000 并发压测通过：无超卖、P99 < 200ms、错误率 < 0.1%
- [ ] 白板讲清 RocketMQ 事务消息两阶段 + 回查时序
- [ ] 混沌演练后能完整复述"一次故障的发现-定位-恢复-复盘"闭环

> 下一阶段：`../phase3-架构设计/month07-DDD与代码架构.md`——从"会用中间件"到"会做架构"。
