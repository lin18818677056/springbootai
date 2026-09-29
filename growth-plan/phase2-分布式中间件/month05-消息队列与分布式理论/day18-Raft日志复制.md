# Day 18 · Raft 日志复制：提交规则与实现佐证

> **今日目标**：讲清 Raft 日志复制全流程（AppendEntries/日志匹配/提交规则/成员变更），到 Redis Sentinel、RocketMQ DLedger、Kafka KRaft 三个实现里找佐证——理论全部落地。
> **时长**：原理 2h / 实现佐证 2.5h / 白板复盘 1h
> **今日产出**：日志复制时序图 + 三实现对照笔记 + "为什么 Raft 流行"观点

## 1. 知识地图

```
日志复制全流程（写请求的一生）：
  客户端写请求 →（可能先重定向到 Leader）
  Leader 追加到本地日志（未提交态）→ 并行发 AppendEntries 给所有 Follower
  Follower 追加到本地日志，返回成功
  Leader 收到【多数派】成功 → 提交（apply 到状态机）→ 返回客户端成功
  下一次心跳（AppendEntries）捎带 leaderCommit 通知 Follower 也提交
  ——注意"两段提交"的味道但不是 2PC：多数派即提交，无需全员 ACK；无阻塞Prepare阶段

日志匹配特性 Log Matching（安全性的第二支柱）：
  两条日志若 (index, term) 相同 → 存储的命令相同（Leader 在同 index 只写一次）
  (index, term) 相同 → 之前的所有日志都相同（AppendEntries 的一致性检查：
      prevLogIndex/prevLogTerm 不匹配 → Follower 拒绝 → Leader 回退 nextIndex 重发）
  → 推论：所有节点的日志前缀逐趋一致（收敛性）

提交规则（只提交当前任期的日志）：
  ⚠ Leader 不能直接提交"旧任期的日志"（即使它已复制到多数派）！
  为什么：场景——旧 Leader 复制日志到多数派但没来得及提交就挂了；
    新 Leader 若直接提交它，而该日志没复制到某分区的多数派，分区愈合后会被覆盖 → 已提交数据丢失
  解法：Leader 只提交"自己任期的日志"，旧日志随最高新日志"间接提交"（Follower 复制新日志时顺带确认旧日志）
  ——这是 Raft 与 Multi-Paxos 最容易讲错的差异点，白板画"旧任期日志不可直接提交"的
    反例时序（Raft 论文图 8 的场景）= 面试顶级加分

成员变更（Joint Consensus 联合共识）：
  直接换配置的危险：新旧配置各自可能形成多数派 → 双主
  两阶段：先进入联合共识（新配置+旧配置都必须多数派同意）→ 再切换到新配置
  单步简化版（工程常用）：每次只增删一个节点（单节点变更永不双主——数学可证）

三个实现佐证（今天的落地任务）：
  ① Redis Sentinel：选 Leader 主持 failover（day17 已对照）；数据层仍是主从复制（非 Raft）
  ② RocketMQ DLedger：Broker 存储层用 Raft——日志=消息，多数派写入，自动选主
     （对比普通主从：Master 挂了不能自动切；DLedger 挂了 10s 内自动切）
  ③ Kafka KRaft：元数据层的 Raft（Controller 选举）——day11 部署时 KAFKA_CFG_PROCESS_ROLES
     就是 Raft 角色，quorum voters 就是投票者名单
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| AppendEntries RPC | 日志复制/心跳请求（复合用途） |
| prevLogIndex / prevLogTerm | 一致性检查字段（前一条日志的位置与任期） |
| nextIndex / matchIndex | Leader 对每个 Follower 的重发指针 |
| Commit / Apply | 提交（多数派确认）/ 应用（执行到状态机） |
| Log Matching | 日志匹配特性（前缀一致性） |
| Indirect Commit | 间接提交（旧任期日志随新任期日志一起提交） |
| Joint Consensus | 联合共识（成员变更两阶段） |
| Single-Server Change | 单节点变更（工程简化版） |
| State Machine | 状态机（Raft 保证各节点状态机按同序执行同命令） |

## 3. 动手实操：到三个实现里找佐证

```text
① DLedger 实验（最有感觉的一个）：
   GitHub 找 openmessaging/dledger，或直接看 RocketMQ 5.x 的 Controller 模式文档
   关键观察点（不用跑通，读文档+画图即可）：
   - 三节点 DLedger 集群 kill Leader → 选主日志（term 变化/投票请求）
   - 写入路径：Producer 消息 → DLedger 多数派 append → 才返回成功
     = acks=all + min.insync.replicas=2 的"协议级加固版"
   画对照图：普通主从（异步复制可丢）vs DLedger（多数派不丢）

