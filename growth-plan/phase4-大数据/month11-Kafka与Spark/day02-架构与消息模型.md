# Day 02 · 架构与消息模型：仓库、货架、格口、进度条

> **今日目标**：用四个词建立 Kafka 的空间感——Broker（仓库）、Topic（货架）、Partition（格口）、Offset（进度条）；搞懂"一个货架为什么切成多个格口"（并行）和"消息为什么有时不按发送顺序到达"（只保证格口内有序）。
> **时长**：架构与模型 1.5h / 分区与顺序实验 2h / 消费组规则 1.5h
> **今日产出**：分区分布观察记录 + 顺序性实验结论 + 消费组分配规则图

## 1. 知识地图

```
四个词一套物流比喻（记牢这张图，Kafka 就有骨架了）：

  ┌─────────── Kafka 集群（物流园区）───────────┐
  │  Broker-0 仓库     Broker-1 仓库（本月单机，M10 思路同款） │
  │   Topic: order（货架）                        │
  │   ├─ P0 格口  [msg3][msg2][msg1] ← 只进不出地append │
  │   ├─ P1 格口  [msg2][msg0]                    │
  │   └─ P2 格口  [msg1]                          │
  └──────────────────────────────────────────┘
  Producer 发货员：指定发到哪个货架（Topic），默认轮询选格口；
    若消息带了 key（如 orderId），相同 key 永远进同一格口（保序的钥匙）
  Consumer 取货员：从格口里按进度条（Offset）顺序取，取完拨进度条
  Consumer Group 搬家小队：队内每人分几个格口，队与队之间互不影响
    ——P 数 < 消费者数时，多出来的人闲着（格口不够分，day04 重点）

关键认知①：分区（Partition）是并行的最小单位——3 个格口最多 3 个人同时搬
关键认知②：顺序只保证"格口内"——msg 全局顺序？除非全塞一个格口（牺牲并行）
关键认知③：Offset 是"消费进度"不是"消息编号"——同一格口，每个消费组
  有自己独立的进度条（A 队读到 5 不影响 B 队从头读）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Broker | 仓库节点（一台 Kafka 服务器） |
| Topic | 货架/主题（一类消息的Logical 分组，如 order-log） |
| Partition | 分区/格口（货架的并行切片，append-only 日志） |
| Offset | 位移（某消费组在某格口的进度条位置） |
| Key 路由 | 相同 key 哈希到同一分区（保序钥匙） |
| Consumer Group | 消费组（组内分格口，组间广播） |

## 3. 动手实操：分区分布与顺序实验

```powershell
# ===== 实验①：观察 key 路由——相同 key 进同一格口 =====
docker exec kafka kafka-topics.sh --create `
  --topic order-log --partitions 3 --replication-factor 1 `
  --bootstrap-server localhost:9092
# 带 key 发消息（用 keytab 不方便，控制台默认无 key；用脚本发带 key 消息）：
docker exec kafka bash -c 'for i in 1 2 3 4 5 6; do
  echo "order-1:pay-$i"; echo "order-2:pay-$i"; done' | `
docker exec -i kafka kafka-console-producer.sh `
  --topic order-log --property parse.key=true --property key.separator=: `
  --bootstrap-server localhost:9092
# 逐分区查看（--partition 指定格口）：
foreach ($p in 0..2) {
  docker exec kafka kafka-console-consumer.sh --topic order-log `
    --partition $p --from-beginning --bootstrap-server localhost:9092 --max-messages 4 2>$null
  Write-Host "---- partition $p ----"
}
# 预期：order-1 的消息全在一个分区、order-2 全在另一个分区——key 路由实锤
```

```powershell
# ===== 实验②：全局顺序被打破的现场（day01 的 msg 顺序可能变）=====
# 发送 3 条到 3 个分区（轮询），再用消费组收：到达顺序 ≠ 发送顺序
# 结论记录：要全局有序 → 单分区（放弃并行）；要保业务序 → 按 key 路由（推荐）
# ===== 实验③：消费组与分区的分配规则 =====
docker exec kafka kafka-console-consumer.sh --topic order-log `
  --group demo-g1 --from-beginning --bootstrap-server localhost:9092 --max-messages 1
# 另开一个终端再加一个同组成员：
docker exec kafka kafka-console-consumer.sh --topic order-log `
  --group demo-g1 --from-beginning --bootstrap-server localhost:9092 --max-messages 1
# 观察消费组状态（谁分到哪些格口）：
docker exec kafka kafka-consumer-groups.sh --describe --group demo-g1 `
  --bootstrap-server localhost:9092
# 预期：CONSUMER-ID 两行，PARTITION 分配不重叠；再开第 4 个消费者 → 1 人空闲
```

## 4. 面试连接

**Q：Kafka 怎么保证消息顺序？（高频必考）**
> 先破题：Kafka 只保证"分区内有序"，全局是无序的——因为多分区并行本身就是乱序的来源。业务上要顺序，用"按 key 路由"：发送时给消息指定 key（比如 orderId），相同 key 哈希后永远进同一个分区，分区内部是 append-only 追加写，消费时按 offset 顺序读——同一个订单的"创建→支付→发货"就天然有序。三个坑要交代：①key 别乱换，扩容分区数后哈希结果会变（老 key 可能换分区，扩容窗口期乱序，要么提前规划分区数要么业务容忍）；②消费端重试会打乱顺序——处理失败的消息如果异步重试，后面的消息先处理了，生产上要么同步重试堵住，要么把乱序风险交给下游幂等（M8 思想：与其保证不乱，不如保证乱了也没事）；③max.in.flight.requests 默认 5，开启幂等后 Kafka 会保证即使重试也不乱序——这是 1.0+ 之后"幂等生产者顺带解决顺序"的彩蛋。顺序是拿并行换的，能按 key 分组就别追求全局序。

**Q：分区数设多少合适？消费者比分区多会怎样？**
> 分区数是并行的天花板：3 个分区最多 3 个消费者同时干活，第 4 个消费者分不到分区，全程闲着——这是"消费者数 > 分区数则空闲"的规则。分区数规划用吞吐倒推：目标每秒 10 万条，压测单分区稳定 1 万条/秒，那至少 10 个分区，再留 50% 余量取 16。但分区不是越多越好：每个分区对应磁盘上若干文件+内存里的索引，几千个分区后 Broker 的文件句柄、Leader 选举时间、端到端延迟都会恶化——经验上限单 Broker 几千分区。还有个坑：Kafka 分区只能加不能减（减分区会破坏 key 路由的稳定性），所以宁可初始保守、按需扩容。我项目的日志 Topic 起了 6 分区，两个消费实例各扛 3 个，单实例压测 8k/s，总吞吐 4.8 万/s，够用且留了余量。

## 5. 今日验收清单

- [ ] 四词模型图（仓库/货架/格口/进度条）能白板画
- [ ] 实验①~③全跑通：key 路由实锤/全局乱序现场/消费组分配规则
- [ ] "分区内有序+key 保序"三个坑能讲（key 换分区/重试乱序/in-flight）
- [ ] 分区数规划方法（吞吐倒推+不能减+经验上限）能展开
- [ ] `git add . && git commit -m "day11-02: partition model"`

---
[← Day 01](day01-Kafka全景与定位.md) | [本月目录](README.md) | [Day 03 · Kafka 为什么快 →](day03-Kafka为什么快.md)
