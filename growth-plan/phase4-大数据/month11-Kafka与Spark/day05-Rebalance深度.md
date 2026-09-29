# Day 05 · Rebalance 深度：换人接力的代价

> **今日目标**：搞懂 Kafka 最容易出生产事故的机制——Rebalance（重新分工）。什么时候触发、为什么"换一个人全队停工"、四种分配策略怎么演进、怎么用配置把停工时间压到最低。今天还要亲手制造一次 Rebalance 并观察全过程。
> **时长**：触发条件与心跳 1.5h / 四种分配策略 1.5h / 制造 Rebalance 实验 2h
> **今日产出**：Rebalance 现场观察记录 + 《Rebalance 治理清单》（含两个超时参数的定法）

## 1. 知识地图

```
什么是 Rebalance（重新分工）？——"搬家小队中途换人，全队停下重新分格子"
  格口和人的绑定关系变了，就必须重新分配；分配期间全组不能消费（Stop the World）

三类触发条件（面试背这三句）：
  ① 成员变化：消费者上线/下线/被踢（心跳超时或处理超时）
  ② 格口变化：分区数扩容（订阅的 Topic 加了分区）
  ③ 订阅变化：组内有人改了订阅的正则/Topic 列表
  —— 生产上 99% 是①，而且大多是"意外下线"（被踢），不是主动扩缩容

被踢的两条线（两个超时，都置人于死地）：
  心跳线：session.timeout.ms（默认 45s）内没心跳 → 网络差/GC 长/机器卡 → 踢
  干活线：max.poll.interval.ms（默认 5min）内没 poll 下一批 → 处理太慢 → 踢
  —— 经典生产事故：单条消息要处理 6 分钟（调外部接口超时重试），
     5 分钟没 poll → 被踢 → rebalance → 换人重头处理 → 又超时 → 循环风暴
  （消息被"踢皮球"，一条都消费不完，堆积飙升——排查方向：消费延迟+反复 rebalance 日志）

四种分配策略演进（一代比一代聪明的分格子方式）：
  Range：按货架逐个分——每个货架的前几格给编号靠前的人 → 编号靠前的人总多拿（倾斜）
  RoundRobin：所有格子打乱全局轮询 → 均匀了，但每次重分都是"全部重排"
  Sticky：尽量保持上次的分工，只挪必须挪的 → 减少变动
  CooperativeSticky（2.4+，当前默认推荐）：增量式——先"预分配"商量好，
    只收回要挪动的格子，其他格子不停消费 → 从"全队停工"变成"只停涉及的两个人"
  （M5 的 RocketMQ 用类似"增量重平衡"思路；思想同源）

减少 Rebalance 的三板斧：
  ① 静态成员：group.instance.id 给消费者起固定名字——重启不触发 rebalance
    （滚动发布时 K8s Pod 重启不再引发全组停工，运维幸福感最强的一招）
  ② 超时按"最坏处理耗时"定：max.poll.interval.ms > 单批最坏处理时间 × 2
  ③ CooperativeSticky + 消费者数规划好（别频繁手动扩缩）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Rebalance | 重新分工（格口↔人绑定重排，期间停止消费） |
| Stop the World | 全组停工（eager 策略下所有人都先交出格子） |
| Session Timeout | 心跳超时（45 秒没报平安，踢） |
| max.poll.interval | 两次拉货的最大间隔（5 分钟没拉货，踢） |
| Static Membership | 静态成员（固定工牌，重启不换人） |
| CooperativeSticky | 协作式粘性分配（增量重排，不停全队） |

## 3. 动手实操：制造一次 Rebalance

```powershell
# ===== 实验①：现场观察（组内起 2 个消费者同组消费 6 分区 topic）=====
# 终端 A（消费者 1）：
docker exec kafka kafka-console-consumer.sh --topic perf-p3 `
  --group rb-demo --from-beginning --bootstrap-server localhost:9092
# 终端 B（消费者 2，用 docker exec 再起一个）：
docker exec kafka kafka-console-consumer.sh --topic perf-p12 `
  --group rb-demo --from-beginning --bootstrap-server localhost:9092
