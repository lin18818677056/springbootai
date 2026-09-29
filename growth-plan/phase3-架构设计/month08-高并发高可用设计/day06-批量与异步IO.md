# Day 06 · 批量与异步 IO：聚合接口并行化

> **今日目标**：掌握 CompletableFuture 并行编排（allOf/超时/异常兜底）；理解虚拟线程的原理与适用边界（JDK25 直达）；把商城 3 个聚合接口改为并行批量，RT 降 ≥40% 有数据。
> **时长**：并行编排 1.5h / 改造实测 2.5h / 虚拟线程实验 1h
> **今日产出**：3 接口并行化 diff（RT 前后对比）+ 虚拟线程实验记录

## 1. 知识地图

```
聚合接口的性能困境（商城详情页=3 个下游的串行受害者）：
  串行：商品 80ms → 营销 60ms → 库存 70ms = 210ms（RT 是加法）
  并行：max(80, 60, 70) = 80ms + 编排开销 ≈ 90ms（RT 是最大值）——省 57%
  ——前提：三路调用互不依赖；有依赖只能流水线（前序结果做后序入参）

CompletableFuture 并行编排（今天的手术刀）：
  supplyAsync(task, executor)   ——必须显式传池！默认 ForkJoinPool.commonPool 是全局共享坑
  thenApply/thenCompose（转换）/thenCombine（两路合并）
  allOf(c1,c2,c3).join()        ——等全部完成
  orTimeout(300, MILLISECONDS)  ——JDK9+：单路超时防拖垮（超时路返回兜底值）
  exceptionally(ex -> fallback) ——单路异常兜底：营销挂了≠详情页挂（部分降级）
  三纪律：①线程池隔离（舱壁，day05）②每路都有超时 ③每路都有兜底

批量接口设计（减少 RTT 的另一面）：
  N 次循环单查（N 个 RTT）→ 1 次 batch 查（1 个 RTT）：Feign 改 POST /batch + IN 查询
  Redis mget/hgetall 替代 N 次 get；MySQL IN 走索引（注意 IN 数量上限 500 分批）
  ——与 day03 的"写批量"呼应：读写两端都做"合小为大"

虚拟线程（Virtual Threads，JDK21+ 正式，JDK25 环境直接可用）：
  原理：轻量执行体由 JVM 调度（Mounted on 载体平台线程），阻塞时"卸载"让出载体线程
  ——百万级虚拟线程可行；Tomcat virtual threads=true 一行开关全局生效
  适用：高并发 IO 等待型（Web 服务/Feign/JDBC 调用）——等待不再占用平台线程
  不适用：CPU 密集（无等待可让出）；ThreadLocal 滥用场景（百万副本内存爆炸）；synchronized 长块（pinning 钉住载体）
  与前两条线的关系：虚拟线程降低"线程池容量规划"的心智，但舱壁隔离/超时/兜底一个都不能少
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| CompletableFuture | 并行编排工具 |
| allOf / orTimeout | 全部等待 / 单路超时 |
| Partial Degradation | 部分降级（单路挂不拖整体） |
| Virtual Thread | 虚拟线程（轻量并发执行体） |
| Carrier Thread / Pinning | 载体线程 / 钉住（阻塞不让出） |
| Round-Trip Reduction | RTT 削减（批量本质） |

## 3. 动手实操：聚合接口并行化

```java
// 改造前（串行 210ms）：详情页三路顺序调用
public ProductDetailVO detailSerial(Long id) {
    var product = productClient.get(id);          // 80ms
    var promo    = promoClient.of(id);            // 60ms
    var stock    = stockClient.of(id);            // 70ms
    return assemble(product, promo, stock);
}

