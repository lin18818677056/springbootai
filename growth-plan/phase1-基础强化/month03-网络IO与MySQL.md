# 第 3 月：网络 IO（Input/Output，输入/输出）与 MySQL 深度

> **本月一句话目标**：打通"网络协议 → IO 模型 → Netty → RPC"链路，并把 MySQL 从"会写 SQL"升级到"懂索引结构、事务原理、锁机制、慢 SQL 治理"——这两块是 P7 专科硬功夫，也是所有高并发设计的地基。

## 本月目标与验收标准

- [ ] 讲清 TCP 三次握手/四次挥手每个状态的含义，能解释 TIME_WAIT 过多如何治理
- [ ] 对比 BIO/NIO/AIO，讲清 Reactor（反应器）模式与 Netty 主从 Reactor 线程模型
- [ ] 手写一个基于 Netty 的简易 RPC（Remote Procedure Call，远程过程调用）框架
- [ ] 画图讲清 B+ 树、聚簇索引、回表、覆盖索引、MVCC（Multi-Version Concurrency Control，多版本并发控制）
- [ ] 独立完成 5 个慢 SQL 的治理（EXPLAIN 执行计划逐字段解读）
- [ ] 产出 2 篇博客：《Netty 手写 RPC》《一条 UPDATE 语句在 MySQL 里的完整旅程》

---

## 每日计划

### 第 1 周：TCP/IP（Transmission Control Protocol / Internet Protocol，传输控制协议/网际协议）与 HTTP

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 网络分层模型 | OSI 七层 vs TCP/IP 四层；每层协议与设备；抓包工具 Wireshark/tcpdump | 抓一次 HTTP 请求包并逐层分析 | 能按层拆解一次完整请求 |
| D2 | TCP 连接管理 | 三次握手（Three-way Handshake）、四次挥手（Four-way Handshake）、状态机（SYN_SENT/ESTABLISHED/TIME_WAIT/CLOSE_WAIT）、半连接队列与全连接队列（SYN Queue / Accept Queue） | `netstat -an` 观察状态；用 `ss -s` 统计 | 能讲清为什么握手三次挥手四次 |
| D3 | TCP 可靠传输 | 滑动窗口（Sliding Window）、拥塞控制（Congestion Control：慢启动、拥塞避免、快重传、快恢复）、Nagle 算法与延迟确认、粘包拆包（Sticky Packet） | 用 Wireshark 观察一次慢启动过程 | 能解释"TCP 为什么会粘包" |
| D4 | TIME_WAIT 与长连接 | TIME_WAIT 2MSL 的原因、过多 TIME_WAIT/CLOSE_WAIT 的排查与治理（连接复用、`tcp_tw_reuse`、应用层连接池） | 模拟高频短连接场景并观察统计 | 能输出一份短连接治理方案 |
| D5 | HTTP 演进 | HTTP/1.1 队头阻塞（Head-of-Line Blocking）、HTTP/2 多路复用（Multiplexing）、头部压缩 HPACK、HTTP/3 QUIC（基于 UDP 的可靠传输） | 用 curl 对比 HTTP/1.1 与 HTTP/2 的资源加载 | 能画出三种协议并发请求的差异 |
| D6 | HTTPS 与 TLS | TLS（Transport Layer Security，传输层安全）握手流程、对称/非对称加密、证书链（Certificate Chain）验证 | 抓包分析一次完整 TLS 1.3 握手（1-RTT） | 能讲清 TLS 1.2 与 1.3 的握手差异 |
| D7 | 周复盘 | 网络协议串讲 | 周记 + 手绘协议状态图两张 | 能脱稿讲 15 分钟网络基础 |

