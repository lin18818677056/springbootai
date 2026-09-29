# Day 10 · 熔断降级：断路器状态机与自动恢复

> **今日目标**：理解断路器三态状态机（CLOSED/OPEN/HALF_OPEN）；用 Sentinel 三种熔断策略实测"熔断→试探→恢复"全过程；Feign+Sentinel 整合让 day06 的 fallbackFactory 升级为自动熔断。
> **时长**：原理 1.5h / 实验实战 2.5h / Hystrix 对比 1h
> **今日产出**：熔断实验报告（三策略对比）+ Feign 整合熔断链路

## 1. 知识地图

```
为什么需要熔断（day06 留的问题升级）：
  fallback 只是"失败后的礼貌拒绝"——但失败请求还是会一次次打过去
  下游已经病了，每个请求都要白等超时（3s）→ 上游线程池被拖垮 → 雪崩蔓延
  熔断 = 发现下游不对劲就直接跳闸：后续请求不再发出，秒回失败——
  "保护自己+给下游喘息"，是雪崩链的截止阀

断路器三态状态机（必背+必画）：
  CLOSED（闭合=正常放行） --失败率/慢调用率超阈值--> OPEN（跳闸=直接拒绝）
  OPEN --静默等待 resetTimeout--> HALF_OPEN（半开=试探）
  HALF_OPEN --1个试探请求成功--> CLOSED（恢复）
  HALF_OPEN --试探请求失败--> OPEN（继续熔断）
  ——类比电路空开：电流过大跳闸，冷却后自动试合闸，故障仍在再跳

Sentinel 三种熔断策略（统计窗口内）：
  ① 慢调用比例：RT>maxRT 的请求占比超 threshold → 熔断（最常用！下游变慢是最常见的病）
     参数：maxRT（判定慢的阈值）/比例阈值/熔断时长/最小请求数/统计时长
  ② 异常比例：异常占比超阈值 → 熔断（下游报错型故障）
  ③ 异常数：异常个数超阈值 → 熔断（低流量场景：比例没意义时用绝对数）
  ⚠ 最小请求数（minRequestAmount）防误伤：窗口内只有 3 个请求挂 1 个=33% 比例很高，
    但样本太小不可信——样本量不足不熔断（统计学的置信度思想）

Feign + Sentinel 整合（day06 fallbackFactory 的升级）：
  feign.sentinel.enabled=true → Feign 的每个方法自动变成 Sentinel 资源
  熔断 OPEN 时：请求直接进 fallbackFactory（连 HTTP 都不发）——快速失败
  与 day06 的区别：没有熔断时 fallback 每次都"真请求→失败→fallback"（慢）；
  有熔断后"熔断期秒进 fallback"（快）——降级路径从 3s 变 0ms

Hystrix 对比（面试怀旧题）：
  Hystrix（停更）：线程池隔离（重但彻底）/ 信号量隔离；固定窗口统计
  Sentinel：并发线程数限流实现"轻量信号量隔离"；滑动窗口统计；规则动态推送+控制台强
  结论：新项目 Sentinel，Hystrix 思想（舱壁隔离/断路器）仍是面试通用语言
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Circuit Breaker | 断路器/熔断器 |
| CLOSED / OPEN / HALF-OPEN | 三态状态机（闭合/跳闸/半开） |
| Slow Call Ratio | 慢调用比例 |
| Exception Ratio / Count | 异常比例/异常数 |
| Min Request Amount | 最小请求数（防小样本误伤） |
| Recovery Timeout | 熔断时长（静默期） |
| Probe Request | 试探请求（半开期放行的那个） |
| Bulkhead Isolation | 舱壁隔离（线程池/信号量隔离） |
| Cascade Failure / Snowball | 级联故障/雪崩 |

## 3. 动手实操：三策略熔断实验 + Feign 整合

```yaml
# mall-order：开启 Feign 的 Sentinel 整合（fallbackFactory 无需改代码！）
feign:
  sentinel:
    enabled: true
# ProductClient 已有 fallbackFactory（day06）——熔断后自动秒进降级
```

```powershell
# 实验准备：mall-product 加一个可控慢接口 /product/slow?ms=5000
# 全链路：Gateway → order(/order/detail) → Feign 调 product /product/slow

