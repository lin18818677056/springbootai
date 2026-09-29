# Day 02 · HDFS 架构：总台账与货架

> **今日目标**：搞懂 HDFS 的三大角色（NameNode/DataNode/SecondaryNameNode）各自干什么、Block 128MB 和三副本是怎么回事——一句话：**NameNode 是仓库总台账，DataNode 是货架，文件入库前先撕成块，每块复印三份放不同货架。** 今天还要亲手搭一个能用的 HDFS。
> **时长**：架构理解 2h / 伪分布式搭建 2h
> **今日产出**：HDFS 架构手绘图 + Docker 伪分布式跑通 + Block 参数实验

## 1. 知识地图

```
问题引入：一个 100GB 的日志文件要存下来，还要"坏了能恢复、随时能读"，
一台机器显然不行（磁盘不够大，机器一挂全丢）。HDFS 的解法分三步：

第一步【切块 Block】：100GB 不是整着放的，先切成 128MB 一块的"页"。
  100GB ÷ 128MB ≈ 800 块。每块就是 HDFS 存储的最小单位。
  为什么是 128MB，不是 4KB（操作系统页大小）？
  ——HDFS 的典型用途是"一次写入、大批量顺序读"（日志/备份/数仓），
    块太小 → 管理块的开销爆炸（NameNode 要记每个块在哪）；
    块太大 → 一个任务处理一个块，并行度不够。
  128MB 是"元数据量可控"和"任务粒度够细"的折中（可在 hdfs-site.xml 调）。

第二步【分放 DataNode】：800 块撒到集群的 DataNode（货架）上。
  DataNode 只管两件事：存块、定期向 NameNode 汇报"我这块还活着"（心跳+块报告）。
  （类比：货架员不需要知道全馆藏书布局，只管自己这排货架，每天打卡报平安。）

第三步【复印三份 Replication=3】：每个块存 3 份，防单点丢失。
  放置策略（机架感知，day03 细讲）：第 1 份在本机架一台机器，
  第 2 份在另一个机架，第 3 份在第 2 份同机架的另一台机器——
  兼顾"坏一台机器不丢"和"读的时候尽量就近"。

谁来记"哪块文件由哪些块组成、每块在哪个货架"？——NameNode（总台账）：
  内存里维护两张表：①文件→块的映射 ②块→DataNode 的映射。
  所以 NameNode 内存是集群规模的天花板——1 亿个块的元数据 ≈ 10GB+ 内存，
  这也是"小文件是灾难"的根源（day04 细讲：一个 1KB 的小文件也占一条完整元数据）。

台账怎么防丢？——FsImage + EditLog 双保险：
  FsImage = 台账的定期完整快照（拍照）；EditLog = 两次拍照之间的流水账（记增量）。
  NameNode 重启 = 加载最近一次快照 + 重放之后的流水账 = 恢复到最新状态。
  （类比：商铺台账每天关店拍张照，当天新订单记在本子上，第二天对上。）

⚠ 高频误区：SecondaryNameNode 不是备份节点！
  它的真实工作是"帮忙合并账本"：定期把 EditLog 合并进 FsImage，
  减少重启时的重放时间。它甚至不是最新的（有合并间隔的空窗）。
  真正的高可用靠 NameNode 主备切换（day04 的 HA 方案）。
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| NameNode | 总台账（管理元数据：哪个文件切成哪些块、每块在哪）——单点，挂了全集群瘫痪 |
| DataNode | 货架（真正存数据块的节点），定期心跳汇报存活与块清单 |
| Block | 数据块（默认 128MB），HDFS 存储与计算的最小分配单位 |
| Replication | 副本数（默认 3），任何一块丢了可以从另外两份恢复 |
| FsImage | 元数据快照（台账照片） |
| EditLog | 元数据操作日志（流水账），重启时重放到最新 |
| SecondaryNameNode | 快照合并助手（不是热备！常见面试坑） |
| Heartbeat | 心跳（DataNode 每 3 秒向 NameNode 报到） |

## 3. 动手实操：伪分布式搭建与 Block 实验

```powershell
# 复用 day01 容器（若没起则重跑 day01 命令），今天改用完整配置启动脚本：
# big-data/02 教程附 docker-compose.yml（namenode+datanode 双容器版），核心验证三件事：

