# Month05 · 消息队列与分布式理论（30 天学习计划）

> **本月一句话目标**：吃透 RocketMQ 与 Kafka 双引擎（存储原理 + 高级特性 + 可靠性治理），补齐 CAP/BASE/Paxos/Raft 分布式理论硬骨头，把上月秒杀项目里的"事务消息"从会用升级到能讲源码，理论全部落到中间件实例。

## 环境准备（开工前 30 分钟完成）

```powershell
# 1. RocketMQ 5.3.1（上月秒杀已拉取镜像，直接复用；若无则先 pull）
docker pull apache/rocketmq:5.3.1
docker pull apacherocketmq/rocketmq-dashboard:latest
docker pull bitnami/kafka:3.7            # W2 day11 用，KRaft 模式免 ZooKeeper

# 2. MySQL 复用上月容器（幂等组件去重表/本地消息表都要用）
docker ps --filter "name=mysql-learning"   # 没有就按 month04 day15 重新起

# 3. 学习仓库（纯 javac，不用 Maven）
New-Item -ItemType Directory -Force "D:\mywork\springbootai\learning\month05-mq-theory\src"
New-Item -ItemType Directory -Force "D:\mywork\springbootai\learning\month05-mq-theory\lib"
# JDBC 驱动从 month04 复制：Copy-Item D:\mywork\springbootai\learning\month04-redis-mysql\lib\mysql-connector-j.jar D:\mywork\springbootai\learning\month05-mq-theory\lib\

# 4. Raft 动画收藏（day17-18 白板推演神器）
# https://raft.github.io/
```

## 本月目标与验收标准

- [ ] 讲清 RocketMQ 架构（NameServer/Broker/Producer/Consumer）与存储设计（CommitLog + ConsumeQueue + IndexFile），能画出一条消息从发送到落盘的完整路径（含刷盘与零拷贝）
- [ ] 顺序/延迟/事务/DLQ 四大高级特性全部落地：订单链路 0 乱序、15 分钟未支付自动关单、断电模拟回查兜底成功
- [ ] 讲清 Kafka ISR/HW/LEO/Exactly-Once 语义，发布博客《RocketMQ 与 Kafka 存储设计对比》
- [ ] 用本地消息表 + 事务消息完成"下单-扣库存-通知"最终一致性链路，讲清 TCC 三问题与 SAGA 适用场景
- [ ] 白板推演 Raft 选举与日志复制全过程；纠正 CAP 三选二误读；手写一致性哈希（含虚拟节点，扩容迁移率 ≈ 1/N）
- [ ] 秒杀项目二阶段增量交付：顺序消息 + 消息轨迹 + 积压演练 + 故障注入对账（step04/05 已在 M4 完成，本月不重复）

## 30 天课程地图

### 第 1 周：RocketMQ 架构与存储（day01-07）

| 日 | 主题 | 核心内容 | 实战任务 |
|----|------|---------|---------|
| D1 | day01-MQ选型全景 | 解耦/异步/削峰三大价值与代价；Kafka/RocketMQ/RabbitMQ/Pulsar 四选对比 | 输出《MQ 选型决策表》 |
| D2 | day02-RocketMQ架构与部署 | NameServer 无状态路由/Broker 主从/消费模式 CLUSTERING vs BROADCASTING | Docker Compose 三件套部署 + Dashboard |
| D3 | day03-消息存储设计 | CommitLog 顺序写/ConsumeQueue 索引/IndexFile/PageCache | 画出一条消息落盘全路径 |
| D4 | day04-刷盘复制与零拷贝 | 同步/异步刷盘、SYNC_MASTER 复制、mmap 与 sendfile | 刷盘策略对比实验 |
| D5 | day05-消费机制与Rebalance | 消费者组/分配策略/位移管理/长轮询 | kill 消费者观察 Rebalance 日志 |
| D6 | day06-消息可靠性与幂等 | 发送三方式/重试/RetryTopic/DLQ/消费幂等 | Redis+DB 双保险幂等组件 |
| D7 | day07-第一周复盘 | 串讲 + 《MQ 可靠性保障清单》（发送/存储/消费三段） | 清单可迁移到任何 MQ |

### 第 2 周：高级特性 + Kafka 深度（day08-14）

| 日 | 主题 | 核心内容 | 实战任务 |
|----|------|---------|---------|
| D1 | day08-顺序消息 | 全局 vs 分区顺序、队列选择器、两端加锁 | 订单"创建→支付→发货"0 乱序 |
| D2 | day09-延迟消息与时间轮 | 18 个 Level 与 5.0 Timer Wheel | 秒杀订单 15 分钟自动关单 |
| D3 | day10-事务消息源码级 | 半消息/本地事务/回查 Check Back | 兑现 M4 day27 埋的 3 个伏笔 |
| D4 | day11-Kafka架构与副本同步 | Topic/Partition/Controller/ISR/LEO/HW | 画副本同步与水位推进图 |
| D5 | day12-Kafka语义与性能 | acks 三档/幂等生产者/事务/Zero-Copy | 三档 acks 吞吐对比压测 |
| D6 | day13-消息积压治理 | 积压排查/扩容受限于分区/转发新 Topic | 模拟 100 万积压并追平 |
| D7 | day14-第二周复盘与博客 | 双 MQ 对比串讲 | 发布博客（含两张存储结构图） |

