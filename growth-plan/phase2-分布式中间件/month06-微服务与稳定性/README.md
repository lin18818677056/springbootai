# Month06 · 微服务与稳定性（30 天学习计划）

> **本月一句话目标**：掌握 Spring Cloud Alibaba 全链路（Nacos/Gateway/OpenFeign/Sentinel/Seata）+ 可观测性三支柱（指标/日志/链路）+ 混沌工程，把单体秒杀的微服务化草图变成落地的商城多模块工程——阶段二毕业，P7 硬技能成型。

## 环境准备（开工前 40 分钟完成）

```powershell
# 1. 基础镜像（M5 已有 mysql/redis/rocketmq/kafka，本月新增四个）
docker pull nacos/nacos-server:v2.3.2
docker pull sentry-groups/sentinel-dashboard:1.8.8   # 若拉取失败用 bladex/sentinel-dashboard:1.8.0
docker pull apache/skywalking-oap-server:9.7.0
docker pull apache/skywalking-ui:9.7.0
docker pull elastic/filebeat:8.11.0                  # EFK 用（可选，day25 再装 ES）

# 2. 商城工程骨架（对应 step01 的多模块结构，Gradle）
New-Item -ItemType Directory -Force "D:\mywork\springbootai\practice-projects\02-microservice-mall\mall\mall-common"
New-Item -ItemType Directory -Force "D:\mywork\springbootai\practice-projects\02-microservice-mall\mall\mall-gateway"
New-Item -ItemType Directory -Force "D:\mywork\springbootai\practice-projects\02-microservice-mall\mall\mall-user"
New-Item -ItemType Directory -Force "D:\mywork\springbootai\practice-projects\02-microservice-mall\mall\mall-product"
New-Item -ItemType Directory -Force "D:\mywork\springbootai\practice-projects\02-microservice-mall\mall\mall-order"
New-Item -ItemType Directory -Force "D:\mywork\springbootai\practice-projects\02-microservice-mall\mall\mall-marketing"
New-Item -ItemType Directory -Force "D:\mywork\springbootai\practice-projects\02-microservice-mall\mall\infra"
# Spring Boot 3.2 + Spring Cloud 2023.0.x + Spring Cloud Alibaba 2022.0.0.0 版本对齐（day02 讲版本陷阱）

# 3. 手写算法仓库（纯 javac）
# learning/month06-ms-stability（已建）——day08 四种限流算法手写主战场

# 4. 复习入口（M5 伏笔清单，本月逐个兑现）
#    day15 Nacos AP/CP 现象 → day03 拆 Distro 协议
#    M4 Sentinel Warm Up 使用 → day09 拆算法
#    M5 day26 排障靠日志 → day26 SkyWalking 提速 10 倍
```

## 本月目标与验收标准

- [ ] 讲清 Nacos 注册与配置原理：Distro（AP）与 Raft（CP）分工、临时/持久实例、配置长轮询 1s 内推送
- [ ] Gateway 路由断言与过滤器链、OpenFeign 超时重试与负载均衡治理（重试铁律能讲清）
- [ ] Sentinel 全家：手写四种限流算法 + 流控/熔断/热点/系统保护四类规则 + 规则 Nacos 持久化
- [ ] Seata AT 模式原理与落地（undo_log/全局锁），TCC 对照 M5 手写版用框架级实现，输出《商城事务一致性总方案》
- [ ] 可观测性三支柱落地：Prometheus+Grafana（RED 指标）、EFK（TraceId 聚合）、SkyWalking（跨 3 服务慢调用定位）
- [ ] 商城项目完成 step01 骨架 + step02 全链路 + step03/04 对应模块；混沌演练 3 场；阶段二毕业检查通过

## 30 天课程地图

### 第 1 周：微服务拆分与服务治理（day01-07）

| 日 | 主题 | 核心内容 | 实战任务 |
|----|------|---------|---------|
| D1 | day01-微服务演进与拆分 | 单体→SOA→微服务→云原生；拆分原则与分布式单体反模式 | 验证秒杀拆分草图+商城五服务拆分 |
| D2 | day02-多模块工程骨架 | Gradle 多模块/版本对齐 BOM/common 模块准入 | 商城六模块编译通过（step01 骨架） |
| D3 | day03-Nacos注册中心 | Distro 协议/临时 vs 持久实例/推空保护 | 兑现 M5 day15 的 AP/CP 三个为什么 |
| D4 | day04-Nacos配置中心 | 长轮询推送/灰度发布/namespace 隔离 | 配置热更新 1s 生效+动态线程池 |
| D5 | day05-Gateway网关 | 路由断言/过滤器链/全局鉴权 | JWT 鉴权+灰度头透传（step02） |
| D6 | day06-OpenFeign与负载均衡 | 动态代理原理/超时重试/重试铁律 | 治理四件套统一配置+降级工厂 |
| D7 | day07-第一周复盘 | 治理串讲 | 《微服务治理配置清单》定稿 |

### 第 2 周：Sentinel 限流熔断与韧性（day08-14）

