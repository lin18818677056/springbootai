# Day 05 · Gateway 网关：路由断言、过滤器链与统一鉴权

> **今日目标**：落地 mall-gateway（step02 的网关模块），打通 gateway→order 全链路；写 AuthGlobalFilter 实现 JWT 统一鉴权 + X-Gray 灰度头透传；讲清"网关 vs Nginx"的职责分层。
> **时长**：路由配置 1.5h / 过滤器链 2h / 鉴权与灰度头 1.5h
> **今日产出**：统一入口网关 + JWT 鉴权过滤器 + 灰度头透传验证

## 1. 知识地图

```
网关的定位（微服务的"总闸门"）：
  外部流量 → Nginx（静态/负载均衡）→ Gateway（动态路由+鉴权+限流+灰度）→ 微服务
  ——职责分层：Nginx 管"连接"（HTTP 层转发，不懂业务），
              Gateway 管"业务流量"（懂路由语义+能编程式干预请求）

Gateway 一次请求的生命周期（WebFlux 响应式）：
  请求 → HttpWebHandlerAdapter
       → DispatcherHandler（找 HandlerMapping）
       → RoutePredicateHandlerMapping：按 Predicate 匹配出唯一 Route
       → FilteringWebHandler：组装 GlobalFilter + 该路由的 GatewayFilter
         （按 getOrder() 排序成责任链，逐个 invoke）
       → 每个 Filter 分 pre（转发前）和 post（响应回程）两段——像洋葱圈
       → Netty Routing Filter：真正发请求（lb:// → 从 Nacos 拉实例 → LoadBalancer 挑一个）

三件套：
  Route     = id + uri(lb://mall-order) + predicates[] + filters[]
  Predicate 路由断言：Path/Method/Header/Query/Time/Weight——"什么请求走这条路"
  Filter    过滤器：StripPrefix/AddRequestHeader/Retry/CircuitBreaker——"路上做点什么"

lb://mall-order 的含义（踩坑点！step02 坑4）：
  lb = LoadBalancer：不是写死 ip:port，而是"服务名 → 注册中心查实例 → 客户端负载均衡"
  ⚠ gateway 必须加 spring-cloud-starter-loadbalancer 依赖，否则 503
    （报错形态：Unable to find instance for mall-order——认脸！）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Route | 路由（网关的基本工作单元） |
| Predicate | 断言（匹配条件，Java 8 Predicate 语义） |
| GlobalFilter | 全局过滤器（作用于所有路由） |
| GatewayFilter | 路由过滤器（只作用于配置它的路由） |
| StripPrefix | 前缀剥离（/api/order/xxx → /xxx） |
| lb:// | 负载均衡协议头（服务名寻址） |
| Reactor / WebFlux | 响应式编程（网关基于事件循环，少量线程扛高并发） |
| Forward / Rewrite | 转发与路径重写 |

## 3. 动手实操：统一入口与 JWT 鉴权

```java
// mall-gateway：AuthGlobalFilter（step02 落地，getOrder 控制执行次序）
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {
    private static final List<String> WHITE_LIST = List.of("/user/login", "/user/register", "/probe");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (WHITE_LIST.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);                       // 白名单直接放行
        }
        String token = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (token == null || !token.startsWith("Bearer ")) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();         // pre 段直接拦截
        }
        Claims claims = JwtUtil.parse(token.substring(7));       // 解析+校验签名/过期
        // 鉴权通过 → 把用户身份写进请求头，透传给下游服务（下游不再各自解析 JWT）
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header("X-User-Id", claims.getSubject())
                .header("X-Gray", String.valueOf(claims.get("gray", Boolean.class)))
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }
    @Override public int getOrder() { return -100; }   // 数值越小越靠前，鉴权必须最先
}
```

```yaml
# mall-gateway application.yml：
spring:
  cloud:
    gateway:
      routes:
        - id: order-route
          uri: lb://mall-order
          predicates: [ Path=/api/order/** ]
          filters: [ StripPrefix=1 ]          # /api/order/create → /create
        - id: user-route
          uri: lb://mall-user
          predicates: [ Path=/api/user/** ]
          filters: [ StripPrefix=1 ]
```

```powershell
# 全链路验证：
# ① 无 token：curl http://localhost:8080/api/order/probe/ping → 401
# ② 登录拿 token（mall-user /user/login 签发 JWT，gray=true 的测试账号）
# ③ 带 token：curl -H "Authorization: Bearer xxx" http://localhost:8080/api/order/probe/ping
#     → {"data":"order-pong"}；且 mall-order 日志能打印 X-User-Id 头
# ④ 灰度头验证：mall-order 探针接口返回收到的 X-Gray 值（day20 全链路灰度的地基）
# ⑤ 观察过滤器链：logging.level.org.springframework.cloud.gateway=DEBUG
#     → 控制台打印完整的 filter order 列表（亲眼看看责任链排序）
```

## 4. 面试连接

**Q：Gateway 和 Nginx 有什么区别？为什么两个都要？**
> Nginx 是七层反向代理：静态路由+极致性能（C+epoll），但改路由要 reload、不能编程式干预；Gateway 是 Java 生态的 API 网关：动态路由（服务名寻址+注册中心联动）+ 过滤器链能写代码做鉴权/灰度/限流。生产典型分层：Nginx 挡在最前（TLS 终止/静态资源/粗粒度负载均衡）→ Gateway 做业务流量治理。追问"为什么要拆两层"：性能敏感的连接层与业务敏感的路由层职责不同——用贵的东西只干它擅长的事。收尾："本质是南北向流量治理的分层设计，跟今天 Redis 前面挡 Nginx、后面挡 Gateway 的思路一致。"

**Q：过滤器链的 order 是怎么排的？pre 阶段抛异常会怎样？**
> GlobalFilter 和路由 GatewayFilter 合并后按 getOrder() 升序执行，pre 段正向、post 段回程逆序（洋葱模型）。pre 抛异常若不处理 → 客户端收到白板 500：规范做法是全局的 ErrorWebExceptionHandler 统一兜底转 Result 格式；鉴权这类"必须最先"的过滤器给负数 order（-100），业务过滤器放 0 以后。追问"Gateway 是 WebFlux 的，能用 ThreadLocal 传用户信息吗"：不能跨线程（响应式线程切换），必须用 exchange 的 header/attribute 传递——这就是为什么我写 X-User-Id 请求头而不是塞 ThreadLocal。（答出 ThreadLocal 陷阱 = 真用过 WebFlux）

## 5. 今日验收清单

- [ ] gateway 模块启动，三条路由全部转发成功
- [ ] 无 token 401 / 带 token 放行（截图）
- [ ] X-User-Id / X-Gray 透传下游验证
- [ ] 过滤器链 DEBUG 日志看过（能说出 order 排序规则）
- [ ] "Gateway vs Nginx"分层能讲清
- [ ] `git add . && git commit -m "day05: gateway & auth filter"`

---
[← Day 04](day04-Nacos配置中心.md) | [本月目录](README.md) | [Day 06 · OpenFeign与负载均衡 →](day06-OpenFeign与负载均衡.md)
