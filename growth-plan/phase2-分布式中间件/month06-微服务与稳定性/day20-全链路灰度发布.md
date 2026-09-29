# Day 20 · 全链路灰度发布：染色、标签路由与金丝雀

> **今日目标**：实现全链路灰度——网关按用户尾号染色（X-Gray，day05 埋的伏笔兑现），自定义 LoadBalancer 标签路由，灰度实例与稳定实例流量隔离；实测灰度发布全过程。
> **时长**：原理与设计 1.5h / 编码落地 3h / 发布演练 0.5h
> **今日产出**：全链路灰度 Demo（灰度用户走灰度实例）+ 发布策略对比表

## 1. 知识地图

```
灰度发布解决什么：新版本不敢全量上——"金丝雀"先带 1% 流量探路，有问题只炸小范围

发布策略对比（先辨析三种，别混淆）：
  蓝绿发布：两套环境整套切换（绿=旧蓝=新）——回滚秒级但资源×2
  滚动发布：逐台替换实例——省资源但版本共存期间行为不一致
  金丝雀/灰度：小流量验证逐步放量（1%→10%→50%→100%）——今天的主角
  K8s 时代三者都靠"多版本 Deployment+流量规则"实现

全链路灰度 = 灰度标记跨服务不丢 + 每个服务都有灰度实例可路由：
  ① 染色入口：网关 AuthGlobalFilter 解析 JWT → X-Gray: true（day05 已埋好！）
     （或按用户尾号/白名单/百分比——"灰度规则"是产品决策不是技术决策）
  ② 透传：所有服务间调用带 X-Gray（Feign RequestInterceptor 统一补头——day06 写过）
  ③ 标签注册：灰度实例注册时带元数据 version=gray
     （eureka.instance.metadata-map / nacos.discovery.metadata.version=gray）
  ④ 标签路由：自定义 LoadBalancer——请求带 X-Gray 就只挑 version=gray 实例，
     灰度实例挂了降级走稳定实例（还是 500？看策略：灰度故障回落稳态更安全）
  ⑤ 网关路由：灰度请求也优先路由灰度网关实例（可选，实验里简化）

实现要点（Spring Cloud 版）：
  实现 ReactorServiceInstanceLoadBalancer，按 header 选实例
  Nacos 实例元数据在 bootstrap 配：spring.cloud.nacos.discovery.metadata.version=gray
  ——灰度实例=同一服务的另一个进程，配置里 metadata.version=gray 启动即带标
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Canary Release | 金丝雀发布/灰度发布 |
| Full-Link Gray Release | 全链路灰度 |
| Traffic Dyeing / Tagging | 流量染色 |
| Metadata / Label | 实例元数据/标签（version=gray） |
| Label Routing | 标签路由（按标选实例） |
| Blue-Green / Rolling | 蓝绿/滚动发布 |
| Traffic Ratio | 放量比例（1%→10%→100%） |
| Fallback to Stable | 灰度故障回落稳态 |

## 3. 动手实操：全链路灰度 Demo

```java
// ① mall-gateway：灰度染色（改造 day05 的 AuthGlobalFilter——已经写了 X-Gray 透传！）
//    本次补充：按用户尾号自动染色（灰度规则）
boolean gray = Long.parseLong(userId) % 10 < 2;   // 尾号 0/1 → 20% 灰度流量
// ② 各服务：Feign 拦截器透传（day06 已写）——检查 X-Gray 是否带上了
public class GrayInterceptor implements RequestInterceptor {
    public void apply(RequestTemplate tpl) {
        String gray = RequestContextHolder.current().getHeader("X-Gray");  // 从当前请求取
        if (gray != null) tpl.header("X-Gray", gray);   // 不丢染色标记
    }
}
// ③ 自定义标签路由（核心组件）：
public class GrayLoadBalancer implements ReactorServiceInstanceLoadBalancer {
    public Mono<Response<ServiceInstance>> choose(Request request) {
        boolean isGray = "true".equals(request.getContext().getClientRequest()
                .getHeaders().getFirst("X-Gray"));
        return instances.filter(list -> list.stream().anyMatch(i ->
                        "gray".equals(i.getMetadata().get("version")) == isGray))
                .map(list -> pick(list.stream().filter(i ->
                        "gray".equals(i.getMetadata().get("version")) == isGray).toList()));
        // 灰度请求只挑 gray 实例；灰度实例不存在时 fallback 稳态（写进代码注释：策略可配）
    }
}
// ④ 灰度实例启动配置（第二个 product 进程，端口 8082）：
// spring.cloud.nacos.discovery.metadata.version=gray
// server.port=8082
// + 一行日志：GrayProductApplication 启动横幅打印 "GRAY INSTANCE"
```

```powershell
# 发布演练（全流程截图）：
# ① 起稳态 product（8081，无标）+ 灰度 product（8082，version=gray）
# ② Nacos 控制台确认：mall-product 两实例，metadata 各自带/不带 gray 标
# ③ 灰度用户（userId 尾号 0）下单 → product 日志只出现在 8082（灰度实例）
# ④ 普通用户（尾号 5）下单 → 只出现在 8081
# ⑤ 关掉 8082 → 灰度用户请求回落 8081（fallback 生效，观察日志）
# ⑥ 灰度验证通过后：稳态实例滚动升级 → 放量 100% → 灰度实例退役
# 思考题笔记：灰度期间数据库 schema 变更怎么办？（答：向后兼容两版 schema，
#  禁止灰度期跑不兼容 DDL——灰度的边界是"代码可灰度，schema 变更必须兼容"）
```

## 4. 面试连接

**Q：全链路灰度怎么实现？难点在哪？**
> 五要素：染色入口（网关按用户尾号/白名单打 X-Gray 标）、标记透传（Feign 拦截器统一补头，MQ 场景把标放进消息头）、标签注册（Nacos 实例元数据 version=gray）、标签路由（自定义 LoadBalancer 按标选实例）、放量与退役（1%→100%→灰度实例下线）。难点两个：一是"标记不丢"——跨 HTTP/MQ/线程池（异步任务要传递上下文，装饰 Runnable）每个跳变点都要覆盖，漏一处灰度就"串流量"；二是数据兼容——灰度版写入的数据稳态版也要能读（schema 向后兼容），否则灰度期的订单稳态实例处理不了。收尾："我们灰度规则是产品定的（哪些用户先体验新功能），技术只保证'染了色的流量走灰度实例，一滴不漏'。"

**Q：蓝绿、滚动、金丝雀怎么选？**
> 资源充裕要秒回滚选蓝绿（两套环境成本高）；普通无状态服务滚动发布是默认；新功能风险未知、需要真实流量验证选金丝雀——"金丝雀的核心价值不是少炸点机器，是拿到真实流量的验证数据（错误率/RT 对比）再决定放量"。混合形态很常见："我们平时滚动+健康检查，大促前的新链路必走灰度（尾号 20%）观察一周。回滚速度排序：蓝绿秒切 > 金丝雀下线灰度实例 > 滚动逐台回滚——回滚速度需求也是选型输入。"

## 5. 今日验收清单

- [ ] 灰度/稳态双实例共存（Nacos 元数据可见）
- [ ] 灰度用户/普通用户流量隔离验证（日志证据）
- [ ] 灰度实例宕机 fallback 稳态生效
- [ ] 三种发布策略对比表 + 选型话术
- [ ] "schema 兼容"边界思考写入笔记
- [ ] `git add . && git commit -m "day20: full-link gray release"`

---
[← Day 19](day19-对账体系与资损防控.md) | [本月目录](README.md) | [Day 21 · 第三周复盘 →](day21-第三周复盘.md)
