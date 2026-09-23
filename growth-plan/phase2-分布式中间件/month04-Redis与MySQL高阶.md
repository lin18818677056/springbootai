# 第 4 月：Redis（Remote Dictionary Server）深度 与 MySQL 高阶

> **本月一句话目标**：把 Redis 用到"源码级理解 + 生产级设计"（数据结构、持久化、高可用、缓存一致性、分布式锁），并完成 MySQL 分库分表实战。本月底启动实战项目一（高并发秒杀系统）。

## 本月目标与验收标准

- [ ] 讲清 Redis 核心数据结构底层实现（SDS、跳表 Skip List、ListPack、QuickList）与对象编码
- [ ] 对比 RDB（Redis Database）/ AOF（Append Only File）持久化，讲清主从复制、哨兵（Sentinel）、集群（Cluster）三种高可用方案
- [ ] 设计一套缓存一致性方案，能讲清缓存穿透/击穿/雪崩的全部治理手段
- [ ] 手写分布式锁演进版（SETNX → Lua → Redisson 看门狗），讲清 RedLock（红锁）争议
- [ ] 用 ShardingSphere 完成订单表分库分表 Demo，输出数据迁移方案
- [ ] 秒杀项目启动：完成架构设计与基础框架搭建（见 `practice-projects/01-seckill-system/step01`）

---

## 每日计划

### 第 1 周：Redis 数据结构与原理

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | Redis 总体架构 | 单线程模型（为什么快：内存 + epoll + 单线程无锁）、6.0 多线程 IO（Multi-IO Thread）、Reactor 模式回顾 | 画出 Redis 命令执行全流程图 | 能回答"Redis 到底是不是单线程" |
| D2 | 底层数据结构(上) | SDS（Simple Dynamic String，简单动态字符串：len/alloc 预分配）、Dict（字典：渐进式 rehash Progressive Rehash）、ZipList→ListPack（紧凑列表） | 读 Redis 源码 sds.c/t_zset.c 关键函数 | 能讲清渐进式 rehash 为何不阻塞 |
| D3 | 底层数据结构(下) | SkipList（跳表：多层索引、随机层数幂次定律）、IntSet（整数集合）、QuickList（快速列表 = ListPack 双向链） | 手写一个简化跳表并压测 | 手写跳表与 zset 对拍通过 |
| D4 | 对象系统与编码 | 五大类型与底层编码映射（String→int/embstr/raw；Hash→listpack/hashtable；ZSet→listpack/skiplist；BitMap、HyperLogLog 基数统计、GEO 地理位置、Stream 消息流） | 用 `OBJECT ENCODING` 逐一验证编码变化 | 能说出每种类型在什么阈值发生编码转换 |
| D5 | 内存与淘汰策略 | 内存碎片（Fragmentation）与 activedefrag（主动碎片整理）、八种淘汰策略（noeviction/allkeys-lru/volatile-lru/lfu 系列）、LRU（Least Recently Used 近似淘汰）与 LFU（Least Frequently Used，最不经常使用：morris 计数） | 实验触发淘汰并观察 `INFO memory` | 能为"缓存库"和"持久库"各选对策略 |
| D6 | 事件与事务 | 文件事件（File Event）与时间事件、MULTI/EXEC 事务（不支持回滚）、Lua 脚本原子性、Pipeline（管道批量）与 Lua 的区别 | 用 Lua 实现原子"库存扣减+限购校验" | 能讲清 Lua 比 MULTI 强在哪 |
| D7 | 周复盘 | 数据结构串讲 | 周记 + 《Redis 类型-编码-场景速查表》 | 面试"跳表 vs 红黑树"能答到源码层面 |

### 第 2 周：持久化、高可用与分布式锁

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 持久化 RDB | fork（写时复制 Copy-On-Write）、save 条件、RDB 文件紧凑但可能丢数据；AOF（Append Only File：always/everysec/no 三档）、AOF 重写（Rewrite）与混合持久化（Hybrid Persistence 4.0+） | Docker 部署 Redis 分别开启 RDB/AOF，kill -9 验证恢复 | 能算出 everysec 最多丢 1 秒数据的原理 |
| D2 | 主从复制 | 全量同步（RDB + 缓冲区）、增量同步（Replid + Offset 复制积压缓冲区 Backlog）、无盘复制（Diskless）、主从延迟监控 | Docker 搭一主二从，模拟从库断线重连 | 能讲清"部分重同步"如何靠 repl_backlog 实现 |
| D3 | 哨兵 Sentinel | 监控/通知/自动故障转移（Failover）、主观下线（SDOWN）vs 客观下线（ODOWN）、Raft 式 Leader 选举、选从库优先级 | 搭 1 主 2 从 3 哨兵，手工 kill 主库观察切换全过程 | 完整复述一次故障转移时间线 |
| D4 | 集群 Cluster | 16384 槽（Hash Slot）、Gossip 协议（流言协议：ping/pong/meet/fail）、MOVED 与 ASK 重定向、智能客户端、批量操作限制 | Docker 搭 3 主 3 从集群，扩容到 4 主（reshard） | 能讲清为什么是 16384 个槽 |
| D5 | 缓存三大问题 | 缓存穿透（Penetration：布隆过滤器 Bloom Filter + 空值缓存）、击穿（Breakdown：互斥锁 + 逻辑过期）、雪崩（Avalanche：随机过期 + 多级缓存 + 熔断） | 为秒杀项目写缓存三件套工具类 | 三种问题的"事前/事中/事后"手段全覆盖 |
| D6 | 缓存一致性 | Cache Aside（旁路缓存：先更库再删缓存）、延迟双删、binlog 异步（Canal 订阅）、读写穿透（Read/Write Through）、Write Behind（异步写回）；最终一致性选型 | 画出四种方案的时序图与适用场景 | 能讲清"为什么是删缓存不是改缓存" |
| D7 | 分布式锁 | SET NX PX + 唯一值 + Lua 释放；Redisson（看门狗 Watchdog 自动续期、可重入、RedLock 红锁及其争议，Martin Kleppmann vs Antirez 论战）；对比 ZooKeeper 锁 | 手写演进版锁 + 用 Redisson 实现可重入锁 | 手写版通过并发压测无超卖 |

