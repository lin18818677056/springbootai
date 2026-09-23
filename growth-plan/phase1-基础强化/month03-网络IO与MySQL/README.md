# Month03 · 网络 IO 与 MySQL（30 天精细化计划）

> **本月一句话目标**：打通"网络协议 → IO 模型 → Netty → 手写 RPC"链路，并把 MySQL 从"会写 SQL"升级到"懂索引结构、事务原理、锁机制、慢 SQL 治理"——这两块是 P7 专科硬功夫，也是后面所有高并发设计的地基。

## 0. 环境准备（开工前 40 分钟）

| 工具 | 用途 | 安装/命令 |
|------|------|----------|
| Wireshark | day01-06 抓包分析 | 官网下载 Windows 版，装完勾选 Npcap |
| MySQL 8.4 | day15-30 全部实验 | Docker 一行命令（见下） |
| Netty 4.1 | day11-14 RPC 实战 | 单 jar 下载（见下） |
| Docker Desktop | 主从复制等 | 已有 |

```powershell
# ① MySQL 单机容器（day15 起全程使用）
docker run -d --name mysql-learning -p 3306:3306 `
  -e MYSQL_ROOT_PASSWORD=root123 -e MYSQL_DATABASE=learn mysql:8.4
docker exec -it mysql-learning mysql -uroot -proot123 -e "SELECT VERSION();"

# ② Netty 单 jar（免 Maven，javac 直接用；下载到学习仓库 lib 下）
mkdir D:\mywork\springbootai\learning\month03-net-mysql\lib -Force
Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/io/netty/netty-all/4.1.115.Final/netty-all-4.1.115.Final.jar" `
  -OutFile D:\mywork\springbootai\learning\month03-net-mysql\lib\netty-all.jar

# ③ 建本月学习仓库
cd D:\mywork\springbootai\learning\month03-net-mysql
git init
# 连接测试（Windows 无 mysql 客户端时用 docker exec 即可）
```

## 1. 每日文件五段模板（与 month01/02 相同）

**① 知识地图（ASCII 图解）→ ② 核心概念（中英对照）→ ③ 动手实操（命令 + 可运行代码/SQL）→ ④ 面试连接 → ⑤ 验收清单**，文末前后导航。周日 day07/14/21 为复盘输出日，day30 月度验收（含阶段一毕业检查）。

## 2. 30 天课程地图

| 周 | 主题 | 天 | 内容 |
|----|------|-----|------|
| **Week1** | TCP/IP 与 HTTP | day01 | 网络分层模型与 Wireshark 抓包入门 |
| | | day02 | TCP 连接管理：三次握手/四次挥手/状态机 |
| | | day03 | TCP 可靠传输：滑动窗口/拥塞控制/粘包 |
| | | day04 | TIME_WAIT 与长连接治理 |
| | | day05 | HTTP 演进：1.1 队头阻塞/2 多路复用/3 QUIC |
| | | day06 | HTTPS 与 TLS 1.3 握手 |
| | | day07 | 第一周复盘输出（两张状态图 + 15 分钟脱稿串讲） |
| **Week2** | IO 模型与 Netty | day08 | IO 五模型：用户态/内核态/阻塞非阻塞 |
| | | day09 | Java NIO：Channel/Buffer/Selector/epoll |
| | | day10 | Reactor 模式三形态（Redis/Netty/Kafka 对号） |
| | | day11 | Netty 核心组件与启动流程 |
| | | day12 | Netty 实战：编解码/心跳/零拷贝 |
| | | day13 | 手写迷你 RPC（上）：协议+代理+传输 |
| | | day14 | 手写迷你 RPC（下）+ 第二周复盘（《Netty 手写 RPC》博客） |
| **Week3** | MySQL 索引与优化 | day15 | InnoDB 页结构与 B+ 树 |
| | | day16 | 聚簇/回表/覆盖/联合最左前缀 |
| | | day17 | 索引高级特性与失效 10 场景 |
| | | day18 | EXPLAIN 执行计划逐字段解读 |
| | | day19 | 查询优化器与 Join 算法 |
| | | day20 | 慢 SQL 治理实战（5 条含深分页） |
| | | day21 | 第三周复盘输出（《SQL 优化检查清单》） |
| **Week4** | 事务/锁/日志/高可用 | day22 | 事务与隔离级别（三种读现象复现） |
| | | day23 | MVCC：版本链与 ReadView |
| | | day24 | 锁体系与加锁规则 |
| | | day25 | 三大日志与两阶段提交 |
| | | day26 | 主从复制与高可用（Docker 一主一从） |
| | | day27 | MySQL 参数与容量评估 |
| | | day28 | 综合演练①：一条 UPDATE 的完整旅程 |
| | | day29 | 综合演练②：慢 SQL 门诊 + 阶段一模拟验收 |
| | | day30 | 月度复盘与 M3 验收 + 阶段一毕业检查 |

## 3. 本月验收标准（M3，day30 逐项核对）

- [ ] 能讲清 TCP 握手挥手每个状态，给出 TIME_WAIT 过多治理方案（day02/04）
- [ ] 能纠正"NIO=非阻塞=异步"混淆，讲清 epoll 红黑树+就绪链表（day08/09）
- [ ] 手写迷你 RPC：双节点负载均衡、10 万条消息无错序无粘包（day13/14）
- [ ] 能画出 B+ 树/聚簇回表/MVCC ReadView 三张图并推演可见性（day15/16/23）
- [ ] 独立治理 5 条慢 SQL（EXPLAIN 逐字段证据 + 前后 RT 对比）（day20）
- [ ] 博客 2 篇：《Netty 手写 RPC》《一条 UPDATE 语句在 MySQL 里的完整旅程》
- [ ] 阶段一毕业检查：30 分钟完成 Arthas+MAT 模拟排查；白板复现手写三件套（day30）

## 4. 与 month01/02 的衔接

- day09 的 NIO 挂起唤醒 → month02 park/unpark 的应用场景
- day13 的 RPC 消费方调用 → month02 CompletableFuture 异步编排的落地
- day27 连接池参数 → month02 day28 手写连接池的工程对照
- day28 的日志时序 → month01 crash-safe 与持久化的呼应

---
[Month02 · 并发编程](../month02-并发编程/README.md) | **本月目录** | [Day 01 · 网络分层与抓包入门 →](day01-网络分层与抓包入门.md)
