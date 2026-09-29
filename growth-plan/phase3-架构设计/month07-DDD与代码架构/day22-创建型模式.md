# Day 22 · 创建型模式：对象怎么生

> **今日目标**：掌握单例（枚举/双检锁）、工厂（简单/方法/抽象）、建造者四件；支付渠道工厂消灭 if-else（README 验收标准⑤的创建侧）；理解模式与 DDD Factory 的关系。
> **时长**：模式学习 2h / 支付渠道改造 2.5h / 面试整理 0.5h
> **今日产出**：枚举单例反射实验 + PayChannelFactory 改造 diff

## 1. 知识地图

```
创建型模式回答一个问题："new 的责任交给谁？"——把创建逻辑收敛、可替换、可控制

① 单例（Singleton）：全局唯一实例——配置中心客户端/ID 生成器
   双检锁（DCL）：volatile 防指令重排 + 两次判空 + synchronized——会写但要背 3 个坑
   枚举单例：JVM 保证唯一+天然防反射/反序列化破坏——《Effective Java》首选
   破坏实验（今天做）：反射 setAccessible 调私有构造 → DCL 出第二个实例，枚举直接炸 IllegalArgumentException
   Spring 视角：容器默认单例（@Component），业务代码很少手写单例——知道什么时候"别用"

② 工厂（Factory）：创建逻辑集中——"new 什么、怎么 new"只有一个地方知道
   简单工厂：一个类+switch 返回产品——够用但加渠道要改它（OCP 半开）
   工厂方法：每渠道一个工厂类——加渠道加类不改旧码（OCP 全开）
   抽象工厂：一族产品一起造（支付渠道+ refunds 渠道成套）——商城暂不需要，会说不用
   ★ 与 DDD 的连线：day19 的 Order.place() 就是"领域工厂"——模式是语法，DDD 是语义

③ 建造者（Builder）：多参数复杂对象的"命名参数"——链式装配可读性
   适用：参数≥5 个且有可选参数（OrderQuery/ES 查询条件）
   JDK/框架里的它：StringBuilder/Lombok @Builder/OkHttp.Request.Builder
   反面：什么都 Builder 是仪式感——3 个参数直接构造函数

支付渠道的创建侧改造（今天主线，day24 策略侧接力）：
  重构前：PayService.pay() 里 switch("wechat")…switch("alipay")…——加渠道改主流程
  重构后：PayChannelFactory.get(channelId) → WechatPayChannel 实例（day23 适配器封 SDK）
  Spring 落法：Map<String,PayChannel> 注入（容器自动收集所有实现）——连工厂类都省了
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Singleton | 单例（全局唯一实例） |
| DCL (Double-Checked Locking) | 双检锁 |
| Factory Method | 工厂方法 |
| Abstract Factory | 抽象工厂（成族创建） |
| Builder | 建造者（链式装配） |
| Open-Closed Principle | 开闭原则（加渠道不改旧码） |

## 3. 动手实操：反射破坏实验 + 渠道工厂

```java
// ① 单例反射破坏实验（learning/month07-ddd-architecture/src/SingletonLab.java）
class DclSingleton {
    private static volatile DclSingleton inst;
    private DclSingleton() { if (inst != null) throw new IllegalStateException("already"); }
    public static DclSingleton get() {
        if (inst == null) synchronized (DclSingleton.class) {
            if (inst == null) inst = new DclSingleton();   // volatile 保证不半初始化
        }
        return inst;
    }
}
enum EnumSingleton { INSTANCE; }                            // 枚举：JVM 级唯一
public class SingletonLab {
    public static void main(String[] a) throws Exception {
        var c = DclSingleton.class.getDeclaredConstructor(); c.setAccessible(true);
        try { c.newInstance(); System.out.println("DCL 被反射攻破（虽有守卫——try/finally 时序绕过）"); }
        catch (Exception e) { System.out.println("DCL 守卫生效: " + e.getCause()); }
        var e2 = EnumSingleton.class.getDeclaredConstructors()[0]; e2.setAccessible(true);
        try { e2.newInstance("x"); } catch (IllegalArgumentException ex) {
            System.out.println("枚举防反射: Cannot reflectively create enum objects");  // JVM 官方拦截
        }
        // 结论：需要手写单例时用枚举；Spring 环境下交给容器
    }
}

