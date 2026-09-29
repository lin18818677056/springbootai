# Day 11 · Kafka 架构与副本同步：ISR、LEO 与高水位

> **今日目标**：部署 KRaft 模式 Kafka（免 ZooKeeper），讲清 Controller 选举、ISR 机制、LEO/HW 水位推进，画出副本同步图——为 day14 双 MQ 对比博客备料。
> **时长**：部署 1h / 原理 2.5h / 画图总结 1h
> **今日产出**：Kafka 环境 + 副本同步水位图 + ISR 变化实验记录

## 1. 知识地图

```
Kafka 架构（对比 RocketMQ 的翻译表）：
  Topic ≈ Topic；Partition ≈ MessageQueue（但 Kafka 每 Partition 独立日志文件）
  Replica（副本）≈ Broker 主从（但 Kafka 副本是 Partition 级，跨 Broker 分布）
  Controller ≈ 没有直接对应（NameServer 更弱，KRaft 后 Controller 是 Raft 选出来的）
  Consumer Group ≈ Consumer Group（Offset 都有：Kafka 在 __consumer_offsets 内部主题）

KRaft 模式（3.x+）：用 Raft 协议选 Controller，去掉 ZooKeeper
  节点角色：broker / controller / both；元数据存内部主题 @metadata
  ——理论照进现实：day17-18 学 Raft 时，Kafka 自己就是活教材

副本同步核心三概念（面试必画）：
  LEO（Log End Offset）：每个副本的下一条待写位置（本地日志末端）
  HW（High Watermark）：所有 ISR 副本中最小的 LEO —— 消费者只能读到 HW 之前
       作用：保证消费者读到的消息一定在"多数副本"上，Leader 挂了也不丢
       代价：可见性延迟（要等慢副本追上）+ 吞吐下降
  ISR（In-Sync Replicas）：与 Leader 保持同步的副本集合（含 Leader 自己）
       判定：replica.lag.time.max.ms（默认 10s，旧版按条数 lag）内追上 Leader 的 LEO
       落伍 → 踢出 ISR；追上 → 加回 ISR。ISR 是动态的！

Leader 选举与 unclean 争议：
  Leader 挂 → Controller 从 ISR 里选新 Leader（ISR 里的数据是有保障的）
  ISR 全空了呢？
    unclean.leader.election.enable=false（默认）：宁可分区不可用（CP 倾向）
    =true：允许非 ISR 副本上位（可用性优先，但丢已 ACK 的消息）——CAP 的现场教学！

HW 推进的演进（面试高阶点）：
  旧版：Leader 等 ISR 全部 fetch 到才推进 HW（慢、且有一致性 bug KIP-101 修过）
  新版 Leader Epoch：Leader 纪元 + 截断机制，防止"高水位时代"的日志分叉/数据丢失
  讲出"HW 机制曾有过丢数据 bug，新版用 Leader Epoch 修复"= 顶级加分
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Partition | 分区，Topic 的物理分片，独立日志文件 |
| Replica | 副本（Leader 处理读写，Follower 只同步） |
| ISR (In-Sync Replicas) | 同步副本集（与 Leader 保持同步的副本） |
| LEO (Log End Offset) | 日志末端位移（下一条写入位置） |
| HW (High Watermark) | 高水位（min(ISR 的 LEO)，消费者可见上限） |
| Controller | 控制器（分区 Leader 选举/元数据管理，KRaft 模式下 Raft 选举产生） |
| Leader Epoch | Leader 纪元（每换主递增，用于日志截断防分叉） |
| Unclean Leader Election | 非同步副本选主（可用性与一致性的经典权衡） |
| KRaft | Kafka Raft 元数据模式（3.x+ 去 ZooKeeper） |
| __consumer_offsets | 消费位移内部主题（50 分区，按组哈希） |

## 3. 动手实操：KRaft 部署 + ISR 实验

```powershell
# ① docker-compose.yml（learning/month05-mq-theory/kafka/）：单节点 KRaft
#    bitnami/kafka:3.7 环境变量：
#      KAFKA_CFG_NODE_ID=0, KAFKA_CFG_PROCESS_ROLES=controller,broker
#      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS=0@kafka:9093
#      KAFKA_CFG_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093
#      KAFKA_CFG_CONTROLLER_LISTENER_NAMES=CONTROLLER
#    端口 9092 映射宿主机
docker compose up -d; docker compose ps

# ② 建三个副本的 Topic（单机多副本做演示够用）
docker exec -it kafka kafka-topics.sh --bootstrap-server localhost:9092 `
  --create --topic order-events --partitions 3 --replication-factor 3 `
  --config min.insync.replicas=2
# ⚠ 单 Broker 起 RF=3 会报错？bitnami 镜像单节点只算 1 个 broker——
#   学习环境降级为 RF=1，ISR 概念用"纸面推演+截图讲解"；
#   有条件的同学用 3 个 kafka 容器（node 0/1/2，quorum voters 三票）搭真集群

# ③ 观察 ISR 与 Leader（真集群时）：
docker exec -it kafka kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic order-events
#   输出关注：Leader、Replicas、Isr 三列——kill 一个 follower 容器，看 Isr 缩短；
#   kill Leader，看 Leader 切换 + Isr 重建的全过程（这是白板推演的实战依据）

# ④ 控制台收发验证：
docker exec -it kafka kafka-console-producer.sh --bootstrap-server localhost:9092 --topic order-events
docker exec -it kafka kafka-console-consumer.sh --bootstrap-server localhost:9092 `
  --topic order-events --from-beginning --group demo_g
```

## 4. 面试连接

**Q：讲讲 ISR、LEO、HW，以及它们怎么协作保证数据安全？**
> 定义三连：LEO 是每个副本自己的日志末端；HW 是 ISR 里最小 LEO，是消费者可见上限；ISR 是动态维护的"跟得上队伍"的副本集（10s 内追平 Leader）。协作逻辑：生产者 acks=all 时消息等 ISR 全部确认；消费者只能读 HW 以下——所以"读到的必不丢"，代价是可见性延迟。收尾加分："HW 机制历史上有一致性缺陷（数据丢失/日志分叉场景），Kafka 用 Leader Epoch + 日志截断修复，说明协议设计都在演进中"——从"背概念"跃迁到"懂演化"。

**Q：ISR 缩到只剩 1 个（自己）时怎么办？**
> 这就是 unclean 争议现场：默认 false → 分区不可用（宁可牺牲可用性保一致，CP）；开 true → 非 ISR 副本可能落后，新 Leader 缺消息，已 ACK 的数据丢（AP）。选哪个看业务：金融/交易关掉，日志/埋点可以开。再补一刀："我们秒杀的订单消息绝不能开 unclean，但用户行为埋点开了也无所谓——一致性预算按数据用途分配（M4 的原则在 MQ 上重演）。"

## 5. 今日验收清单

- [ ] KRaft Kafka 部署完成，控制台收发通过
- [ ] ISR/LEO/HW 手绘图（含 kill 副本后的变化，真集群者附实验记录）
- [ ] unclean.leader.election 两个方向的选择观点能讲
- [ ] Leader Epoch 防"日志分叉"的演化故事能讲
- [ ] Kafka vs RocketMQ 术语翻译表默写
- [ ] `git add . && git commit -m "day11: kafka isr"`

---
[← Day 10](day10-事务消息源码级.md) | [本月目录](README.md) | [Day 12 · Kafka语义与性能 →](day12-Kafka语义与性能.md)