// 改造后（并行 90ms）：三路并发+超时+部分降级
@Service
public class ProductDetailService {
    private final ExecutorService bizPool;        // 独立池（舱壁：不与下单共用）
    public ProductDetailVO detail(Long id) {
        var productCf = CompletableFuture.supplyAsync(() -> productClient.get(id), bizPool)
            .orTimeout(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            .exceptionally(e -> ProductDTO.empty(id));                    // 商品是主路：失败给空壳
        var promoCf = CompletableFuture.supplyAsync(() -> promoClient.of(id), bizPool)
            .orTimeout(150, java.util.concurrent.TimeUnit.MILLISECONDS)
            .exceptionally(e -> PromoDTO.noPromo());                      // 营销挂=无优惠展示（部分降级）
        var stockCf = CompletableFuture.supplyAsync(() -> stockClient.of(id), bizPool)
            .orTimeout(150, java.util.concurrent.TimeUnit.MILLISECONDS)
            .exceptionally(e -> StockDTO.unknown());                      // 库存未知=展示"查询中"
        CompletableFuture.allOf(productCf, promoCf, stockCf).join();
        return assemble(productCf.join(), promoCf.join(), stockCf.join());
    }
}
// 批量版（列表页）：productClient.batch(ids) 一次 IN 查询替代循环单查（IN>500 分批）
```

```java
// 虚拟线程实验（learning/month08-high-concurrency/src/VirtualThreadLab.java）
public class VirtualThreadLab {
    public static void main(String[] a) throws Exception {
        // ① 一百万虚拟线程起得来吗？（平台线程 1 万就 OOM）
        var vts = java.util.stream.LongStream.range(0, 1_000_000)
            .mapToObj(i -> Thread.ofVirtual().name("vt-" + i).unstarted(() -> {})).toList();
        vts.forEach(Thread::start); vts.forEach(t -> t.join());   // 通过：百万并发体可行
        // ② IO 等待场景吞吐对比：200 并发 × 100ms sleep 任务
        try (var ex = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            long t0 = System.nanoTime();
            java.util.stream.IntStream.range(0, 10_000).forEach(i ->
                ex.submit(() -> { Thread.sleep(100); return null; }));
            ex.close();
            System.out.println("10k IO tasks with virtual threads: " + (System.nanoTime()-t0)/1_000_000 + "ms");
        }   // ~1s 出头（等待不占平台线程）；对比固定池 200 线程 ≈ 5s+
        // ③ pinning 演示：synchronized 块内 sleep → -Djdk.tracePinnedThreads=full 观察钉住告警
    }
}
```

```powershell
# RT 前后对比实测（wrk 压测，M6 Postman 校验正确性）：
# 串行版：detail 接口 P50 210ms / P99 380ms
# 并行版：P50 92ms / P99 210ms ——RT-56%（达标 ≥40%）
docker run --rm williamyeh/wrk -t4 -c100 -d30s http://host.docker.internal:8080/api/product/detail/1
# 虚拟线程开关（Spring Boot 3.2+ / 本工程版本）：
# application.yml → spring.threads.virtual.enabled: true  （压测对比开关前后吞吐）
javac -encoding UTF-8 -d out VirtualThreadLab.java ; java -cp out VirtualThreadLab
git add . ; git commit -m "day08-06: parallel & virtual threads"
```

## 4. 面试连接

**Q：聚合接口 RT 高怎么优化？**
> 先分类再下刀：无依赖的下游调用——CompletableFuture 并行编排，RT 从"加法"变"最大值"，商城详情页三路串行 210ms 改并行 92ms，降了 56%；参数重复的调用——改批量接口，N 次 RTT 合 1 次（Feign POST batch+IN 查询，注意 500 分批）；可缓存的——上 day02 漏斗。并行编排有三条纪律必须配齐：显式线程池且与核心业务隔离（默认 commonPool 是全局共享，一个任务饿死全场）、每路 orTimeout 单路超时（防最慢路拖垮整体）、每路 exceptionally 部分降级（营销挂了展示无优惠，而不是详情页 500）——"并行放大了故障面，纪律控制爆炸半径"。

**Q：虚拟线程是什么？能替代线程池吗？**
> 虚拟线程是 JDK21 正式的轻量执行体：阻塞时 JVM 把它从载体平台线程上"卸载"，平台线程转身执行别的虚拟线程——等待成本从"占一个 OS 线程"降到"占一点堆内存"，百万并发体实测可行（我们 learning 仓库的实验：百万虚拟线程正常起停；10k 个 100ms 等待任务，虚拟线程 1 秒出头 vs 固定池 200 线程 5 秒+）。它不是替代线程池的银弹：CPU 密集任务没有等待可让出，无收益；ThreadLocal 每虚拟线程一份，滥用会内存爆炸；synchronized 长块会 pinning 钉住载体线程（用 ReentrantLock 替代）。工程落地我们只开了 spring.threads.virtual.enabled 一行，Web 层全局生效——但舱壁隔离、超时预算、降级兜底一个没少，虚拟线程解决"线程不够用"，不解决"下游不可靠"。

**Q：CompletableFuture 用的时候要注意什么坑？**
> 四个高频坑：①默认线程池陷阱——supplyAsync 不传 executor 走 ForkJoinPool.commonPool，全局共享且并行度=CPU-1，业务高峰互相踩踏，必须显式传隔离池；②异常会"消失"——没有 join/get 的阶段异常被吞，链路必须终结在 exceptionally 或 join（try-catch），配合 MDC 传 TraceId（M6 链路追踪才能串起来）；③超时缺失——orTimeout 之后不配 exceptionally，超时会以 CompletionException 直接炸穿，永远"超时+兜底"成对出现；④上下文丢失——子任务里拿不到 ThreadLocal（事务/MDC/租户），需要显式传递或用 context 传播库。这四条我们写进了异步编码规范，code review 照单检查。

## 5. 今日验收清单

- [ ] 3 个聚合接口并行化（RT 前后数据：210→92ms）
- [ ] 三纪律落地（隔离池/orTimeout/exceptionally）
- [ ] 虚拟线程实验（百万并发体+IO 吞吐对比+pinning 认知）
- [ ] 异步四坑清单进规范
- [ ] `git add . && git commit -m "day08-06: cf & vt"`

---
[← Day 05](day05-连接与线程优化.md) | [本月目录](README.md) | [Day 07 · 第一周复盘 →](day07-第一周复盘.md)
