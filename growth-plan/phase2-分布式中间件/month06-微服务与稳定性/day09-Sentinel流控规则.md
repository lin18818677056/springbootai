# Day 09 · Sentinel 流控规则：Dashboard、流控模式与 Warm Up

> **今日目标**：部署 Sentinel Dashboard，mall-order 接入；玩全三种流控模式与三种流控效果；兑现 M4 day09 伏笔——Warm Up 冷启动到底怎么"预热"。
> **时长**：部署接入 1.5h / 规则实验 2.5h / 原理 1h
> **今日产出**：Sentinel 环境 + 流控规则实验记录 + Warm Up 算法拆解笔记

## 1. 知识地图

```
Sentinel 的定位：流量防卫兵——限流/熔断/系统保护/热点四合一（阿里双十一沉淀）

资源与规则模型：
  一切皆资源：URL（自动）/ @SentinelResource 注解方法（手动）/ 代码 SphU.entry()
  规则挂资源：FlowRule（流控）/ DegradeRule（熔断，明天）/ SystemRule（系统）/ ParamFlowRule（热点）

流控规则三要素：
  ① 阈值类型：QPS（每秒请求数）or 并发线程数（信号量隔离思想）
  ② 流控模式（对谁计数）：
     直接：资源自己超了就限自己
     关联：资源 B 超限时限资源 A——"写接口忙时掐掉读接口"（保写弃读）
     链路：只统计"从某入口进来的"调用——精确到链路粒度
  ③ 流控效果（超了怎么办）：
     快速失败：直接抛 FlowException（默认）
     Warm Up：冷启动——阈值从 threshold/coldFactor(默认3) 渐进爬到设定值（预热期默认 10s）
     排队等待（漏桶思想）：请求匀速通过，超时的在队列等，等不到就超时丢弃

Warm Up 兑现 M4 day09 的伏笔（秒杀预热脚本里见过它）：
  场景：服务刚启动——JIT 未编译热路径/缓存未加载/连接池未建满 → 立即给满量 QPS 直接打挂
  算法：令牌桶变体。令牌生成速率从 count/coldFactor 线性增长到 count，历时 warmUpPeriodSec
        （消费令牌越快 → 桶里存量越少 → 生成速率越快——"喝得越快倒得越快"的斜坡）
  与排队的本质区别：WarmUp 是"先少给后多给"（保护自己冷启动），排队是"匀速给"（保护下游）

@SentinelResource 的 blockHandler vs fallback（高频面试坑）：
  blockHandler：处理 BlockException（限流/熔断触发）——"哨兵拦的"
  fallback：处理业务异常（RuntimeException 等）——"代码炸了"
  两个都没配：BlockException 抛出 → 白板 500（FlowException 堆栈刷屏）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Resource | 资源（被保护的代码块） |
| FlowRule | 流控规则 |
| Flow Mode (Direct/Assoc/Chain) | 流控模式（直接/关联/链路） |
| Warm Up | 预热/冷启动（渐进提升阈值） |
| Rate Limiter (Queueing) | 排队等待（匀速通过，漏桶） |
| coldFactor | 冷启动因子（初始阈值 = count/coldFactor） |
| BlockException | 限流熔断异常（与业务异常分流） |
| Sentinel Dashboard | 控制台（规则可视化+实时监控） |

## 3. 动手实操：Dashboard 部署与规则实验

```yaml
# mall-order 接入（build.gradle 加 sentinel starter）：
# implementation 'com.alibaba.cloud:spring-cloud-starter-alibaba-sentinel'
spring:
  cloud:
    sentinel:
      transport:
        dashboard: localhost:8858      # 控制台地址
        port: 8719                     # 本地起的服务端口（dashboard 反向拉数据）
      eager: true                      # 取消懒加载，启动即注册到 dashboard
# Feign 整合（明天配合熔断用）：
# feign.sentinel.enabled=true
```

```powershell
# 1. docker run -d --name sentinel-dashboard -p 8858:8858 -p 8719:8719 `
#      bladex/sentinel-dashboard:1.8.8     # 账号密码 sentinel/sentinel
Start-Process "http://localhost:8858"