② KRaft 佐证（回看 day11 部署配置）：
   KAFKA_CFG_CONTROLLER_QUORUM_VOTERS=0@kafka:9093 ← Raft 投票者
   元数据主题 @metadata 的 Leader 就是 Raft Leader
   结论一句话：Kafka 把"集群元数据"也交给了共识协议——ZooKeeper 时代 Controller 是
   ZK 临时节点选出的，KRaft 时代是协议选出的（去掉外部依赖）

③ Sentinel 佐证（上月 day10 日志回看）：
   翻当时的切换日志：+sdown → +odown → +vote-for-leader（各哨兵投票）→ +failover...
   对照今天的 Raft 术语：sdown=超时，vote-for-leader=RequestVote，多数派=Quorum
   差异记录：Sentinel 的投票选的是"主持人"不是"数据 Leader"；数据切换靠 replicateof 重定向

白板推演任务：
  ① 完整复制时序图（3 Follower，标注 prevLogIndex 检查与 leaderCommit 通知）
  ② Follower 日志落后的修复过程（nextIndex 回退重发）
  ③ "旧任期日志不可直接提交"反例时序（Raft 论文图 8 简化版）
```

## 4. 面试连接

**Q：讲讲 Raft 日志复制，为什么它比 2PC 好？**
> 全流程：Leader 本地 append → 并行 AppendEntries → 多数派确认即提交 → 心跳捎带 leaderCommit 通知 Follower。对比 2PC 的优势：①非阻塞——2PC 协调者单点且全员 ACK（一个慢就拖全部），Raft 多数派即提交；②无锁等待阶段——2PC 的 Prepare 锁资源，Raft 日志无锁；③自带容错——Leader 挂了选举续命，2PC 协调者挂了全卡住。所以 Raft 是"容错版两阶段提交"。再加一句差异观点："Raft 也不是全优——日志复制要落盘+多数派 RTT，单次写延迟高于异步主从复制，DLedger 的 TPS 低于普通主从就是代价。"

**Q：为什么 Raft 比 Paxos 流行？（day16 埋的问题今天收尾）**
> 三点：①可理解性设计——Strong Leader 一切写经 Leader（Paxos 允许任意 Proposer，活锁难防）、日志连续无空洞（Paxos 日志可乱序补洞）、状态空间小（term+log 覆盖全部状态）；②论文即指南——Raft 论文把"实现需要的所有细节"写全了（选举/复制/快照/成员变更），Paxos 论文留了坑；③生态正循环——etcd/K8s 带火 Raft，越多人用越多人懂。收尾金句："Paxos 证明了'能做'，Raft 定义了'怎么做'——工程界奖励可复制的方案。"

## 5. 今日验收清单

- [ ] 日志复制全流程时序图（含一致性检查与提交通知）
- [ ] "旧任期日志不可直接提交"反例推演完成
- [ ] DLedger/KRaft/Sentinel 三佐证笔记（对照图）
- [ ] Raft vs 2PC 三个优势能脱稿
- [ ] "为什么 Raft 流行"三点观点能讲
- [ ] 映射表新增：Raft→KRaft/DLedger/etcd 三行
- [ ] `git add . && git commit -m "day18: raft log replication"`

---
[← Day 17](day17-Raft选举.md) | [本月目录](README.md) | [Day 19 · 一致性哈希手写 →](day19-一致性哈希手写.md)
