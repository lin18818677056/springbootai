# Day 12 · 并发工具三剑客（CountDownLatch / Semaphore / CyclicBarrier）

> **今日目标**：三个最常用的 AQS 共享模式应用：倒计时门闩（等齐开工）、信号量（控制并发量）、循环栅栏（集齐放行）。每个都配真实业务场景实现，最后能一张表说清选型。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：三个工具的业务场景 Demo + 选型决策表

## 1. 知识地图

```
三剑客一张图（都是 AQS 共享模式，state 语义不同）：

CountDownLatch 倒计时门闩          Semaphore 信号量              CyclicBarrier 循环栅栏
──────────────────────          ────────────────────          ─────────────────────
state = 剩余计数                  state = 剩余许可               state = 未到达人数
countDown() 减一                  acquire() 许可-1（可阻塞）      await() 到齐人数+1
await() 数到 0 才放行             release() 许可+1               集齐 parties 全放行
一次性（数完不能重置）             无限次获取/释放                 可复用（自动重置）
"等事件"（事件 vs 线程）           "限流量"                       "等线程"（人数固定）

场景对号入座：
  并行初始化：主线程等 4 个模块全部加载完   → CountDownLatch(4)
  限流/资源池：DB 连接池最多 10 并发        → Semaphore(10)
  分批计算：每批 5 个线程算完再汇总         → CyclicBarrier(5, 汇总回调)
  接口多下游聚合：等 3 个 RPC 都返回        → CountDownLatch(3)（day18 会被
                                              CompletableFuture 替代，知道渊源）

CyclicBarrier 独门特性：barrierAction 回调
  最后一个到达的线程执行回调（开新桌/触发汇总/打印阶段统计）
  ⚠ 回调在"到达线程"里执行，别写重活（会拖住其他等待者）

Phaser（了解即可）：更灵活的多阶段栅栏（动态注册/分阶段推进），
  用得少，面试一句"多阶段的 CyclicBarrier"即可
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| CountDownLatch | 倒计时门闩 | 一次性，等 N 个事件完成 |
| Semaphore | 信号量 | 限流/资源池，acquire/release |
| CyclicBarrier | 循环栅栏 | 可复用，固定人数集齐放行 |
| Latch / Barrier | 门闩/栅栏 | 等事件 / 等队友 |
| Barrier Action | 栅栏动作 | 集齐后由最后到达线程执行的回调 |
| Fairness（Semaphore） | 公平性 | 可设 fair=true 防插队 |

## 3. 动手实操

### 3.1 实验①：CountDownLatch——并行初始化（等齐开工）

```java
// InitDemo.java —— 主线程等 4 个模块全部初始化完成
import java.util.concurrent.CountDownLatch;

public class InitDemo {
    public static void main(String[] args) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(4);
        String[] modules = {"配置中心", "注册中心", "缓存预热", "连接池"};

        for (String m : modules) {
            new Thread(() -> {
                try { Thread.sleep((long) (Math.random() * 1000)); } catch (Exception e) {}
                System.out.println(m + " 初始化完成");
                latch.countDown();                     // 一个模块完成，计数-1
            }).start();
        }
        latch.await();                                 // 数到 0 才放行（可设超时重载）
        System.out.println("★ 全部就绪，应用启动完成");
    }
}
// 要点：latch 的计数对应"事件"（不关心是哪个线程 countDown）
```

### 3.2 实验②：Semaphore——模拟 DB 连接池限流

```java
// PoolDemo.java —— 10 个许可，30 个线程抢，观察排队
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;

public class PoolDemo {
    public static void main(String[] args) {
        Semaphore pool = new Semaphore(10);            // 连接池容量 10
        LongAdder concurrent = new LongAdder();
        LongAdder peak = new LongAdder();

        for (int i = 0; i < 30; i++) {
            new Thread(() -> {
                try {
                    pool.acquire();                    // 拿许可（拿不到排队）
                    concurrent.increment();
                    peak.accumulate(Math.max(peak.sum(), concurrent.sum()));  // 记录峰值
                    Thread.sleep(200);                 // 模拟用连接
                    concurrent.decrement();
                } catch (Exception e) {
                } finally {
                    pool.release();                    // 必须归还！否则许可泄漏
                }
            }, "T" + i).start();
        }
    }
}
// 观察点：把 release 挪出 finally（异常时不归还）→ 池子逐渐耗干 = "连接泄漏"
// 这就是生产连接池泄漏的原理级复现
```

### 3.3 实验③：CyclicBarrier——分批计算与汇总回调

```java
// BatchDemo.java —— 每 5 个线程一批，批内集齐后统一触发"汇总"
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;

public class BatchDemo {
    public static void main(String[] args) {
        CyclicBarrier barrier = new CyclicBarrier(5, () ->
            System.out.println("★ 本批 5 人集齐，触发汇总（由最后到达者执行）"));

        for (int i = 1; i <= 10; i++) {                // 两批：1-5 和 6-10
            final int id = i;
            new Thread(() -> {
                try { Thread.sleep((long) (Math.random() * 800)); } catch (Exception e) {}
                System.out.println("worker-" + id + " 计算完成，等待队友");
                try { barrier.await(); } catch (Exception e) {}   // BrokenBarrierException 防御
                System.out.println("worker-" + id + " 进入下一阶段");
            }).start();
        }
    }
}
// 观察点：第二批复用同一个 barrier（数完自动重置）→ "Cyclic"的含义
// 对比：CountDownLatch 数完就废了，重置只能 new 一个新的
```

### 3.4 选型决策表（记进笔记）

| 需求 | 选型 | 关键 API |
|------|------|---------|
| 等 N 个"事件"完成再继续 | CountDownLatch | await / countDown |
| 限制并发访问资源的数量 | Semaphore | acquire / release |
| 固定人数轮流集合（多轮） | CyclicBarrier | await + 回调 |
| 等待/唤醒更复杂的多阶段协作 | Phaser | register / arriveAndAdvance |

## 4. 面试连接

**Q：CountDownLatch 和 CyclicBarrier 的区别？**
> 三个维度：① CountDownLatch 等事件（计数与线程解耦），CyclicBarrier 等固定人数线程；② 前者一次性，后者可复用；③ 后者有集齐回调。一句话："Latch 是门闩，Barrier 是集结号"。

**Q：Semaphore 怎么实现限流？原理？**
> AQS 共享模式，state=剩余许可：acquire 时 state-1（不足则入队挂起），release 时 state+1 并唤醒等待者。公平模式可防插队。生产扩展：动态调整许可数（Redis 分布式限流的本地对照实现）。

**Q：CyclicBarrier 的回调在哪个线程执行？要注意什么？**
> 由"最后一个到达"的线程执行。注意回调里别做重活/别再 await（会拖住甚至死锁所有等待者）；回调抛异常会破坏本轮栅栏（其他线程收到 BrokenBarrierException）。

## 5. 今日验收清单

- [ ] 三个 Demo 全部跑通，各自记录一段真实输出
- [ ] PoolDemo 复现"不归还导致耗干"
- [ ] 选型决策表默写
- [ ] 能说清三者 state 语义差异（AQS 视角）
- [ ] `git add . && git commit -m "day12: latch-semaphore-barrier"`

---
[← Day 11](day11-ReentrantLock与Condition.md) | [本月目录](README.md) | [Day 13 · 手写迷你 AQS →](day13-手写迷你AQS.md)
