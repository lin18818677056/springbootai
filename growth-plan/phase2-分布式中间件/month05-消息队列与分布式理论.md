# 第 5 月：消息队列（Message Queue，MQ）与分布式理论

> **本月一句话目标**：吃透 RocketMQ 与 Kafka 双引擎（原理 + 高级特性 + 幂等治理），补齐 CAP/BASE/Raft 等分布式理论硬骨头，让"为什么这么设计"能答到协议层。继续推进秒杀项目。

## 本月目标与验收标准

- [ ] 讲清 RocketMQ 架构（NameServer/Broker/Producer/Consumer）与消息存储（CommitLog + ConsumeQueue）设计
- [ ] 掌握顺序消息、事务消息、延迟消息、死信队列（DLQ，Dead Letter Queue）四大高级特性并落地秒杀项目
- [ ] 讲清 Kafka ISR（In-Sync Replicas，同步副本集）、HW/LEO、Exactly-Once（精确一次）语义
- [ ] 用本地消息表/事务消息完成一套可靠的"下单-扣库存-通知"最终一致性链路
- [ ] 白板推演 Raft（共识算法）选举与日志复制；讲清 CAP/BASE、一致性哈希
- [ ] 秒杀项目完成：MQ 削峰 + 订单异步化（step04）+ 压测调优（step05）

---

## 每日计划

### 第 1 周：RocketMQ（阿里开源分布式消息中间件）架构与存储

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | MQ 选型全景 | 消息队列价值（解耦/异步/削峰）与代价（一致性/复杂度/堆积）；Kafka vs RocketMQ vs RabbitMQ vs Pulsar 对比（吞吐/延迟/功能/运维） | 输出《MQ 选型决策表》 | 能按业务场景给出选型及理由 |
| D2 | RocketMQ 架构 | NameServer（无状态路由中心）、Broker（主从/DLedger 多副本）、Producer/Consumer、消费模式（集群 CLUSTERING vs 广播 BROADCASTING） | Docker Compose 部署 RocketMQ + Dashboard | 画出整体架构与通信关系 |
| D3 | 消息存储设计 | CommitLog（所有消息顺序写一个文件）、ConsumeQueue（逻辑消费队列索引）、IndexFile（按 Key 查询）、PageCache 与刷盘（同步 Sync Flush / 异步 Async Flush）、零拷贝 mmap | 读 DefaultMessageStore 存储主线源码 | 能画出一条消息落盘的完整路径 |
| D4 | 消费机制 | 消费者组（Consumer Group）、Rebalance（重平衡：分配策略 AVG/一致性哈希）、消费位移（Offset）管理、长轮询（Long Polling）拉消息 | 模拟消费者上下线观察 Rebalance 日志 | 能讲清 Rebalance 何时触发与重复消费风险 |
| D5 | 消息可靠性(发) | 发送三种方式（同步/异步/单向 OneWay）、发送重试与 Broker 端刷盘/复制策略（SYNC_FLUSH/SYNC_MASTER 等）、发送超时治理 | 写可靠性发送模板代码 | 能画出"各级别丢失风险点"图 |
| D6 | 消息可靠性(消) | 消费重试（RetryTopic）、死信队列 DLQ（Dead Letter Queue）、消费幂等（唯一键 + 去重表 / 状态机） | 实现消费幂等组件（Redis + DB 双保险） | 幂等组件压测重复投递不重复处理 |
| D7 | 周复盘 | RocketMQ 串讲 | 周记 + 《MQ 可靠性保障清单》（发送/存储/消费三段） | 三段清单可迁移到任何 MQ |

### 第 2 周：RocketMQ 高级特性 + Kafka 深度

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 顺序消息 | 全局顺序 vs 分区顺序（Partitioned Order：同一订单 Key 路由同队列 + 队列内单线程消费）、顺序消费的锁（Broker 端队列锁 + 消费端 ProcessQueue 锁） | 实现订单"创建→支付→发货"分区顺序消费 | 乱序压测 0 乱序 |
| D2 | 延迟消息 | 固定级别延迟（18 个 Level）与任意时间延迟（Timer Wheel 时间轮，5.0 TimerMessageStore）；应用：订单超时关闭 | 秒杀订单 15 分钟未支付自动关闭 | 时间轮原理能画图讲解 |
| D3 | 事务消息 | 半消息（Half Message）→ 执行本地事务 → 提交/回滚 + 回查（Check Back）；与本地消息表对比 | 秒杀下单接入事务消息保障扣库存 | 断电模拟：回查机制兜底成功 |
| D4 | Kafka 架构与副本 | Topic/Partition/Replica、Controller（控制器选举）、ISR（In-Sync Replicas 同步副本集）、LEO（Log End Offset 日志末端位移）与 HW（High Watermark 高水位）、unclean.leader.election 争议 | 画出副本同步与水位推进图 | 能讲清 HW 保障什么、牺牲什么 |
| D5 | Kafka 语义与性能 | acks（0/1/all）、幂等生产者（PID + Sequence Number）、事务（Exactly-Once 精确一次：跨分区原子写）、Zero-Copy sendfile；消费者 offset 提交（自动/手动） | 对比三种 acks 的吞吐与可靠性压测 | 能按场景配置"可靠性-性能"档位 |
| D6 | 消息积压治理 | 积压排查（消费能力 vs 生产速率）、扩容消费者（受限于分区数）、快速消费方案（转发到新 Topic + 临时大线程池批量拉取）、跳过策略 | 模拟 100 万条积压并演练追平 | 输出《积压应急预案》文档 |
| D7 | 周复盘 | 双 MQ 对比串讲 | 博客：《RocketMQ 与 Kafka 存储设计对比：从一条消息的一生说起》发布 | 博客含两张存储结构图 |

