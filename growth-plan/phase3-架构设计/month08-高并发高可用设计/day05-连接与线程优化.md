# Day 05 · 连接与线程优化：超时预算表

> **今日目标**：掌握连接池参数推导与反模式；制定全链路超时预算（逐级递减）；线程数与 IO 模型匹配公式——产出商城《超时与线程预算表》。
> **时长**：原理 1.5h / 预算表制定 2.5h / 配置落地 1h
> **今日产出**：全链路超时与线程预算表 + HikariCP 调优 diff

## 1. 知识地图

```
连接池（HikariCP——Spring Boot 默认）的参数逻辑：
  池大小经验公式（HikariCP 官方）：connections = core_count × 2 + effective_spindle_count
  4C 容器无盘阵列 ≈ 4×2 = 8~10 个连接——不是越大越好！
  反直觉真相：连接越多上下文切换越凶，DB 侧每个连接=一个线程，256 连接能把 DB CPU 打满在调度上
  maximumPoolSize=10 / minimumIdle=5（预热保留）/ connectionTimeout=3s（拿连接等 3s 报错，别无限等）
  maxLifetime=30min（小于 DB/中间件的 wait_timeout，防"半死连接"）
  验证法：压测时盯 pool 指标（active/idle/wait）——wait>0 说明池小，active 长期<<max 说明池虚大

超时预算逐级递减（M6 day13 的深化——今天的完整表）：
  原则：下游超时 > 上游超时（上游必须先放弃，防"上游已走、下游还在干活"的资源泄漏）
  浏览器(5s) → Gateway(3s) → 订单服务(2s) → 库存服务(1.5s) → MySQL(1s queryTimeout)
                                    → Redis(100ms，读写分离配置)
  每级预算 = 自身处理时间 + 下游总和 + 余量——预算表要"对得上账"
  配套：Feign connect 1s/read 3s（M6 day06 已配）→ 本月校准为预算表数值

线程数与 IO 模型匹配（线程池大小的两种算法）：
  CPU 密集型：N_threads = core_count + 1（计算为主，切多了白切）
  IO 密集型：N_threads = core_count × (1 + wait/compute)（等待占比高，多备线程堵等待）
  Web 容器（Tomcat）默认 200 线程：8C + wait/compute≈10（RT 50ms 里 45ms 等 IO）→ 理论够用到 4 万 QPS×50ms
  舱壁隔离（M6 day13 复用）：核心/非核心线程池分开，积分发放的慢不能拖死下单
  ——day06 的虚拟线程是这个话题的"新解"：等待不再占平台线程
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Connection Pool | 连接池（复用 TCP+认证开销） |
| maximumPoolSize | 最大连接数 |
| Timeout Budget | 超时预算（逐级递减） |
| Bulkhead | 舱壁隔离（线程池/连接池分仓） |
| Wait/Compute Ratio | 等待/计算比（线程数公式核心） |
| Pool Starvation | 连接池饥饿 |

## 3. 动手实操：预算表与连接池调优

```markdown
<!-- 商城《超时与线程预算表》（docs/capacity/timeout-budget.md） -->
| 跳 | 组件 | 超时 | 线程/连接 | 依据 |
|----|------|------|----------|------|
| 1 | 浏览器→Gateway | 5s | - | 用户放弃阈值 |
| 2 | Gateway 路由 | 3s | Netty eventloop(默认) | 需覆盖订单 2s+余量 |
| 3 | 订单服务接口 | 2s | Tomcat 200 | 下单=聚合调库存+营销 |
| 4 | Feign→库存 | 1.5s(读3s改) | 舱壁池 50 | 库存扣减 P99 实测 120ms×10 倍余量 |
| 5 | MySQL 连接池 | queryTimeout 1s | Hikari 10 | 4C×2 公式+压测校准 |
| 6 | Redis 读写 | 100ms/200ms | Lettuce 共享连接 | P99<5ms，10 倍余量够 |
| 7 | MQ 发送 | 300ms | - | ack=sync 深度权衡（M5） |
对账检查：订单 2s ≥ 库存1.5s? ❌ → 修正：库存 1.2s（上游必须大于下游总和）
```

```java
// HikariCP 调优 diff（application.yml——从"默认值"到"有依据的值"）
spring:
  datasource:
    hikari:
      maximum-pool-size: 10        # 4C×2=8 → 10（压测校准：active 峰值 7+缓冲）
      minimum-idle: 5              # 预热保留，防冷启动建连抖动
      connection-timeout: 3000     # 拿连接 3s 拿不到就失败（快速失败优于排队雪崩）
      max-lifetime: 1740000        # 29min < MySQL wait_timeout(默认 8h 但中间件常 30min)
      keepalive-time: 120000       # 每 2min 心跳探活