| 日 | 主题 | 核心内容 | 实战任务 |
|----|------|---------|---------|
| D1 | day08-限流算法全景 | 固定窗口→滑动窗口→漏桶→令牌桶 | 手写四种算法+精度压测对比 |
| D2 | day09-Sentinel流控规则 | QPS/线程数/三种流控效果与模式 | 兑现 M4 Warm Up 伏笔：拆冷启动算法 |
| D3 | day10-熔断降级 | 状态机 Closed→Open→Half-Open/三策略 | 下游故障注入观察熔断恢复 |
| D4 | day11-热点与系统保护 | 热点参数限流/系统自适应保护 | 商品 ID 维度热点限流实测 |
| D5 | day12-规则持久化与集群流控 | 动态数据源/Token Server | 规则推 Nacos 重启不丢 |
| D6 | day13-服务韧性设计 | 舱壁隔离/超时预算/降级三式 | 《核心链路韧性矩阵表》 |
| D7 | day14-第二周复盘与博客 | 韧性串讲 | 博客《限流熔断降级防护网》发布 |

### 第 3 周：Seata 分布式事务与一致性（day15-21）

| 日 | 主题 | 核心内容 | 实战任务 |
|----|------|---------|---------|
| D1 | day15-SeataAT模式 | 两阶段/undo_log/全局锁/@GlobalTransactional | 下单 AT 三服务事务+异常回滚 |
| D2 | day16-AT深入与压测 | 全局锁性能/隔离级别/空回滚悬挂 | AT TPS 损耗实测 |
| D3 | day17-TCC框架级实战 | Seata TCC/对照 M5 手写版 | 扣库存 TCC 化+三坑防御验证 |
| D4 | day18-消息最终一致整合 | AT/TCC/事务消息三方案混布 | 非核心链路（积分/通知）消息化 |
| D5 | day19-对账体系与资损防控 | 准实时+T+1 对账/差异分级 | 双写对账 Job 发现人为差异 |
| D6 | day20-全链路灰度发布 | 金丝雀/染色透传/标签路由 | 按用户尾号全链路灰度 Demo |
| D7 | day21-第三周复盘 | 一致性全景串讲 | 《商城事务一致性总方案》+模拟评审 |

### 第 4 周：可观测性与混沌+毕业（day22-30）

| 日 | 主题 | 核心内容 | 实战任务 |
|----|------|---------|---------|
| D1 | day22-可观测性三支柱 | Metrics/Logging/Tracing 与 OTel | 三支柱数据流架构图 |
| D2 | day23-Prometheus与RED | Micrometer/PromQL/Grafana | 商城 RED 大盘+JVM 指标 |
| D3 | day24-告警体系 | Alertmanager/分级/收敛静默 | 5 条分级告警+runbook 链接 |
| D4 | day25-EFK日志体系 | 结构化日志/TraceId 贯穿/采集链 | Docker 搭 EFK 按 TraceId 召回 |
| D5 | day26-SkyWalking链路追踪 | Span/Context 传播/字节码增强 | 定位跨 3 服务慢调用根因 |
| D6 | day27-混沌工程体系化 | 稳态假设/ChaosBlade/爆炸半径 | 商城 3 场演练+发现修复闭环 |
| D7 | day28-全月大串讲 | 微服务+稳定性五线合一 | 跨月连线+代码资产盘点 |
| D8 | day29-M6模拟验收 | 30 问限时+白板熔断状态机 | 薄弱区清单 |
| D9 | day30-月度复盘与阶段二毕业 | 6 条验收+M2 里程碑检查 | 《P7 硬技能清单自评表》 |

## 本月产出清单

1. 文档四件：《微服务治理配置清单》《核心链路韧性矩阵》《商城事务一致性总方案》《P7 硬技能清单自评表》
2. 博客 1 篇：《限流熔断降级：一套高并发防护网的设计》（含手写四算法压测数据）
3. 手写代码（learning/month06-ms-stability）：RateLimiterSuite（四种限流算法）+ LeapArraySim（滑动窗口原理）
4. 商城工程交付：六模块可运行 + step02 全链路验证 + AT/TCC/灰度/可观测性全接入 + 混沌演练记录 3 份

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

## 学习方法提示

- **框架对照手写**：M5 手写过 TCC/一致性哈希，本月 Sentinel 限流算法也先手写再用框架——"知其所以然"路线贯穿全程
- **配置必须出清单**：微服务治理 70% 是配置纪律（超时/重试/熔断阈值），day07 的清单是新服务初始化模板
- **上月伏笔清单**：Nacos AP/CP 三问（day15）→ day03；Sentinel Warm Up 冷启动因子（M4）→ day09；排障靠日志慢（day26）→ SkyWalking；秒杀微服务拆分草图（day30 作业）→ day01 开工验证

---

[← Month05 · 消息队列与分布式理论](../month05-消息队列与分布式理论/README.md) | [Month07 · DDD 与代码架构 →](../../phase3-架构设计/month07-DDD与代码架构/README.md)
