# Day 26 · SkyWalking 链路追踪：Span、上下文传播与字节码增强

> **今日目标**：部署 SkyWalking（OAP+UI+javaagent）；理解 Trace/Span 模型与跨进程上下文传播；讲透字节码增强原理；实测"定位跨 3 服务慢调用根因"——兑现 M5 day26 伏笔。
> **时长**：部署接入 2h / 原理 2h / 实战排障 1h
> **今日产出**：SkyWalking 环境 + 慢调用定位演练报告 + 字节码增强原理图

## 1. 知识地图

```
SkyWalking 三件套：
  Agent（javaagent）：字节码增强自动埋点——业务代码零改动！（核心卖点）
  OAP Server：接收 trace 数据（gRPC 11800）→ 分析/聚合/存储（ES/自研 H2）→ 端口 12800
  UI（8080）：拓扑图/trace 查询/指标面板/告警

Trace/Span 模型（标准 OpenTracing 语义）：
  Trace：一次请求的完整生命周期（全局唯一 traceId）
  Span：一次调用/一段工作——含：开始时间/耗时/标签/日志/父子关系
    Span 类型：Entry（服务入口）/ Exit（调出依赖）/ Local（本地方法段）
  Span 树：order(Entry) → [product(Exit) → product-service(Entry) → db(Exit)] → ...
  耗时分析：整体耗时 = 自身耗时（span self time）+ 子 span 耗时——"慢在谁身上一目了然"

跨进程上下文传播（链路不断线的秘密）：
  进程内：ThreadLocal 存 TraceContext（⚠ 异步线程池要传 context——M2 知识：装饰 Runnable）
  跨 HTTP：header 透传（sw8 header）——Agent 自动加，这正是 day22 手动 traceId 的自动化版
  跨 MQ：消息属性带 trace 上下文——消费端延续链路（Agent 对 RocketMQ/Kafka 有插件）
  断线场景：自定义线程池/自研 RPC 框架没有插件 → trace 断开 → 需要手动 context snapshot

字节码增强原理（Agent 的黑魔法，面试硬核题）：
  java -javaagent:skywalking-agent.jar → JVM 启动时执行 premain()
  → ByteBuddy 在类加载时拦截匹配的方法（按插件规则：RestTemplate/Feign/JDBC/Redis...）
  → 动态改字节码：方法前后织入"创建 span/上报"逻辑
  → 业务类被增强但源码不动——AOP 是运行时代理（Bean 层），字节码增强是类加载期改码（任何类）
  对比 Spring AOP：AOP 只能代理 Spring Bean，Agent 能增强 JDBC 驱动/HTTP 客户端等任意类
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Trace / Span | 追踪链/跨度 |
| Entry / Exit / Local Span | 入口/出口/本地 Span |
| Context Propagation | 上下文传播（跨进程/线程） |
| JavaAgent / premain | Java 探针/启动拦截 |
| ByteBuddy | 字节码操作库（Agent 的织入引擎） |
| Bytecode Enhancement | 字节码增强（类加载期织入） |
| Topology | 服务拓扑图（自动发现依赖） |
| Sampling Rate | 采样率（1%~100%，成本开关） |

## 3. 动手实操：部署与慢调用定位

```powershell
# ① docker-compose（learning/month06-ms-stability/skywalking/）：
#   elasticsearch 已有（day25）→ OAP 复用
#   skywalking-oap:9.7.0（SW_STORAGE=elasticsearch，11800/12800）
#   skywalking-ui:9.7.0（8080，OAP 地址指向 oap:12800）
docker compose up -d
Start-Process "http://localhost:8080"

# ② 应用接入（零代码！启动命令加 -javaagent）：
java -javaagent:D:\agent\skywalking-agent\skywalking-agent.jar `
     -Dskywalking.agent.service_name=mall-order `
     -Dskywalking.collector.backend_service=127.0.0.1:11800 `
     -jar mall-order.jar
# 五个服务都加 agent → General Service 拓扑图自动出现完整调用关系（无需任何配置）

# ③ 慢调用定位演练（M5 day26 痛点的终局答案）：
#  制造故障：product 的 DB 查询加 sleep(700) 模拟慢 SQL
#  压测 → UI 的 Trace 查询：按"耗时>1s"过滤 → 打开一条 trace：
#    order(总 850ms) → product(Exit 780ms) → mysql(Exit 760ms)
#    ——三层展开直接看到"慢在 SQL"，连慢 SQL 语句都在 span 标签里！
#  对比 M5 排障：日志 grep 半小时 vs 现在 30 秒——"提速 10 倍"验收达成
# ④ 拓扑图实验：General Service 自动发现 order→product→mysql/redis 全景
# ⑤ 采样实验：agent.sample_n_per_3_sec 调低 → 观察低成本模式（生产常用 1%~10%）
```

## 4. 面试连接

**Q：SkyWalking 的 Agent 为什么业务代码零侵入？原理讲一下。**
> JavaAgent 机制：JVM 启动加载 agent 的 premain，用 ByteBuddy 在类加载期按插件规则（Feign/JDBC/Redis 客户端等）拦截目标类，改写字节码在方法前后织入 span 创建与上报逻辑——业务源码零改动。对比 Spring AOP：AOP 是容器运行时对 Bean 做代理，覆盖不了 JDBC 驱动类和第三方客户端；Agent 是类加载期增强，理论上能改任意类——这就是为什么它能自动追踪 MySQL/Redis 调用。追问"什么场景会断链"：自定义线程池（ThreadLocal 上下文不随线程切换传播，要装饰 Runnable 传递 context snapshot）与没有插件的自研协议——Agent 不是万能的，知道边界才显功力。

**Q：一次慢请求，你怎么用三支柱定位根因？**
> 完整链路演示（今天刚练过的）：Grafana 告警"下单 P99 超 1s"（Metrics 发现）→ SkyWalking 按 RT 过滤 trace，打开慢请求看 span 树：order→product 边占 90% 耗时，展开 product 的 span 直达 SQL 标签——760ms 是一条没走索引的查询（Tracing 定位）→ 复制 traceId 到 Kibana 召回该请求全程日志，确认是哪个参数触发了坏执行计划（Logging 深挖）→ 修索引后 RED 大盘 P99 回落验证。收尾："三支柱是一条流水线的三站：发现→定位→深挖，TraceId 是车票。单独任何一支柱都做不到 30 秒定位——组合起来才是可观测性。"

## 5. 今日验收清单

- [ ] OAP+UI 部署，五服务接入（拓扑图自动生成）
- [ ] Trace/Span 模型能画（Entry/Exit/Local+父子树）
- [ ] 慢调用定位演练：30 秒找到慢 SQL（截图报告）
- [ ] 字节码增强原理图（premain→ByteBuddy→织入）
- [ ] 断链场景清单（线程池/自研协议）+ 手动传递方案
- [ ] 采样率与成本笔记
- [ ] `git add . && git commit -m "day26: skywalking tracing"`

---
[← Day 25](day25-EFK日志体系.md) | [本月目录](README.md) | [Day 27 · 混沌工程体系化 →](day27-混沌工程体系化.md)