# 压测验证脚本要点（day23 阶梯加压时复核）：
# 观察指标：hikaricp_connections_active / pending / timeout_total
# 判读：pending 持续>0 且 timeout 上涨 → 加池或降 RT；active 长期 <50% max → 减池省资源
```

```powershell
# 超时链路验证（M6 的 Postman 集合 + 人为制造下游慢）：
# ① 库存服务注入 3s 延迟（Sentinel/调试端点）→ 订单侧应在 1.5s 熔断返回（预算表生效）
# ② Redis 停 10s → 读链路 100ms 超时走降级（不拖垮线程池——舱壁验证）
# ③ 记录每级实际 RT P99 → 回填预算表"实测"列
cd D:\mywork\springbootai\practice-projects\02-microservice-mall\mall ; .\gradlew.bat :mall-order:build
git add . ; git commit -m "day08-05: timeout budget"
```

## 4. 面试连接

**Q：数据库连接池怎么配置？为什么不是越大越好？**
> 两条依据：池大小公式 connections ≈ core_count×2+磁盘数，我们 4C 容器给 10 个连接；超时三件套——拿连接 3 秒快速失败、maxLifetime 29 分钟（必须小于中间件的 wait_timeout，否则用到半死连接报 communications link failure）、keepalive 心跳探活。为什么不是越大越好：DB 侧每个连接是一个线程，256 个连接的上下文切换能把 DB CPU 消耗在调度上，吞吐反而下降——HikariCP 官方实测 4C 机器 10 个连接往往跑出最高 TPS。调参不是拍脑袋：压测时盯 active/pending/timeout 三个指标，pending 持续大于零说明池小或 RT 该降，active 长期不到 max 一半说明池虚大在浪费 DB 资源。

**Q：全链路超时怎么设计？**
> 一条原则：逐级递减，上游超时必须大于下游超时总和，否则上游已超时返回、下游还在干活，资源白烧还可能造成数据不一致（上游以为失败走了补偿，下游其实成功）。我们商城的预算表：浏览器 5s→网关 3s→订单服务 2s→库存 Feign 1.2s→MySQL queryTimeout 1s，Redis 单独 100ms 级。做表时发现过一处矛盾：库存配 1.5s 但订单只有 2s，订单自身逻辑只剩 0.5s——修正库存为 1.2s，这就是"对账"的价值。配套动作：每级超时都要有降级或重试决策（写操作禁重试是 M6 的铁律），预算表进 code review，变更超时必须改表。

**Q：线程池大小怎么算？什么是舱壁隔离？**
> 两种算法按任务类型：CPU 密集型 core+1（上下文切换是纯浪费）；IO 密集型 core×(1+wait/compute)——等 IO 占比越高可以配越多线程。Web 容器 Tomcat 默认 200，按我们 8C、RT 50ms 中 45ms 等待估算，理论支撑约 4 万 QPS，配合压测校准。但"一个池走天下"是反模式：积分发放这个非核心慢调用一旦抖动，会把 200 线程全占满，下单核心接口跟着饿死——这就是舱壁隔离：核心（下单/支付）与非核心（积分/通知/日志）物理分池，各自限额，慢的一方只能饿死自己拖不垮别人。M6 day13 的韧性四板斧里它是第三斧，今天的预算表把每个池的大小和超时都落了数字。

## 5. 今日验收清单

- [ ] 《超时与线程预算表》七跳齐全（含对账修正记录）
- [ ] HikariCP 调优 diff（每参数有依据注释）
- [ ] 下游慢注入实验（1.5s 熔断生效截图）
- [ ] 线程数两种算法+舱壁隔离能讲
- [ ] `git add . && git commit -m "day08-05: conn & thread budget"`

---
[← Day 04](day04-水平扩展.md) | [本月目录](README.md) | [Day 06 · 批量与异步IO →](day06-批量与异步IO.md)
