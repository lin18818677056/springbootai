# Day 14 · 第二周复盘与博客：双 MQ 存储设计对比

> **今日目标**：发布博客《RocketMQ 与 Kafka 存储设计对比：从一条消息的一生说起》（含两张存储结构图），完成第二周复盘与六题自测。
> **时长**：博客 3h / 复盘自测 2h
> **今日产出**：博客 1 篇发布 + 周记 + 自测记录

## 1. 博客骨架（照着填，3 小时成文）

```markdown
# RocketMQ 与 Kafka 存储设计对比：从一条消息的一生说起

## 引言（200 字）
用"同一笔订单消息"分别在两个 MQ 里的一生串起全文——
出生（发送）、成长（存储）、工作（消费）、退休（清理）。

## 一、出生：两条进入方式
Kafka：消息进 Partition 的活跃段（Active Segment），batch 攒批写
RocketMQ：消息进全局 CommitLog，单文件顺序追加
   观点：Kafka 把"批量"作为一等公民（攒批换吞吐），RocketMQ 把"单文件"作为武器（省心顺序写）

## 二、成长：两套存储结构【图 1：Kafka 分区日志分段结构图】
【图 2：RocketMQ CommitLog + ConsumeQueue + IndexFile 三件套图】
Kafka：Partition 独立目录 → Segment（1GB）→ 稀疏索引（.index/.timeindex）→ 消费即读日志
RocketMQ：混写 CommitLog → ConsumeQueue 定长索引两跳读 → IndexFile 运营查询
   对比表（至少 6 行）：写路径/读路径/索引结构/文件布局/删除方式/零拷贝
   核心观点：Kafka"读写一体"（日志即消费单元）vs RocketMQ"读写分离"（写混读索）
   ——前者赢在消费吞吐（sendfile 顺路），后者赢在多 Topic 下的写稳定性

## 三、工作：副本与消费的镜像设计
ISR/HW（Kafka）vs 主从/DLedger（RocketMQ）——都是"多数派思想"的不同工程形态
Consumer Group/Rebalance 两家几乎同构（差异点：Kafka 有 Generation，RocketMQ 靠快照收敛）

## 四、退休：文件清理
Kafka：Segment 粒度删除（按时间/大小）+ Compaction（日志压实，按 key 保最新）
RocketMQ：MappedFile 段按时间删除（默认凌晨 4 点），无压实
   引申：为什么 Kafka 能做 Compaction（分区独立、key 有意义）而 RocketMQ 不做（混写没法按 key 清）

## 五、怎么选（200 字）
吞吐管道/大数据生态 → Kafka；交易链路/业务特性（事务/延迟/轨迹）→ RocketMQ
收尾观点：存储设计决定了它们的天赋树——没有最好的，只有长在你业务上的。

## 附：实验数据（day04 刷盘对比 + day12 acks 对比 + day13 积压演练数据）
```

```text
发布渠道任选：掘金/知乎/CSDN/公众号。配图要求：两张存储结构图必须自画（手绘拍照或 draw.io）。
自检清单：标题有钩子 / 开头 200 字内给出全文地图 / 每节有观点不只罗列 / 结尾有选型结论 / 附实测数据。
```

## 2. 第二周主线串讲

```
day08 顺序消息：分区有序=队列选择器+Orderly 两端锁；毒消息挂起是代价
day09 延迟消息：SCHEDULE_TOPIC 中转（4.x 18 档）→ 5.x 时间轮任意延迟；关单四方案对比
day10 事务消息：半消息=隐藏 Topic + 回查=HALF/OP 差集扫描；M4 三个伏笔兑现
day11 Kafka 副本：ISR/LEO/HW 水位图；unclean 争议=CAP 现场教学；Leader Epoch 演化
day12 Kafka 语义：acks 三档+不丢三件套；Exactly-Once=幂等生产+事务+read_committed 拼图
day13 积压治理：三板斧定位+三层治理；扩容受限于队列数；转发拆流兜底

第二周的主线：四大高级特性（顺序/延迟/事务/DLQ）+ 双 MQ 对照——
  你现在可以回答"任何一个 MQ 面试题"背后的原理，而不是背题。
```

## 3. 六题自测（每题 2 分钟）

```text
1 顺序消息的生产端和消费端各靠什么保证？毒消息会发生什么？怎么兜底？
2 18 个延迟级别在 Broker 端怎么实现？（SCHEDULE_TOPIC+按级扫描+到期恢复）
3 事务消息回查的触发者是谁？Producer 重启后回查还通吗？（transactionListener 重新注册）
4 Kafka 的 HW 保证什么、牺牲什么？Leader Epoch 修了什么 bug？
5 Exactly-Once 的完整公式？为什么说"业务 DB 不受 Kafka 事务保护"？
6 100 万积压的完整处理剧本（先问什么、再做什么、什么时候拆流）？
```

## 4. 周记模板

```markdown
# M5 第二周周记
## 本周完成
- 四大高级特性全部落地（0 乱序/15 分钟关单/断电回查/DLQ 告警）
- Kafka 环境与 acks 三档压测（数据：X/X/X）
- 100 万积压追平演练（方案 C 耗时 X 分钟）
- 博客 1 篇发布（阅读量/评论待追踪）
## 最大认知变化
（示例：以前以为 Kafka 和 RocketMQ 是"同类竞品"，现在明白它们是
"存储设计不同→特性天赋树不同→适用场景不同"的同源异构体）
## 遗留问题（带进第三周）
- DLedger 的 Raft 到底怎么选主？（day17-18 正面回答）
- unclean 争议背后的 CAP 到底怎么定义？（day15）
```

## 5. 面试连接

**Q：看过 RocketMQ 源码吗？说说印象最深的一处设计。**
> 三个备选，选最熟的讲深：①半消息=隐藏 Topic（RMQ_SYS_TRANS_HALF_TOPIC）——不建专用存储，复用 CommitLog，"系统主题"解决"消费者不可见"；②ConsumeQueue 20 字节定长——可寻址性换解析开销；③延迟消息=改 Topic 中转——把"延迟存储"问题降维成"延迟可见性"问题。共同点：都是"用最小改动复用既有机制"的工程智慧。能讲出一个"为什么这么设计+代价是什么"，就超过 90% 背题选手。

## 6. 今日验收清单

- [ ] 博客发布（含两张自画图+实验数据）
- [ ] 六题自测通过（录音回听）
- [ ] 周记完成
- [ ] 博客链接存入 learning/docs/blog-list.md
- [ ] `git add . && git commit -m "day14: blog published"`

---
[← Day 13](day13-消息积压治理.md) | [本月目录](README.md) | [Day 15 · CAP与BASE →](day15-CAP与BASE.md)
