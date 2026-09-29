# Day 11 · 热点与系统保护：热点参数限流与全局兜底

> **今日目标**：掌握热点参数限流（按商品 ID 精准限流）；理解系统保护规则作为"最后防线"；把 M4 的"热点 key"问题在应用层接住。
> **时长**：热点参数 2h / 系统保护 1.5h / 案例串联 1.5h
> **今日产出**：热点参数限流实验 + 系统保护配置 + "三道防线"防护体系图

## 1. 知识地图

```
热点参数限流（ParamFlowRule）——解决"平均限流"的盲区：
  普通流控：接口总 QPS 100——但 100 个请求全打同一个商品 ID 呢？（热点）
  热点参数：按"参数值"分别计数——每个商品 ID 各自 QPS 上限，还能给 VIP ID 单独配例外
  @SentinelResource(value="getProduct", blockHandler=...)
  ParamFlowRule: paramIdx=0（第 0 个参数）, count=10（每个参数值 10 QPS）
  paramFlowItem: paramValue=1001L, count=100（爆款商品 1001 单独放宽——例外项）

热点 key 的完整防御体系（M4 Redis 热点在这里补上应用层）：
  L1 客户端缓存 → L2 CDN/网关缓存 → L3 应用本地缓存（Caffeine）→ L4 Redis → L5 DB
  热点探测：滑动窗口统计参数值 TopN → 自动提升为本地缓存（京东 HotKey 思想）
  ——面试金句：热点防不住在某层，就在每层都"接一点"

系统保护规则（SystemRule）——最后一道防线（全局视角）：
  Load（> 期望值且当前并发数>预估容量）/ RT / 入口 QPS / CPU 使用率 / 线程数
  触发后拦截所有入口流量（EntryType.IN）的一定比例——"系统级总闸"
  与限流的分工：限流管"某个接口"，系统保护管"整个机器快不行了"
  类比：限流是各部门的门禁，系统保护是整栋楼的紧急断电预案

三道防线总图（本阶段成果总结）：
  第1道 网关层：全局 QPS 限流 + IP 维度限流（防恶意流量进系统）
  第2道 服务层：接口限流 + 热点参数限流 + 熔断降级（防单点过载/下游故障）
  第3道 系统层：系统保护规则（防整机过载的最后兜底）
  ——层层设防，任何一层失守下一层还有机会（纵深防御 Defense in Depth）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| ParamFlowRule | 热点参数限流规则 |
| ParamFlowItem | 热点例外项（特定参数值单独配额） |
| Hot Key | 热点 key（参数值高度集中） |
| System Rule | 系统保护规则 |
| Defense in Depth | 纵深防御（层层设防） |
| TopN Detection | TopN 探测（自动发现热点） |
| Local Cache (Caffeine) | 本地缓存（热点前置层） |
| EntryType.IN | 入口流量（系统保护的作用范围） |

## 3. 动手实操：热点参数与系统保护实验

```java
// mall-product：商品详情接口（热点参数限流实验对象）
@GetMapping("/product/{id}")
@SentinelResource(value = "getProduct", blockHandler = "productBlock")
public Result<ProductDTO> getProduct(@PathVariable Long id) {
    return Result.ok(productService.getById(id));
}
public Result<ProductDTO> productBlock(Long id, BlockException e) {
    return Result.fail(429, "当前商品太火爆，请稍后再试");   // 带上 id 提示更友好
}
```

```powershell
# 实验一：热点参数限流
#   ParamFlowRule: 资源 getProduct, paramIdx=0, count=5（每个商品 5 QPS）
#   压测商品 1001（5 QPS 内全过）+ 同时压测商品 2002（也全过）——互不影响
#   → 对照普通流控：接口总量限 10 的话，1001 独占 10 个就把 2002 饿死了
# 实验二：例外项
#   paramFlowItem: 1001 → count=50（爆款单独放宽）
#   → 压测验证：1001 能扛 50 QPS，其他商品仍是 5
# 实验三：系统保护（CPU 使用率阈值 50%）
#   开压测把 CPU 打上去 → 观察入口流量被按比例拦截（Dashboard 系统规则面板）
#   → 降 CPU 后自动恢复——"总闸"会自己回来

# 热点 key 综合案例（串联 M4）：
#   商品 1001 突然爆量：L3 本地缓存 Caffeine（maxSize=1万，TTL=10s）先接住
#   → 热点参数限流兜住穿透到 Redis 的量 → Redis 单 key 读压力可控
#   压测对比：无本地缓存 vs 有本地缓存，Redis QPS 差 10 倍+（数据记录进笔记）
```

## 4. 面试连接

**Q：什么是热点问题？你们怎么处理的？**
> 两类热点：访问热点（某商品/主播的流量高度集中）与数据热点（单行/单 key 读写集中）。处理分五层讲纵深防御：客户端缓存与 CDN 挡静态；应用层 Caffeine 本地缓存挡热读（配合热点探测自动提升 TopN）；Redis 层 key 分片（秒杀库存分段，M4 day26 落地过）；应用层热点参数限流保证"再热也热不挂服务"；数据库层单行拆多行/读走从库。加分句："热点限流我强调参数维度——接口总限流会互相饿死，按参数值各自计数才能既保爆款又保长尾。"

**Q：系统保护规则用过吗？跟限流的区别？**
> 限流对象是"某个资源"，系统保护对象是"整个实例"：Load/RT/CPU/入口 QPS 超阈值时按比例拦截入口流量。我的定位是"最后防线"：正常情况下靠接口限流+熔断就该把问题拦在前两道，系统保护触发说明流量估计错了或出了未知故障——宁可拒掉一部分也要保住整机不挂，因为它挂了这台上面的所有服务全完。实践提醒：系统保护阈值必须基于容量压测（day13 讲怎么压），拍脑袋定 CPU=60% 要么太松要么误杀。

## 5. 今日验收清单

- [ ] 热点参数限流实验（两个商品互不影响）
- [ ] 例外项实验（爆款单独配额）
- [ ] 系统保护触发与自动恢复观察
- [ ] "三道防线"体系图能画（网关/服务/系统层）
- [ ] 热点 key 五层防御与 M4 知识连线完成
- [ ] `git add . && git commit -m "day11: hot param & system rule"`

---
[← Day 10](day10-熔断降级.md) | [本月目录](README.md) | [Day 12 · 规则持久化与集群流控 →](day12-规则持久化与集群流控.md)