### 第 3 周：分布式理论（CAP / 共识 / 一致性）

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | CAP 与 BASE | CAP 定理（Consistency/Availability/Partition tolerance，一致性/可用性/分区容错）的正确理解（P 必选，C/A 二选一发生在分区时）、BASE（Basically Available/Soft state/Eventually consistent，基本可用/软状态/最终一致） | 用 Nacos（AP/CP 可切换）与 Eureka/ZK 对比验证 | 能纠正"CAP 三选二"的常见误读 |
| D2 | Paxos 思想 | 拜占庭将军（Byzantine Generals）vs 非拜占庭故障、Basic Paxos（两阶段：Prepare/Promise、Accept/Accepted）角色与多数派（Quorum，多数派 = N/2+1） | 手推一轮 Paxos 提案过程 | 能讲清"为什么需要多数派" |
| D3 | Raft 选举 | Leader/Follower/Candidate（领导者/跟随者/候选者）、任期（Term）、随机超时（Randomized Timeout）防瓜分选票、脑裂（Split Brain）与多数派防护 | 用动画网站（raft.github.io）单步跟踪选举 | 白板能推演 5 节点选举全过程 |
| D4 | Raft 日志复制 | 日志复制流程、日志匹配特性（Log Matching）、提交规则（只提交当前任期日志 + 间接提交旧日志）、成员变更（Joint Consensus 联合共识） | 结合 etcd/Redis Sentinel/DLedger 找实现佐证 | 能讲清"为什么 Raft 比 Paxos 流行" |
| D5 | 一致性哈希 | 普通取模扩容问题、一致性哈希环（Consistent Hashing Ring）与虚拟节点（Virtual Node）、有界负载；应用：负载均衡/分片/缓存路由 | 手写一致性哈希（含虚拟节点）并统计迁移率 | 扩容 1 节点数据迁移 ≈ 1/N |
| D6 | 分布式事务全家桶 | 2PC（Two-Phase Commit 两阶段提交）与 XA、3PC、TCC（Try-Confirm-Cancel）与空回滚/悬挂/幂等三问题、SAGA（长事务编排）、本地消息表、事务消息、最大努力通知 | 输出《分布式事务选型决策树》 | 决策树覆盖 6 种方案与适用场景 |
| D7 | 周复盘 | 理论→实践映射 | 周记：把每个理论对应到一个真实中间件实现 | 任何理论追问都能落到实例 |

### 第 4 周：秒杀项目攻坚（MQ 削峰 + 压测）

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 异步化改造 | 下单链路重构：同步扣减库存 → 发事务消息 → 消费端落单；削峰填谷（Peak Clipping & Valley Filling） | 完成 `practice-projects/01-seckill-system/step04` 前半 | 下单 RT 从 200ms+ 降至 <50ms |
| D2 | 消费端幂等 | 订单号去重表 + 唯一索引 + Redis 预判三层幂等 | 完成 step04 后半（幂等消费） | 重复消费 0 重复订单 |
| D3 | 库存回补 | 支付超时回补库存（延迟消息）、退款回补、库存对账任务 | 实现回补链路 + 对账 Job | 对账脚本发现并修复 1 处不一致 |
| D4 | JMeter 压测 | 压测计划设计（线程组/断言/监听器）、阶梯加压、TPS（Transactions Per Second，每秒事务数）/RT/错误率三指标 | 完成 `step05`：5000 并发压测 | 无超卖、错误率 < 0.1%、P99 < 200ms |
| D5 | 瓶颈定位与调优 | 压测报告分析：火焰图 + 慢 SQL + GC 日志三路定位；至少完成 3 项优化（如本地缓存、连接池参数、Lua 合并命令） | 完成 step05 调优部分并记录前后数据 | TPS 提升有量化记录 |
| D6 | 项目复盘 | 架构复盘文档（假设流量再涨 10 倍怎么办？→ 单元化/热key/多级缓存/弹性扩容） | 完成 step05 复盘与答辩 PPT | 能 20 分钟讲清整个项目 |
| D7 | 月度总复盘 | MQ+理论+项目串讲 | 月记 + 《秒杀系统架构演进路线图》 | 达成本月全部验收标准 |

---

## 本月产出清单

1. 《MQ 选型决策表》《MQ 可靠性保障清单》《积压应急预案》
2. 博客 1 篇（双 MQ 存储对比）+ 《分布式事务选型决策树》
3. 手写代码：一致性哈希（含虚拟节点）、消费幂等组件、事务消息链路
4. 秒杀项目完整交付（含压测报告与架构演进 PPT）

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

> 下月：`../month06-微服务与稳定性.md`——Spring Cloud Alibaba 全家桶 + 可观测性体系。
