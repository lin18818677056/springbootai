# Day 23 · 结构型模式：适配器防腐层落地

> **今日目标**：掌握四个高频结构型模式（适配器/装饰器/代理/门面）；把第三方支付 SDK 封装进防腐适配器（README 验收标准⑤）；说清适配器/装饰器/代理三者区别——面试高频辨析题。
> **时长**：模式学习 2h / PayAdapter 落地 2.5h / 辨析整理 0.5h
> **今日产出**：WechatPayAdapter（SDK 零渗透）+ 缓存装饰器 + 三模式辨析卡

## 1. 知识地图

```
结构型模式回答："对象怎么组合成更大的结构？"——四个高频 + 三个眼熟即可：

① 适配器（Adapter）：把"别人的接口"翻译成"我的接口"——结构型之首
   商城三处落地（全是旧知识的新名字！）：
   · day05 PaymentGateway 接口 + WechatPay 实现 = 驱动侧适配器理论
   · day11 PromotionGatewayImpl = 三方营销 SDK 的防腐翻译
   · 今天 WechatPayAdapter = 支付渠道 SDK 的封装（README 验收⑤的适配器位）
   判句："外面说的语言我听不懂，适配器当翻译——且翻译只住在这层"

② 装饰器（Decorator）：同接口、动态叠加职责——缓存/日志/重试
   与适配器辨析（面试必考）：适配器"改接口"（B 形状→A 形状），
   装饰器"保接口"（A 形状→更强的 A）——形状变不变是分水岭
   实战：OrderRepository 的缓存装饰器——CachingOrderRepository implements OrderRepository
        包一层 Redis，领域层毫不知情（透明增强）

③ 代理（Proxy）：控制访问——AOP 的本质（M3 day12 动态代理的呼应）
   静态代理手写 vs JDK 动态代理（接口+Proxy.newProxyInstance）vs CGLIB（子类字节码）
   Spring AOP 的事务/@Async/@Cacheable 全是代理在暗中工作
   与装饰器辨析：结构几乎一样，意图不同——装饰器"加功能"，代理"控制访问"（权限/远程/懒加载）

④ 门面（Facade）：为一堆子系统提供统一入口——Controller 就是领域门面
   下单门面 createOrder() 内部排：查商品快照→算价→锁库存→生成单——对外一个方法
   与六边形呼应：驱动端口接口（CreateOrderUseCase）就是领域的门面契约

眼熟即可：组合（树形结构——菜单/权限树）、桥接（两维度分离——消息类型×发送渠道）、
          享元（共享细粒度对象——Integer 缓存池/字符串常量池）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Adapter | 适配器（接口翻译） |
| Decorator | 装饰器（同接口叠职责） |
| Proxy | 代理（控制访问，AOP 本质） |
| Facade | 门面（统一入口） |
| Transparent Enhancement | 透明增强（装饰器对调用方不可见） |
| SDK Isolation | SDK 隔离（防腐的落点） |

## 3. 动手实操：WechatPayAdapter + 缓存装饰器

```java
// ① 适配器防腐：第三方支付 SDK 封装（adapter 层——day05 PayChannel 的实现位）
package com.mall.order.adapter.pay;
import com.github.wxpay.sdk.WXPay;            // 三方 SDK：只许住在这层
import com.github.wxpay.sdk.WXPayUtil;

@Component
public class WechatPayChannel implements PayChannel {          // 实现 day22 的渠道接口
    private final WXPay wxPay;                                  // SDK 客户端封死在这
    public WechatPayChannel(WXPayConfig cfg) { this.wxPay = new WXPay(cfg); }

    @Override
    public PayResult pay(PayOrder order) {                      // 领域语言进
        try {
            var req = toWxReq(order);                           // ① 领域模型→SDK 请求
            var resp = wxPay.unifiedOrder(req);                 // ② SDK 调用+异常边界
            return toDomain(resp);                              // ③ SDK 响应→领域模型
        } catch (Exception e) {
            throw new PayChannelException("WECHAT", "下单失败", e);  // SDK 异常翻译成本域异常
        }
    }
    private Map<String,String> toWxReq(PayOrder o) {            // 字段级翻译（含金额分转换）
        return Map.of("body", o.subject(), "out_trade_no", o.orderNo(),
                      "total_fee", String.valueOf(o.amount().cents()),   // Money 分→微信分
                      "notify_url", o.notifyUrl(), "trade_type", "APP");
    }
    private PayResult toDomain(Map<String,String> r) {          // 返回码语义校正
        if (!"SUCCESS".equals(r.get("return_code")) || !"SUCCESS".equals(r.get("result_code")))
            return PayResult.fail(r.getOrDefault("err_code_des", "unknown"));
        return PayResult.ok(r.get("prepay_id"));
    }
}
// 验收红线（day11 机械手法）：grep 领域层+应用层 目录 → 不得出现 com.github.wxpay