# 2. 压测工具准备（ab/JMeter/wrk 任选，这里用 PowerShell 循环模拟）：
#    while($true){ Invoke-WebRequest http://localhost:8080/api/order/probe/ping -UseBasicParsing }
# 实验一：直接模式+快速失败——给 /probe/ping 配 QPS=2 快速失败
#   → 压测观察：超 2 QPS 的请求秒回 FlowException（熔断前先看限流形态）
# 实验二：关联模式——给 /order/create 配"关联 /order/query，QPS>50 限读"
#   → 开两个压测：写接口打满 → 读接口开始被限（保写弃读的落地）
# 实验三：Warm Up——配 QPS=100，Warm Up 10s
#   → 压测曲线：前几秒放行量从 ~33 爬坡到 100（threshold/coldFactor=100/3 起步）
#   → 对照 day08 手写的令牌桶：Warm Up 就是"生成速率动态变化"的令牌桶
# 实验四：排队等待——配 QPS=10，超时 2s
#   → 观察响应被"匀速化"：请求不再秒回，而是按 100ms 一个的节奏放行（漏桶整形）
# 实验五：blockHandler 验证——@SentinelResource(blockHandler="flowBlock")
#   → 限流触发返回 Result.fail(429) 而不是白板 500
```

```java
// @SentinelResource 标准写法（mall-order 探针升级版）：
@GetMapping("/probe/flow")
@SentinelResource(value = "flowProbe",
        blockHandler = "flowBlock",            // 限流/熔断走这里（BlockException）
        fallback = "flowFallback")             // 业务异常走这里
public Result<String> flow() {
    if (Math.random() < 0.3) throw new RuntimeException("boom");   // 模拟业务异常
    return Result.ok("flow-ok");
}
public Result<String> flowBlock(BlockException e) { return Result.fail(429, "限流啦"); }
public Result<String> flowFallback(Throwable e) { return Result.fail(500, "业务异常兜底"); }
```

## 4. 面试连接

**Q：Sentinel 的 Warm Up 冷启动原理？为什么需要它？**
> 需求来源：冷系统直接扛满量必挂——JIT 未热编译、缓存冷、连接池未就绪。实现是令牌桶变体：令牌生成速率不是恒定，而是从 count/coldFactor（默认 1/3 满量）随时间线性爬升到 count，历时 warmUpPeriodSec；内部状态机从 START→WAITING→THROTTING，核心变量"剩余令牌数"决定当前生成速率——放行越快速率涨得越快，形成预热斜坡。追问"跟排队等待的区别"：WarmUp 是对自己冷启动的保护（先少后多），排队是漏桶整形（恒速匀速，削峰填谷）。收尾："我在秒杀项目里网关前置限流配的就是 Warm Up——开抢前 10 秒爬坡，正好给缓存预热留时间窗。"（M4 day09 的伏笔在这里闭环）

**Q：blockHandler 和 fallback 的区别？都不配会怎样？**
> blockHandler 只拦 BlockException——限流/熔断/系统保护触发的"哨兵异常"；fallback 拦业务运行时异常。两者是"门禁拦下"与"屋里着火"两条路径，都不配的话 BlockException 直接冒泡成白板 500。生产规范：核心资源必须配 blockHandler 统一返回 429/降级文案，监控上把 BlockException 次数当作"限流触发率"指标看（突增=要么流量异常要么阈值设低了）。追问"注解资源和 URL 资源怎么选"：URL 自动埋点适合粗粒度网关防护，@SentinelResource 适合精确到业务语义的资源命名——name 带业务含义的监控才有可读性。

## 5. 今日验收清单

- [ ] Dashboard 部署 + mall-order 上线可见（簇点链路有数据）
- [ ] 五个实验全做完（直接/关联/WarmUp/排队/blockHandler）
- [ ] Warm Up 爬坡曲线截图（与手写令牌桶对照）
- [ ] blockHandler vs fallback 区别能秒答
- [ ] "保写弃读"关联模式场景能举例
- [ ] `git add . && git commit -m "day09: sentinel flow control"`

---
[← Day 08](day08-限流算法全景.md) | [本月目录](README.md) | [Day 10 · 熔断降级 →](day10-熔断降级.md)
