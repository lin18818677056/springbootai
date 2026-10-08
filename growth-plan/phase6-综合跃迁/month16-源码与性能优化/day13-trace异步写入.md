# Day 13 · trace 异步写入：服务员别老往记账房跑

> **今日目标**：把 M15 定罪的"同步留痕"改成异步批量写入——主链路不等磁盘；同时守住底线：关键留痕（审批/退款）绝不丢。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：AsyncTraceWriter 落地 + 主链路增量实测（目标 <1ms）

## 1. 知识地图（先讲人话）

```
同步写的画像（M15 现状）：
  服务员每上一道菜 → 停下 → 跑去记账房登记 → 回来继续端菜
  菜好端，账难记：每趟 40ms，一桌五道菜白跑 200ms。

异步批量写（今天的改造）：
  服务员把小票丢进"待记账盒"（内存队列）→ 马上回去端菜；
  专职记账员（后台线程）攒一批小票，一次录入（批量写库）。
  主链路只花"丢小票"的时间：微秒级。

三个关键设计决策（不是随便一改，每条都有理由）：
  ① 队列必须有界（比如 1 万条）
     无界队列 = 记账员处理不过来时内存被小票撑爆（day08 的坑再现）
  ② 拒绝策略要分级
     普通轮次 trace：满了就丢（记个丢弃计数器，监控可见）
     高危留痕（审批创建/退款执行）：绕过队列直接同步写——
     "可以慢，不能丢"（M15 审批流的审计底线）
  ③ 批量落盘：攒 50 条或 200ms，先到先刷
     摊薄开销：一次 IO 写 50 条 ≈ 写 1 条的成本

背压（Backpressure）意识：
  队列持续涨 = 写入速度 < 产生速度 → 系统在"欠账"。
  三个选择：丢弃（可接受损失时）/ 阻塞生产者（拖慢主链路，
  等于又变回同步）/ 扩容写入端。监控队列深度就是监控"欠账本"。
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 大白话 |
|------|------|--------|
| Async Batch Write | 异步批量写 | 丢小票 + 记账员批量录入 |
| Bounded Queue | 有界队列 | 有限大小的待记账盒（防内存爆炸） |
| Backpressure | 背压 | 生产快于消费时的"反压"信号 |
| Graceful Degradation | 优雅降级 | 丢得起的部分丢了，关键部分保住 |
| Fire-and-Forget | 发后即忘 | 丢进队列就不管（前提：丢得起） |

## 3. 动手实操

### 3.1 AsyncTraceWriter（落地代码，60 行内）

```java
public class AsyncTraceWriter {
    private final BlockingQueue<Trace> queue =
            new ArrayBlockingQueue<>(10_000);                 // 有界！
    private final AtomicLong dropped = new AtomicLong();      // 丢弃计数（监控用）
    private final JdbcTemplate db;
    private final TraceWriter syncFallback;                   // 高危直写通道

    public AsyncTraceWriter(JdbcTemplate db, TraceWriter syncFallback) {
        this.db = db; this.syncFallback = syncFallback;
        startWorker();                                        // 一个专职记账员
    }

    /** 主链路唯一调用的方法：丢小票，微秒级返回 */
    public void save(Trace t) {
        if (t.isCritical()) {                                 // 审批/退款：可以慢不能丢
            syncFallback.saveSync(t); return;
        }
        if (!queue.offer(t)) dropped.incrementCount();        // 满了就丢+计数（不阻塞主链路）
    }

    private void startWorker() {
        Thread.ofVirtual().name("trace-flusher").start(() -> {  // 记账员也用虚拟线程
            List<Trace> batch = new ArrayList<>(50);
            while (true) {
                try {
                    Trace first = queue.poll(200, TimeUnit.MILLISECONDS);  // 最多等 200ms
                    if (first == null) continue;
                    batch.add(first);
                    queue.drainTo(batch, 49);                 // 一把抓走，最多 49 条
                    db.batchUpdate("INSERT INTO agent_trace VALUES(?,?,?,?)",
                            batch.stream().map(this::toParams).toList());  // 批量落盘
                } catch (Exception e) { log.error("flush fail", e); }
                finally { batch.clear(); }
            }
        });
    }
    public long droppedCount() { return dropped.get(); }
}
// 三条设计决策全部体现在代码里：有界队列/分级拒绝/批量 50 条或 200ms
```

### 3.2 复测（台账优化点②的复测格）

```powershell
# 改造后重跑 day06 的 Arthas trace：
trace com.demo.agent.AsyncTraceWriter save '#cost > 1'
# 预期：save 耗时从 45ms 级 → 微秒级（offer 一下就返回）
# 主链路 trace AgentLoop runRound：留痕增量 < 1ms ✓
# 盯 dropped 计数器：压测期间 ___ 次（有丢弃但可控，写进报告）
```

### 3.3 故障小实验：记账员罢工

```
实验：把批量落盘改成抛异常（模拟数据库故障）
预期观察：① 主链路不受影响（还在正常答题）——异步化的容错红利
        ② 队列持续涨 → 满 → dropped 计数飙升 → 告警
结论：异步写把"存储故障"和"服务故障"解耦了——
     记账房塌了，餐厅还能营业（但账本在漏，监控必须叫醒你）
```

### 3.4 思考题

为什么高危留痕不走异步队列？如果给高危也排队，最坏会发生什么？（提示：审批单是法律和审计凭证，"提交成功"承诺必须落库兑现；排队最坏 = 服务崩溃时队列里未落盘的审批单全丢——用户以为审批了，实际没有，比慢可怕得多）

## 4. 面试连接

**Q：异步化改造怎么保证不丢关键数据？**
> 分级：先给数据分"丢得起/丢不起"两级——普通 trace 走有界队列可丢弃（丢弃计数器监控），审批、退款等高危留痕绕过队列同步直写。再配批量落盘（50 条或 200ms）摊薄 IO 成本。实测主链路增量从 45ms 降到 1ms 以内，磁盘故障时主服务不受影响。

**Q：队列满了怎么办？**
> 我的三选一：丢（可接受损失+计数器告警）、堵（offer 改 put，等于背压传导给上游）、扩（记账员加消费并行度）。我选丢+监控，因为 trace 的价值密度低于服务可用性——但这个决策要写进设计文档，因为"丢什么"是业务判断不是技术判断。

## 5. 今日验收清单

- [ ] AsyncTraceWriter 落地：有界队列+批量+分级拒绝三要素齐全
- [ ] 复测：主链路增量 <1ms，dropped 计数器工作
- [ ] 记账员罢工实验完成，能讲"解耦"结论
- [ ] `git add . && git commit -m "day16-13: async-trace"`
- [ ] 笔记：画出"丢小票+记账员"架构图（含高危直写支路）

---
[← Day 12](day12-CompletableFuture编排.md) | [本月目录](README.md) | [Day 14 · 第二周复盘 →](day14-第二周复盘.md)
