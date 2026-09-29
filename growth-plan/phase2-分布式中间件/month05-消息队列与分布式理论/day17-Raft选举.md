# Day 17 · Raft 选举：任期、随机超时与脑裂防护

> **今日目标**：用 raft.github.io 单步跟踪选举全过程，白板推演 5 节点选举；用 Raft 术语重新解释 Redis Sentinel 的 failover（上月知识理论化）。
> **时长**：动画跟踪 1.5h / 白板推演 2h / Sentinel 对照 1.5h
> **今日产出**：5 节点选举白板推演图 + Sentinel→Raft 对照笔记

## 1. 知识地图

```
Raft 三角色状态机（任意时刻必居其一）：
  Follower：被动接收，只响应 Leader/Candidate 的请求
      心跳超时（election timeout，随机 150-300ms）→ 转 Candidate
  Candidate：发 RequestVote 拉票，term+1
      多数派选票 → Leader；收到别人更大 term 的心跳 → 认怂回 Follower
      选举超时 → 再来一轮（term 继续加）
  Leader：周期性心跳（AppendEntries 空包）维持统治，处理所有写请求

Term（任期）= 逻辑时钟：
  单调递增；每次选举 +1；看到更大 term 立即更新并回 Follower
  一切消息带 term；收到旧 term 的消息直接拒绝（旧领导的话不算数）
  ——Term 就是 Raft 世界的"届"，旧届官员对新届没有管辖权

随机超时 Randomized Timeout 为什么必须随机？【防瓜分选票】
  若 5 个节点超时相同 → 同时变 Candidate → 各自 1 票（投自己）→ 谁也没多数派
  → 全部超时 → 再同时……死循环（选举风暴）
  随机化后：总有一个先超时 → 先发起者大概率赢（其余节点在随机窗内被心跳招安）
  工程实例：Sentinel 的 failover 也是随机延迟触发（类似思想）

选举的两个约束（安全性根基）：
  ① 每人每任期只投一票（先到先得）→ 保证每任期最多一个 Leader
  ② 投票要检查候选人日志"够不够新"（日志至少和我一样新）→ 保证新 Leader 有全部已提交日志
     （日志比较规则：先比最后一条日志的 term，term 大者新；同 term 比 index 大者新）

脑裂 Split Brain 与多数派防护：
  网络分区把 5 节点切成 2+3：
  3 侧：有多数派 → 能选出 Leader，正常服务
  2 侧：无多数派 → 选不出 Leader（Candidate 永远拿不到 3 票），拒绝服务
  分区愈合：旧侧看到更大 term → 自动降级回 Follower 同步新 Leader
  ——没有双主，没有数据分叉：多数派机制天生防脑裂（对比 Redis 主从复制的脑裂，M4 day10 讲过 min-replicas 防护，Raft 是结构性防护而不是参数防护）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Leader / Follower / Candidate | 领导者/跟随者/候选者 |
| Term | 任期（逻辑时钟，单调递增） |
| Election Timeout | 选举超时（随机 150-300ms） |
| RequestVote RPC | 拉票请求 |
| Heartbeat | 心跳（空 AppendEntries） |
| Split Vote | 瓜分选票（无人过半） |
| Log Freshness | 日志新旧（选举约束②的检查项） |
| Majority / Quorum | 多数派 |
| Split Brain | 脑裂（分区导致双主的可能） |

## 3. 动手实操：raft.github.io 单步跟踪

```text
操作路径（动画网站，强烈推荐边看边截图）：
  ① 打开 https://raft.github.io/ → 右侧演示区默认 5 节点
  ② Time=0：全 Follower。点暂停，观察随机 election timeout 倒计时
  ③ 最先超时的节点 → Candidate → term+1 → 向其余发 RequestVote（箭头可视化）
  ④ 单步（Time 步进）观察：多数派投票 → 立即转 Leader → 开始发心跳
  ⑤ 手动实验 A：暂停期间手动停掉 Leader（点节点）→ 观察谁超时、新 term 是多少
  ⑥ 手动实验 B：制造 Split Vote——调快动画，故意在同一瞬间停 Leader，
     观察两个 Candidate 各拿 2 票（5 节点）→ 都不够 3 票 → 超时重选（term 再+1）
  ⑦ 手动实验 C：分区模拟——把 5 节点切成 2/3 两团（网站支持网络延迟调节，
     效果近似），确认 2 节点侧永远选不出 Leader
截图 6 张存 learning/docs/raft-screenshots/，白板重画选举状态机

白板推演任务（5 节点，明天验收要考）：
  场景 1：正常选举（含投票检查日志新旧的跳过）
  场景 2：Leader 宕机后的重新选举时间线
  场景 3：2/3 分区 + 愈合的全过程
```

## 4. 面试连接

**Q：白板推演一下 Raft 选举？（高频白板题，直接掏三场景）**
> 画状态机（Follower/Candidate/Leader 三态两环），然后按场景走：正常选举讲"随机超时→Candidate→term+1→RequestVote→多数派→Leader→心跳"；宕机重选讲"心跳断→超时触发，投票时检查日志新旧保证新 Leader 不丢已提交日志"；分区讲"3 侧有 Leader 服务、2 侧选不出，愈合后旧 Leader 看到大 term 自动退位"。每步带术语（Term/RequestVote/Quorum），每个"为什么"带一句（为什么随机、为什么比日志、为什么多数派）——五个为什么链走完，白板题满分。

**Q：Redis Sentinel 的 failover 和 Raft 选举像吗？**
> 像，但有层次差异：相似点——都要选"哨兵 Leader"来主持 failover（Sentinel 用的是自己简化版 Raft：先到先得+多数派确认）；日志约束——Sentinel 只选"谁来主持切换"，不管数据一致性（数据靠主从复制+min-replicas 参数防护脑裂），Raft 是数据与领导权一体（日志复制+选举互锁）。所以 Sentinel 防脑裂是参数级（min-replicas-to-write），Raft 是协议级。这段对照直接把上月 day10 的知识和今天的理论焊死——面试里就是"我研究过两者差异"的稀缺观点。

## 5. 今日验收清单

- [ ] raft.github.io 三实验完成（6 张截图）
- [ ] 三场景白板推演画完（拍照存档）
- [ ] 随机超时防瓜分选票的原理能讲
- [ ] Sentinel→Raft 对照笔记完成（3 个相似+2 个差异）
- [ ] "Raft 防脑裂是结构性的"这句话能展开
- [ ] `git add . && git commit -m "day17: raft election"`

---
[← Day 16](day16-Paxos与多数派.md) | [本月目录](README.md) | [Day 18 · Raft日志复制 →](day18-Raft日志复制.md)
