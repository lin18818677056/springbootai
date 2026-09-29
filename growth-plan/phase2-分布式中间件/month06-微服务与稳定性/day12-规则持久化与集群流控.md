# Day 12 · 规则持久化与集群流控：让规则活过重启

> **今日目标**：解决"Dashboard 推规则重启就丢"的生产级问题——规则持久化到 Nacos（推模式）；理解单机限流的总量误差与集群流控 Token Server。
> **时长**：规则持久化 2.5h / 集群流控 2h / Nacos 联动 0.5h
> **今日产出**：Nacos 持久化的流控规则（重启不丢）+ 集群流控笔记

## 1. 知识地图

```
问题：控制台推的规则存在哪？
  默认（原始模式）：应用内存——应用重启规则全丢，Dashboard 重启也丢
  生产不能忍：限流规则是"资产"，重启即裸奔

三种持久化模式：
  ① 原始模式：内存（学习/演示用）
  ② Pull 模式：规则写本地文件，应用定时轮询文件——简单但实时性差、多实例可能不一致
  ③ Push 模式（生产标准）：规则存配置中心（Nacos/ZK/Apollo），
     监听配置变更 → 实时下发到应用内存 ——跟 day04 的配置热更新一套机制！
  实现：sentinel-datasource-nacos 依赖 + application.yml 配 dataId/group
  闭环：控制台改规则 → 写 Nacos → 监听推送 → 应用内存生效（1s 内，跟配置热更同款链路）
  （默认 Dashboard 不带写 Nacos 的功能——要么用改造版 dashboard，要么规则在 Nacos 控制台改）

集群流控（Cluster Flow Control）——为什么需要：
  单机限流的总量误差：限 1000 QPS、10 台机器各限 100 → 实际放行可达 1000+
    但 1000/10=100 分不均时（1 台打 500？）——每台 100 太保守或总体超标
  集群流控：一个 Token Server 统一计数发令牌——全局精确 1000
  两种部署：嵌入式（选一台应用兼职 Token Server——省资源但故障转移要配）/
           独立部署（专用 Token Server——精确但要维护高可用）
  权衡：大多数场景单机限流+合理容量规划够用；精确总量控制（如秒杀总量）才上集群流控
  （M4 秒杀的 Redis 库存其实就是"分布式精确限流"的另一种实现——Lua 原子性！）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Rule Persistence | 规则持久化 |
| Pull Mode / Push Mode | 拉模式（轮询文件）/ 推模式（配置中心监听） |
| DataSource | 规则数据源（nacos/zk/apollo/file） |
| Cluster Flow Control | 集群流控 |
| Token Server | 令牌服务端（集群流控的统一计数器） |
| Embedded / Standalone | 嵌入式/独立式部署 |
| Total Quota | 全局总量配额 |

## 3. 动手实操：规则持久化到 Nacos

```groovy
// mall-order build.gradle 追加（规则数据源）：
// implementation 'com.alibaba.csp:sentinel-datasource-nacos'
```

```yaml
# application.yml：规则源指向 Nacos（Push 模式的客户端侧）
spring:
  cloud:
    sentinel:
      datasource:
        flow-rules:
          nacos:
            server-addr: 127.0.0.1:8848
            data-id: mall-order-flow-rules      # 规则存这个配置里
            group-id: SENTINEL_GROUP
            rule-type: flow                     # flow/degrade/param-flow/system
        degrade-rules:
          nacos:
            server-addr: 127.0.0.1:8848
            data-id: mall-order-degrade-rules
            group-id: SENTINEL_GROUP
            rule-type: degrade
# Nacos 控制台创建 dataId=mall-order-flow-rules，内容（JSON 数组）：
# [
#   {
#     "resource": "getProduct",
#     "limitApp": "default",
#     "grade": 1,              // 0=线程数 1=QPS
#     "count": 100,
#     "strategy": 0,           // 0=直接
#     "controlBehavior": 0     // 0=快速失败 1=WarmUp 2=排队
#   }
# ]
```

```powershell
# 验证闭环：
# ① 应用启动 → Dashboard 看到 Nacos 里的规则已加载
# ② Nacos 控制台改 count=100→10 → 1s 内 Dashboard 同步 → 压测立即生效
# ③ 重启 mall-order → 规则还在（从 Nacos 重新加载）——持久化目标达成
# ④ 故意把 JSON 改坏 → 观察应用日志报错但服务不死（规则解析失败不影响运行，
#    但规则不加载——配置校验意识！）

# 集群流控（认知实验，不做生产部署）：
# Dashboard → 集群流控 → 配置 Token Server（本机嵌入演示）
# 思考题笔记：单机 100×10 台 vs 集群 1000 的差异压测（各实例流量均匀 vs 打偏）
# 结论记录：什么场景值得上集群流控（秒杀总量闸门），什么场景单机够用（常规接口）
```

## 4. 面试连接

**Q：Sentinel 规则怎么持久化？生产上怎么管理？**
> 默认规则在应用内存，重启即丢。生产用 Push 模式：规则持久化在 Nacos 配置中心，应用通过 sentinel-datasource-nacos 监听 dataId，变更 1s 内推送到内存生效——机制上跟配置中心热更新完全同源（长轮询）。管理规范："规则变更走 Nacos 走 GitOps——规则 JSON 提交评审后发布，谁改的、为什么改、什么时候改全程可追溯；限流规则和代码一样有'变更风险'。"追问"Pull 模式为什么不行"：轮询有延迟、多实例各自读文件可能不一致、没有集中变更审计——本质是"配置管理的老问题，配置中心早就解过一遍"。

**Q：单机限流和集群限流的区别？什么时候必须集群限流？**
> 单机限流是"每台各自计数"：部署 10 台、每台限 100，均匀流量下总量约 1000，但流量倾斜时（LB 不均/热点实例）总量失真——要么过保要么超卖。集群流控由 Token Server 统一发令牌，全局精确。判断标准："要不要'精确总量'语义——秒杀的库存扣减是精确总量（所以用 Redis Lua 而不是每台限流），常规接口防刷用单机就够（精确到 1000±50 无所谓）。集群流控的代价是 Token Server 高可用要维护——为精确性付出复杂度，没有免费午餐。"（把 M4 秒杀库存、Redis Lua 串进来=跨月知识网络）

## 5. 今日验收清单

- [ ] Nacos 规则持久化配置完成（flow+degrade 两组）
- [ ] "改 Nacos→1s 生效→重启不丢"闭环验证
- [ ] 坏 JSON 实验做过（规则解析失败不影响服务）
- [ ] 集群流控原理与 Token Server 两种部署能讲清
- [ ] "精确总量 vs 单机够用"的判断标准能举例
- [ ] `git add . && git commit -m "day12: rule persistence & cluster flow"`

---
[← Day 11](day11-热点与系统保护.md) | [本月目录](README.md) | [Day 13 · 服务韧性设计 →](day13-服务韧性设计.md)