# 触发：直接 docker restart kafka（broker 重启会话断）或 Ctrl-C 杀掉一个消费者
# 观察：两个终端都打出 "rebalance" 相关日志（Attempting to rebalance group）
# ===== 实验②：看分工结果与策略效果 =====
docker exec kafka kafka-consumer-groups.sh --describe --group rb-demo `
  --bootstrap-server localhost:9092
# 记录：每次成员变化后 PARTITION 重新划分；对比"杀一人前后"分区归属变化
# ===== 实验③：验证静态成员（Java 代码 5 行，用 kafkajs/kafka-cli 不便则记录配置要点）=====
# props.put("group.instance.id", "consumer-fixed-1")
# props.put("session.timeout.ms", "45000")
# 重启消费者进程（kill + 重启）→ 观察日志：无 rebalance（broker 认得这块固定工牌）
# —— 静态成员是"滚动发布不触发全组停工"的关键配置，写进治理清单
```

## 4. 面试连接

**Q：线上 Kafka 消费组反复 Rebalance，怎么排查？（高频排障题）**
> 先看 rebalance 日志的"退出成员"是谁、什么原因，两条线分开查。心跳线（session.timeout 被踢）：常见原因是消费者实例 GC 停顿或机器 CPU 打满——45 秒没心跳；排查方向是实例监控（GC 日志/CPU），根治是修 GC 或升配。干活线（max.poll.interval 被踢）更常见：单条消息处理超过 5 分钟没机会 poll，典型的"处理逻辑里有慢调用"（下游接口超时 10 秒×重试 30 次就炸了）。处置分三层：立刻层——调大 max.poll.interval.ms、调小 max.poll.records（每批少拿几条）；短期层——把慢调用加超时和降级（M8 的思路：别让一条坏消息拖死全组）；根治层——重逻辑拆出去异步化，poll 线程只做轻活。还有一个隐蔽诱因：滚动发布没有配静态成员，每次发版全组 rebalance——配置 group.instance.id 后发版只影响单实例。我自己的治理清单就是按这三层写的，另外把"rebalance 频率"接进监控（broker 侧 rebalance 次数指标），一小时超过 3 次就告警——rebalance 本身不可怕，可怕的是没人知道它在反复发生。

**Q：四种分区分配策略怎么选？CooperativeSticky 好在哪？**
> 演进逻辑就是"每次重排的代价越来越小"。Range 按 Topic 逐个分，实现简单但编号靠前的消费者总多拿（多 Topic 时倾斜明显），基本不用；RoundRobin 全局轮询，均匀但任何一次 rebalance 都全量重排——所有消费者先交出全部分区再重新拿，期间全组停消费；Sticky 在重排时尽量保持上次分工，只挪必须挪的，把"变化面"缩小；CooperativeSticky 是质变——从"先全部交出再分配"（eager）变成"增量协商"：第一轮只标记要收回的格子，消费者交出这几格，其余格子照常消费，第二轮把空出的格子分给该拿的人。全组停工变成"只有涉及的消费者短暂停"，对一个 20 实例的消费组，停工时间从分钟级降到秒级。选型直接说结论：新业务一律 CooperativeSticky（2.4+ 默认能力），配合静态成员——这两个配置加上合理的超时参数，就是 Rebalance 治理的全部基本面。

## 5. 今日验收清单

- [ ] 三类触发条件+两条被踢线（心跳/干活）能脱稿讲
- [ ] "处理慢被踢→循环风暴"生产事故链能完整复述
- [ ] 四种策略演进逻辑（代价递减）能展开
- [ ] 制造 Rebalance 实验完成+describe 前后对比留痕
- [ ] 《Rebalance 治理清单》成文（三板斧+两个超时定法+监控指标）
- [ ] `git add . && git commit -m "day11-05: rebalance"`

---
[← Day 04](day04-分区与消费组.md) | [本月目录](README.md) | [Day 06 · 副本与 ISR →](day06-副本与ISR.md)
