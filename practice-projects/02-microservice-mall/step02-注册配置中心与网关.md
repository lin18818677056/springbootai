# Step 2：注册配置中心与网关（Nacos + Gateway + OpenFeign）

> **本步目标**：接入 Nacos 注册与配置中心、Gateway 网关（路由+统一鉴权+灰度头透传）、OpenFeign 服务间调用治理（超时/重试/降级），打通完整跨服务链路。

## 前置术语

| 英文 | 中文 |
|------|------|
| Service Registry | 服务注册中心 |
| Configuration Center | 配置中心 |
| API Gateway | API 网关 |
| Declarative HTTP Client | 声明式 HTTP 客户端（OpenFeign） |
| Load Balancing | 负载均衡 |
| JWT (JSON Web Token) | JSON Web 令牌 |

## 1. Nacos 接入

```yaml
# mall-order/src/main/resources/application.yml
spring:
  application:
    name: mall-order
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
        namespace: dev                     # 环境隔离：dev/test/prod 各一个 namespace
      config:
        server-addr: 127.0.0.1:8848
        file-extension: yaml
        shared-configs:                    # 公共配置：数据源/redis/log 抽到共享
          - data-id: mall-common.yaml
            refresh: true
  config:
    import: optional:nacos:${spring.application.name}.yaml
```

**配置热更新实战**（验证 refresh 生效）：

```java
@RestController
@RefreshScope
public class ConfigProbeController {
    @Value("${order.pay-limit:10000}")
    private BigDecimal payLimit;           // Nacos 改值 → 1s 内生效，无需重启
    @GetMapping("/probe/pay-limit")
    public String probe() { return String.valueOf(payLimit); }
}
```

## 2. Gateway 网关

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: order-route
              uri: lb://mall-order            # lb = 从注册中心负载均衡
              predicates:
                - Path=/api/order/**
              filters:
                - StripPrefix=1
                - AddGrayHeader=priority      # 自定义：灰度标记（见下）
            - id: product-route
              uri: lb://mall-product
              predicates: [ "Path=/api/product/**" ]
              filters: [ "StripPrefix=1" ]
```

**全局鉴权过滤器**（JWT 校验 + 用户上下文透传）：

```java
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {
    private static final List<String> WHITE_LIST = List.of("/api/auth/login", "/actuator/**");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().toString();
        if (WHITE_LIST.stream().anyMatch(path::startsWith)) return chain.filter(exchange);

        String token = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        Claims claims = jwtUtil.parse(token);                    // 无效会抛 401
        // 解析出的用户身份放入请求头传给下游（下游不再重复解析 JWT）
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header("X-User-Id", claims.getSubject())
                .header("X-Gray", String.valueOf(claims.get("gray", Boolean.class)))
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }
    @Override public int getOrder() { return -100; }
}
```

> **X-Gray 灰度头**：登录时按规则给用户打灰度标记，网关透传 → 各服务负载均衡器按标记路由到灰度实例，实现"一次标记、全链路灰度"。

## 3. OpenFeign 服务间调用治理

```java
@FeignClient(name = "mall-product",
             configuration = FeignDefaultConfig.class,
             fallbackFactory = ProductFallbackFactory.class)   // 降级必须带原因
public interface ProductFacade {
    @PostMapping("/inner/stock/lock")
    Result<List<StockLockResult>> lockStocks(@RequestBody List<LockStockCmd> cmds);
}

@Component
public class ProductFallbackFactory implements FallbackFactory<ProductFacade> {
    @Override
    public ProductFacade create(Throwable cause) {
        return cmds -> {   // 降级：锁定失败统一返回失败原因，绝不静默吞掉
            log.warn("product service degraded: {}", cause.getMessage());
            return Result.fail(5030, "库存服务繁忙，请稍后重试");
        };
    }
}
```

**治理四件套配置**（每个服务统一，防"重试风暴"）：

```yaml
spring:
  cloud:
    openfeign:
      client:
        config:
          default:
            connect-timeout: 1000     # 连接 1s
            read-timeout: 3000        # 读取 3s（按下游 P99×2 校准）
            retryer: none             # 写操作禁用自动重试！幂等才允许重试
            logger-level: basic
```

> **重试铁律**：POST 类非幂等接口禁用框架自动重试；需要重试就改造下游幂等（幂等键），在业务层显式重试 + 退避。

## 4. 全链路验证清单

- [ ] 5 个服务全部注册到 Nacos（控制台可见实例）
- [ ] Nacos 配置修改 → @RefreshScope 热更新生效
- [ ] 网关统一鉴权：无 token 401，白名单放行，X-User-Id 正确透传到下游日志
- [ ] 灰度链路：带灰度标记的请求全部命中灰度实例（日志验证）
- [ ] 断掉 mall-product，下单接口返回友好降级文案（非 500）

## 常见坑

1. **lb:// 不生效**：缺 `spring-cloud-starter-loadbalancer` 依赖（新版本不再默认带 Ribbon）。
2. **配置优先级混乱**：牢记 命令行 > 环境变量 > 应用配置 > 共享配置 > 默认。
3. **网关全局过滤器顺序**：鉴权 Order 必须 < 路由转发，且异步链里不能阻塞（禁止 `.block()`）。
4. **Feign 传 header 丢失**：需要 RequestInterceptor 把 X-User-Id 透传给下游。

> 完成后进入 `step03-分布式事务与最终一致性.md`。
