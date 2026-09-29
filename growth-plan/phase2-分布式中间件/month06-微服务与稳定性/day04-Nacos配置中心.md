# Day 04 · Nacos 配置中心：长轮询推送与动态线程池

> **今日目标**：接入 Nacos 配置中心，实现配置热更新 1s 内生效，手写"动态线程池"（改配置不重启调整核心参数），讲清配置长轮询原理——并与 RocketMQ 长轮询对照。
> **时长**：接入 1.5h / 动态线程池 2h / 原理对照 1.5h
> **今日产出**：动态线程池组件（可运行）+ 双长轮询对照笔记

## 1. 知识地图

```
配置中心长轮询（Long Polling）原理：
  客户端：启动后立即拉一次全量配置 + 每次拉取时携带"当前配置的 MD5 值"发起长轮询
        服务端 hold 住请求（默认 29.5s），期间配置变更（MD5 不匹配）→ 立即返回变更的 dataId
        没变更 → 29.5s 超时返回空，客户端重新发起（循环）
  服务端：配置写入 → 触发 MD5 对比 → 有变化的 dataId 立即唤醒对应长轮询请求
  ——为什么不用"定时轮询"？变更感知延迟 = 轮询间隔（秒级以上）+ 无效请求多
  为什么不用"纯推送"（长连接）？1.x 时代避免服务端维护海量长连接的复杂度——长轮询是
  "拉的简单 + 推的实时"折中（与 RocketMQ 消费长轮询同构！）

双长轮询对照表（跨月连线，面试好料）：
  维度       | Nacos 配置长轮询            | RocketMQ 消费长轮询
  客户端行为 | 挂 29.5s 带 MD5 等 MD5 变化 | 挂 5s 等新消息
  服务端唤醒 | 配置写入时唤醒              | 消息到达时唤醒
  超时返回   | 空（重新发起）              | 空（重新发起）
  共同思想  │ 客户端挂起 + 服务端有数据即唤醒 + 超时循环——把"轮询的简单"和"推送的实时"焊在一起

配置灰度发布：
  Nacos 控制台按 IP 灰度：先推给指定实例验证 → 观察无误再全量
  （应用侧配合：@RefreshScope 的 Bean 拿到新值；未灰度实例仍是旧值——天然 A/B）

命名空间与分组（隔离三板斧）：
  Namespace：环境隔离（dev/test/prod 各一个）
  Group：业务域隔离（DEFAULT_GROUP / MALL_GROUP）
  dataId：具体配置文件（mall-order.yaml / mall-common.yaml 共享配置）
  配置优先级：命令行 > 环境变量 > 应用主配置 > shared-configs > 默认值
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Config Listener | 配置监听器（客户端注册 dataId 监听） |
| MD5 Check | MD5 校验（长轮询的变更感知依据） |
| @RefreshScope | 刷新作用域（配置变更时 Bean 重建） |
| Shared Config | 共享配置（多服务公共配置抽离） |
| Dynamic Thread Pool | 动态线程池（运行时调参，不重启） |
| Gray Config | 配置灰度（按 IP 小流量先验证） |
| Namespace / Group / DataId | 配置三维坐标 |

## 3. 动手实操：动态线程池组件

```java
// mall-common：DynamicThreadPool（配置热更新 + 参数可运行时修改）
// 原理：@RefreshScope 在配置变更时重建 Bean——但线程池对象若被别处引用就是旧对象！
// 所以动态线程池的标准做法：监听器模式，配置变更事件里调 setCorePoolSize 等 setter
@RefreshScope
@Component
public class DynamicThreadPool extends ThreadPoolExecutor {
    public DynamicThreadPool(@Value("${order.pool.core:8}") int core,
                             @Value("${order.pool.max:16}") int max,
                             @Value("${order.pool.queue:1000}") int queue) {
        super(core, max, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(queue),
              new ThreadFactoryBuilder().setNameFormat("order-pool-%d").build(),
              new ThreadPoolExecutor.CallerRunsPolicy());   // 拒绝策略也要可配置（day13 讲过CallerRuns的削峰效果）
    }
    // 配置变更监听：Nacos Listener 收到新值 → setCorePoolSize/setMaximumPoolSize
    // ⚠ LinkedBlockingQueue 容量不可动态改——要可变队列需自定义 ResizableCapacityQueue
    //   （手写一个：把 capacity 去掉 final，加 setCapacity 并遍历唤醒——day 的手写彩蛋）
}
// 配置探测接口：@GetMapping("/probe/pool") 返回 core/max/queue/activeCount
// 验证：Nacos 控制台改 order.pool.core=8→32 → 探针接口 1s 内反映新值（截图）
```

```yaml
# mall-order 的 Nacos 配置（控制台创建 dataId=mall-order.yaml，Group=MALL_GROUP）：
order:
  pool:
    core: 8
    max: 16
    queue: 1000
# bootstrap 等价物（Boot 3 用 spring.config.import）：
# spring.config.import=optional:nacos:mall-order.yaml
# spring.cloud.nacos.config.shared-configs[0].data-id=mall-common.yaml (refresh: true)
```

## 4. 面试连接

**Q：配置中心的动态刷新原理？为什么 1 秒内生效？**
> 长轮询机制：客户端带着配置 MD5 发起长轮询，服务端 hold 29.5s；配置写入时服务端比对 MD5 发现变化，立即唤醒返回变更 dataId，客户端再拉取最新配置并触发 @RefreshScope 的 Bean 重建/监听器回调——全链路只有一次往返，所以 1s 内生效。补充实践深度："我们动态线程池不依赖 @RefreshScope 重建（旧引用问题），而是监听回调里调线程池 setter——Bean 重建方案对无状态配置够用，对有状态组件必须用 setter 热更。"

**Q：动态线程池为什么要手写可变队列？**
> JDK 的 LinkedBlockingQueue 容量是 final，运行时改不了；而"扩容"场景里往往瓶颈恰恰是队列上限（核心数上调没用，任务全被拒绝）。解法是自定义 ResizableCapacityLinkedBlockingQueue：把 capacity 从 final 改为 volatile 可写，setCapacity 时遍历清空被阻塞的 put 操作。进阶观点："美团动态线程池方案的核心就这一处硬骨头+参数 setters+监控上报三件套——把'改配置'变成'改参数'，把'重启'变成'刷新'。"（能讲出 final→volatile 这个细节=读过源码或真实现过）

## 5. 今日验收清单

- [ ] 配置中心接入，shared-configs 生效
- [ ] 配置变更 1s 生效（探针截图）
- [ ] 动态线程池组件运行（core 热更验证）
- [ ] 双长轮询对照表完成（Nacos vs RocketMQ）
- [ ] "@RefreshScope 重建 vs setter 热更"的区别能讲
- [ ] 可变队列手写（或讲清原理）
- [ ] `git add . && git commit -m "day04: config center"`

---
[← Day 03](day03-Nacos注册中心.md) | [本月目录](README.md) | [Day 05 · Gateway网关 →](day05-Gateway网关.md)
