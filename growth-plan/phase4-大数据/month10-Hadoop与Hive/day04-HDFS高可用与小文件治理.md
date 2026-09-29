# Day 04 · HDFS 高可用与小文件灾难

> **今日目标**：解决 day02 留下的两个大问题——①NameNode 是单点，它挂了整个集群就瘫，怎么办（HA 高可用方案）；②为什么 1000 万个 1KB 小文件能把集群搞死（小文件问题），以及治理三板斧。今天还会亲手演练一次 NameNode 故障切换。
> **时长**：HA 机制 2h / 小文件问题与治理 2h
> **今日产出**：HA 架构图 + NameNode 切换演练记录 + 《小文件治理方案》v1

## 1. 知识地图

```
问题一：NameNode 挂了怎么办？——它是总台账，台账没了，货再多也是废铁。
（对照 M8 day16 学过的"冗余三级"：实例冗余 → 组件冗余，套路完全一样）

HA（High Availability）方案：两个 NameNode（Active 主 + Standby 备）+ 三个角色支撑：

① JournalNode（共享账本夹）：奇数个（3 个）节点，Active 每写一条 EditLog
   就同步写到多数派 JournalNode 上，Standby 持续从那读——
   两个 NameNode 的账本通过它保持一致。
   为什么奇数？——多数派投票防脑裂（M8 day16 脑裂三防线的原样复用：
   3 台 JournalNode 挂 1 台还能形成多数派；偶数台挂一半就僵局）。
② ZKFC（选主裁判）：每个 NameNode 旁边蹲一个，盯着健康状态，
   通过 ZooKeeper 抢锁决定谁是 Active——主挂了，备自动上位。
   （类比：哨兵模式的 HDFS 版——M4 Redis 哨兵那套"盯着+抢锁+切换"）
③ 客户端的无感切换：客户端配置了两个 NameNode 地址，连不上主就连备。

⚠ 一个坑：切换瞬间 Standby 必须把 EditLog 全部重放完才能接客，
   所以切换不是瞬时的（秒级）——和 M8 Redis 哨兵切换 30s 一个道理：
   冗余 ≠ 无感，要给业务配重试。

问题二：1000 万个 1KB 小文件，为什么是灾难？——算一笔元数据账：
  每个文件（不管 1KB 还是 128MB）在 NameNode 占约 150 字节元数据
  + 每个"块对象"也要 150 字节。1000 万个小文件 ≈ 30 亿+ 字节 ≈ 3GB 内存
  才能放元数据（真实生产 1 亿小文件就是 20GB+，NameNode 直接 OOM）。
  而且：①启动加载慢（全量读 FsImage）②每个小文件一个 Map 任务，调度开销大于计算
  ③寻址多、磁盘随机 IO 多，读写都慢。
  （类比：仓库里 1000 万张便签纸，每张都要登记台账——台账管理员累死，
    而且每张便签领一次都要走一遍流程。）

治理三板斧（按"预防→治理→规避"排）：
① 源头预防：写入端先合并——日志滚动别太频繁（按小时滚动而不是按分钟），
   上传前在本地先 merge 成大文件（M8 day05 攒批思想的存储版）
② 已有治理：归档合并——用 HAR（Hadoop Archive，把小文件打成一个包）
   或 CombineFileInputFormat（读的时候把多个小文件拼成一个任务）
   或重跑一个"合并任务"把目录里小文件重写成大文件（数仓层最常用）
③ 设计规避：数仓分层本身就治小文件——ODS 层即使乱，到 DWD 层统一
   "每天一个分区一个大文件"（day15 分层的又一个理由）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| HA (High Availability) | 高可用（主备两个 NameNode，主挂备上） |
| JournalNode | 共享日志节点（两个 NameNode 之间的"账本同步盘"，奇数个） |
| ZKFC (ZK Failover Controller) | 选主裁判（盯健康+ZooKeeper 抢锁+执行切换） |
| Active / Standby | 主（对外服务）/ 备（同步账本待命） |
| Small Files Problem | 小文件问题（元数据撑爆 NameNode+任务调度开销爆炸） |
| HAR (Hadoop Archive) | 归档文件（把小文件打包成一个大文件存） |
| CombineFileInputFormat | 合并输入格式（计算时把多小文件拼成一个任务） |

## 3. 动手实操：小文件实测与治理

```powershell
# 实验①：亲手制造 1 万个小文件，看 NameNode 元数据膨胀
docker exec hadoop-single bash -c "for i in ```$(seq 1 10000); do echo small > /tmp/f_```$i.txt; done"
docker exec hadoop-single hdfs dfs -mkdir -p /smallfiles
docker exec hadoop-single bash -c "hdfs dfs -put /tmp/f_*.txt /smallfiles/"
# Web UI (9870) 首页看 Heap Memory 使用——对比 day02 基线，肉眼可见上涨

