# Day 06 · OpenFeign 与负载均衡：声明式调用的原理与治理

> **今日目标**：mall-order 通过 OpenFeign 调 mall-product（step02 治理四件套落地）；讲清动态代理原理与 LoadBalancer 选实例机制；立下"重试铁律"——写操作禁重试。
> **时长**：接入 1.5h / 原理 2h / 超时重试治理 2h
> **今日产出**：ProductClient + fallbackFactory + 治理四件套配置 + 重试铁律笔记

## 1. 知识地图

```
声明式调用的演进：
  RestTemplate：手拼 url + 手解析 Result——样板代码多，url 散落
  OpenFeign：写个接口 + @FeignClient("mall-product") → 像调本地方法一样调远程
  本质：JDK 动态代理把"接口方法调用"翻译成"HTTP 请求"

OpenFeign 一次调用的完整链路（源码主线）：
  @FeignClient 接口 → 启动扫描（EnableFeignClients）注册 FeignClientFactoryBean
  → 首次调用生成代理对象：ReflectiveFeign → 每个方法一个 SynchronousMethodHandler
  → 调用链：参数编码（Encoder）→ RequestInterceptor 补头（透传 X-User-Id！）
           → LoadBalancer 拿服务名查实例列表 → 挑一个（轮询默认）
           → 委托底层 client（默认 HttpURLConnection / 可换 okhttp）
           → 响应解码（Decoder）→ 非 2xx 走 ErrorDecoder
  ——面试主线题：能从头到尾画出这条链 = 读过源码

超时与重试（治理四件套，step02 落地）：
  ① connect-timeout: 1000   连接超时 1s（连不上=机器没了，快速失败）
  ② read-timeout: 3000      读超时 3s（慢查询不能拖垮上游线程）
  ③ Retryer.NEVER_RETRY     默认禁重试（Feign 默认就是 NEVER，别手贱打开全局重试）
  ④ fallbackFactory 降级    抛异常时走兜底逻辑而非白板报错

重试铁律（为什么写操作禁重试）：
  超时 ≠ 失败！read-timeout 触发时，服务端可能已经执行成功（响应丢了）
  → POST 下单重试 = 重复下单（除非幂等）——"重试把一次故障放大成两次资损"
  规则：GET 可安全重试；写操作要么不重试、要么先做幂等（M5 幂等三板斧在这里接上）
  连锁放大：A→B→C 各层都重试 3 次 = 1 次故障变 27 次请求（重试风暴）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Declarative HTTP Client | 声明式 HTTP 客户端（接口即契约） |
| Dynamic Proxy | 动态代理（Feign 实现机制） |
| LoadBalancer | 客户端负载均衡（Spring Cloud 官方，替代 Ribbon） |
| Round Robin | 轮询（默认策略） |
| FallbackFactory | 降级工厂（能拿到 Throwable 的降级） |
| ErrorDecoder | 错误解码器（把 HTTP 错误翻译成业务异常） |
| RequestInterceptor | 请求拦截器（补头/透传上下文） |
| Retry Storm | 重试风暴（多层重试指数放大） |

## 3. 动手实操：order → product 声明式调用

```java
// mall-order：ProductClient（step02 落地）
@FeignClient(name = "mall-product", fallbackFactory = ProductClientFallbackFactory.class)
public interface ProductClient {
    @GetMapping("/product/{id}")
    Result<ProductDTO> getById(@PathVariable("id") Long id);

    @PostMapping("/product/stock/deduct")       // 写操作！
    Result<Boolean> deductStock(@RequestBody DeductRequest req);
}

@Component
public class ProductClientFallbackFactory implements FallbackFactory<ProductClient> {
    @Override
    public ProductClient create(Throwable cause) {          // 能拿到异常：是超时？4xx？5xx？
        return new ProductClient() {
            public Result<ProductDTO> getById(Long id) {
                log.warn("product fallback, id={}, cause={}", id, cause.getMessage());
                return Result.fail(503, "商品服务繁忙，稍后重试");   // 快速失败+友好提示
            }
            public Result<Boolean> deductStock(DeductRequest req) {
                return Result.fail(503, "扣减失败");         // 写操作降级=拒绝，绝不偷偷吞掉
            }
        };
    }
}
```

```yaml
# 治理四件套配置（mall-order application.yml）：
spring:
  cloud:
    openfeign:
      client:
        config:
          default:
            connect-timeout: 1000
            read-timeout: 3000
            logger-level: basic
# 重试显式声明为不重试（默认即 NEVER_RETRY，写出来是给 review 的人看的）：
# Retryer: NEVER_RETRY（代码里 new Retryer.Default 之前先想清楚铁律！）
```

```powershell
# 验证实验：
# ① 正常调用：mall-order /order/create 内部调 ProductClient.getById → 通
# ② 降级生效：docker stop product 容器 → 调用走 fallbackFactory（观察 warn 日志）
#     → 等注册中心剔除期间，LoadBalancer 还会把请求打到死实例 → fallback 兜住
# ③ 超时生效：product 加个 Thread.sleep(5000) 的慢接口 → 3s 后超时触发 fallback
# ④ 写操作验证：deductStock 在降级路径必须"拒绝"而不是返回成功（面试考点！）
# ⑤ 上下文透传：ProductClient 调用时带 X-User-Id 头（RequestInterceptor 从网关头里拿）
```

## 4. 面试连接

**Q：OpenFeign 的原理？为什么写个接口就能发 HTTP 请求？**
> 启动期 EnableFeignClients 扫描 @FeignClient 接口，注册为 FactoryBean；注入时产出 JDK 动态代理，方法调用被路由到 SynchronousMethodHandler：按注解元数据把参数编码成 HTTP 报文 → 经 RequestInterceptor 补头 → LoadBalancer 按服务名从注册中心取实例并挑选 → 底层 client 发请求 → 响应解码/错误解码。追问"和 Dubbo 调用有什么本质区别"：Feign 是 HTTP+JSON（通用但序列化开销大），Dubbo 是 TCP+定制协议（性能高+更丰富路由能力）——接口契约思想同构，传输层与序列化选型不同。收尾："我用 Feign 从不裸用——治理四件套（超时/禁重试/fallbackFactory/日志级别）是工程模板的一部分。"

**Q：微服务间调用超时和重试你们怎么设计的？**
> 超时：上游超时必须小于下游的（网关 5s > order 调 product 3s > product 调 db 1s），否则上游超时后下游还在白干活——线程池被打爆。重试：只对幂等操作重试（GET/带幂等键的写），网络异常与超时可重试，业务异常绝不重试；重试配指数退避+次数上限；跨层重试要错开或收敛到一层。真实教训："我们有次下单慢，有人在 Feign 层打开了全局重试，结果高峰期重复下单——超时不等于失败，服务端可能已成功。这就是我立'写操作禁重试、先幂等再考虑重试'铁律的原因，正好接上 M5 幂等三板斧。"

## 5. 今日验收清单

- [ ] ProductClient 调用打通（order→product）
- [ ] fallbackFactory 生效（停 product 观察 warn 日志）
- [ ] 慢接口超时实验（3s 触发降级）
- [ ] 写操作降级=拒绝（不吞错）
- [ ] X-User-Id 透传到 product
- [ ] 重试铁律能复述（超时≠失败 + 重试风暴）
- [ ] `git add . && git commit -m "day06: openfeign & lb"`

---
[← Day 05](day05-Gateway网关.md) | [本月目录](README.md) | [Day 07 · 第一周复盘 →](day07-第一周复盘.md)