### 第 3 周：MySQL 高阶（分库分表与海量数据）

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 何时分库分表 | 单表行数/容量经验值、垂直拆分（Vertical：按业务/按冷热）vs 水平拆分（Horizontal：按行路由）；拆分带来的问题清单（跨库 join、分布式事务、翻页、ID） | 估算一个订单系统的拆分规模 | 能画出拆分前后架构对比图 |
| D2 | 分片策略 | 范围分片（Range）、哈希分片（Hash：取模/一致性哈希）、基因法解决"多维度查询"（订单号里嵌入买家 ID 基因）、非分片键查询方案（异构索引表/ES 宽表/CQRS） | 设计订单库分片键方案并写设计文档 | 能讲清基因法的位运算原理 |
| D3 | ShardingSphere 实战 | Apache ShardingSphere：JDBC 模式接入、分片算法配置、绑定表/广播表、分布式主键（Snowflake 雪花算法：符号位+时间戳+机器ID+序列号） | 完成订单表 2 库 × 4 表分片 Demo | 路由日志可见精确命中单库单表 |
| D4 | 分布式 ID 与翻页 | 雪花算法时钟回拨（Clock Backward）问题与解决（等待/扩展位/号段模式 Leaf）；分库分表深分页方案（游标/二次查询法/ES） | 手写改良雪花算法；实现跨分片分页 Demo | 时钟回拨三种方案能白板写 |
| D5 | 数据迁移与双写 | 全量迁移（DataX/Shell 脚本）+ 增量同步（Canal/binlog）+ 灰度切流（按用户比例）+ 数据校验（对账）；双写不一致场景 | 输出《分库分表迁移方案》文档 | 方案含回滚预案，通过自评 |
| D6 | 读写分离与高可用 | MySQL 高可用方案对比（MHA/MGR/Orchestrator/云 RDS）、Proxy vs SDK 路由、主从延迟下的读一致性（强制走主/会话粘性/GTID 等待） | ShardingSphere 配置读写分离 Demo | 能画出读写分离完整链路 |
| D7 | 周复盘 | 海量数据方案串讲 | 周记 + 《分库分表决策树》（何时拆/怎么拆/怎么迁） | 决策树可应付任何拆表追问 |

### 第 4 周：综合实战 + 秒杀项目启动

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 项目一启动 | 秒杀系统需求澄清、指标设定（预估峰值 QPS、库存量、RT 目标）、整体架构分层设计 | 阅读 `practice-projects/01-seckill-system/step01-架构设计与环境搭建.md` 并完成 | 架构图 + 接口定义完成 |
| D2 | 基础框架搭建 | Spring Boot 多模块（multi-module）工程、Gradle 依赖管理、Docker Compose 一键拉起 MySQL/Redis 环境 | 完成 step01 环境搭建与骨架代码 | `gradlew bootRun` 启动成功 |
| D3 | 商品与库存模块 | 库存模型设计（防超卖的核心：数据库乐观锁/扣减语句） | 完成 `step02` 商品详情与库存接口 | 接口联调通过 |
| D4 | 缓存预热与详情页 | 缓存预热（Warm-up）、多级缓存（本地 Caffeine + Redis）、页面静态化思想 | 完成 step02 缓存体系与压测 | 详情接口 QPS 达标（本机 ≥ 3000） |
| D5 | 防超卖核心 | Lua 原子扣减、令牌桶预热库存 | 完成 step03 防超卖实现 | 并发扣减 0 超卖 0 少卖 |
| D6 | 限流与防刷 | Sentinel 接入、接口限流、验证码/答题削峰、黑名单 | 完成 step03 限流防刷部分 | 单机限流阈值生效 |
| D7 | 月度总复盘 | Redis+MySQL 全域串讲 | 月记 + 秒杀项目周报 | 达成本月全部验收标准 |

---

## 本月产出清单

1. 《Redis 类型-编码-场景速查表》《分库分表决策树》《迁移方案文档》
2. 手写代码：简化跳表、演进版分布式锁、改良雪花算法
3. 秒杀项目：架构文档 + 可运行骨架 + 商品/库存/缓存模块
4. 1 篇博客：《Redis 分布式锁的三代演进与 RedLock 之争》

## 本月术语表

| 英文 | 中文 |
|------|------|
| SDS (Simple Dynamic String) | 简单动态字符串 |
| Progressive Rehash | 渐进式 rehash（扩容时分批迁移桶） |
| Hash Slot | 哈希槽（Redis Cluster 分片单位，共 16384） |
| Gossip Protocol | 流言协议（集群节点间去中心化通信） |
| Watchdog | 看门狗（Redisson 锁自动续期机制） |
| Bloom Filter | 布隆过滤器（可能误判存在、不会漏判） |
| Clock Backward | 时钟回拨 |
| Sharding / Resharding | 分片 / 重新分片 |
| Vertical Scaling / Horizontal Scaling | 垂直扩展（升配置） / 水平扩展（加机器） |

> 本月已启动项目一，接下来边学边做：`../month05-消息队列与分布式理论.md`。
