# Day 16 · Paxos 与多数派：共识的祖师爷

> **今日目标**：理解 Basic Paxos 两阶段流程与多数派（Quorum）的数学根基，手推一轮完整的提案过程——不是为了背 Paxos，是为了明天 Raft 学得毫不费力（Raft=Paxos 的工程化）。
> **时长**：理论 2.5h / 手推 2h / 表达 1h
> **今日产出**：Paxos 手推过程图 + "为什么多数派"证明笔记

## 1. 知识地图

```
问题域：一群节点，怎么对"一个值"达成不可分割的共识？（拜占庭 vs 非拜占庭）
  拜占庭故障：节点会撒谎/作恶（军队叛徒）→ 拜占庭将军问题，需 3f+1 容 f 个作恶（区块链领域）
  非拜占庭故障：节点只会崩溃/失联（宕机、网络断），不作恶 → Paxos 的适用域
  ——数据中心里 99% 的问题是崩溃故障，所以 Paxos 一族够用（不作恶假设合理）

Basic Paxos 三个角色：
  Proposer 提案者：发起提案 (提案编号 n, 值 v)
  Acceptor 接受者：投票的法官（通常 3/5 个）
  Learner 学习者：旁听结果

两阶段流程（Prepare/Promise + Accept/Accepted）：
  阶段一 Prepare：
    Proposer 选全局递增编号 n，向多数派 Acceptor 发 Prepare(n)
    Acceptor 承诺：不再接受编号 < n 的提案（记下 n），并返回自己已接受的最大编号提案 (n', v')
  阶段二 Accept：
    Proposer 收到多数派 Promise 后：
      若有返回值 v' → 必须提案 v'（尊重既成事实！这是安全性的关键）
      若没有 → 可以提自己的值
    发 Accept(n, v)，多数派 Accept 后，值被选定（Chosen）

为什么多数派 Quorum = N/2+1 就够？【手推重点】
  两个多数派必然相交：任意两个 ⌈N/2⌉ 子集至少共享一个节点
  → 第二轮提案者必然从交点节点学到"已选定的值"→ 不会推翻已选定的值（安全性 Safety）
  → 只要多数派存活，系统就可用（活性 Liveness 的下限）
  数学：|A| = |B| = N/2+1，若 A∩B=∅ 则 |A∪B| = N+2 > N，矛盾。必相交。✓

Paxos 为什么难落地（进阶观点）：
  Basic Paxos 只对一个值共识；Multi-Paxos 要选出稳定 Leader 优化（跳过 Prepare）
  原论文晦涩 + 细节（编号生成/活锁/成员变更）论文没讲全 → 工业界各自造轮子
  Raft 的动机：可理解性（Decomposition 分解 + State Reduction 状态简化）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Consensus | 共识（多节点对同一值达成一致） |
| Proposer / Acceptor / Learner | 提案者/接受者/学习者 |
| Prepare / Promise | 准备（第一阶段请求）/ 承诺（第一阶段应答） |
| Accept / Accepted | 接受（第二阶段请求）/ 已接受（第二阶段应答） |
| Quorum (Majority) | 多数派 N/2+1 |
| Proposal Number | 提案编号（全局递增，如 (轮次, 节点ID) 拼接） |
| Safety / Liveness | 安全性（不错）/ 活性（不停） |
| Byzantine Fault | 拜占庭故障（节点作恶撒谎） |
| Crash Fault | 崩溃故障（节点宕机失联，不作恶） |

## 3. 动手实操：手推一轮 Paxos（纸上，三节点多数派）

```text
场景：5 个 Acceptor（A1-A5），两个 Proposer 竞争，目标：对值 X 还是 Y 达成共识

第一轮（正常流程，Proposer P1）：
  P1: Prepare(n=1) → A1,A2,A3
  A1-A3: Promise(1)，无已接受值
  P1: Accept(1, X) → A1,A2,A3
  A1,A2 Accepted(1,X)——多数派达成，值 X 被选定 ✅
  此时 P3（Learner）从任意多数派都能学到 X

第二轮（并发竞争，Proposer P2 迟到但编号更大）：
  P2: Prepare(n=2) → A2,A3,A4
  A2 返回已接受的 (1,X)，A3 返回 (1,X)  ← 关键！P2 必须提 X，不能提 Y
  P2: Accept(2, X) → 全体
  结论：既成事实被尊重——即使后来的编号更大，也只会"再确认" X 而不会推翻

第三轮（脑洞：如果 P2 在 A2/A3 Accepted 之前就完成 Prepare？）
  推演到"P2 的 Accept 还没到多数派时 P1 的 Accept 先到多数派"的各种交错
  会发现：编号交错可能互相阻塞（活锁 Live Lock）→ Multi-Paxos/Raft 用稳定 Leader 解决
  ——亲手推一遍，明天 Raft 的"为什么要有任期和 Leader"就全通了

画图任务：5 节点两阶段时序图（Prepare/Promise/Accept/Accepted 四种箭头）
         + 两个多数派必相交的韦恩图
```

## 4. 面试连接

**Q：为什么共识算法都要"多数派"？**
> 数学+工程双回答：数学上，任意两个多数派必相交（N/2+1 的韦恩图证明），交点保证新提案者能学到已选定值，决策不会反转（安全性）；工程上，多数派存活即可用（N=5 挂 2 不影响），这是可用性与安全性的最优交点。引申：Kafka 的 min.insync.replicas=2（RF=3 时）、Raft 选举/日志复制、ZooKeeper 的过半提交、Raft 元数据（KRaft）全是同一个数学根基——"多数派"是分布式系统的万金油。

**Q：Paxos 和 Raft 什么关系？为什么工业界偏爱 Raft？**
> Raft 是 Multi-Paxos 的工程化重构，数学安全性与 Paxos 同源（多数派+日志匹配）。偏爱 Raft 的原因：可理解性——Strong Leader（一切经 Leader）、日志连续无空洞、成员变更用联合共识；论文目标就是易懂，所以落地实现（etcd/Consul/KRaft/DLedger）遍地开花。一句话总结："Paxos 是数学，Raft 是软件工程"。

## 5. 今日验收清单

- [ ] 三轮手推全部完成（含活锁场景）
- [ ] 多数派必相交的韦恩图证明能写出来
- [ ] 两阶段时序图手绘（四种箭头标注）
- [ ] "拜占庭 vs 崩溃故障"的区别与各自解法能讲
- [ ] 映射表新增：Paxos→ZooKeeper ZAB/Multi-Paxos、多数派→ISR/KRaft
- [ ] `git add . && git commit -m "day16: paxos quorum"`

---
[← Day 15](day15-CAP与BASE.md) | [本月目录](README.md) | [Day 17 · Raft选举 →](day17-Raft选举.md)
