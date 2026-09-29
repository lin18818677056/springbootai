# Day 02 · RocketMQ 架构与部署：四角色 + 三件套搭建

> **今日目标**：搞清 NameServer/Broker/Producer/Consumer 四角色分工与通信关系，Docker Compose 部署 NameServer + Broker + Dashboard 三件套，用控制台完成第一次收发。
> **时长**：架构 1.5h / 部署 1.5h / 源码浏览 1h
> **今日产出**：可运行的 RocketMQ 环境 + 架构通信图

## 1. 知识地图

```
RocketMQ 架构（四角色两交互）：

  Producer ──①发消息──→ Broker ──⑤推消息──→ Consumer
     │                    │ ②                    │
     │③④                 │ ⑥                    │
     ↓                    ↓                      ↓
  NameServer ◄─────────── 注册心跳(30s)
  （无状态路由中心）

  ① Producer 从 NameServer 拿路由（Broker 地址/队列数），30s 缓存
  ② Broker 收到消息写入 CommitLog
  ④ Broker 每 30s 向所有 NameServer 发心跳注册 Topic 路由
  ⑤ Consumer 同样从 NameServer 拿路由，向 Broker 拉消息（长轮询）

关键设计问答（面试高频）：
  Q NameServer 为什么是无状态的？ZooKeeper 那么强为什么不用？
  A NameServer 之间互不通信，Broker 同时向所有 NameServer 注册。
    牺石"全局一致的路由视图"换来极致简单（无共识开销）。
    路由 30s 内不一致（客户端缓存）对消息中间件可接受——这就是 CAP 里选 AP 的工程体现（day15 正式讲）。
  Q Broker 主从怎么分工？
  A Master 收写请求，Slave 只做备份与读分担（4.5+ 支持readsFromSlave）；
    Master 挂了 4.x 默认不能自动切换（要 DLedger 或 5.0 Controller）。
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| NameServer | 路由注册中心，无状态，部署多台互不通信 |
| Broker | 消息存储与转发节点；Master 对应 slave，brokerId=0 是主 |
| Topic | 消息主题（逻辑分类），一 Topic 多 Queue |
| MessageQueue | 分区/队列，并行度与顺序性的基本单元（类比 Kafka 的 Partition） |
| Tag | 二级过滤标签（Topic=订单，Tag=创建/支付） |
| Consumer Group | 消费者组，组内集群消费（分摊），组间广播 |
| CLUSTERING | 集群模式：一条消息一组内只有一个消费者处理 |
| BROADCASTING | 广播模式：一条消息组内每个消费者都处理 |
| Offset | 消费位移，集群模式存 Broker（RemoteBrokerOffsetStore） |

## 3. 动手实操：Docker Compose 三件套

```powershell
# 目录与配置
New-Item -ItemType Directory -Force "D:\mywork\springbootai\learning\month05-mq-theory\rocketmq"
# broker.conf（写到上面目录）关键内容：
#   brokerClusterName = DefaultCluster
#   brokerName = broker-a
#   brokerId = 0
#   namesrvAddr = namesrv:9876
#   brokerIP1 = 127.0.0.1          ← 必配！否则宿主机客户端拿到容器内网 IP 连不上
#   deleteWhen = 04
#   fileReservedTime = 48
#   flushDiskType = ASYNC_FLUSH    ← day04 讲刷盘时改它

# docker-compose.yml（同目录）：三个服务
#   namesrv:  apache/rocketmq:5.3.1 → sh mqnamesrv        端口 9876
#   broker:   apache/rocketmq:5.3.1 → sh mqbroker -n namesrv:9876 -c /home/rocketmq/broker.conf
#             挂载 broker.conf，端口 10909/10911/10912
#   dashboard: apacherocketmq/rocketmq-dashboard:latest
#             环境变量 NAMESRV_ADDR=namesrv:9876，端口 8180
# 注意 broker 内存默认 8g 会爆，加 JAVA_OPT_EXT="-Xms512m -Xmx512m -Xmn256m"

docker compose up -d
docker compose ps                 # 三者 Up
Start-Process "http://localhost:8180"   # Dashboard：集群→ broker-a 在线

# 控制台收发验证（进入 broker 容器）
docker exec -it learning-broker-1 bash
sh mqadmin updateTopic -n namesrv:9876 -c DefaultCluster -t TEST_TOPIC
sh mqadmin sendMessage  # 5.x 也可直接用 tools.sh 演示；简单方式：Dashboard 顶部"消息"→发送消息
# 或写 Java 客户端（秒杀工程里直接加个 main 方法测试）：
#   DefaultMQProducer producer = new DefaultMQProducer("demo_group");
#   producer.setNamesrvAddr("127.0.0.1:9876");
#   producer.start();
#   producer.send(new Message("TEST_TOPIC", "TagA", "hello rocketmq".getBytes()));
```

## 4. 面试连接

**Q：NameServer 挂了一台会怎样？**
> 几乎无感。客户端拿过路由会缓存 30s，期间照常收发；NameServer 多台互为独立副本，挂一台只是少一个查询入口。真正的单点是 Broker——它才是存数据的。这体现 RocketMQ 的设计哲学：把复杂度从"路由中心"转移到"数据节点"，路由中心可以做得极简（对比 ZooKeeper 的一致性开销）。

**Q：集群模式与广播模式的区别？Offset 存哪里？**
> 集群模式：组内分摊，一条消息只处理一次，Offset 存 Broker 端（便于重平衡与重消费）；广播模式：组内每个消费者都处理，Offset 存消费者本地文件（因为消费进度彼此独立）。广播模式没有重试与死信机制，失败只能记日志——所以生产几乎只用集群模式，广播用于本地配置刷新类场景。

## 5. 今日验收清单

- [ ] 三件套部署完成，Dashboard 能看到 broker-a
- [ ] broker.conf 的 brokerIP1 作用能讲清（内网 IP 坑）
- [ ] 手画四角色通信图（含 30s 心跳与缓存）
- [ ] Java 客户端发送成功，Dashboard"消息"页能看到
- [ ] 集群/广播模式区别 + Offset 存储位置能脱稿
- [ ] `git add . && git commit -m "day02: rocketmq deploy"`

---
[← Day 01](day01-MQ选型全景.md) | [本月目录](README.md) | [Day 03 · 消息存储设计 →](day03-消息存储设计.md)
