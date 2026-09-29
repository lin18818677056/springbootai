# Day 07 · 第一周复盘：从单体到"能互相调"的微服务

> **今日目标**：把 day01-06 串成一条线（拆分→工程→注册配置→网关→调用），输出一篇博客《我把单体拆成微服务的第一周》，自测 12 题，画出全链路架构图。
> **时长**：自测 1.5h / 博客 2h / 架构图 0.5h
> **今日产出**：全链路架构图（可画在白板上的版本）+ 博客一篇 + 错题清单

## 1. 知识地图：本周主线串讲

```
本周因果链（每一环都是为下一环服务）：
  day01 拆分方案（五服务+依赖无环）
    → day02 六模块工程（模块边界≈未来服务边界，BOM 版本对齐）
      → day03 注册中心（服务有了"地址簿"，Distro AP + 临时/持久 + 推空保护）
        → day04 配置中心（配置集中+热更新，长轮询；动态线程池组件）
          → day05 网关（统一入口：路由断言+过滤器链+JWT 鉴权+X-Gray 透传）
            → day06 服务间调用（Feign 动态代理+负载均衡+治理四件套+重试铁律）

一句话架构图（面试白板版）：
  Client → Nginx → Gateway[AuthGlobalFilter: JWT→X-User-Id/X-Gray]
       → lb:// + LoadBalancer → mall-order ⇄(Feign+fallbackFactory)⇄ mall-product
       → 全部注册于 Nacos(Distro AP) / 配置于 Nacos Config(长轮询)

本周埋下的伏笔（下周逐一兑现）：
  ① 请求一多把 order 打挂怎么办 → day08 限流算法 → day09 Sentinel 流控
  ② product 挂了 fallback 只是"礼貌拒绝"，怎么自动熔断恢复 → day10 熔断降级
  ③ 超时/限流的参数怎么定 → day13 服务韧性设计
  ④ X-Gray 头透传 → day20 全链路灰度发布
```

## 2. 本周自测 12 题（合上资料默答，错题记入清单）

```
工程与拆分：
  1. 拆分三原则是什么？分布式单体反模式的判定特征？（day01）
  2. 为什么 common 模块禁止放业务实体？准入标准？（day02）
  3. 三个 BOM 是怎么对齐版本的？错配的报错长什么样？（day02）
注册与配置：
  4. Distro 协议三个机制？为什么注册中心选 AP？（day03，兑现 M5 day15）
  5. 临时实例和持久实例分别走什么协议、谁做健康检查？（day03）
  6. 推空保护防什么事故？（day03）
  7. 配置长轮询 29.5s + MD5 的完整流程？与 RocketMQ 长轮询的同构点？（day04）
  8. 动态线程池为什么不用 @RefreshScope 重建 Bean？（day04）
网关与调用：
  9. Gateway 一次请求的过滤器链生命周期？order 怎么排？（day05）
 10. lb:// 缺 loadbalancer 依赖会报什么？（day05，step02 坑4）
 11. Feign 从接口到 HTTP 请求的完整链路？（day06）
 12. 为什么写操作禁重试？"超时≠失败"怎么理解？（day06）
```

## 3. 动手实操：画图与验收

```powershell
# 周末集中验收：全链路冒烟（一条命令串起所有组件）
# 1. docker compose up -d        # nacos 起来
# 2. 启动 user/product/order/gateway 四个服务
# 3. 带 token 走网关下单：Gateway 鉴权 → order → Feign 调 product → 返回订单号
# 4. 故障注入：stop product → 下单接口走 fallback 返回 503（不白板）
# 5. 配置热更：Nacos 改 order.pool.core=32 → 探针 1s 内生效（day04 的动态线程池）
# 6. git 提交整理：本周 6 个 commit + 打 tag week06-1
cd D:\mywork\springbootai\practice-projects\02-microservice-mall\mall
git tag week06-1
```

```
博客大纲《我把单体拆成微服务的第一周》（发布到掘金/CSDN）：
  开头：拆之前我以为是"多起几个进程"，拆之后才知道是"治理体系的重建"
  ① 拆分：不是按层拆（controller/service/dao 各一个服务）而是按业务域拆——分布式单体警告
  ② 版本对齐：三个 BOM 的一段 Gradle 配置 + 错配报错认脸
  ③ 注册中心：Distro 协议图 + 推空保护的真实事故故事
  ④ 网关+调用：AuthGlobalFilter 代码 + 重试铁律（超时≠失败的踩坑叙事）
  结尾预告：下周 Sentinel——"现在请求打挂了只能靠 restart 大法，下周让它学会自保"
```

## 4. 面试连接

**Q：（开放题）介绍一下你们的微服务架构？**
> 白板画图题，标准答案=本周架构图：分层讲"接入层网关（鉴权+灰度头）→ 业务层五服务（依赖方向单向无环）→ 基础设施层（Nacos 双角色）"。讲的时候按"一个请求的生命周期"走：带 JWT 的请求 → 网关校验并透传用户身份 → 网关按路由+LoadBalancer 选实例 → order 用 Feign 同步调 product（1s/3s 超时+禁重试+降级工厂）→ 全程注册中心提供地址、配置中心提供参数。每个组件都带一句"为什么"：为什么注册中心 AP（可用性优先）、为什么禁重试（超时≠失败）、为什么网关透传头（下游无状态）。（面试官要的不是名词，是名词之间的因果）

## 5. 今日验收清单

- [ ] 自测 12 题 ≥10 题全对（错题记清单）
- [ ] 全链路架构图（白板版）能 5 分钟画出并讲完
- [ ] 周末冒烟验收 6 步全通过
- [ ] 博客发布（附 Distro 协议手绘图）
- [ ] 4 个伏笔写进下周计划
- [ ] `git tag week06-1 && git commit -m "day07: week1 review"`

---
[← Day 06](day06-OpenFeign与负载均衡.md) | [本月目录](README.md) | [Day 08 · 限流算法全景 →](day08-限流算法全景.md)
