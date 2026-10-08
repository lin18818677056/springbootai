# Day 08 · 线程池源码：execute 的三步舞

> **今日目标**：白板写出 ThreadPoolExecutor 的 execute 三步流程；说清每个参数"什么时候生效、什么时候失效"；看懂 ctl 那个神秘的 int。
> **时长**：理论 1.5h / 实操 1h / 输出 0.5h
> **今日产出**：execute 三步白板图 + 参数失效场景清单

## 1. 知识地图（先讲人话）

```
线程池 = 餐厅排班（类比先立住）：
  corePoolSize   → 正式工编制（闲着也养着）
  workQueue      → 等位区（客人多了先排队）
  maximumPoolSize → 全店最大员工数（含临时工）
  keepAliveTime  → 临时工闲多久就辞退
  RejectedHandler → 满员还来的客人怎么办（拒绝的艺术）

execute 提交任务的"三步舞"（源码主线，必须白板级）：
  ① 正式工没招满 → 招一个新正式工直接干活
     (workerCountOf(c) < corePoolSize → addWorker(command, true))
  ② 正式工满了   → 任务去等位区排队
     (workQueue.offer(command))
  ③ 等位区也满了 → 招临时工；临时工也到顶 → 拒绝策略
     (addWorker(command, false) 失败 → reject)

⚠️ 三步的顺序反直觉（面试高频坑）：
  是"先排临时工"还是"先排队"？—— 答案：先排队！
  招临时工比排队更贵（新线程=新成本），所以池子优先用队列缓冲，
  撑不住了才扩人。这跟很多人背的"先扩到 max 再排队"正好相反。

ctl：一个 int 同时存两件事（源码里的精打细算）：
  高 3 位 = 线程池状态（RUNNING/SHUTDOWN/STOP/...）
  低 29 位 = 线程数量（约 5 亿上限，够用）
  runStateOf(c) 取状态 / workerCountOf(c) 取数量
  ——一个 AtomicInteger 搞定，CAS 一次改两个信息
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 大白话 |
|------|------|--------|
| ThreadPoolExecutor | 线程池 | 工人班组 + 等位区 + 辞退规则 |
| Core Pool | 核心线程 | 正式工（默认闲着也不裁） |
| Bounded Queue | 有界队列 | 有限长度的等位区（无界=撑爆风险） |
| RejectedExecutionHandler | 拒绝策略 | 满员时的四选一 |
| CAS (Compare-And-Swap) | 比较并交换 | 无锁改数字："没人动过我就改" |
| Worker | 工人对象 | 源码里的 Worker 类 = 线程 + 第一个任务 |

## 3. 动手实操

### 3.1 源码主线 20 行（JDK 25 里长这样）

```java
public void execute(Runnable command) {
    int c = ctl.get();                                    // 状态+数量 一锅端
    if (workerCountOf(c) < corePoolSize) {                // ① 正式工没满
        if (addWorker(command, true)) return;             //    招人直接干
        c = ctl.get();                                    //    没招到（并发竞争）重读
    }
    if (isRunning(c) && workQueue.offer(command)) {       // ② 排队
        int recheck = ctl.get();
        if (! isRunning(recheck) && remove(command))      //    排上队了池子却关了
            reject(command);
        else if (workerCountOf(recheck) == 0)             //    池子空了补个工人收尾
            addWorker(null, false);
    }
    else if (!addWorker(command, false))                  // ③ 队满招临时工
        reject(command);                                  //    临时工也满 → 拒绝
}
// 三个细节讲给面试官：①重读 ctl 防并发竞争 ②排队后二次校验
// ③"空池补工人"保证池子不因队列有货而全灭
```

### 3.2 参数失效场景清单（背下来，都是血泪）

```
① corePoolSize 懒创建：池子建好后不提交任务，一个线程都没有
   → 要预热：预提交几个空任务，或 allowCoreThreadTimeOut(false)
② 队列无界（new LinkedBlockingQueue<>() 默认 Integer.MAX_VALUE）：
   三步舞永远停在②，maximumPoolSize 和拒绝策略【永远不触发】
   ——这是 OOM 和"配了 max 却没生效"的经典事故
③ keepAliveTime 只管临时工（默认）：
   core 线程默认永生；设 allowCoreThreadTimeOut(true) 才会裁核心
④ Executors.newFixedThreadPool 的坑：队列无界（同②）
   newCachedThreadPool 的坑：max=Integer.MAX_VALUE（线程爆炸）
   ——阿里规约禁用 Executors 工厂不是玄学，是这两条
```

### 3.3 实验复现"max 不生效"（今天必做）

```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(
        2, 5, 60, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(),          // 无界！
        new ThreadPoolExecutor.AbortPolicy());
for (int i = 0; i < 100; i++) pool.submit(() -> Thread.sleep(1000));
// 预期：线程数停在 2，队列堆到 98——max=5 从未生效
// 把队列改成 new LinkedBlockingQueue<>(2) 再跑：线程涨到 5，
// 第 3 个溢出任务触发 AbortPolicy 抛异常——三步舞全走通了
System.out.println("活跃线程: " + pool.getActiveCount()
        + " | 队列: " + pool.getQueue().size());
```

### 3.4 思考题

拒绝策略怎么选？CallerRunsPolicy 为什么被戏称"让调用者自己干活的降压阀"？它有什么副作用？（提示：上游线程亲自执行→天然限流拖慢上游，但会占用 Tomcat/调用方线程，高并发下把慢传导给上游——适合能接受"变慢但绝不丢"的场景）

## 4. 面试连接

**Q：execute 的执行流程？**
> 三步舞：核心线程未满先招核心；满了入队；队满了扩到 max；再满走拒绝。我会主动补一句反直觉点：是先排队再扩线程，因为新建线程比排队贵。再补一刀：如果队列无界，后面两步永远不触发——我在实验里复现过 max 不生效。

**Q：ctl 一个 int 存两个值怎么做到的？**
> 位运算切分：高 3 位状态、低 29 位数量。好处是 CAS 一次原子改两个信息，不用加锁。这是"能省则省"的源码美学，M2 手写线程池时我抄过这个设计。

## 5. 今日验收清单

- [ ] execute 三步白板画一遍（含重读 ctl 的细节）
- [ ] 复现"无界队列下 max 失效"实验，两种队列对比
- [ ] 参数失效场景四条全部能举例
- [ ] `git add . && git commit -m "day16-08: tpe-execute"`
- [ ] 笔记：餐厅排班类比图（编制/等位区/临时工/拒绝）

---
[← Day 07](day07-第一周复盘.md) | [本月目录](README.md) | [Day 09 · 线程池调参实战 →](day09-线程池调参实战.md)