### 第 2 周：IO 模型与 Netty（异步事件驱动网络框架）

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | IO 基础概念 | 用户态/内核态（User Space / Kernel Space）、缓冲区 IO 与直接 IO、同步/异步、阻塞/非阻塞（五个 IO 模型总览） | 画出五模型对比表 | 能纠正"NIO = 非阻塞 = 异步"的混淆 |
| D2 | Java NIO | Channel（通道）、Buffer（缓冲区：position/limit/capacity）、Selector（选择器）、`select/poll/epoll`（多路复用系统调用，epoll 事件驱动） | 手写 NIO Echo 服务端 | 能讲清 epoll 的红黑树+就绪链表 |
| D3 | Reactor 模式 | 单 Reactor 单线程、单 Reactor 多线程、主从 Reactor 多线程（Master-Slave Reactor）；Proactor（前摄器）模式对比 | 画出三种 Reactor 结构图并对应到 Netty | 能把 Redis/Netty/Kafka 对号入座 |
| D4 | Netty 核心组件 | EventLoop（事件循环）与 EventLoopGroup、Channel、Pipeline（管道）与 Handler、ByteBuf（池化字节缓冲）、Future/Promise | 读 Netty 启动流程源码主线（bind → register → boss 接收） | 能讲清一条消息在 Netty 中的流转路径 |
| D5 | Netty 实战要点 | 编解码器（Encoder/Decoder）、LengthFieldBasedFrameDecoder 解决粘包、心跳（IdleStateHandler）、零拷贝（Zero-Copy：CompositeByteBuf、FileRegion/mmap、sendfile） | 实现自定义协议（魔数+长度+JSON）通信 | 两个服务互发 10 万条消息无错序无粘包 |
| D6 | 手写 RPC(上) | RPC 架构：动态代理（Dynamic Proxy）、序列化（Serialization：JSON/Protobuf/Hessian 对比）、网络传输、注册中心 | 实现代理 + 协议 + Netty 传输 | 消费者能透明调用提供者方法 |
| D7 | 手写 RPC(下) | 注册中心（用 Nacos 或简化 Map）、负载均衡（Load Balancing：随机/轮询/一致性哈希） | 完成双节点负载均衡 RPC；博客开写 | 博客：《Netty 手写 RPC》发布 |

### 第 3 周：MySQL 索引与查询优化

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | InnoDB 存储结构 | 页（Page，16KB）、区（Extent）、段（Segment）、B+ 树（B+ Tree）与 B 树对比、 why 3~4 层可存千万级数据 | 用数据反推 B+ 树高度公式 | 能算出 2000 万行数据的树高 |
| D2 | 索引分类与原理 | 聚簇索引（Clustered Index）与二级索引（Secondary Index）、回表（Back to Table）、覆盖索引（Covering Index）、联合索引与最左前缀（Leftmost Prefix） | 建千万级测试表，逐一验证各现象 | 每个概念配一个 EXPLAIN 证据 |
| D3 | 索引高级特性 | 索引下推（ICP，Index Condition Pushdown）、MRR（Multi-Range Read）、索引跳跃扫描（Skip Scan，8.0）、前缀索引、索引失效的 10 种场景 | 故意写 10 条失效 SQL 再修复 | 输出《索引失效排查清单》 |
| D4 | EXPLAIN 执行计划 | type 等级（system>const>eq_ref>ref>range>index>ALL）、key/rows/filtered、Extra（Using index/Using filesort/Using temporary） | 逐字段解读 5 条复杂 SQL 的执行计划 | 能根据计划直接判断优劣 |
| D5 | 查询优化器 | 成本模型（Cost Model）、统计信息（ANALYZE TABLE）、join 算法（NLJ 嵌套循环、BNL、Hash Join 8.0）、驱动表选择 | 实验：改变统计信息观察执行计划变化 | 能讲清优化器为什么会选错索引 |
| D6 | 慢 SQL 治理实战 | slow_query_log 慢查询日志、pt-query-digest（Percona 分析工具）、SQL 改写（深分页优化、join 改写、count 优化） | 治理 5 条慢 SQL（含一条 500 万行深分页），记录优化前后 RT | 5 条全部优化达标并记录 |
| D7 | 周复盘 | 索引体系串讲 | 周记 + 《SQL 优化检查清单 v1》 | 脱稿讲清"回表与覆盖索引"完整链路 |