### 第 3 周：分布式理论（day15-21）

| 日 | 主题 | 核心内容 | 实战任务 |
|----|------|---------|---------|
| D1 | day15-CAP与BASE | CAP 正确理解（P 必选）/Nacos AP-CP 切换验证 | 纠正"三选二"误读笔记 |
| D2 | day16-Paxos与多数派 | 拜占庭 vs 非拜占庭、两阶段、Quorum | 手推一轮提案过程 |
| D3 | day17-Raft选举 | 三角色/任期/随机超时/脑裂防护 | raft.github.io 单步跟踪 |
| D4 | day18-Raft日志复制 | 日志匹配/提交规则/成员变更 | 到 Sentinel/DLedger 找实现佐证 |
| D5 | day19-一致性哈希手写 | 哈希环/虚拟节点/有界负载 | ConsistentHashRouter 迁移率实测 |
| D6 | day20-分布式事务全家桶 | 2PC/3PC/TCC 三问题/SAGA/消息最终一致 | 输出《分布式事务选型决策树》 |
| D7 | day21-第三周复盘与决策树 | 理论→实践映射表 | 每个理论对应一个真实中间件 |

### 第 4 周：理论落地 + 秒杀二阶段（day22-30）

| 日 | 主题 | 核心内容 | 实战任务 |
|----|------|---------|---------|
| D1 | day22-本地消息表实战 | 可靠消息最终一致七步链路 | 手写本地消息表 + 对账 Job |
| D2 | day23-TCC与SAGA实战 | Try-Confirm-Cancel 骨架/空回滚/悬挂 | 手写 TCC 框架骨架 |
| D3 | day24-秒杀二阶段01顺序与轨迹 | 顺序消息接入秒杀/消息轨迹面板 | 轨迹查一条消息全生命周期 |
| D4 | day25-秒杀二阶段02积压与监控 | 消费端限流/Dashboard 指标/告警 | 积压演练 +《应急预案》 |
| D5 | day26-秒杀二阶段03故障演练 | kill Broker/网络分区注入/对账修复 | 故障演练报告 + 修复记录 |
| D6 | day27-全月大串讲 | MQ+理论+项目三线合一 | 跨月连线表 + 代码资产盘点 |
| D7 | day28-M5模拟验收 | 30 问限时 + 白板 Raft 推演 | 薄弱区清单 |
| D8 | day29-月度复盘与M5验收 | 6 条验收打分 + 周报 + 产出核对 | 正式验收 |
| D9 | day30-阶段二中期检查 | M4+M5 双月复盘/简历初稿更新/M6 预告 | 中期检查清单 |

## 本月产出清单

1. 文档四件：《MQ 可靠性保障清单》《积压应急预案》《分布式事务选型决策树》《MQ 选型决策表》
2. 博客 1 篇：《RocketMQ 与 Kafka 存储设计对比：从一条消息的一生说起》
3. 手写代码（learning/month05-mq-theory）：ConsistentHashRouter、消费幂等组件 IdempotentConsumer、本地消息表链路 LocalMsgDemo、TCC 骨架 TccDemo
4. 秒杀二阶段增量：消息轨迹截图 + 积压演练记录 + 故障演练报告

## 本月术语表

| 英文 | 中文 |
|------|------|
| ISR (In-Sync Replicas) | 同步副本集（与 Leader 保持同步的副本） |
| HW / LEO | 高水位 / 日志末端位移（消费者只能读到 HW 之前） |
| Exactly-Once Semantics | 精确一次语义 |
| Long Polling | 长轮询（挂起请求直到有数据或超时） |
| Half Message | 半消息（事务消息的预备状态） |
| Check Back | 事务回查 |
| Quorum | 多数派（N/2+1，共识决策最小集合） |
| Split Brain | 脑裂（集群出现双主） |
| Peak Clipping | 削峰（用队列把瞬时流量摊平） |
| Log Matching | 日志匹配特性（Raft 两日志同 index 同 term 则前缀相同） |

## 学习方法提示

- **源码别贪多**：RocketMQ 只精读 DefaultMessageStore 存储主线与事务消息回查两处，其余用画图代替
- **理论必须配实例**：每学一个理论（CAP/Paxos/Raft），当天必须写"它对应哪个中间件的哪个机制"
- **上月伏笔清单**：M4 day27 事务消息"不明觉厉"3 点（半消息怎么存？回查谁触发？为什么先落库再发消息不行？）→ day10 逐个兑现；哨兵 failover 选主 → day17 用 Raft 术语重新解释

---

[← Month04 · Redis 与 MySQL 高阶](../month04-Redis与MySQL高阶/README.md) | [Month06 · 微服务与稳定性 →](../month06-微服务与稳定性/README.md)
