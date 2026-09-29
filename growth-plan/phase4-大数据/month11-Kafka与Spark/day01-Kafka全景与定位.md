# Day 01 · Kafka 全景：消息界的"中央传送带"

> **今日目标**：先回答 M10 留下的思考题②（Kafka 为什么能扛百万 TPS——今天开个头，day03 给完整答案）；搞清 Kafka 和你 M5 学过的 RocketMQ 是什么关系、为什么整个大数据生态都围着它转；把 Kafka 容器拉起来，发出第一条消息。
> **时长**：定位与对照 1.5h / 容器与首条消息 1.5h / 思考题作答 1h
> **今日产出**：Kafka vs RocketMQ 对照表 + 容器跑通 + M10 思考题②作答记录

## 1. 知识地图

```
先说人话：Kafka 是大数据世界的"中央传送带"——
  所有系统产生的数据（埋点/日志/订单变更）都先扔上传送带，
  需要数据的系统（数仓/搜索/风控/监控）各自从传送带上取自己那份。
  它既是消息队列，又是数据总线，还是一个"可回放的临时仓库"。

三个身份（按你的经验对应着记）：
  ① 消息队列（M5 已学）：削峰填谷/异步解耦——和 RocketMQ 同岗位
  ② 数据管道（本月新视角）：埋点日志先进 Kafka 再进 HDFS/Spark——
     上游系统只管"扔上传送带"，下游谁来取、取多少，上游完全不用知道
  ③ 流式存储：消息写进来默认保留 7 天——消费错了？把进度条拨回去重放
     （RocketMQ 也能回溯，但 Kafka 的"回放+生态"组合是大数据的命根子）

M10 思考题②开个头（day03 完整版）：
  问：Kafka 为什么能扛百万 TPS？——先给直觉：
  ① 磁盘顺序写比随机写快百倍（写日志本 vs 在字典里插行）
  ② 不经过应用层内存倒手（零拷贝，M3 学过的 sendfile 派上用场）
  ③ 攒一批再发再存（快递拼车）
  ④ 一个 Topic 切成多个分区，多台机器并行（多车道）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Kafka | 分布式消息系统（中央传送带+可回放仓库） |
| Data Pipeline | 数据管道（各系统数据汇入/分发的总线） |
| Replay / Backlog | 回放/积压（消费慢了先攒着，随时从头再读） |
| Log（Kafka 语义） | 追加式日志（只往末尾写、不改中间——像日记本不像表格） |
| Ecosystem | 生态（Connect 连接器/Streams 流计算等外围组件） |

## 3. 动手实操：容器拉起+第一条消息

```powershell
# ===== 步骤 1：拉起 Kafka（KRaft 模式：不需要 ZooKeeper，README 里的命令原样执行）=====
docker run -d --name kafka --network bigdata -p 9092:9092 `
  -e KAFKA_CFG_NODE_ID=0 `
  -e KAFKA_CFG_PROCESS_ROLES=controller,broker `
  -e KAFKA_CFG_CONTROLLER_QUORUM_VOTERS=0@kafka:9093 `
  -e KAFKA_CFG_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 `
  -e KAFKA_CFG_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092 `
  -e KAFKA_CFG_CONTROLLER_LISTENER_NAMES=CONTROLLER `
  -e KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT `
  bitnami/kafka:3.7
docker logs kafka --tail 5        # 看到 "Kafka Server started" 即成功

# ===== 步骤 2：建 Topic + 发消息 + 收消息（控制台三连）=====
docker exec kafka kafka-topics.sh --create `
  --topic hello-kafka --partitions 3 --replication-factor 1 `
  --bootstrap-server localhost:9092
docker exec kafka bash -c "echo 'msg-1'; echo 'msg-2'; echo 'msg-3'" | `
docker exec -i kafka kafka-console-producer.sh --topic hello-kafka --bootstrap-server localhost:9092
docker exec kafka kafka-console-consumer.sh --topic hello-kafka `
  --from-beginning --bootstrap-server localhost:9092 --max-messages 3
# 预期输出：msg-1 / msg-2 / msg-3（顺序可能变！day04 讲为什么）
```

## 4. 面试连接

**Q：项目里 RocketMQ 和 Kafka 你怎么选？（M5+M11 对照题）**
> 先说共同点：两者都做削峰/异步/解耦，核心模型（生产者-消费者-存储）一样，M5 的消费组/重试/事务消息概念在 Kafka 里都有对应物。分叉点看三件事：①业务消息还是数据管道——订单/支付这类强事务场景我选 RocketMQ（事务消息/延迟消息/消息查询是标配，重试机制更精细）；埋点日志、Binlog 分发、数仓接入这类高吞吐数据流我选 Kafka（吞吐上限更高，大数据生态的 Connect/Streams 全家桶围着它转）。②顺序性语义——两者都是"分区内有序"，RocketMQ 的顺序消息在队列级封装得对业务更友好；Kafka 靠按 key 路由到同一分区实现。③团队栈——纯 Java 微服务团队 RocketMQ 更顺手；有数据团队/实时计算需求时 Kafka 几乎是唯一解。一句话：业务消息看 RocketMQ，数据洪流看 Kafka——我们项目两个都在用，各干各的活。

**Q：为什么整个大数据生态都以 Kafka 为中心？（大数据视角题）**
> 三个原因。第一是"多订阅者"模型：同一份埋点数据，数仓要、实时风控要、监控也要——Kafka 一份存储 N 个消费组各自拉进度条，互不干扰；要是每个系统都来业务库抽一遍，业务库就被抽死了。第二是"缓冲区"角色：上游埋点流量尖峰 10 万 QPS，下游 HDFS 只能稳定吃 2 万——Kafka 顶在中间把洪峰攒住（磁盘便宜），下游按自己的节奏消费，这就是 M8 削峰思想在大数据域的原样复用。第三是"可回放"：下游 Spark 作业算错要重算？把 offset 拨回 7 天前的位置重跑就行——数据还在传送带上没扔。这三件事凑在一起，Kafka 就成了大数据的"数据总入口"，这也是为什么 M12 学 Flink 时你会发现：Flink 的数据源十有八九就是 Kafka。

## 5. 今日验收清单

- [ ] Kafka 三身份（消息队列/数据管道/流式存储）各一句话能讲
- [ ] 对照表：Kafka vs RocketMQ 三个分叉点（场景/顺序/生态）脱稿讲
- [ ] 容器跑通：建 Topic→发 3 条→收 3 条全流程截图留痕
- [ ] M10 思考题②作答记录落档（四点直觉+day03 补完整版）
- [ ] `git add . && git commit -m "day11-01: kafka overview"`

---
[← 本月目录](README.md) | [Day 02 · 架构与消息模型 →](day02-架构与消息模型.md)
