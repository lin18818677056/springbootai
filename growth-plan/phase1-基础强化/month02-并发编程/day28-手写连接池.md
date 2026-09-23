# Day 28 · 综合实战二：手写连接池（超时 / 健康检查 / 无泄漏）

> **今日目标**：本周实战第二弹。手写一个 80 行迷你连接池：阻塞借出 + 超时获取 + 归还校验 + 健康检查，压测验证"100 线程借还无死锁无泄漏"（M2 验收项），最后对照 HikariCP ConcurrentBag 讲差距。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：MiniConnPool（压测报告）+ 泄漏复现与检测 + HikariCP 差距清单

## 1. 知识地图

```
连接池的四个生命职责（也是四个并发考点）：

  ┌─────────────────────────────────────────────────────┐
  │ MiniConnPool                                        │
  │                                                     │
  │  idle    : BlockingQueue<Connection> ← 空闲连接停车场 │
  │  leased  : Set<Connection>（借出登记） ← 谁把车开走了  │
  │  all     : 总连接（上限控制）                          │
  └─────────────────────────────────────────────────────┘

  ① 借 borrow(timeout)：
     从 idle.poll(timeout) ——★带超时！拿不到快速失败（day11 tryLock 思想）
     借出登记 leased.add
  ② 用：业务自己拿去执行 SQL（超时控制靠池还是靠驱动？——池管借还，SQL 超时另说）
  ③ 还 release(conn)：
     健康检查 conn.isValid(1) —— 坏的丢弃重建，好的回 idle
     ★归还登记 leased.remove
  ④ 泄漏防线：
     借出登记的作用 = 定期巡检发现"借了 5 分钟没还的连接"（生产必配）

对比 HikariCP（面试必备一句）：
  ConcurrentBag 用 ThreadLocal<List> 缓存"上次用过的连接"实现无锁快路径
  + 窃取（steal）别的线程的列表 + SynchronousQueue 交接
  → 我的 BlockingQueue 版是有锁慢路径，但骨架思想一致
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Connection Pool | 连接池 | 建连贵，所以复用 |
| Borrow / Release | 借出/归还 | 池的两把核心操作 |
| Idle / Leased | 空闲/借出 | 两种状态集合 |
| Leak Detection | 泄漏检测 | 借出超时未还的巡检告警 |
| Health Check | 健康检查 | isValid / ping 验活 |
| ConcurrentBag | 并发袋子 | HikariCP 的核心容器 |

## 3. 动手实操

### 3.1 主菜：MiniConnPool（核心 80 行 + 压测）

```java
// MiniConnPool.java —— 迷你连接池（借/还/超时/验活/登记）
import java.sql.Connection;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;

public class MiniConnPool {
    private final int maxSize;
    private final ConcurrentLinkedQueue<Connection> idle = new ConcurrentLinkedQueue<>();
    private final Set<Connection> leased = ConcurrentHashMap.newKeySet(); // 借出登记
    private final Semaphore permits;                       // 总量阀门

    public MiniConnPool(int maxSize, ConnectionFactory factory) throws Exception {
        this.maxSize = maxSize;
        this.permits = new Semaphore(maxSize);
        for (int i = 0; i < maxSize; i++) idle.add(factory.create());  // 预热
    }

    /** 借连接：带超时，拿不到返回 null（快速失败，day11 tryLock 思想） */
    public Connection borrow(long timeoutMs) throws InterruptedException {
        if (!permits.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) return null;  // ① 总量阀门
        Connection conn = idle.poll();                          // ② 先取空闲
        if (conn == null) {
            conn = idle.poll(timeoutMs, TimeUnit.MILLISECONDS); // ③ 没有则限时等归还
            if (conn == null) { permits.release(); return null; }
        }
        leased.add(conn);                                       // ④ 借出登记（防泄漏的关键）
        return conn;
    }

    /** 还连接：验活 + 归还登记 */
    public void release(Connection conn) {
        if (conn == null || !leased.remove(conn))               // 不是我借出去的 → 拒收
            throw new IllegalStateException("非法归还（泄漏检测的第一现场）");
        leased.remove(conn);
        permits.release();
        try {
            if (conn.isValid(1)) idle.offer(conn);              // 好的回停车场
            else { System.err.println("坏连接丢弃"); conn.close(); /*重建略*/ }
        } catch (Exception e) { System.err.println("验活失败: " + e.getMessage()); }
    }

    public int leasedCount() { return leased.size(); }          // 巡检接口：借出未还数
    public interface ConnectionFactory { Connection create() throws Exception; }
}
```

```java
// PoolStressTest.java —— 100 线程 × 借还 1000 次：无死锁无泄漏（M2 验收）
import java.sql.Connection;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

