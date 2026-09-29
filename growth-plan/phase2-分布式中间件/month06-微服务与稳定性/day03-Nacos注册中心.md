# Day 03 · Nacos 注册中心：Distro 协议与实例治理

> **今日目标**：部署 Nacos，兑现 M5 day15 的 AP/CP 三个"为什么"：Distro 协议怎么工作？临时/持久实例为何两套协议？推空保护防什么？源码主线走读注册流程。
> **时长**：部署 1h / 原理 2.5h / 源码主线 1.5h
> **今日产出**：Nacos 环境 + Distro 协议手绘图 + 三个伏笔的答案

## 1. 知识地图：先还 M5 day15 的三个"为什么"

```
伏笔①：Distro 协议到底是什么？（临时实例的 AP 底座）
  定位：Nacos 自研的 AP 分布式协议（最终一致 + 分片复制）
  三个机制：
  ① 分片负责：每台 Nacos 只负责部分服务（按 hash 取模），写请求路由到负责节点
  ② 异步复制：负责节点写内存+磁盘快照 → 异步批量同步给其他节点（秒级，不阻塞写）
  ③ 全量拉取兜底：新节点加入/数据缺失 → 从任意节点全量拉一次快照
  ——对比 Raft：Distro 不要多数派确认，写快（AP），代价是短暂读到旧数据
  为什么注册中心适合 AP？实例列表旧几秒没关系（消费端有本地缓存+失败重试路由），
  但可用性不能丢——挂一半 Nacos 剩下的还能查（对比 ZooKeeper 过半不可用就瘫痪）

伏笔②：临时 vs 持久实例，为什么两套协议？
  临时实例（ephemeral=true，默认）：客户端心跳续约（5s/次，15s 标记不健康，30s 剔除）
    → Distro AP；适合服务实例（上下线频繁，可用性优先）
  持久实例（ephemeral=false）：服务端主动健康探测（TCP/HTTP 探活）
    → Raft CP（2.x 起用 JRaft）；适合数据库/Redis 等基础设施地址（不会频繁变，要准确）
  ——一句话：服务的"地址簿"按"变的快不快"分两类，快变用 AP，慢变用 CP

伏笔③：推空保护防什么？
  场景：网络抖动/批量重启 → Nacos 误把健康实例全剔除 → 客户端拿到空实例列表
       → 所有请求直接失败（比拿旧列表打几个失败请求更惨——"推空事故"）
  防护：客户端发现"新列表为空且旧列表非空" → 丢弃本次推送，继续用旧列表 + 告警
  ——小细节大事故：真实生产里 Nacos 推空导致全站不可用的案例不止一次
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Distro Protocol | Nacos AP 协议（分片负责+异步复制+快照兜底） |
| Ephemeral Instance | 临时实例（心跳续约，Distro 管理） |
| Persistent Instance | 持久实例（服务端探活，Raft 管理） |
| Heartbeat / Renew | 心跳续约（5s 周期） |
| Push vs Pull | 订阅推送（UDP/gRPC）+ 客户端定时拉取兜底 |
| Push Empty Protection | 推空保护（空列表不推送） |
| Graceful Shutdown | 优雅上下线（主动注销 vs 被动剔除） |
| Naming Service / Config Service | 注册中心/配置中心（Nacos 双角色） |

## 3. 动手实操：Nacos 部署与验证

```powershell
# docker-compose（learning/month06-ms-stability/nacos/）：
#   nacos: nacos/nacos-server:v2.3.2 standalone 模式
#     MODE=standalone, NACOS_AUTH_ENABLE=false（学习环境）
#     端口 8848（HTTP）/9848（gRPC 客户端，2.x 必须暴露！）
docker compose up -d
Start-Process "http://localhost:8848/nacos"    # 控制台：服务管理/配置管理

# 商城五个服务接 Nacos（day02 的依赖已带 nacos-discovery）：
# application.yml: spring.cloud.nacos.discovery.server-addr=127.0.0.1:8848, namespace=dev
# 依次启动 user/product/order → 控制台"服务列表"出现三服务（实例数/健康状态）

# 实验一：临时实例的生命周期
#  杀掉 mall-order 进程 → 控制台观察：15s 变不健康（红点）→ 30s 消失
#  重启 → 几秒内重新注册（对比持久实例：探测失败只标不健康，不剔除）
# 实验二：订阅与本地缓存
#  在 mall-order 打印服务列表日志（NacosWatch/订阅回调）→ 观察"本地缓存快照"存在
#  停掉 Nacos → 服务间调用仍然成功（缓存兜底）——AP 可用性的客户端侧证据
# 实验三：推空保护触发（模拟）
#  iptables/防火墙断 product 心跳 30s+ → 观察订单侧日志是否出现"实例列表为空"防护记录
```

## 4. 面试连接

**Q：Nacos 的 AP 和 CP 怎么选？底层协议分别是什么？**
> 按实例类型分：临时实例（服务应用，默认）走 Distro 协议——分片负责+异步复制+快照兜底的 AP 实现，心跳续约剔除，挂一半仍可用；持久实例（数据库/中间件地址）走 Raft（JRaft）——强一致，服务端探活。选择依据是"数据变化频率与可用性需求"：服务实例上线下线频繁且消费端能容忍秒级旧数据 → AP；基础设施地址错一个就是事故 → CP。收尾："选型本质不是选协议是选业务语义——M5 CAP 周的结论在这里完全落地。"

**Q：注册中心的推送是推还是拉？服务挂了客户端多久知道？**
> Nacos 2.x：订阅制——gRPC 长连接推送变更（毫秒级），同时客户端每 6s 定时拉取兜底（双保险，防推送丢失）。实例挂了的感知速度：推送通道上最快秒级；心跳超时路径是 15s 标记不健康、30s 剔除。补充推空保护与本地缓存："即使 Nacos 全挂，客户端本地快照还能撑很久——注册中心的可用性设计是层层兜底的，跟 MQ 可靠性三道闸门一个思路。"

## 5. 今日验收清单

- [ ] Nacos 部署完成，三服务注册可见
- [ ] 三个伏笔的答案写成笔记（Distro 手绘图）
- [ ] 临时实例生命周期实验（不健康→剔除→重注册）
- [ ] 停 Nacos 服务调用仍通（本地缓存证据）
- [ ] 推空保护机制能讲清（含真实事故案例引用）
- [ ] `git add . && git commit -m "day03: nacos discovery"`

---
[← Day 02](day02-多模块工程骨架.md) | [本月目录](README.md) | [Day 04 · Nacos配置中心 →](day04-Nacos配置中心.md)
