# Day 25 · EFK 日志体系：结构化日志与 TraceId 召回

> **今日目标**：搭 EFK 栈（ES+Filebeat+Kibana）；日志 JSON 结构化改造（traceId 贯穿）；实现"按 TraceId 秒级召回全链路日志"；立日志规范。
> **时长**：EFK 部署 2.5h / 结构化改造 1.5h / 规范 1h
> **今日产出**：EFK 环境 + JSON 日志模板 + TraceId 召回演示 + 日志规范卡

## 1. 知识地图

```
日志体系的三层（采集→存储→检索）：
  应用：Logback 输出 JSON 到文件（结构化！文本日志没法按字段检索）
  采集：Filebeat 轻量 agent 监听日志文件 → 输出 ES（量大可过 Kafka 削峰——M5 知识复用）
  存储：Elasticsearch 倒排索引——按任意字段全文检索
  检索：Kibana——查询/看板/告警入口

为什么必须结构化（JSON）日志：
  文本日志：grep 关键词——"order 1001 支付失败" grep "1001" 误命中一堆
  JSON 日志：{"ts":"...","level":"WARN","traceId":"a1b2","orderNo":"1001","msg":"支付失败"}
    → Kibana 按 traceId/orderNo/level 精确过滤——从"搜字符串"升级为"查数据库"

TraceId 贯穿（day22 实验的工程化落地）：
  logback pattern 加 %X{traceId} → JSON 输出用 logstash-logback-encoder
  Feign 拦截器透传 traceId（day06 的 RequestInterceptor 已经在做 X-Gray，同款机制）
  MQ 场景：消息 header 带 traceId（RocketMQ 的 message.putUserProperty）
  ——日志规范第一条：没有 traceId 的日志在微服务里等于没打（day22 金句）

日志规范卡（写进团队 README 的那种）：
  级别：ERROR=影响功能需处理 / WARN=可自愈需观察（限流触发/重试）/
       INFO=关键业务事件（下单/支付）/ DEBUG=细节（生产不输出）
  内容：谁（userId/单号）+ 做了什么（动作）+ 结果（成功/失败+原因）+ 耗时
  红线：不打敏感信息（密码/手机号脱敏）/ 不打大对象 / 循环里不打 INFO
  成本：ES 按 ILM 滚动（热 7 天→温 30 天→删）——日志量=磁盘成本，采样分级（day22）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| EFK | Elasticsearch + Filebeat + Kibana |
| Structured Logging | 结构化日志（JSON 输出） |
| Inverted Index | 倒排索引（ES 检索原理） |
| Index / ILM | 索引/索引生命周期管理 |
| MDC (Mapped Diagnostic Context) | 日志诊断上下文（traceId 载体） |
| Logstash Encoder | logstash-logback-encoder（JSON 输出） |
| Shipper | 日志采集器（Filebeat/Fluentd） |
| Log Sampling | 日志采样 |

## 3. 动手实操：EFK 搭建与召回

```xml
<!-- ① logback-spring.xml：JSON 结构化输出（logstash-logback-encoder） -->
<configuration>
    <appender name="JSON" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/mall-order.log</file>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"app":"mall-order"}</customFields>
        </encoder>
        <!-- JSON 输出自动带 MDC：traceId 由 day22 的 filter 塞进 MDC -->
    </appender>
</configuration>
```

```yaml
# ② filebeat.yml：
# filebeat.inputs:
#   - type: filestream
#     paths: ["/logs/*.log"]         # 挂载各服务日志目录
#     json.keys_under_root: true     # JSON 字段提升为文档字段（能按字段查！）
# output.elasticsearch:
#   hosts: ["elasticsearch:9200"]
#   index: "mall-logs-%{+yyyy.MM.dd}"    # 按天索引
```

```powershell
# ③ docker-compose（learning/month06-ms-stability/efk/）：
#   elasticsearch:8.11.0（单节点，xpack security 关闭——学习环境）
#   kibana:8.11.0（端口 5601）  filebeat:8.11.0（挂载 /logs）
docker compose up -d
Start-Process "http://localhost:5601"    # Kibana → Stack Management → 创建 data view: mall-logs-*

# ④ TraceId 召回实验（day22 实验的完整版）：
#  下单一次 → 从响应头/网关日志拿 traceId（例 a1b2c3d4）
#  Kibana 查询：traceId: "a1b2c3d4" → 三个服务的日志按时间线整齐排列
#  对比感受：M5 时代 grep 三台机器 vs 现在一行查询——"提速 10 倍"的具象化
# ⑤ 字段分析实验：按 level:ERROR 过滤 / 按 orderNo 精确查单 / 按 app+level 聚合看错误分布
# ⑥ 压测下的日志洪峰：打开 debug 级别压测 → 观察 ES 写入压力（理解为什么要采样与分级）
```

## 4. 面试连接

**Q：你们的日志体系怎么搭的？怎么按 TraceId 查问题？**
> 三层：应用层 Logback+logstash-logback-encoder 输出 JSON（自动带 MDC 里的 traceId 与服务名）；采集层 Filebeat 监听日志文件（json.keys_under_root 提升字段，量大场景中间加 Kafka 削峰）；存储层 ES 按天索引+ILM 滚动（热 7 温 30 后删）。排障流程：网关日志拿 traceId → Kibana 一行查询 `traceId:"xxx"` → 全链路日志按时间线召回，配合 day26 的 SkyWalking 定位"哪条边慢"，再用 traceId 精确到日志"为什么慢"。收尾："M5 我们排查一个跨服务问题要登三台机器 grep 半小时，现在 30 秒定位——结构化+traceId 是日志体系的两块基石，缺一不可。"

**Q：日志规范你会怎么定？线上日志太多/太少怎么办？**
> 规范四条：级别语义（ERROR 需处理/WARN 可自愈需观察/INFO 关键业务事件/DEBUG 细节）、内容四要素（谁+动作+结果+耗时）、红线（敏感信息脱敏/不打大对象/循环不打 INFO）、结构化必带 traceId。太多：DEBUG 生产关闭、高频路径采样（每 N 条打 1 条）、ES ILM 滚动控成本；太少：规范里定义"必须 INFO 的业务事件清单"（下单/支付/退款），错误必带上下文（不能光打 e.getMessage，要打参数与 traceId）。追问"日志丢了怎么办"：Filebeat 至少一次+重试，极端丢弃计数打指标——"日志是可容忍丢失的可观测信号，但要监控'丢了多少'，这与 MQ 可靠性权衡是同构思想。"（跨支柱+跨月连线）

## 5. 今日验收清单

- [ ] EFK 三件套部署完成，data view 就绪
- [ ] JSON 结构化日志输出（字段可过滤）
- [ ] TraceId 一行查询召回全链路（截图）
- [ ] 日志规范卡写进工程 README
- [ ] ILM/采样成本意识笔记
- [ ] `git add . && git commit -m "day25: efk & structured logging"`

---
[← Day 24](day24-告警体系.md) | [本月目录](README.md) | [Day 26 · SkyWalking链路追踪 →](day26-SkyWalking链路追踪.md)
