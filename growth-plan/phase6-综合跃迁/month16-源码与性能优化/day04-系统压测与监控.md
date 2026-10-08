# Day 04 · 系统压测与监控：给 Agent 服务做整车路试

> **今日目标**：搞懂微基准和压测的分工；亲手压测 Agent 服务打出基线吞吐/延迟；学会用 jstat/jcmd 盯着 JVM 的"仪表盘"。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：Agent 服务基线压测报告（分档并发）+ jstat 观察记录

## 1. 知识地图（先讲人话）

```
压测（Load Test）的本质：回答三个问题——
  ① 现在的吞吐多少？（每秒处理几个请求）
  ② 延迟什么时候开始恶化？（加压到哪根弦绷断）
  ③ 断的那根弦是什么？（CPU/内存/连接池/下游依赖）

压测三戒律（跟做实验一个道理）：
  ① 单变量：一次只动并发数（其他全固定），不然不知道是谁的功劳
  ② 分档找拐点：并发 1/5/10/20 逐级加，找"延迟开始起飞"的那一档
  ③ 记环境：CPU 核数/内存/JVM 参数/数据量全写进报告——
     没环境的数字是废纸（别人复现不了）

拐点（Knee Point）长什么样：
  并发 1→5：吞吐线性涨（CPU 没吃饱，加人真多干活）
  并发 5→10：吞吐涨但变缓（开始排队）
  并发 10→20：吞吐不涨反跌，P99 爆炸（队伍堵死）
  → 最佳工作点通常在"吞吐最高且延迟还没起飞"的档位附近

本机压测 Agent 的诚实口径（先说清楚再动手）：
  模型推理（Ollama 单卡）是绝对大头且本月不优化它；
  本月要证明的是"模型之外的部分"（工具串行/同步留痕）占比
  和优化效果——所以压测报告要分两栏看：模型时间 / 我们的代码时间。

监控三件套（JVM 仪表盘）：
  jstat -gcutil <pid> 1000  → 每秒刷 GC 占比（O 区涨太快=对象太多）
  jcmd <pid> VM.flags       → 看 JVM 实际生效参数（验证配置没白配）
  jcmd <pid> Thread.print   → 线程快照（卡死/死锁第一现场）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 大白话 |
|------|------|--------|
| Load Test | 负载/压测 | 主动加流量，看系统什么时候顶不住 |
| Throughput | 吞吐 | 每秒处理请求数（req/s） |
| Knee Point | 拐点 | 加压不再换来吞吐、只剩延迟的那一档 |
| Contention | 竞争 | 多个线程抢同一把锁/同一个池子——拐点的常见幕后黑手 |
| Saturation | 饱和 | 资源全部占满的状态，过了拐点就进入饱和区 |

## 3. 动手实操

### 3.1 写一个 40 行的并发压测器（Windows 免装工具）

```java
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public class MiniLoad {
    public static void main(String[] args) throws Exception {
        String url = "http://localhost:8080/agent/ask?q=test";  // Agent 接口
        int[] concurrencies = {1, 5, 10, 20};                    // 分档
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build();

        for (int c : concurrencies) {
            ExecutorService pool = Executors.newFixedThreadPool(c);
            List<Long> lat = Collections.synchronizedList(new ArrayList<>());
            long start = System.nanoTime();
            int total = c * 10;                                  // 每档 10 个请求/线程
            CountDownLatch done = new CountDownLatch(total);
            for (int i = 0; i < total; i++) {
                pool.submit(() -> {
                    long t = System.nanoTime();
                    try { client.send(HttpRequest.newBuilder(URI.create(url)).build(),
                                     HttpResponse.BodyHandlers.ofString()); } catch (Exception ignored) {}
                    lat.add((System.nanoTime() - t) / 1_000_000);
                    done.countDown();
                });
            }
            done.await();
            double sec = (System.nanoTime() - start) / 1e9;
            lat.sort(Long::compare);
            System.out.printf("并发%2d | 吞吐 %5.2f req/s | P50 %5d ms | P99 %6d ms%n",
                    c, total / sec, lat.get(lat.size()/2), lat.get((int)(lat.size()*0.99)-1));
            pool.shutdown();
        }
    }
}
// Windows 降级说明：wrk/ab 在本机不好装；JMeter 太重；
// 这 40 行足够"分档压测+记分位"，口径和 wrk 一致（面试可照讲）
```

### 3.2 一边压一边盯仪表盘

```powershell
# 终端 1：压测跑起来后，终端 2 盯 GC（pid 换成你的 Java 进程）
jstat -gcutil <pid> 1000
# 重点看三列：O(老年代占用%) YGC/FGC(次数) FGCT(全程停顿秒数)
# 压测时 O 快速涨→大量短命对象（trace 字符串？）→day17 泄漏排查的引子

jcmd <pid> VM.flags | Select-String "MaxHeapSize|UseG1GC"
# 验证配置真的生效了——"配了但没生效"是常见白干
```

### 3.3 记录基线（台账 v2）

```
环境：Windows / JDK25 / -Xmx1g（默认 G1）/ Ollama qwen2.5:7b 单卡
并发 1  | 吞吐 ___ req/s | P50 ___ ms | P99 ___ ms
并发 5  | 吞吐 ___ req/s | P50 ___ ms | P99 ___ ms
并发 10 | 吞吐 ___ req/s | P50 ___ ms | P99 ___ ms
并发 20 | 吞吐 ___ req/s | P50 ___ ms | P99 ___ ms
拐点在第 __ 档；FGC 次数从 _ 到 _（压测前→压测后）
```

### 3.4 思考题

压测发现并发 20 时吞吐反而比并发 10 低。列出三个最可能的原因。（提示：线程池队列排队/数据库连接池耗尽/GC 压力飙升互相拖累——分别用什么手段证实？）

## 4. 面试连接

**Q：怎么做一次科学的压测？**
> 三戒律：单变量、分档找拐点、记录环境。我会先跑 1/5/10/20 四档并发，画吞吐曲线找拐点，同时开着 jstat 看 GC——因为很多"性能拐点"其实是 GC 压力拐点。压测报告必须带环境信息，否则数字没法复现也没法对比。

**Q：压测时 P99 突然恶化，怎么定位？**
> 三板斧：①看 jstat 是不是 FGC 频繁（GC 抖动）；②jstack 抓线程快照看大家堵在哪（锁/连接池/下游）；③看下游依赖监控（数据库/Ollama 是不是自己先饱和了）。我在 Agent 压测里就发现并发上去后 P99 变差主要来自 Ollama 排队——所以优化重心放在模型之外的环节，口径很诚实。

## 5. 今日验收清单

- [ ] MiniLoad 四档压测跑通，基线表填进台账
- [ ] jstat 观察 O 区变化并记录（为 day17 埋个引子）
- [ ] 能讲清"拐点"和"单变量"两个词
- [ ] `git add . && git commit -m "day16-04: load-test & jstat"`
- [ ] 笔记：画出吞吐-并发拐点曲线（手画）

---
[← Day 03](day03-JMH微基准.md) | [本月目录](README.md) | [Day 05 · 火焰图 JFR →](day05-火焰图JFR.md)
