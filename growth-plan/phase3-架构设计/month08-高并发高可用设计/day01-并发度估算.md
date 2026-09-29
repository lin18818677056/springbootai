# Day 01 · 并发度估算：从日活推到峰值 QPS

> **今日目标**：掌握并发度估算的完整链条（DAU→日均请求→平均 QPS→峰值 QPS→并发数）；对商城给出每个系数有来源的估算文档——这是所有容量设计的起点。
> **时长**：公式推演 1.5h / 商城估算实操 3h / 面试整理 0.5h
> **今日产出**：商城并发度估算文档（每系数有依据）+ QpsEstimator 手写工具

## 1. 知识地图

```
估算链条（每一跳都有"系数"，每个系数都要有来源）：
  ① 业务量：DAU（日活）= 100 万（产品给/竞品对标/增长目标）
  ② 人均请求：DAU × 平均每人发起的请求数 = 100万 × 20 次 = 2000 万次/天
  ③ 平均 QPS：2000 万 ÷ 86400 秒 ≈ 232 QPS（日均意义不大——流量不均匀！）
  ④ 峰值 QPS：二八法则（80% 的流量集中在 20% 的时间）
     峰值 ≈ 日总量 × 80% ÷ (86400 × 20%) ≈ 4 × 平均 ≈ 928 QPS
     ——电商大促系数更高（双 11 零点可达日均在 20~50 倍），按场景另算
  ⑤ 读写拆分：读写比（Read/Write Ratio）电商典型 10:1~100:1
     928 QPS 里读 ≈ 840、写 ≈ 90——两条链路分开设计（day02/day03）
  ⑥ 并发数（并发度）≠ QPS！（面试必纠错）
     并发数 = QPS × 平均 RT（Little's Law 利特尔法则）
     928 QPS × 50ms = 46 个并发——"多少线程在同时处理"
  ⑦ 实例数：并发数 ÷ 单实例并发能力（压测得出，day23）
     46 ÷ 20（单实例拐点并发）≈ 3 个实例 → 冗余 +1 = 4 个（N+1 原则）

系数来源清单（估算文档的"证据链"）：
  高峰系数：自有日志统计 > 行业报告 > 经验值（4~20）
  读写比：网关层 access log 统计 GET:POST
  RT：压测报告（没有就用 50ms 起估，标注"待压测校准"）
  ——面试金句："估算不是算命，是给每个不确定数字一个来源和一个校准计划。"
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| DAU (Daily Active Users) | 日活跃用户数 |
| Peak QPS | 峰值每秒查询数 |
| 80/20 Rule | 二八法则（流量集中度） |
| Little's Law | 利特尔法则（并发=QPS×RT） |
| Read/Write Ratio | 读写比 |
| Concurrency | 并发数（同时处理的请求数，≠QPS） |
| N+1 Redundancy | N+1 冗余（容量+一台兜故障） |

## 3. 动手实操：商城估算 + 手写估算器

```java
// learning/month08-high-concurrency/src/QpsEstimator.java（纯 javac 可跑）
public class QpsEstimator {
    record Estimate(String name, long dau, int reqPerUser, double peakFactor,
                    double rwRatio, double avgRtMs) {
        long dailyTotal()  { return dau * reqPerUser; }
        double avgQps()    { return dailyTotal() / 86400.0; }
        double peakQps()   { return avgQps() * peakFactor; }          // 峰值系数直给（内部=4，大促另算）
        double readQps()   { return peakQps() * rwRatio / (1 + rwRatio); }
        double writeQps()  { return peakQps() / (1 + rwRatio); }
        double concurrency() { return peakQps() * avgRtMs / 1000; }    // Little's Law
        int instances(int perInstConc) {                                 // N+1
            return (int) Math.ceil(concurrency() / perInstConc) + 1;
        }
    }
    public static void main(String[] a) {
        var mall = new Estimate("商城日常", 1_000_000, 20, 4, 10, 50);
        var promo = new Estimate("商城大促", 1_000_000, 35, 20, 8, 80);  // 大促：请求涨+系数涨+RT 涨
        System.out.println(mall);  // 输出全链条：avg/peak/read/write/conc/instances
        System.out.println(promo); // 大促读 QPS≈1.7 万 → Redis 预热+多级缓存的必要性直观可见
    }
}
// 运行：javac -encoding UTF-8 -d out QpsEstimator.java && java -cp out QpsEstimator
```

```markdown
<!-- 商城并发度估算文档骨架（docs/capacity/estimation.md——每个系数一行来源） -->
| 参数 | 取值 | 来源 |
|------|------|------|
| DAU | 100 万 | 产品季度目标（对标竞品 80 万+20% 增长） |
| 人均请求 | 20 次/天 | 网关 access log 抽样统计（M6 day22 可观测数据） |
| 高峰系数 | 4 | 自有流量 30 天分时曲线 P99 集中度 |
| 读写比 | 10:1 | Nginx 日志 GET:POST 统计 |
| 平均 RT | 50ms | M6 压测数据（day13 拐点测试） |
| 单实例并发 | 20 | 待 day23 压测校准（初值按 4C8G 经验） |
结论：日常峰值 928 QPS（读 840/写 90），4 实例；大促峰值 ≈ 1.9 万 QPS（读 1.7 万）
→ 读链路必须多级缓存（day02），写链路 MQ 削峰（day03），大促单独预案（day19）
```

## 4. 面试连接

**Q：给你一个日活 1000 万的系统，怎么估算 QPS？**
> 五步链条：①DAU 1000 万 × 人均请求数（问产品或查日志，假设 20 次）= 日请求 2 亿；②平均 QPS = 2 亿/86400 ≈ 2315；③峰值按二八法则 ≈ 4 倍 ≈ 9260 QPS，如果是大促场景系数另按 20~50 估；④读写拆分——电商典型 10:1，读约 8400、写约 850，两条链路分开设计；⑤并发数用 Little's Law：9260 × 50ms ≈ 463 并发，按单实例 20 并发要 24 实例再加 N+1 冗余。我特别会补一句：每个系数都有来源和校准计划——高峰系数取自自有日志的 30 天分时曲线，RT 和单实例能力用压测校准（day23），估算文档跟着压测报告滚动更新。估算的价值是定架构形态（要不要缓存/要不要 MQ），不是精确到个位。

**Q：QPS 和并发数是一回事吗？**
> 不是，这是高频纠错点：QPS 是"每秒完成多少请求"（吞吐量），并发数是"同一瞬间有多少请求在处理中"（占用度），两者用 Little's Law 连接：并发数 = QPS × 平均 RT。同一个 1000 QPS：RT 10ms 时并发只要 10，RT 500ms 时并发要 500——所以优化 RT 本身就是给系统"降并发占用"，这也解释了为什么缓存（把 RT 从 200ms 打到 5ms）比堆机器更划算。线程池配置也由此推导：容器 200 线程，在 RT 50ms 时理论上限 4000 QPS，超了就排队——线程数不是越大越好，超过 CPU 核数×合理阻塞比只会增加上下文切换（day05 展开连接与线程预算）。

**Q：大促场景的估算和日常有什么不同？**
> 三个系数全变：请求量系数涨（大促人均浏览翻倍）、峰值系数涨（零点抢购集中在几分钟，二八法则变成"一九九"式的尖峰）、RT 涨（缓存失效+DB 压力导致劣化）。所以大促不能拿日常估算乘个倍数了事：我们商城大促估算用独立参数（人均 35 次/峰值系数 20/RT 80ms），得出读 QPS 约 1.7 万——这个数字直接决定架构形态：读链路必须提前预热+多级缓存扛 95% 以上，剩下打到 Redis 预扣和 MQ 的写链路必须削峰。估算分"日常档/大促档"两张表，是大促保障预案（day19）的第一张纸。

## 5. 今日验收清单

- [ ] QpsEstimator 跑通（日常档+大促档两行输出）
- [ ] 商城估算文档完成（6 参数全带来源）
- [ ] "并发数≠QPS"+Little's Law 能脱稿讲
- [ ] 大促三系数变化能展开
- [ ] `git add . && git commit -m "day08-01: qps estimation"`

---
[← 本月目录](README.md) | [Day 02 · 读多写少三板斧 →](day02-读多写少三板斧.md)