# ① 看元数据服务的健康（Live datanodes 应有 1 个）
docker exec hadoop-single hdfs dfsadmin -report | Select-String "Live datanodes" -Context 0,5

# ② 上传一个 >128MB 的文件，验证切块
docker exec hadoop-single bash -c "dd if=/dev/urandom of=/tmp/big.bin bs=1M count=300"
docker exec hadoop-single hdfs dfs -put /tmp/big.bin /
# 300MB ÷ 128MB = 2 个整块 + 1 个 44MB 尾块 = 3 块
docker exec hadoop-single hdfs fsck /big.bin -files -blocks
# 输出应显示 3 个 block，每个有 blk_xxx 编号——这就是"切块"的直观证据

# ③ 看副本系数与块大小配置（默认值在哪）
docker exec hadoop-single hdfs getconf -confKey dfs.replication      # 3
docker exec hadoop-single hdfs getconf -confKey dfs.blocksize        # 134217728 (=128MB)

# ④ 观察"块报告"：Web UI http://localhost:9870 → Utilities → Browse 也能看到块列表
# 思考题自验：如果把 blocksize 改成 64MB，300MB 会切几块？（答：4 块+校验，动手改参数重传验证）
```

## 4. 面试连接

**Q：讲讲 HDFS 的架构？（大数据面试第一题，白板画图题）**
> 三个角色各一句话。NameNode 是总台账：内存里维护"文件→块→DataNode"两级映射，所有元数据的查询都走它，所以它是集群的单点和天花板。DataNode 是货架：真正存 128MB 的数据块，每 3 秒心跳报到、定期交块报告，断联超时就被判"下架"，上面的块由 NameNode 指挥其他节点补副本。SecondaryNameNode 最容易被误解——它不是热备，只是定期把 EditLog 合并进 FsImage 的助手，真正的容灾是 NameNode HA 主备加 JournalNode 共享日志。数据可靠性靠三副本加机架感知放置：坏一台机器甚至坏一个机架，数据都不丢。这个架构和 Java 后端的知识是同构的：NameNode 类似注册中心加路由表（Nacos），DataNode 类似无状态的存储节点，心跳机制就是 Redis 哨兵那套存活探测——换汤不换药。

**Q：Block 为什么默认 128MB？太小或太大各有什么问题？**
> 这是个折中题，答"权衡"而不是背数字。HDFS 面向的是大文件批处理：块太小，比如 4KB，一个 1TB 文件就是 2.7 亿个块，NameNode 每块要存约 150 字节元数据，光元数据就 40GB——内存爆了，而且任务调度开销也按块数涨。块太大，比如 1GB，一个文件只有几个块，能并行处理的任务数就少，集群并行度上不去，而且小文件和块大小错配会更严重。128MB 让 1TB 文件有 8000 个块（并行度足够），元数据 1.2MB（内存无压力）。追问答：块大小可以按场景调——数仓列存文件通常设到 256MB 减少 NameNode 压力，这是后面 day19 的伏笔。

## 5. 今日验收清单

- [ ] 三角色职责+FsImage/EditLog 机制白板可画（含 SecondaryNameNode 误区标注）
- [ ] 伪分布式跑通：300MB 文件切块实验完成（亲眼看到 3 个块）
- [ ] Block 128MB 的权衡论证能脱口而出
- [ ] 心跳与块报告机制能讲清（3 秒心跳/块报告内容）
- [ ] `git add . && git commit -m "day10-02: hdfs arch"`

---
[← Day 01](day01-大数据全景与后端视角.md) | [本月目录](README.md) | [Day 03 · HDFS 读写流程 →](day03-HDFS读写流程.md)