# 实验一：慢调用比例熔断（主力实验）
#   DegradeRule: 资源 GET:http://mall-product/product/slow
#     maxRT=1000ms, 比例=0.5, 熔断时长=10s, 最小请求=5, 统计时长=10s(默认1000ms要改)
#   压测 → 观察：
#   t0-t3s  请求真发出去，3s 超时进 fallbackFactory（慢失败，响应~3s）
#   t3s+    慢调用比例超 50% → OPEN：响应瞬间变快（秒回降级）——熔断生效的标志！
#   t13s+   HALF_OPEN 放一个试探 → product 已修复则 CLOSED（恢复）
#   观察手段：压测工具看响应时间曲线"3s→0s"的突变；Dashboard 熔断资源标红

# 实验二：异常比例熔断
#   product 加 /product/boom 30% 概率抛异常 → 异常比例=0.2 触发 → 观察 OPEN

# 实验三：最小请求数防误伤
#   minRequestAmount=20，手动点 3 个请求全失败 → 不熔断（样本不足）
#   → 压力加起来后立即熔断——理解"统计置信度"

# 实验四：熔断时长 vs 下游恢复
#   熔断时长设 5s，但 product 停机 30s → 观察"半开试探失败→反复熔断"
#   → 结论：熔断时长应 ≥ 下游预期恢复时间（熔断参数不是拍脑袋）
```

```java
// 面试手写级：极简断路器（15 行讲清状态机精髓）
public class MiniCircuitBreaker {
    enum State { CLOSED, OPEN, HALF_OPEN }
    private volatile State state = State.CLOSED;
    private final int threshold; private final long resetTimeoutMs;
    private final AtomicInteger failures = new AtomicInteger();
    private volatile long openedAt;
    public MiniCircuitBreaker(int threshold, long resetTimeoutMs) { this.threshold = threshold; this.resetTimeoutMs = resetTimeoutMs; }
    public synchronized boolean allowRequest() {
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAt > resetTimeoutMs) { state = State.HALF_OPEN; return true; } // 试探
            return false;                                   // 熔断期：秒拒
        }
        return true;
    }
    public synchronized void record(boolean success) {
        if (!success) { if (failures.incrementAndGet() >= threshold && state == State.CLOSED) { state = State.OPEN; openedAt = System.currentTimeMillis(); } }
        else if (state == State.HALF_OPEN) { state = State.CLOSED; failures.set(0); }   // 试探成功→恢复
    }
}
```

## 4. 面试连接

**Q：讲讲熔断器的原理？三种状态怎么流转？**
> 断路器是一个带状态机的代理：CLOSED 正常放行并统计失败/慢调用比例，超阈值跳 OPEN——后续请求不再发出直接拒绝，保护自己（不再白等超时）也给下游喘息；OPEN 静默 resetTimeout 后进 HALF_OPEN，放一个试探请求：成功则回 CLOSED 恢复，失败则回 OPEN 继续熔断。加分细节："两个参数最容易配错——最小请求数太小会被小样本误伤（3 个失败 1 个就 33%），熔断时长比下游恢复时间短会导致反复试探空转。我的实验数据：maxRT=1s、比例 0.5、minRequest=5 时，熔断触发后接口响应从 3s 秒变 0ms——这个'响应时间断崖'就是熔断生效的直观证据。"

**Q：Hystrix 和 Sentinel 怎么选？线程池隔离和信号量隔离的区别？**
> Hystrix 停更了，新项目选 Sentinel（规则动态化+控制台+滑动窗口），但 Hystrix 的思想仍是面试语言。隔离方式：线程池隔离=每个依赖独立线程池，故障不传染但线程切换开销大（Tomcat 200 线程够开几个池？）；信号量隔离=只是计数器限制并发，轻量无切换，但超时要靠自己配（不能 interrupt 别人的超时）。Sentinel 用"并发线程数流控"实现了轻量信号量隔离语义。选型建议："高频小接口信号量够用；重 IO 易超时的依赖（调第三方）才值得线程池隔离——隔离粒度是成本与安全的平衡，不是越多越好。"

## 5. 今日验收清单

- [ ] 三态状态机手画并能讲出流转条件
- [ ] 慢调用熔断实验：观察到"3s→0ms 响应断崖"（截图）
- [ ] 异常比例 + 最小请求数实验完成
- [ ] Feign+Sentinel 整合，熔断期秒进 fallbackFactory
- [ ] MiniCircuitBreaker 手写跑通
- [ ] 熔断时长 ≥ 下游恢复时间的结论有实验支撑
- [ ] `git add . && git commit -m "day10: circuit breaker"`

---
[← Day 09](day09-Sentinel流控规则.md) | [本月目录](README.md) | [Day 11 · 热点与系统保护 →](day11-热点与系统保护.md)