// ② 支付渠道工厂（消灭创建侧 if-else；行为多态 day24 接力）
public interface PayChannel { PayResult pay(PayOrder order); String channelId(); }
@Component
public class WechatPayChannel implements PayChannel { /* day23 适配器封装 SDK */ }
@Component
public class AlipayChannel  implements PayChannel { /* 同上 */ }

@Component
public class PayChannelFactory {
    private final Map<String, PayChannel> channels;          // Spring 注入所有实现
    public PayChannelFactory(List<PayChannel> list) {
        this.channels = list.stream().collect(java.util.stream.Collectors.toMap(PayChannel::channelId, c -> c));
    }
    public PayChannel get(String channelId) {
        PayChannel c = channels.get(channelId);
        if (c == null) throw new IllegalArgumentException("unknown channel: " + channelId);
        return c;                                             // 加新渠道=加一个 @Component，本类零改动（OCP）
    }
}
```

```powershell
# 实验与改造验证：
cd D:\mywork\springbootai\learning\month07-ddd-architecture
javac -encoding UTF-8 -d out src\SingletonLab.java ; java -cp out SingletonLab
# 支付改造 diff 前后对照：
# ① switch 行数：重构前 PayService 40 行 switch → 重构后 3 行工厂调用
# ② 新增渠道演练：写一个 MockChannel @Component → 不改任何旧码即接入（OCP 验证）
cd D:\mywork\springbootai\practice-projects\02-microservice-mall\mall ; .\gradlew.bat :mall-pay:build
git add . ; git commit -m "day22: creational patterns"
```

## 4. 面试连接

**Q：单例有几种写法？什么方式最好？**
> 主流两种：双检锁（volatile+两次判空+synchronized，要点是 volatile 防止"分配内存→初始化→赋值引用"重排序导致别的线程拿到半初始化对象）和静态内部类（利用类加载机制天然线程安全）。最优是枚举：JVM 保证实例唯一，且天然防反射与反序列化破坏——我做过实验，反射 newInstance 枚举会直接被 JVM 以 IllegalArgumentException 拦截，而 DCL 即使加了构造守卫也可能被时序绕过。但工程上更重要的判断是"什么时候别手写"：Spring 容器默认单例且线程安全策略由我们控制，业务代码 90% 的单例需求交给 @Component 就行，手写单例留给无容器场景（SDK/工具库）。

**Q：工厂模式解决什么问题？什么时候用简单工厂/工厂方法？**
> 工厂把"创建什么、怎么创建"的知识收敛到一处，调用方依赖产品抽象而非具体类。选择看变化频率：渠道就三种且稳定，简单工厂（一个类+Map 路由）足够——过度分层是负担；渠道会频繁新增（接银联/云闪付），用"Spring 注入 List 收集实现"的工厂方法式打法，加渠道只加 @Component，工厂零改动，这是 OCP 在创建侧的落地。抽象工厂只在"一族产品要成套替换"时用（换支付服务商要渠道+退款+对账单一起换），我们暂用不上——我会在面试里主动说"抽象工厂我判断过，当前场景不需要"，这比硬套更体现设计能力。顺带连线：DDD 的聚合工厂（day19 的 Order.place）是这个思想在领域层的语义化——创建逻辑本身是业务规则时，工厂住进领域。

**Q：建造者模式什么时候用？和构造函数怎么选？**
> 判据是"参数数量+可选性"：参数 ≥5 且存在可选参数时用建造者——链式调用自带参数名，可读性完胜位置参数构造函数（new OrderQuery(uid, null, null, "PAID", null, 1, 20) 谁知道中间 null 是什么）。我们 ES 查询条件和 OrderQuery 都用（Lombok @Builder 降低样板代码）。三个参数以内的必选构造直接构造函数，硬上 Builder 是仪式感。进阶点：建造者还能做"构建时校验"（build() 里检查必填组合）和不可变对象的友好装配——这与 day15 值对象不可变是配套打法：Builder 管"装配期可读"，final 管"装配后不可变"。

## 5. 今日验收清单

- [ ] 反射破坏实验跑通（DCL 守卫 vs 枚举 JVM 拦截）
- [ ] PayChannelFactory 落地（Map 注入+OCP 演练）
- [ ] 三种工厂的选用判据说清
- [ ] Builder 适用判据+不可变配套打法能讲
- [ ] `git add . && git commit -m "day22: singleton & factory & builder"`

---
[← Day 21](day21-第三周复盘.md) | [本月目录](README.md) | [Day 23 · 结构型模式 →](day23-结构型模式.md)
