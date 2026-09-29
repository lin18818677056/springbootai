# Day 09 · 端到端可靠性拼图：三段拼成"不丢不重"

> **今日目标**：把 day06（broker 侧）和 day08（消费侧）的知识拼成一张**全链路可靠性地图**——补上最后一块"生产者侧"（重试/幂等生产者/失败处理），并设计业务级的"重试 Topic+死信队列"链路。今天之后，"Kafka 消息丢没丢/重没重"的任何面试追问你都有完整答案。
> **时长**：生产者侧补全 1.5h / 全景拼图 1h / 死信链路设计 2.5h
> **今日产出**：《消息不丢全景图》+ 重试/死信 Topic 设计文档（含 Java 代码骨架）

## 1. 知识地图

```
消息的一生三段，每段都有"丢"和"重"的风险点——可靠性是三段各自达标：

【第一段：生产者 → broker】
  丢的风险：网络抖动没送达就当成功（acks=0）/ broker 写 Leader 后没等副本就挂
  不丢配方：acks=all + retries=MAX + enable.idempotence=true + 带回调失败落库
  重的风险：retries 导致 broker 收到重复 → 幂等生产者解决（PID+序列号：
    broker 按 <PID, 分区, 序列号> 去重，重试不发重）
  乱的风险：retries 后到达顺序错乱 → max.in.flight.requests.per.connection<=5
    且开幂等（broker 按序列号重排，5 以内保序）
  —— 幂等生产者的代价：单分区会话内有效（Producer 重启换新 PID 就失效），
     跨会话/跨分区去重要靠事务（transactional.id）

【第二段：broker 存储】（day06 已学，复习定位）
  不丢：副本因子 3 + min.insync.replicas=2 + acks=all 组合拳
  不重：broker 侧由幂等生产者保证； Compact 场景（day10）天然"每 key 留最新"

【第三段：消费者】（day08 已学，复习定位）
  不丢：先处理后提交（手动 commitSync）
  不重：消费端幂等三件套（唯一键/状态机/SETNX）

业务级兜底：消费失败的"重试阶梯+死信"（今天的重头戏）
  消费失败 ≠ 无限重试堵死整个分区（毒丸消息：一条坏的让整条流水线卡死）
  标准链路（M5 RocketMQ 重试队列思想的 Kafka 手工版）：
    消费失败 → 发到 xxx-retry-1m（1 分钟后重试）→ 再失败 → xxx-retry-10m
    → 三级耗尽 → xxx-dlq 死信 Topic + 告警 → 人工介入/自动修复后回放
    （Kafka 没有内置延迟队列：用"重试 Topic + 延迟消费"或定时任务扫描模拟）
  设计要点：重试消息带上 retry_count 和首次失败原因头信息；
    死信必须有监控和 SLA（死信堆积=业务在流血，2 小时没人管就是事故）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Idempotent Producer | 幂等生产者（broker 按序列号去重，重试不发重） |
| PID + Sequence | 身份证+序号（幂等的去重钥匙，会话内有效） |
| retries | 重试次数（配合幂等开大无副作用） |
| DLQ (Dead Letter Queue) | 死信队列（救不活的消息的"重症监护室"） |
| Poison Message | 毒丸消息（每次处理都失败、会堵死分区的坏消息） |
| Retry Ladder | 重试阶梯（1min→10min→1h 的逐级降速重试） |

## 3. 动手实操：重试+死信链路设计

```java
// ===== 《重试/死信设计文档》核心骨架（docs/kafka/retry-dlq-design.md）=====
// 链路：order-topic --失败--> order-retry-1m --再败--> order-retry-10m --> order-dlq
// 关键：三个重试 Topic 的消费组用"暂停-恢复"实现延迟（拉到消息看时间头，未到点 pause）
public void onMessage(ConsumerRecord<String, String> r) {
    try {
        handle(r);                                   // 业务处理（内部有幂等三件套）
    } catch (RetryableException e) {                 // 可重试失败（超时/临时性）
        int n = retryCount(r);                       // 从 header 取已重试次数
        if (n >= 3) sendToDlq(r, e);                 // 阶梯耗尽 → 死信+告警
        else sendToRetry(r, n + 1);                  // 发下一级重试 Topic
    } catch (NonRetryableException e) {              // 不可重试（参数错/格式坏）
        sendToDlq(r, e);                             // 直接死信，别浪费重试
    }
    // 注意：无论哪条路，主 Topic 的 offset 都正常提交——失败消息已"转移"而非"滞留"
}
// ===== 实验①：观察生产端回调与重试（记录要点）=====
// producer.send(record, (meta, ex) -> { if (ex != null) saveToFailTable(record); });
// 断网实验：docker network disconnect bigdata kafka → 发消息 → 观察回调异常
//           → 恢复网络 → 验证重试成功（retries 生效）——留操作记录
// ===== 实验②：毒丸复现（理解为什么要死信）=====
// 消费逻辑遇到 value="bad" 就抛异常且不提交 → 发一条 bad 进 order-log
// 观察后续所有消息卡住不消费（分区被毒丸堵死）——这就是无限重试的反面教材
// 修复：catch 后转死信 → 分区恢复流动——对比前后 Lag 曲线
```

## 4. 面试连接

**Q：怎么保证 Kafka 链路消息不丢？（全景必考题）**
> 我按消息的一生分三段回答，每段有独立的"不丢配方"，拼起来才是完整答案。第一段生产者：acks=all（等 ISR 全员确认）+ retries 调大（网络抖动自动补发）+ enable.idempotence=true（broker 按 PID+序列号去重，重试不会造成 broker 重复）+ 发送必须带回调，回调异常的记录落库转补偿——这一段最常见的翻车是"异步发送不看结果"，发送失败自己都不知道。第二段 broker：副本因子 3 + min.insync.replicas=2 + acks=all 组合，保证"回复成功"时数据已在至少 2 台机器上；unclean 选举关掉，宁可分区不可用不让旧数据顶上。第三段消费者：手动提交且"先处理后 commitSync"，杜绝先提交后处理的丢消息姿势；配 rebalance 监听器在分区被收回前提交一次，压缩重复窗口。三段之外还有一层业务兜底：失败走重试阶梯到死信，死信有告警有 SLA——"不丢"的完整定义是"要么成功处理，要么进了有人管的死信"，而不是"永远在重试"。这套答案我在项目里实际配置过，三个域（订单/埋点/通知）各有一张参数卡，没有一刀切。

**Q：消费失败的消息你怎么处理？为什么不能无限重试？（设计题）**
> 无限重试是分区杀手。Kafka 的消费粒度是分区，一条每次都失败的消息（毒丸：比如业务代码没料到的脏数据格式）会阻塞当前分区——它后面的健康消息全部排队，Lag 飙升，最后拖到 max.poll.interval 被踢，触发 rebalance，换个人来接着堵——我在 M5 学 RocketMQ 时就有"重试 16 次进死信"的机制，Kafka 没有内置，必须自己搭。我的链路是三级重试阶梯：order-retry-1m → order-retry-10m → order-dlq，消息头带 retry_count 和失败原因；异常分类决定走向——可重试异常（超时/限流）进阶梯，不可重试异常（格式错/参数非法）直接死信，不浪费重试。两个设计细节：重试消费用"暂停-恢复"实现延迟（拉到重试消息看时间头，未到点就 pause 这个分区，poll 空转不阻塞）；死信 Topic 必须有监控和处置 SLA——死信堆积就是业务在流血，我定的是 2 小时内必须有人认领。修复后的回放用独立工具消费死信重新投递，回放本身也要幂等（说不定健康流量已经把这条业务重做过了）。这套设计和 M8 的"快速失败+隔离+可观测"一脉相承：让坏消息尽快离开主车道，别堵路。

## 5. 今日验收清单

- [ ] 《消息不丢全景图》三段式成文（每段配方+翻车点）
- [ ] 幂等生产者原理（PID+序列号）和作用边界（会话内/单分区）能讲
- [ ] 重试阶梯+死信链路设计文档成文（含毒丸事故分析）
- [ ] 两个实验完成（断网重试/毒丸堵死与修复）
- [ ] "不丢的完整定义"金句能脱口而出
- [ ] `git add . && git commit -m "day11-09: e2e reliability"`

---
[← Day 08](day08-位移提交与消费语义.md) | [本月目录](README.md) | [Day 10 · Kafka 运维实战 →](day10-Kafka运维实战.md)
