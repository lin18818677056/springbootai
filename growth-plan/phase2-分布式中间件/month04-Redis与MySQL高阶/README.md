# Month 04 · Redis 深度 与 MySQL 高阶（阶段二开篇）

> **本月一句话目标**：把 Redis 用到"原理级理解 + 生产级设计"（数据结构底层、持久化、哨兵/集群、缓存一致性、分布式锁），完成 MySQL 分库分表实战，并启动实战项目一「高并发秒杀系统」（对接 `practice-projects/01-seckill-system`）。

## 环境准备（开工前 30 分钟一次性搞定）

```powershell
# ① 本月学习仓库（延续 month03 风格：纯 javac 可复现）
mkdir D:\mywork\springbootai\learning\month04-redis-mysql
cd D:\mywork\springbootai\learning\month04-redis-mysql
mkdir lib, out
git init

# ② Redis 7 容器（month03 day30 预习任务，没做就现在做）
docker run -d --name redis-learning -p 6379:6379 redis:7
docker exec -it redis-learning redis-cli ping        # 期望 PONG

# ③ MySQL 容器复用 month03 的 mysql-learning（在就跳过）
docker start mysql-learning

# ④ MySQL JDBC 驱动单 jar（day18 手写分片路由用，免构建工具）
Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/9.1.0/mysql-connector-j-9.1.0.jar" -OutFile "lib\mysql-connector-j.jar"
# 以后 Java+MySQL 编译运行模板：
#   javac -cp "lib/mysql-connector-j.jar" -d out X.java
#   java  -cp "out;lib/mysql-connector-j.jar" X

# ⑤ Redis 客户端不引第三方：day06 手写 RESP 协议客户端（纯 JDK Socket），延续 month03 手写风
```

> **工程体系说明**：day01-22 全部用 learning 仓库（纯 javac）；day23-27 秒杀项目周切换到 `practice-projects/01-seckill-system`（Gradle + Spring Boot 体系，有独立的技术栈与骨架，直接按 step 文档执行）——两套体系并存正是真实工作常态。

## 30 天课程地图

| 周 | 天 | 文件 | 主题与核心产出 |
|----|----|------|----------------|
| W1 原理 | 01 | day01-Redis架构与为什么快.md | 单线程模型/6.0 多线程 IO/Reactor 对号 |
| | 02 | day02-SDS与字典渐进式rehash.md | SDS 三元组/两张哈希表/渐进迁移 |
| | 03 | day03-跳表SkipList.md | 手写简化跳表 + 与 ZSet 对拍 |
| | 04 | day04-对象系统与编码.md | OBJECT ENCODING 逐一验证/阈值表 |
| | 05 | day05-内存与淘汰策略.md | 八种策略/LRU vs LFU 实验/碎片整理 |
| | 06 | day06-事务与Lua.md | MULTI vs Lua/手写 RESP 客户端/原子扣减 |
| | 07 | day07-第一周复盘输出.md | 《Redis 类型-编码-场景速查表》 |
| W2 高可用 | 08 | day08-RDB与AOF持久化.md | fork COW/everysec/混合持久化/kill -9 恢复 |
| | 09 | day09-Redis主从复制.md | 一主二从/replid+backlog/部分重同步 |
| | 10 | day10-哨兵Sentinel.md | 3 哨兵/kill 主库/故障转移时间线 |
| | 11 | day11-集群Cluster.md | 3 主 3 从/16384 槽/MOVED 重定向 |
| | 12 | day12-缓存三大问题.md | 穿透/击穿/雪崩 全治理手段 |
| | 13 | day13-缓存一致性.md | Cache Aside/延迟双删/Canal 时序 |
| | 14 | day14-分布式锁与第二周复盘.md | SETNX→Lua→看门狗/RedLock 争议+博客 |
| W3 分库分表 | 15 | day15-何时分库分表.md | 垂直/水平/拆分问题清单 |
| | 16 | day16-分片策略与基因法.md | hash/range/基因法/非分片键查询 |
| | 17 | day17-分布式ID雪花算法.md | 手写雪花/时钟回拨三方案/号段模式 |
| | 18 | day18-手写分片路由实战.md | 2 库×4 表手写路由（JDBC 单 jar） |
| | 19 | day19-跨分片查询与深分页.md | 二次查询法/游标/异构索引 |
| | 20 | day20-数据迁移与双写.md | 全量+增量+灰度+对账/迁移方案文档 |
| | 21 | day21-第三周复盘与决策树.md | 《分库分表决策树》 |
| W4 项目周 | 22 | day22-读写分离与MySQL高可用.md | MHA/MGR/Orchestrator 对比/路由方案 |
| | 23 | day23-秒杀01需求与架构.md | 容量估算/流量漏斗（对接 step01） |
| | 24 | day24-秒杀02环境与骨架.md | docker compose/建表/Gradle 骨架 |
| | 25 | day25-秒杀03防超卖核心.md | 三方案对比/Lua 原子扣减（step03） |
| | 26 | day26-秒杀04缓存与限流.md | 多级缓存/Sentinel 限流（step02/03） |
| | 27 | day27-秒杀05削峰与压测.md | MQ 削峰/压测复盘（step04/05） |
| 收官 | 28 | day28-全月大串讲.md | Redis+MySQL 知识网/跨月连线 |
| | 29 | day29-M4模拟验收.md | 面试 30 问/白板手写 |
| | 30 | day30-月度复盘与M4验收.md | 验收打分/项目周报/M5 预告 |

## M4 验收标准（day30 逐条打分）

1. 讲清 Redis 核心数据结构底层（SDS/跳表/ListPack/QuickList）与对象编码转换阈值
2. 对比 RDB/AOF；讲清主从复制、哨兵、集群三种高可用方案的取舍
3. 设计缓存一致性方案；穿透/击穿/雪崩"事前/事中/事后"手段全覆盖
4. 手写分布式锁演进版（SETNX→Lua→看门狗），能讲 RedLock 争议双方观点
5. 手写分片路由 Demo（2 库×4 表）+ 输出《分库分表迁移方案》
6. 秒杀项目：架构文档 + 可运行骨架 + 防超卖压测 0 超卖

## 前后衔接

```
← month03 给你的底子：
   Reactor 三形态(day10) → day01 Redis 线程模型对号
   长度头协议(day12)     → day06 RESP 协议手写（文本版协议设计）
   MySQL WAL/2PC(day25)  → day08 Redis 持久化对照
   主从复制(day26)       → day09 Redis 复制对照（思想同源）
→ month05 要去的地方：消息队列与分布式理论（秒杀削峰的 MQ 深入）
```

## 本月产出清单（作品集）

1. 《Redis 类型-编码-场景速查表》《分库分表决策树》《迁移方案》三份文档
2. 手写代码：简化跳表、RESP 客户端、演进版分布式锁、雪花算法、分片路由
3. 秒杀项目：step01-05 跑通 + 周报
4. 1 篇博客：《Redis 分布式锁的三代演进与 RedLock 之争》

---
[← Month03 · 网络IO与MySQL](../../phase1-基础强化/month03-网络IO与MySQL/README.md) | [Day 01 · Redis 架构与为什么快 →](day01-Redis架构与为什么快.md)
