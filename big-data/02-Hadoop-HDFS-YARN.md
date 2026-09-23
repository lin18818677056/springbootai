# 02 · Hadoop：HDFS 分布式存储与 YARN 资源调度

> **本篇目标**：理解大数据世界的"地基"——HDFS（Hadoop Distributed File System，Hadoop 分布式文件系统）怎么存下 PB 级数据、怎么容错；YARN（Yet Another Resource Negotiator，资源调度器）怎么把集群资源分给计算任务。虽是"老技术"，但其分片/副本/调度思想被后来所有组件继承，**面试考 Hadoop 其实在考分布式基本功**。

## 术语表

| 英文 | 中文 |
|------|------|
| NameNode / DataNode | 名称节点（元数据管家）/ 数据节点（真正存块的工人） |
| Block | 数据块（HDFS 默认 128MB 一块，文件被切成块分散存储） |
| Replication | 副本数（默认 3：本机架 1 份 + 邻机架 2 份，防单点故障） |
| Rack Awareness | 机架感知（副本跨机架分布，防整个机架断电） |
| Federation | 联邦（多个 NameNode 分管不同目录，扩元数据上限） |
| Container | 容器（YARN 分配资源的最小单位：若干 CPU 核 + 内存） |
| ResourceManager / NodeManager | 资源管理器（全局调度）/ 节点管理器（单机执行） |
| ApplicationMaster | 应用主管（每个作业自带的"包工头"，向 RM 要资源、协调任务） |
| MapReduce | 映射归约（分而治之的计算模型，Hadoop 初代计算引擎） |
| Shuffle | 洗牌（Map 输出按 key 分发到 Reduce 的过程，性能大头） |

## 1. HDFS 设计目标：为什么大文件要切块？

单机磁盘上限 + 单机故障率 → 分布式存储的两大问题：**怎么放、怎么不丢**。

**切块（Block）的意义**：
1. 一个 1TB 文件 = 8192 个 128MB 块 → 分散到几十台机器**并行读写**（吞吐 = 机器数 × 单盘吞吐）。
2. 块是容错单位：某块坏了，副本顶上，**不影响文件其他部分**。
3. 块太大 → 小文件浪费空间且任务并行度低；块太小 → NameNode 元数据爆炸。

**读写流程（面试常问"客户端读一个文件的完整过程"）**：
```
写：Client → NameNode(问：这些块放哪？) → 返回 DataNode 列表（就近+负载）
  → Client 把块写入第一个 DN → DN 之间流水线复制（pipeline）→ 确认 → 提交
读：Client → NameNode(问：块在哪？) → 直接从最近的 DN 读块（数据不经过 NameNode！）
```

**架构铁律**：NameNode 只管元数据（文件→块的映射），**数据流永远绕过 NameNode**——这是它扛住高并发的关键。

## 2. NameNode 容错：元数据丢了全完蛋

| 机制 | 作用 |
|------|------|
| FsImage + EditLog | 磁盘镜像 + 增量编辑日志（类似 MySQL 的全量 binlog + redo） |
| Standby NameNode（HA，高可用） | 主备双 NameNode，基于 JournalNode 集群共享 EditLog，故障秒级切换 |
| 高可用对 = ZooKeeper 选主 | 防脑裂（两主并存） |

类比：NameNode HA ≈ 你熟悉的 MySQL 主从 + Raft 选主（成长计划 month05），概念完全相通。

## 3. 小文件问题（生产最常见 HDFS 病）

**症状**：NameNode 内存按"每块约 150 字节"记账，1 亿个小文件 = 元数据几十 GB；计算时每个小文件起一个 task（任务），调度开销吞掉全部算力。

**治理三板斧**：
1. 入库前合并：上游按大小聚合（SequenceFile / Parquet 行组）。
2. 定期 Compaction（合并小文件任务）：Hive 的 `concatenate`、Spark `repartition` 后重写。
3. 架构层：日志类海量小文件走 Kafka 直接流式入库，别落地成小文件。

## 4. YARN：资源调度的"中央粮仓"

```
Client 提交作业
  → ResourceManager 的 AppsManager 接收，起一个 ApplicationMaster（包工头）
  → AM 向 RM 的 Scheduler 申请 Container（资源容器）
  → RM 与 NodeManager 协作在对应机器拉起 Container 跑任务
  → 任务完成，Container 释放
```

**调度器对比**：

| 调度器 | 特点 | 场景 |
|--------|------|------|
| FIFO | 先来先得 | 只有体验 |
| Capacity Scheduler | 队列切分资源配额，队列间可弹性借用 | 多团队共享（国内主流） |
| Fair Scheduler | 公平共享，空闲资源自动分给需要的作业，不设配额硬隔离 | 小团队多作业共享集群 |

**核心参数直觉**：`mapreduce.map.memory.mb`（map 任务内存）、`yarn.nodemanager.resource.memory-mb`（单机总内存配额）——任务内存 > 配额 = 被 YARN 直接杀掉（报 Container killed，容器被杀），这是新手第一坑。

## 5. MapReduce：分而治之的祖师爷（理解思想即可，不必写）

```java
// WordCount 思想：两阶段
map(line)    → 拆词，输出 (word, 1)                    // 映射：切碎
reduce(word, List<1>) → sum → 输出 (word, 总数)         // 归约：汇总
```

**Shuffle 是灵魂**：Map 输出按 `hash(word) % reduce数` 分区 → 落盘排序 → Reduce 远程拉取 → 这是**数据在网络上的大迁徙**，MR/Spark 性能调优 80% 都在调 Shuffle。

为什么现在不直接写 MR？→ 太啰嗦（一个 WordCount 几百行）→ Hive 把 SQL 编译成 MR/Spark 作业 → 你写 SQL，它写样板代码。

## 6. 动手实验（Docker 单机版，1 小时）

```bash
docker run -d --name hadoop -p 9870:9870 -p 8088:8088 \
  apache/hadoop:3.3.6 hive   # 官方镜像带 HDFS+YARN（9870 HDFS UI，8088 YARN UI）
# 练习清单：
# 1. hdfs dfs -mkdir /test && 上传一个大日志文件
# 2. 浏览器打开 :9870 看块的分布与副本
# 3. 杀掉一个 DataNode（单机可模拟停止进程）观察 UI 告警与副本自愈
# 4. 跑一次 hadoop jar mapreduce 示例作业，在 :8088 看 Container 分配
```

## 验收自测

- [ ] 能说清"切块的三重意义"，并解释块大小权衡
- [ ] 能完整口述 HDFS 写/读流程（含"数据不经过 NameNode"）
- [ ] 能解释 FsImage/EditLog 与 NameNode HA 原理
- [ ] 能诊断小文件问题并说出三种治理手段
- [ ] 能画出 YARN 提交作业流程，并解释 AM 是什么
- [ ] 能解释 Shuffle 为什么是性能大头

## 延伸阅读

- Hadoop 官方文档 HDFS Architecture 章节（英文原文 + 本文术语表对照）
