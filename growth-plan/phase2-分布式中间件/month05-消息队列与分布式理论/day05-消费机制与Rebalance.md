# Day 05 · 消费机制与 Rebalance：谁消费哪条消息的分配学

> **今日目标**：讲清消费者组的消息分配机制（Rebalance 触发条件/流程/分配策略）与位移管理、长轮询原理，用"kill 消费者"实验亲眼观察 Rebalance——以及它为什么会导致重复消费。
> **时长**：原理 2h / 实验 1.5h / 面试表达 1h
> **今日产出**：Rebalance 实验日志 + "重复消费根因"笔记

## 1. 知识地图

```
Rebalance（重平衡）的本质：队列 ↔ 消费者 的绑定关系重算

  Queue[0..7]  →  消费者 A、B（默认 AVG_BETWEEN 分配：A 拿 4 个，B 拿 4 个）
  B 崩了 → A 拿 8 个（分配重算 = Rebalance）
  C 加入 → A/B/C 各拿 ~3 个（队列数 8 不能整除，余数靠策略）

触发条件三个（谁变了谁触发）：
  ① 队列数变化（扩队列/缩队列）
  ② 消费者组内成员变化（上线/下线/心跳超时——broker 每 20s 清理失联消费者）
  ③ 订阅关系变化（同组不同订阅 = 大忌，会互踢造成消费混乱！）

流程（以消费者 B 下线为例）：
  broker 心跳超时剔除 B → 通知组内所有消费者触发 Rebalance
  → 每个消费者本地独立计算（按同样策略 + 当前成员/队列快照）
  → A 认领 B 原来的队列，从 broker 拉最近提交的 Offset 继续

分配策略三种：
  AVG（按均值分）、AVG_BY_CIRCLE（轮询逐个分）、CONSISTENT_HASH（一致性哈希——day19 手写它！）
  setAllocateMessageQueueStrategy 指定；CONSISTENT_HASH 在消费者频繁扩缩时迁移最少

为什么 Rebalance 会导致重复消费？
  B 被踢到 A 认领之间，B 可能已经拉了一条消息处理到一半还没提交 Offset；
  A 接管后从"最近提交的 Offset"开始 → 这条消息 A 又处理一遍。
  结论：Rebalance 期间必然存在"至少一次"的重复窗口 → 消费幂等不是可选项（day06）

长轮询 Long Polling：
  Consumer 发拉取请求，Broker 没新消息时不立刻返回空，挂起 5s（默认 longPollingWait）；
  期间有新消息立即唤醒返回。既是"拉"的简单，又有"推"的实时。
  ——PushConsumer 只是对长轮询的封装，本质都是拉。
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Rebalance | 重平衡，队列与消费者的重新分配 |
| AllocateStrategy | 分配策略（AVG/轮询/一致性哈希） |
| Offset Store | 位移存储（集群模式存 Broker，广播存本地） |
| Commit Offset | 提交位移（逻辑消费位置，物理在 ConsumeQueue 索引上） |
| Long Polling | 长轮询（挂起等数据，默认 5s 唤醒） |
| Pull Consumer | 拉模式消费者（自己管位移与节奏，适合流式/批处理） |
| MessageListenerConcurrently | 并发消费（线程池并行，可能乱序） |
| MessageListenerOrderly | 顺序消费（队列级锁，day08 主角） |
| Client Rebalance | 客户端各自计算分配（无中心协调者，靠相同快照收敛） |

## 3. 动手实操：kill 消费者观察 Rebalance

```powershell
# ① 写两个消费者程序（秒杀工程或 learning 仓库均可）：
#    RebalanceDemoA / RebalanceDemoB —— 同一个 group "rebalance_demo_g"
#    每个 onMessage 里打印：线程名 + MsgId + queueId + 处理时间
#    Topic 建 8 个队列（updateTopic -r 8 -w 8）

# ② 起两个消费者（两个 PowerShell 窗口分别跑）
#    观察 Dashboard → 消费者 → rebalance 页：A/B 各绑 4 个队列

# ③ 实验 1：kill 掉 B 的窗口（Ctrl+C）
#    观察 A 的日志：10~30s 内开始接管 B 的 4 个队列（REBALANCE 打印）
#    注意点：看 A 是否从 B 未提交的位置继续 → 若 B 正在处理时被杀，
#    A 会重投该消息 → 日志里找同一条 MsgId 出现两次 = 亲眼看到重复！

# ④ 实验 2：再起一个消费者 C
#    观察 A/B 各让出 ~1 个队列给 C（分配重算日志）

# ⑤ 实验 3（大忌演示）：改 C 的订阅 Tag 为别的，看日志互踢
#    （同组订阅不一致 → 每次 Rebalance 互相踢队列 → 消费震荡）
#    ——这个坑真实生产里出现过，讲出来就是加分项

# Dashboard 里对应页面：消费者 → 客户端/重平衡/位移 三个 Tab 对照看
```

## 4. 面试连接

**Q：Rebalance 什么时候触发？带来什么问题？**
> 三类触发：队列数变化、组内成员变化（含心跳超时 20s 剔除）、订阅关系变化。问题两个：①消费暂停与迁移期间的处理延迟；②重复消费窗口——旧持有者已处理未提交、新持有者从已提交处重拉。所以设计上要：消费逻辑幂等 + 避免单次消费耗时超过心跳周期（长任务拆分或异步化），订阅关系必须组内全一致。

**Q：Push 和 Pull 消费者的本质区别？**
> 本质都是拉。Push（DefaultMQPushConsumer）是把长轮询封装成回调：客户端后台线程拉到消息立刻提交给业务线程池，用起来像推；Pull 自己控制拉取节奏与位移，适合大数据批处理、可回溯场景。选型口诀：在线业务用 Push（低延迟），离线搬运用 Pull（控速）。

## 5. 今日验收清单

- [ ] 三种触发条件 + Rebalance 流程手绘图
- [ ] 实验 1-3 全部完成，重复消费的 MsgId 截图留证
- [ ] "订阅不一致互踢"演示过并能讲清
- [ ] 三种分配策略能说清适用场景（一致性哈希在 day19 落地）
- [ ] 长轮询原理 + Push/Pull 本质能 1 分钟讲清
- [ ] `git add . && git commit -m "day05: rebalance"`

---
[← Day 04](day04-刷盘复制与零拷贝.md) | [本月目录](README.md) | [Day 06 · 消息可靠性与幂等 →](day06-消息可靠性与幂等.md)
