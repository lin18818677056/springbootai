# Step 4：稳定性保障 —— Sentinel、全链路观测、混沌演练（项目收官）

> **本步目标**：给商城装上"防护网 + 望远镜"：Sentinel 限流熔断降级全套规则、SkyWalking 全链路追踪、EFK 日志聚合，最后用混沌演练验证稳定性设计真实有效，产出项目复盘。

## 前置术语

| 英文 | 中文 |
|------|------|
| Circuit Breaking | 熔断 |
| Fallback / Degradation | 降级 |
| Tracing / Span | 链路追踪 / 跨度 |
| Chaos Engineering | 混沌工程 |
| Fault Injection | 故障注入 |
| Blast Radius | 爆炸半径 |

## 1. Sentinel 稳定性规则矩阵（按链路重要性配置）

| 链路 | 限流 | 熔断 | 降级 |
|------|------|------|------|
| 下单（核心） | QPS 预热 10s 上限 | 慢调用 RT>800ms 比例>50% 熔断 10s | 排队文案 + 稍后重试 |
| 商品详情（可缓存） | QPS 2000 | 异常比例 >30% 熔断 | 返回"稍旧"缓存数据 |
| 营销活动（边缘） | QPS 500 | 快速熔断 | 直接隐藏活动入口 |
| 库存锁定（强一致） | 线程数隔离（信号量 50） | 慢调用熔断 | 失败快速返回，靠重试/对账 |

**热点参数限流**（秒杀商品突刺防护）：

```java
// 对 goodsId 维度单独限流：爆款商品只给它自己降级，不影响其他商品
@SentinelResource(value = "productDetail", blockHandler = "detailBlocked")
public Result<GoodsVO> detail(@RequestParam long goodsId) { ... }
// 规则：参数索引 0，单机阈值 1000 QPS，超出走 blockHandler
```

**规则持久化**：Sentinel 控制台改的规则默认在内存（重启丢失）→ 实现 `ReadableDataSource` 对接 Nacos，规则存 Nacos、控制台推送同步回写（生产标配）。

## 2. SkyWalking 全链路接入（Agent 无侵入）

```powershell
# 启动参数（每个服务统一）
java -javaagent:D:\tools\skywalking-agent\skywalking-agent.jar `
     -Dskywalking.agent.service_name=mall-order `
     -Dskywalking.collector.backend_service=127.0.0.1:11800 `
     -jar mall-order.jar
```

演练三件事：
1. **拓扑图**：看到 gateway→order→product/user/marketing 完整拓扑。
2. **慢调用定位**：制造一个 500ms 慢 SQL，从拓扑点进去看 span 时间线，锁定到具体 SQL。
3. **跨服务日志串联**：SkyWalking traceId 注入日志（logback `tid`），EFK 里按 traceId 一键召回全链路日志。

## 3. 日志聚合（EFK 极简版）

```
各服务 JSON 日志（含 tid） → Filebeat 采集 → Elasticsearch(索引按天) → Kibana 检索
日志规范三条：level 正确用、异常带堆栈、业务关键动作必打 INFO（含关键 ID）
```

## 4. 混沌演练（验证防护网真的有效）

用 ChaosBlade/手动手段逐项注入，**每个实验先写预期，再动手**：

| 实验 | 注入方式 | 预期 | 实际结果 |
|------|---------|------|---------|
| E1 依赖超时 | product 接口 sleep 2s | 熔断 10s 内开启，下单快速失败非 500 | 待填 |
| E2 依赖宕机 | kill mall-user | 降级文案 + 30s 内注册中心摘除实例 | 待填 |
| E3 CPU 打满 | stress 工具 100% CPU | Sentinel 系统保护触发，优先保下单 | 待填 |
| E4 MQ 不可用 | 停 RocketMQ | 下单降级为同步模式（或熔断活动）+ 告警触发 | 待填 |
| E5 消费重复 | 手工重发 10 条消息 | 幂等生效，0 重复订单 | 待填 |

> 演练纪律：低峰执行、逐个来、随时可停（爆炸半径控制）、每次输出《发现→修复→复盘》。

## 5. 项目总复盘（对照输出物清单）

- [ ] 稳定性规则矩阵表（上表补全真实数据）
- [ ] SkyWalking 拓扑图与慢调用定位截图
- [ ] 混沌演练记录 5 份
- [ ] 《商城一致性总方案》《大促保障预案》
- [ ] 博客 1 篇（推荐：《DDD 微服务实战：从建模到混沌演练》）
- [ ] 20 分钟项目答辩录像（建模→架构→事务→稳定性四段）

## 面试自测（项目二必过五问）

1. 服务怎么拆的？为什么这么拆？（限界上下文 + 核心域独立 + 变化频率）
2. 分布式事务为什么三种方案混用？（分层一致性论证）
3. 全链路灰度怎么实现的？（染色头 + 负载均衡扩展 + 网关透传）
4. 混沌演练发现过什么问题？（讲你 E1-E5 的真实发现）
5. 如果让你把这个系统做高可用双机房，怎么演进？（同城双活 → 异地多活，数据同步方案）

> **项目二完成**。接下来进入 `../03-realtime-analytics/`，进军大数据实时链路。
