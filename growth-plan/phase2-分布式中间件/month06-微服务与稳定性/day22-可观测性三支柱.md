# Day 22 · 可观测性三支柱：Metrics、Logging、Tracing

> **今日目标**：建立可观测性（Observability）体系认知——三支柱各自解决什么、怎么组合排障；理解 OpenTelemetry 统一标准；画出商城的三支柱数据流架构图。
> **时长**：理论 2h / 数据流设计 1.5h / OTel 体验 1.5h
> **今日产出**：三支柱数据流架构图 + 排障场景-支柱映射表 + OTel 认知笔记

## 1. 知识地图

```
监控 vs 可观测（概念升级，面试开场题）：
  监控（Monitoring）：看"已知的问题"——CPU 高了报警（预设的阈值/预设的故障模式）
  可观测（Observability）：回答"未知的问题"——为什么今天 RT 慢了 30ms？
    （通过系统对外输出的信号推断内部状态——Cloud Native 的核心思想）
  ——监控是"体检套餐"，可观测是"能问任何问题的诊断能力"

三支柱（Three Pillars）与分工：
  ① Metrics 指标：数值时序（QPS/RT/错误率/连接数）——便宜、可聚合、适合告警
     工具：Micrometer → Prometheus（拉取/存储）→ Grafana（可视化）
     特点：有维度无细节——"P99=800ms"但不知道是哪个请求慢
  ② Logging 日志：离散事件文本——最详细、最贵、适合"查案"
     工具：Logback 结构化输出 → Filebeat 采集 → ES 存储 → Kibana 查询（EFK，day25）
     特点：有细节无全局——单个请求的日志有了，跨服务的调用链要自己拼
  ③ Tracing 链路追踪：请求在分布式系统的完整路径（Span 树）——连接前两者
     工具：SkyWalking（字节码增强零侵入，day26）/ OTel SDK
     特点：有全局无细节——知道 order→product 哪步慢，具体慢在哪要看日志

三支柱协同排障（今天的核心输出——映射表）：
  症状（Metrics）："P99 从 80ms 涨到 800ms"（Grafana 告警知道"有事"）
    → 定位（Tracing）：SkyWalking 看 trace：慢在 order→product 这条边（知道"哪里"）
      → 深挖（Logging）：拿 traceId 去 Kibana 查该请求日志：product 查 DB 慢 700ms（知道"为什么"）
  ——TraceId 是三支柱的"串联钥匙"：日志里必须打 traceId（MDC），span 上必须挂异常

OpenTelemetry（OTel）——可观测的统一标准：
  背景：三大件各有 N 个厂商协议 → 接入成本高 → CNCF 出 OTel 统一 SDK/协议/API
  三个信号统一：Metrics/Logs/Traces 一套 SDK 采集（OTLP 协议）→ 后端可换（供应商中立）
  现状：Tracing 最成熟（SkyWalking/Jeager 都支持 OTLP）；Java 用 javaagent 零代码接入
  ——认知升级：学的是"数据从埋点到存储到展示的流水线"，工具都是流水线上的站点
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Observability | 可观测性 |
| Metrics / Logging / Tracing | 指标/日志/追踪（三支柱） |
| Golden Signals | 黄金信号（延迟/流量/错误/饱和度，Google SRE） |
| Trace / Span | 追踪链/跨度（一次调用的一段） |
| TraceId / MDC | 追踪 ID / 日志上下文（串联三支柱的钥匙） |
| OpenTelemetry (OTel) | 可观测统一标准（SDK+OTLP 协议） |
| Cardinality | 基数（标签组合数，Metrics 成本核心变量） |
| White-box Monitoring | 白盒监控（看内部信号，相对黑盒） |

## 3. 动手实操：数据流设计与 TraceId 贯穿

```
商城可观测数据流架构图（今天画在笔记本上的"总图"）：
  五个服务 ──┬─ Micrometer 暴露 /actuator/prometheus ──> Prometheus 抓取 ──> Grafana 大盘（day23）
             ├─ Logback JSON 日志（含 traceId）──> Filebeat ──> Kafka（削峰，M5 知识复用）──> ES ──> Kibana（day25）
             └─ OTel javaagent 字节码增强 ──> OTLP gRPC ──> SkyWalking OAP ──> SkyWalking UI（day26）
  三条流水线三种采集方式：HTTP 拉取 / 文件采集 / 字节码注入——各自适配数据特性
  成本意识：Metrics 基数控制（不要 userId 当标签！组合爆炸）/
           日志采样与分级（debug 不进 ES）/ Tracing 采样率（1%~10%）
```

```powershell
# TraceId 贯穿实验（今天就能做，不用装 ES）：
# ① mall-order 日志 pattern 加 %X{traceId}（MDC），用 logback-spring.xml 配置
# ② SkyWalking 还没装——先用 filter 手动生成 traceId 演示"串联"概念：
#    OncePerRequestFilter 里 MDC.put("traceId", UUID 短码)，网关生成、header 透传
# ③ 发一次下单请求 → 三服务日志各打出同一 traceId → grep 一次捞全链路日志
#    Select-String -Path .\logs\*.log -Pattern "a1b2c3d4"   # 一条命令捞出整条链！
# ④ 体会："这就是 M5 day26 痛点的答案——排障 30 分钟变 3 分钟的第一步就是 traceId"

# OTel javaagent 体验（为 day26 铺垫，只下载不深究）：
# Invoke-WebRequest https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases -OutFile otel.jar
# java -javaagent:otel.jar -jar mall-order.jar   # 启动即自动埋点（字节码增强的威力）
```

## 4. 面试连接

**Q：什么是可观测性？和监控有什么区别？Metrics/Logs/Traces 怎么选？**
> 监控回答已知的、预设的问题（阈值告警），可观测性回答未知的、开放式的问题（为什么慢、为什么错）——通过系统的三类输出信号推断内部状态。三支柱选型按"问题和成本"：指标便宜可聚合，负责发现"有事"和触发告警；链路负责定位"哪里"——分布式系统跨服务因果关系的唯一解；日志最贵最详细，负责回答"为什么"。三者不是选择关系是协同关系：指标发现→链路定位→日志深挖，TraceId 串联。收尾："我们日志规范里第一条就是'日志必须带 traceId'——没有 traceId 的日志在微服务里基本等于没打。"（用排障场景证明真懂）

**Q：三个黄金信号是什么？为什么不用 CPU 判断服务健康？**
> Google SRE 四黄金信号：延迟（服务处理请求的耗时分布，看 P99 而非均值）、流量（请求量，判断负载是否符合预期）、错误（失败率）、饱和度（资源水位——连接池使用率/队列深度）。CPU 是"资源指标"不是"服务指标"：CPU 100% 但服务无请求堆积可能没问题（算力型），CPU 30% 但线程池打满却已经在拒绝请求——判断健康要看"服务输出"（延迟/错误），资源只辅助定位原因。这四个信号就是 day23 的 RED 大盘的蓝本（Rate/Errors/Duration）。

## 5. 今日验收清单

- [ ] 三支柱数据流总图画出（三条流水线）
- [ ] TraceId 贯穿实验：一条命令捞全链路日志
- [ ] 排障场景-支柱映射表完成（发现/定位/深挖）
- [ ] 监控 vs 可观测、黄金信号能脱口而出
- [ ] 成本意识三条（基数/采样/日志分级）写进笔记
- [ ] `git add . && git commit -m "day22: observability pillars"`

---
[← Day 21](day21-第三周复盘.md) | [本月目录](README.md) | [Day 23 · Prometheus与RED →](day23-Prometheus与RED.md)