public class PoolStressTest {
    public static void main(String[] args) throws Exception {
        // 模拟连接：用代理/匿名类太啰嗦，直接定义一个 FakeConnection 接口级别的演示
        MiniConnPool pool = new FakePoolBuilder().build(10);    // 10 个连接，见下方辅助类

        int threads = 100, loops = 1000;
        LongAdder ok = new LongAdder(), timeout = new LongAdder();
        CountDownLatch latch = new CountDownLatch(threads);
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        long begin = System.currentTimeMillis();
        for (int t = 0; t < threads; t++) {
            exec.execute(() -> {
                try {
                    for (int i = 0; i < loops; i++) {
                        Connection c = pool.borrow(500);        // 借，最多等 500ms
                        if (c == null) { timeout.increment(); continue; }
                        Thread.sleep(1);                        // 模拟执行 SQL
                        pool.release(c);                        // ★finally 级纪律：必还
                        ok.increment();
                    }
                } catch (Exception e) {
                    System.err.println("异常: " + e);            // 死锁会表现为整体卡住
                } finally { latch.countDown(); }
            });
        }
        boolean finished = latch.await(60, TimeUnit.SECONDS);   // 60 秒兜底：卡住=有死锁
        System.out.printf("完成=%s 成功=%d 超时=%d 泄漏(借出未还)=%d 耗时=%dms%n",
                finished, ok.sum(), timeout.sum(), pool.leasedCount(),
                System.currentTimeMillis() - begin);
        exec.shutdown();
        // 验收标准：finished=true（无死锁）+ 泄漏=0（无泄漏）+ 超时占比可解释
    }
}
// FakePoolBuilder：创建 MiniConnPool 并塞入 10 个假连接（实现 Connection 接口，
// isValid 返回 true，其余方法空实现——IDEA 里 Alt+Enter 一键生成即可）
```

### 3.2 实验②：泄漏复现与"登记巡检"抓现行

```java
// LeakScene.java —— 模拟业务忘了还：leased 登记让泄漏现形
import java.sql.Connection;

public class LeakScene {
    public static void main(String[] args) throws Exception {
        MiniConnPool pool = new FakePoolBuilder().build(3);     // 只有 3 个连接

        // 场景①：借了不还（业务代码 bug：没有 finally release）
        for (int i = 0; i < 3; i++) {
            Connection c = pool.borrow(100);
            System.out.println("借出 " + (i + 1) + " → " + (c != null));
        }
        System.out.println("当前借出未还 = " + pool.leasedCount() + "/3");
        // 第 4 次借出：500ms 拿不到 → null（连接耗干，正是 day12 PoolDemo 的现象）
        System.out.println("第4次借出 = " + pool.borrow(500));

        // 生产巡检逻辑：定时任务打印 leasedCount > 0 的告警 + 借出堆栈（登记集合理存堆栈）
        // HikariCP 的 leakDetectionThreshold 就是这个思路的工业化版本
    }
}
```

### 3.3 实验③：对照 HikariCP 的差距清单（面试弹药）

```text
我的 MiniConnPool vs HikariCP ConcurrentBag：

  □ 无锁快路径：Hikari 用 ThreadLocal<List<T>> 缓存"线程上次用过的连接"
    → 大多数借还是纯读自己 ThreadLocal，零锁零 CAS
  □ 窃取机制 steal：自己 ThreadLocal 没有了，偷别的线程缓存的
  □ 交接：SynchronousQueue 做线程间直接交接（无排队堆积）
  □ 泄漏检测：com.zaxxer.hikari.proxy 代理层记录借出堆栈，超时打印 WARN
  □ 健康检查：keepaliveTime 定期验活 + 连接最大寿命 maxLifetime 滚动重建
  → 面试话术："我手写过 BlockingQueue 骨架版，所以能看懂 ConcurrentBag
     每一处优化是在解决什么问题——ThreadLocal 缓存、窃取、交接三层递进。"
```

### 3.4 压测报告模板（填上你的数据）

```text
《MiniConnPool 压测报告》
  环境：JDK 25 / Windows 22H2 / 本机
  负载：100 线程 × 1000 次借还，池容量 10，单次"SQL"耗时 1ms
  结果：完成=true 成功=____ 超时=____ 泄漏=0 耗时=____ms
  泄漏场景：借 3 还 0 后第 4 次借出返回 null，leasedCount=3 ✓
  结论：无死锁（60s 兜底未触发）、无泄漏（登记巡检归零）
  Hikari 对照：慢在有锁排队，快路径方向 = ThreadLocal 缓存 + steal
```

## 4. 面试连接

**Q：让你设计一个连接池，考虑哪些点？**
> 四职责：① 总量控制（Semaphore/计数）；② 借还路径（空闲队列 + 超时快速失败）；③ 健康管理（验活、坏连接重建、最大寿命）；④ 泄漏防线（借出登记 + 巡检告警 + 非法归还拒绝）。然后主动对比 HikariCP 的 ConcurrentBag 三层优化——手写过的回答自带细节。

**Q：线上连接池耗尽了怎么排查？**
> 三步：① 监控看 active/idle/wait 指标确认耗尽；② 开 HikariCP 的 leakDetectionThreshold 抓"借了不还"的堆栈（我的 leased 登记集合同理）；③ 典型根因对号：慢 SQL 占住、异常路径没 finally 归还（day12 PoolDemo 复现过）、池小于并发突峰（day16 调参）。修复后加对拍压测回归。

**Q：为什么池大小不是越大越好？**
> DB 侧瓶颈：连接数越大，数据库上下文切换和锁竞争越重（参考 HikariCP 官方测试：池 10 左右常见最优，公式 connections = cores×2 + disks）。再接一句："和线程池一个道理——day16 我压测过线程数的拐点，连接池同理要压测。"两月知识互相印证。

## 5. 今日验收清单

- [ ] MiniConnPool 跑通 + PoolStressTest 报告成文（M2 验收项）
- [ ] LeakScene 复现耗干 + 巡检抓现行
- [ ] HikariCP 差距清单能脱稿讲三层优化
- [ ] 能讲"池大小为什么不是越大越好"
- [ ] `git add . && git commit -m "day28: mini connection pool"`

---
[← Day 27](day27-手写令牌桶限流器.md) | [本月目录](README.md) | [Day 29 · 综合演练与并发 50 问 →](day29-综合演练与并发50问.md)