// ② 装饰器：透明缓存（infra 层——领域零感知）
class CachingOrderRepository implements OrderRepository {      // 同接口=装饰器的身份证
    private final OrderRepository delegate;                    // 被装饰者
    private final Cache<String, Order> redis;
    public Order find(OrderId id) {
        var hit = redis.get(id.value());
        if (hit != null) return hit;                           // 增强点：缓存
        var fresh = delegate.find(id);                         // 原逻辑委托
        redis.put(id.value(), fresh);
        return fresh;
    }
    public void save(Order o) { delegate.save(o); redis.invalidate(o.id().value()); }  // 写后失效
}
// 装配自由：new CachingOrderRepository(new MybatisOrderRepository(mapper))
// ——要不要缓存变成配置问题，领域层一行不改（装饰器的透明性红利）
```

```powershell
# 验证三连：
cd D:\mywork\springbootai\practice-projects\02-microservice-mall\mall
Get-ChildItem -Recurse -Include *.java -Path .\mall-order\src\main\java\com\mall\order\domain,.\mall-order\src\main\java\com\mall\order\application |
  Select-String -Pattern 'wxpay|alipay.sdk' | Format-Table LineNumber, Line -AutoSize -Wrap
# ① 领域/应用层 SDK import 计数 = 0（防腐红线）
# ② 缓存装饰器 A/B：同一 orderId 二次查询走 Redis（日志观察 delegate 只调一次）
# ③ SDK 超时演练：断网点微信 → PayChannelException 带渠道名（不是裸 IOException 渗透）
git add . ; git commit -m "day23: structural patterns"
```

## 4. 面试连接

**Q：适配器、装饰器、代理怎么区分？**
> 一句话分水岭：适配器改接口（把微信 SDK 的 Map 进 Map 出翻译成我们的 PayOrder 进 PayResult 出——形状变了）；装饰器和代理都保接口，区别在意图——装饰器是加职责（缓存装饰器给 Repository 透明叠加 Redis），代理是控制访问（事务代理控制"进方法前开事务出方法后提交"）。落地检验：我在商城三处用适配器（支付/营销 SDK 防腐+网关端口），一处装饰器（仓储缓存，领域零感知可插拔），代理靠 Spring AOP（@Transactional 幕后就是 JDK 动态代理，M3 手写过原理）。能说清"为什么这里用这个不用那个"，比背 UML 图高一个档次。

**Q：说说你对 AOP 代理的理解？**
> AOP 的本质是动态代理：Spring 为 Bean 生成代理对象，把横切逻辑（事务/日志/缓存）织入方法前后。两个实操级认知：①JDK 动态代理基于接口（Proxy.newProxyInstance+InvocationHandler，M3 手写过），CGLIB 基于子类字节码，Spring Boot 默认 CGLIB（proxyTargetClass=true）——所以 final 方法切不到；②自调用失效的根因：this.pay() 走的是原始对象不是代理，@Transactional 不生效——解法是拆类或 AopContext.currentProxy()。这段我把它连回结构型模式：AOP 就是"容器在装配期替你叠代理/装饰器"，模式是原理，框架是产品化。

**Q：门面模式和六边形架构的端口什么关系？**
> 门面是"为复杂子系统提供简化的统一入口"，六边形的驱动端口（CreateOrderUseCase 接口）就是领域对外的门面契约——外部世界（HTTP/MQ 适配器）只见一个清爽的用例方法，不见内部要协调的仓储/网关/事件发布。差别在立场：门面模式站在"简化调用方"的视角，可以出现在任何层（我们支付对前端的聚合接口也是门面）；端口站在"领域主权"的视角——接口由领域定义，适配器来实现，依赖方向被锁死。我的用法：对外 HTTP 聚合接口用门面思维做减法，对内的六边形端口用依赖倒置锁方向——两个模式在不同层面各司其职。

## 5. 今日验收清单

- [ ] WechatPayAdapter 落地（翻译/异常翻译/SDK 零渗透 grep=0）
- [ ] CachingOrderRepository 装饰器 A/B 实验跑通
- [ ] 三模式辨析卡（改接口/加职责/控访问）
- [ ] AOP 代理自调用失效根因能讲
- [ ] `git add . && git commit -m "day23: adapter & decorator & proxy"`

---
[← Day 22](day22-创建型模式.md) | [本月目录](README.md) | [Day 24 · 行为型模式 →](day24-行为型模式.md)