### 第 4 周：事务、锁、日志与高可用地基

| 日 | 主题 | 知识点（中英对照） | 实战任务 | 验收标准 |
|----|------|-------------------|---------|---------|
| D1 | 事务与隔离级别 | ACID（原子性/一致性/隔离性/持久性）、读现象（脏读/不可重复读/幻读 Phantom Read）、四种隔离级别与默认 RR（Repeatable Read，可重复读） | 两个会话手动复现三种读现象 | 能讲清 RR 下如何"基本解决"幻读 |
| D2 | MVCC 深度 | 隐藏列（trx_id/roll_pointer）、undo log 版本链（Version Chain）、ReadView（可见性判断：m_ids/min_trx_id/max_trx_id/creator_trx_id）、RC 与 RR 生成 ReadView 时机差异 | 画出版本链 + ReadView 判断流程图 | 能推演任意事务的可见性行 |
| D3 | 锁体系 | 全局锁、表锁、元数据锁（MDL）、意向锁（Intent Lock）、行锁三兄弟：记录锁（Record Lock）、间隙锁（Gap Lock）、临键锁（Next-Key Lock）；加锁规则分析 | 用 `performance_schema.data_locks` 观察各种 SQL 的加锁 | 能说出 10 条典型 SQL 各加了什么锁 |
| D4 | 三大日志 | redo log（重做日志，crash-safe 崩溃安全）、undo log（回滚日志）、binlog（归档日志）；两阶段提交（2PC in MySQL：prepare redo → write binlog → commit）；组提交（Group Commit） | 画出一条 UPDATE 的完整执行日志时序 | 博客素材：《一条 UPDATE 的完整旅程》 |
| D5 | 主从复制与高可用 | 复制原理（主库 dump 线程/从库 IO+SQL 线程）、异步/半同步（Semi-Sync）/组复制 MGR（Majority，多数派）、主从延迟原因与治理 | Docker 搭一主一从并制造延迟观察 | 能讲清延迟三大原因与五种治理手段 |
| D6 | MySQL 参数与容量 | buffer pool（缓冲池）、脏页刷新（Dirty Page Flush）、doublewrite、连接数/超时参数；容量评估与连接池（HikariCP）配置 | 输出一份生产 MySQL 参数模板 | 每个参数知道调大调小的代价 |
| D7 | 月度总复盘 | 网络+IO+MySQL 串讲 | 博客：《一条 UPDATE 语句在 MySQL 里的完整旅程》发布 + 《SQL 优化检查清单 v2》 | 达成本月全部验收标准 |

---

## 本月产出清单

1. 博客 2 篇（手写 RPC + UPDATE 之旅）
2. 可运行的迷你 RPC 框架（Netty + 注册中心 + 负载均衡）
3. 《索引失效排查清单》《SQL 优化检查清单》《MySQL 生产参数模板》
4. 5 条慢 SQL 治理记录（前后 RT 对比）

## 本月术语表

| 英文 | 中文 |
|------|------|
| epoll | Linux 多路复用机制（事件驱动） |
| Zero-Copy | 零拷贝（减少用户态/内核态数据复制） |
| Sticky Packet / Half Packet | 粘包 / 半包 |
| RTL ... | （略）TLS Record Layer |
| Back to Table | 回表 |
| Covering Index | 覆盖索引 |
| Phantom Read | 幻读 |
| Crash-Safe | 崩溃安全（宕机后数据可恢复到一致状态） |
| Semi-Sync Replication | 半同步复制 |

## 阶段一毕业检查（对照 README 里程碑 M1）

- [ ] 30 分钟内用 Arthas + MAT 完成模拟的 CPU 100% 与内存泄漏排查
- [ ] 手写 AQS/线程池/限流器代码可现场白板复现
- [ ] B+ 树、MVCC、TCP 握手三大图能脱稿画对

> 下一阶段：`../phase2-分布式中间件/month04-Redis与MySQL高阶.md`。