# 实验②：数一数元数据账单（NameNode 报告块与文件数）
docker exec hadoop-single hdfs dfsadmin -metasave meta.txt
docker exec hadoop-single bash -c "cat /opt/hadoop/logs/meta.txt | head -10"
# 关注 Total files / Total blocks 两行——10000 个文件 10000 个块，1KB 也是一整个块！

# 实验③：治理——用合并任务重写（数仓层标准做法）
docker exec hadoop-single hdfs dfs -getmerge /smallfiles /tmp/merged.txt
docker exec hadoop-single hdfs dfs -rm -r /smallfiles
docker exec hadoop-single hdfs dfs -put /tmp/merged.txt /smallfiles-merged/
docker exec hadoop-single hdfs dfsadmin -metasave meta2.txt
docker exec hadoop-single bash -c "cat /opt/hadoop/logs/meta2.txt | head -10"
# 文件数 10000 → 1，块数 10000 → 1：一笔账看懂治理价值
# （生产里这步用 Hive 的 INSERT OVERWRITE 重写分区实现，day12 之后就用上了）

# HA 切换演练：伪分布式跑不了双 NameNode，改为"读配置理解法"——
# 阅读 big-data/04 附的 hdfs-site.xml HA 配置段（dfs.nameservices/dfs.ha.namenodes/...），
# 手画切换时序：Active 宕 → ZKFC 察觉 → ZK 抢锁 → Standby 重放日志 → 接客（秒级）
```

## 4. 面试连接

**Q：NameNode 是单点，怎么保证 HDFS 高可用？（答完架构必追问）**
> HA 双节点加三个支撑角色。两个 NameNode 一主一备：Active 对外服务，Standby 躲在后面同步账本待命。一致性靠 JournalNode 集群——Active 每条元数据修改都写多数派 JournalNode，Standby 实时读，保证备机的账本几乎和主机一样新。选主靠 ZKFC：每个 NameNode 挂一个故障控制器，通过 ZooKeeper 抢锁，主进程宕了备机自动接锁切换，客户端配了两个地址无感重连。两个细节体现工程理解：一是 JournalNode 为什么奇数——多数派投票防脑裂，这和 Redis 哨兵、etcd 选主是同一套数学；二是切换有秒级空窗（Standby 要重放完日志），所以业务端要配重试，冗余不等于完全无感。我在容器里没法跑三节点，但切换时序、脑裂防线这两块我都是手推过流程的——机制懂了，参数只是查文档的事。

**Q：小文件为什么是问题？你们怎么治理的？（数仓面试必问）**
> 先算账再说方案。NameNode 每个文件加每个块各占约 150 字节内存元数据，一亿个小文件就是几十 GB——NameNode 先 OOM；就算不挂，每个小文件默认一个 Map 任务，任务的启动调度开销比算 1KB 数据本身贵几百倍；读的时候随机寻址也多。治理按三个层次：源头治——采集端攒批，日志按小时滚动而不是按分钟，上传前先本地合并；存量治——定期跑合并任务把小目录重写成大文件，Hive 里就是 INSERT OVERWRITE 分区，顺带调 spark.sql.files.maxPartitionBytes 这类参数控制输出文件大小；设计防——数仓分层规范里写死"DWD 层每分区文件数上限、单文件 128M~1G"，把小文件挡在制度里。我们项目里最典型的是埋点日志：客户端 5 分钟滚一个文件，一天 288 个小文件/机，进 ODS 前先按小时合并，到 DWD 层每天每分区就几十个大文件——NameNode 和计算引擎都轻松了。

## 5. 今日验收清单

- [ ] HA 三支撑角色（JournalNode/ZKFC/双地址）白板可画
- [ ] JournalNode 奇数的多数派逻辑能讲（连 M8 脑裂三防线）
- [ ] 小文件三笔账（内存/任务/IO）能算
- [ ] 1 万小文件制造+合并治理实验完成（metasave 前后对比有数字）
- [ ] `git add . && git commit -m "day10-04: ha smallfiles"`

---
[← Day 03](day03-HDFS读写流程.md) | [本月目录](README.md) | [Day 05 · HDFS 实操与运维速查 →](day05-HDFS实操与运维速查.md)
