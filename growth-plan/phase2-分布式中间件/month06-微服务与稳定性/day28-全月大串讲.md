# Day 28 · 全月大串讲：微服务与稳定性五线合一

> **今日目标**：把 M6 四周五条线（治理/韧性/一致性/可观测/混沌）串成一个故事；绘制"商城微服务全景架构图（终版）"；盘点本月代码资产清单；完成 M5→M6 跨月连线表。
> **时长**：串讲梳理 2.5h / 资产盘点 1h / 全景图 1.5h
> **今日产出**：全景架构图终版 + 代码资产清单 + 跨月连线表

## 1. 知识地图：五线合一

```
M6 的五条技术线，其实是一个故事："把单体变成能自我保护的微服务体系"

  第一线 治理基座（W1）：拆分三原则 → 六模块工程 → Nacos 双中心（AP 注册+长轮询配置）
        → 网关统一入口（JWT/灰度头）→ Feign 声明式调用（治理四件套/重试铁律）
  第二线 流量韧性（W2）：限流四算法手写 → Sentinel 流控/熔断/热点/系统保护
        → 规则持久化 → 韧性四板斧（超时预算/舱壁/容量压测/RUNBOOK）
  第三线 数据一致（W3）：Seata AT（undo_log/全局锁）→ TCC（冻结/三坑）
        → 链路分级混布 → 对账（P0/P1/P2 分级自愈）→ 全链路灰度
  第四线 可观测（W4 前 5 天）：三支柱方法论 → Prometheus/Grafana（RED+业务指标）
        → 告警体系（三原则） → EFK（traceId 召回） → SkyWalking（30 秒定位）
  第五线 验证闭环（W4 后 4 天）：混沌工程五步法 → 三场演练 → 修复项闭环
        → 全月串讲 → 模拟验收 → 毕业检查

一条"事故的处理全过程"串起全部（面试杀手锏）：
  ① 事前：容量压测定限流（13 日）+ 灰度发布防变更事故（20 日）+ RUNBOOK 预案
  ② 事中：RED 告警发现（23/24 日）→ SkyWalking 30 秒定位（26 日）
         → traceId 召回日志深挖（25 日）→ 熔断限流自动止损（9-11 日）
  ③ 事后：对账确认无资损（19 日）→ 混沌复盘变预案（27 日）→ MTTR 持续下降
```

## 2. 代码资产盘点（本月 learning + mall 的沉淀清单）

```
learning/month06-ms-stability/（javac 纯手写资产）：
  ① RateLimiterSuite.java       四种限流算法+对比压测（day08）
  ② MiniCircuitBreaker.java     15 行断路器状态机（day10）
  ③ monitor/ docker-compose     Prometheus+Grafana（day23）
  ④ efk/ docker-compose         ES+Kibana+Filebeat（day25）
  ⑤ skywalking/ docker-compose  OAP+UI（day26）
  ⑥ chaos/ ChaosBlade 演练脚本×3（day27）

practice-projects/02-microservice-mall/（Gradle 六模块工程资产）：
  ① mall-common                 Result/BizException/DynamicThreadPool（day02/04）
  ② mall-gateway                路由+AuthGlobalFilter（JWT/X-Gray 染色）（day05/20）
  ③ mall-user/product/order/marketing  五业务服务
  ④ ProductClient+fallbackFactory+治理四件套（day06）
  ⑤ Sentinel 接入+Nacos 规则持久化（day09/12）
  ⑥ StockTccAction（TCC 冻结三坑防御）（day17）
  ⑦ 事务消息链路+对账 Job（day18/19）
  ⑧ GrayLoadBalancer 标签路由（day20）
  ⑨ OrderMetrics 业务指标+JSON 日志+MDC traceId（day23/25）
  ⑩ RUNBOOK.md 预案表+容量压测报告（day13）
  ——对照 M4 秒杀项目：M4 是"单点技术深度"，M6 是"体系化工程能力"——简历两大支柱齐了
```

## 3. 动手实操：全景图与跨月连线

```
商城微服务全景架构图（终版，白板 5 分钟讲完的版本）：
  Client → Nginx → Gateway（JWT 鉴权/染色/全局限流/Sentinel）
    → mall-order ──(Feign+熔断+标签路由)──> mall-product（TCC 库存/热点限流/双实例灰度）
    │      │──(事务消息)──> mall-marketing（积分/幂等消费）
    │      └──AT──> 券核销
    └── mall-user（登录/JWT 签发）
  基础设施：Nacos（注册 AP+配置长轮询+Sentinel 规则源）/ RocketMQ（事务消息）
  可观测：Prometheus+Grafana（RED+业务）/ EFK（traceId）/ SkyWalking（span 树）
  韧性：Sentinel 三道防线 / RUNBOOK / 混沌演练 / 对账
  ——面试讲法：按"一个请求的生命周期"+"一次故障的处理流程"两条线讲，别按组件罗列

M5→M6 跨月连线表（本月兑现的伏笔盘点）：
  M5 day15 AP/CP 三问     → day03 Distro 协议拆解        ✅
  M5 day26 排障靠日志慢   → day26 SkyWalking 提速 10 倍   ✅
  M4 day09 Warm Up 使用   → day09 冷启动算法源码级        ✅
  M4 Redis 热点 key       → day11 热点参数限流五层防御    ✅
  M4 Redis Lua 原子扣减   → day12 集群流控对照            ✅
  M5 幂等三板斧           → day06 重试铁律 + day18 消费幂等 ✅
  M5 day30 拆分草图作业   → day01 开工验证+批判修正       ✅
  M5 TCC 三坑手写         → day17 框架级对照              ✅
  M5 事务消息源码         → day18 积分链路落地            ✅
  M5 六方案决策树         → day21 终版决策树+评审         ✅
  留给 M7 的伏笔：step01 的 DDD 建模只用了"骨架"——聚合/值对象/领域事件
    的深化（Order 充血模型/Money 值对象）下周 M7 正式展开
```

## 4. 面试连接

**Q：（终极大题）从 0 到 1 搭一个微服务系统，你的技术蓝图是什么？**
> 按建设顺序四阶段讲（不是组件堆砌）：①基座期——模块化工程+BOM 对齐+注册配置中心+网关鉴权+Feign 治理（超时/禁重试/降级模板），先把"能跑"变成"能治理"；②韧性期——Sentinel 三道防线+规则持久化+容量压测定阈值+RUNBOOK；③一致期——链路分级选型（AT/TCC/消息混布）+对账兜底+灰度发布；④可观测期——三支柱流水线+分级告警+混沌演练闭环。每阶段带一个"为什么"：为什么先治理后韧性（不知道容量的限流是玄学）、为什么混布一致方案（单一方案要么性能崩要么一致性崩）、为什么混沌收官（没验证过的预案=没有预案）。（蓝图题=架构叙事能力，M6 一个月就是在攒这个故事）

## 5. 今日验收清单

- [ ] 全景架构图终版（白板 5 分钟版）
- [ ] 代码资产清单核对（learning 6 项+mall 10 项）
- [ ] 跨月连线表 10 条全勾
- [ ] "事故处理全过程"叙事能脱稿讲
- [ ] 技术蓝图四阶段能画能讲
- [ ] `git add . && git commit -m "day28: month6 review"`

---
[← Day 27](day27-混沌工程体系化.md) | [本月目录](README.md) | [Day 29 · M6模拟验收 →](day29-M6模拟验收.md)
