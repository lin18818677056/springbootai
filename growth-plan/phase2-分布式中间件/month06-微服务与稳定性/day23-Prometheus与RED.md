# Day 23 · Prometheus 与 RED：指标体系与 Grafana 大盘

> **今日目标**：部署 Prometheus+Grafana；mall 服务接入 Micrometer；理解四种指标类型与 PromQL 基础；搭建商城 RED 大盘并自定义业务指标。
> **时长**：部署接入 2h / PromQL 与指标模型 1.5h / 大盘搭建 1.5h
> **今日产出**：RED 大盘（截图）+ 自定义业务指标 + PromQL 速查卡

## 1. 知识地图

```
Prometheus 核心模型（时序数据库 + 拉模型）：
  时间序列 = 指标名 + 一组标签（label）→ {__name__="http_requests_total",
    job="mall-order", method="POST", uri="/order/create", status="200"} 数值流
  拉模型（Pull）：Prometheus 定时抓 /actuator/prometheus 文本端点
    ——服务只管暴露，抓取节奏/目标发现（Nacos SD/静态配置）归 Prometheus
    对比推模型（StatsD/日志采集）：拉模型天然容错——抓失败了下轮再抓，服务无感知

四种指标类型（面试必考，选错类型=大盘做不出来）：
  Counter 计数器：只增不减（请求数/错误数/消息数）——速率用 rate() 算
  Gauge 仪表盘：可增可减的瞬时值（线程数/连接数/队列深度/内存）
  Histogram 直方图：分桶统计（RT 分布）——算 P95/P99 用它（桶边界预定义）
  Summary 摘要：客户端直接算分位数——多实例聚合不了（不建议，用 Histogram）

RED 方法（微服务接口监控标准三件套）：
  Rate：每秒请求数    sum(rate(http_server_requests_seconds_count[1m])) by (uri)
  Errors：错误率      sum(rate(...{status=~"5.."}[1m])) / sum(rate(...[1m]))
  Duration：耗时分布  histogram_quantile(0.99, sum(rate(..._seconds_bucket[1m])) by (le, uri))
  ——只监控"服务输出"，不看 CPU：黄金信号思想的落地（day22 埋的线）
  USE 方法（资源监控的对照）：Utilization/Saturation/Errors——给 DB/Redis/MQ 用

自定义业务指标（从"技术健康"到"业务健康"）：
  Counter：mall_order_created_total（下单量）/ mall_pay_success_total / mall_pay_fail_total
  Gauge：mall_order_pending_count（待支付订单堆积——积压预警）
  Histogram：mall_order_create_cost（下单耗时分布）
  ⚠ 标签基数纪律（day22 埋的线）：标签值是有限枚举（uri/status/method），
    绝不能用 userId/orderId 当标签——每个新值一条时间序列，百万用户=百万序列=爆炸
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Time Series / Label | 时间序列/标签 |
| Pull Model | 拉模型（Prometheus 主动抓取） |
| Counter / Gauge | 计数器/仪表盘 |
| Histogram / Summary | 直方图/摘要 |
| rate() / increase() | 速率/增量（Counter 的算术） |
| histogram_quantile() | 分位数计算（P95/P99） |
| RED / USE | 接口监控法/资源监控法 |
| Cardinality | 标签基数（爆炸风险） |
| Service Discovery (SD) | 服务发现（Nacos/静态配置抓取目标） |

## 3. 动手实操：监控栈搭建与 RED 大盘

```yaml
# docker-compose（learning/month06-ms-stability/monitor/）：
#   prometheus: prom/prometheus:v2.51.0  端口 9090
#   grafana: grafana/grafana:10.4.0      端口 3000
# prometheus.yml 核心：
#   global: { scrape_interval: 15s }
#   scrape_configs:
#     - job_name: 'mall-services'
#       metrics_path: '/actuator/prometheus'
#       static_configs:                    # 学习环境静态配（生产用 Nacos SD/Consul SD）
#         - targets: ['host.docker.internal:8081','host.docker.internal:8082']
#           labels: { app: 'mall-order' }
```

```java
// mall-order：自定义业务指标（业务健康三件套）
@Component
public class OrderMetrics {
    private final Counter orderCreated;
    private final Counter paySuccess;
    private final Counter payFail;
    public OrderMetrics(MeterRegistry registry) {
        orderCreated = Counter.builder("mall_order_created_total")
                .description("下单量").register(registry);
        paySuccess = Counter.builder("mall_pay_result_total").tag("result", "success").register(registry);
        payFail    = Counter.builder("mall_pay_result_total").tag("result", "fail").register(registry);
        // Histogram：Gauge 不行！耗时分布要用 Timer（自动生成 histogram 桶）
        Timer.builder("mall_order_create_cost").publishPercentileHistogram().register(registry);
    }
    public void onOrderCreated() { orderCreated.increment(); }
    // service 里埋点：下单成功调 onOrderCreated()，支付回调里 paySuccess/payFail
}
```

```powershell
# ① build.gradle 加 spring-boot-starter-actuator + micrometer-registry-prometheus
#    application.yml: management.endpoints.web.exposure.include=prometheus,health
# ② docker compose up -d → Grafana 加数据源（Prometheus: http://prometheus:9090）
# ③ 导入大盘：Grafana 官方模板 4701（JVM）/ 11378（Spring Boot），再自建 RED 盘：
#    Rate:    sum(rate(http_server_requests_seconds_count[1m])) by (application)
#    Errors:  sum(rate(http_server_requests_seconds_count{status=~"5.."}[1m]))
#             / sum(rate(http_server_requests_seconds_count[1m]))
#    P99:     histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket[1m])) by (le, uri))
# ④ 压测验证：压 /order/create → 大盘 QPS 曲线动起来 + P99 变化
# ⑤ 业务指标验证：下 10 单 → mall_order_created_total=10；
#    故意让支付失败 3 次 → 支付成功率指标 70%（大盘加单值面板）
```

## 4. 面试连接

**Q：Prometheus 为什么用拉模型？四种指标类型怎么选？**
> 拉模型把"采集节奏"与"服务解耦"：服务只暴露文本端点，Prometheus 控制抓取频率与目标发现，抓取失败是监控端的事不影响业务——故障域隔离；还能从 /targets 一眼看采集健康度。四类型：只增不减用 Counter（速率交给 rate() 计算）；瞬时值用 Gauge（连接数/队列深度）；耗时分布用 Histogram（服务端分桶，支持多实例聚合算 P99）；Summary 客户端算分位数导致多实例没法聚合，基本不用。追问"标签基数爆炸"：标签值必须是有限枚举，把 userId 放标签会让时间序列数=用户数，Prometheus 直接 OOM——高基数维度去日志/链路里查，不进指标。（这是生产踩过坑才会讲的知识）

**Q：你们服务的监控大盘有什么？怎么判断服务健康？**
> 两层：技术层 RED——每接口的 QPS/错误率/P99，加 JVM 面板（GC 暂停/堆内存/线程数）与中间件面板（连接池使用率/Redis 命中率）；业务层自定义指标——下单量、支付成功率、待支付订单堆积量（业务指标异动比技术指标更早暴露问题：下单量掉 50% 未必服务坏了，但一定是事故级信号）。判断健康看 RED 不看 CPU：错误率突增或 P99 突增即异常，CPU 只是定位时参考。收尾："大盘是给'值班的人'看的——每个面板都能回答一个问题，回答不了问题的面板就是装饰品。"（监控哲学=信息密度）

## 5. 今日验收清单

- [ ] Prometheus+Grafana 部署，抓取 mall 服务成功
- [ ] RED 三面板搭建完成（截图）
- [ ] JVM 大盘（4701 模板）导入可用
- [ ] 自定义业务指标 3 个埋点并验证
- [ ] PromQL 速查卡完成（rate/histogram_quantile/聚合）
- [ ] 标签基数纪律笔记
- [ ] `git add . && git commit -m "day23: prometheus & red"`

---
[← Day 22](day22-可观测性三支柱.md) | [本月目录](README.md) | [Day 24 · 告警体系 →](day24-告警体系.md)
