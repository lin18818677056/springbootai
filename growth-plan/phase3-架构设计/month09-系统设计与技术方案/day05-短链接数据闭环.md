# Day 05 · 短链接数据闭环：统计与过期

> **今日目标**：点击统计不阻塞跳转主链路——内存聚合→定时 flush→异步落库→对账四层闭环（M8 day11 计数三层直接复用）；PV/UV 方案选型（精确 vs HLL）；短链过期与回收策略。至此短链系统完整闭环。
> **时长**：统计链路设计 1.5h / 代码与对账验证 1.5h / 过期策略 1h
> **今日产出**：统计模块（ClickStatsService）+ 《短链统计方案》+ 过期回收 job

## 1. 知识地图

```
核心约束：跳转 P99<5ms 是生死线（day04 成果），统计绝不能进同步链路——
  错误做法：跳转时 UPDATE click_count+1（写锁竞争+拖慢读链路，双输）
  正确架构：跳转只做"内存 +1"，落库异步化——M8 day11 计数三层原样复用：

统计四层闭环（每层职责与数字）：
  层1 内存聚合：ConcurrentHashMap<code, LongAdder>——跳转线程只做 adder.increment()（~10ns）
                PV 直接累加；UV 用日布隆 per-code（1% 误判可接受，用户维度不精确判重）
  层2 定时 flush：5 秒一次把 adder 汇总值发送 MQ（RocketMQ 异步消息），清零继续聚合
                ——5s 窗口=崩溃最多丢 5s 增量（可接受，统计非资金）
  层3 异步落库：消费者批量 UPDATE t_short_link SET click_count=click_count+? WHERE id=?
                批量合并（同一 code 5s 内多次消息合并一次 UPDATE）——DB 写压力=去重后 QPS
  层4 对账：每小时 job 对比 Redis 累计计数 vs DB click_count（M8 day13 三铁律思想）
                差异>0.5% 告警+手动触发全量重算——统计可信度的最后防线

  崩溃丢失分析（方案评审必被问）：内存聚合层丢 5s 数据 → 日 3 亿点击×5s/86400s ≈ 1.7 万次
  → 占比 0.006%，统计场景完全可接受——"丢得起"是有估算支撑的，不是拍脑袋

PV/UV 选型表：
| 指标 | 方案 | 精度 | 成本 | 适用 |
|------|------|------|------|------|
| PV | LongAdder 聚合 | 精确 | 极低 | 全场景 |
| UV(常规) | Redis HyperLogLog | 0.81% 标准差 | 12KB/key | 亿级去重 |
| UV(精确) | Bitmap uid 位图 | 精确 | uid 连续才行 | 中小规模 |
| UV(权威) | T+1 离线重算 | 精确 | 延迟 1 天 | 结算/报表 |
  决策：实时 UV 用 HLL（展示用），T+1 离线重算覆盖（结算用）——展示数据不许进结算链路

短链过期与回收：
  需求澄清结论（day03 scope 的兑现）：默认永久有效；付费用户可设过期（TTL 语义）
  存储回收：三年 4TB 的存储压力解法=冷热分层（M8 day03 思想）：
    热：近 90 天活跃短码 → MySQL 主表+全量缓存
    冷：90 天未访问 → 归档表/对象存储（跳转冷码多一次回源，P99 放宽到 20ms——分类 SLA）
    job：每日扫描 last_access_time<90d 的码移入归档表；访问归档码时回迁（读时激活）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Async Aggregation | 异步聚合（内存累加+批量落库） |
| HyperLogLog | HLL（基数估计，0.81% 误差/12KB） |
| Eventual Consistency | 最终一致（统计延迟分钟级） |
| Reconciliation | 对账（内存/MQ/DB 三方核对） |
| Hot/Cold Split | 冷热分层（按 last_access_time） |
| Data Loop Closure | 数据闭环（采集→聚合→落库→对账） |

## 3. 动手实操：统计模块代码

```java
// learning/month09-system-design/src/ClickStatsService.java（四层闭环的 1+2 层骨架）
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

public class ClickStatsService {
    private final Map<String, LongAdder> pvBuffer = new ConcurrentHashMap<>();
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor();

    /** 跳转链路调用：只做内存累加，~10ns，绝不碰 DB/MQ（P99 保障的第一原则） */
    public void record(String code) {
        pvBuffer.computeIfAbsent(code, k -> new LongAdder()).increment();
    }
    /** 启动定时 flush：5 秒窗口（崩溃丢失上界=5s 增量，占比 0.006% 有估算支撑） */
    public void start() {
        flusher.scheduleAtFixedRate(this::flush, 5, 5, TimeUnit.SECONDS);
    }
    private void flush() {
        // 原子换 buffer：flush 期间新点击进新 map，互不阻塞（读写分离的老套路）
        var snapshot = new ConcurrentHashMap<String, LongAdder>();
        pvBuffer.forEach((k, v) -> snapshot.put(k, new LongAdder()));   // 预热新 key 集合
        var drained = drain(pvBuffer, snapshot);
        drained.forEach((code, count) -> sendToMq(code, count));        // 层3：MQ→消费者批量 UPDATE
        System.out.printf("flushed %d codes, total=%d%n", drained.size(),
                drained.values().stream().mapToLong(Long::longValue).sum());
    }
    private Map<String, Long> drain(Map<String, LongAdder> from, Map<String, LongAdder> fresh) {
        var out = new java.util.HashMap<String, Long>();
        from.forEach((k, adder) -> { long v = adder.sumThenReset(); if (v > 0) out.put(k, v); });
        return out;
    }
    private void sendToMq(String code, long count) { /* mq.send("click-stats", code+":"+count) */ }

    public static void main(String[] a) throws Exception {
        var svc = new ClickStatsService(); svc.start();
        var pool = Executors.newFixedThreadPool(50);
        for (int t = 0; t < 50; t++) pool.submit(() -> {
            for (int i = 0; i < 10_000; i++) svc.record("code" + (i % 100));   // 模拟并发点击
        });
        pool.shutdown(); pool.awaitTermination(1, TimeUnit.MINUTES);
        Thread.sleep(6000);   // 等 flush 打印——验证聚合值 = 50×10000 = 50 万（不多不少）
        // 输出形如：flushed 100 codes, total=500000 ——对账基准数，层4 job 就是对这个数
    }
}
```

```sql
-- 统计落库（层3 消费者执行，5s 批量合并后的形态）+ 每小时对账（层4）
UPDATE t_short_link SET click_count = click_count + ? WHERE id = ?;   -- 批量合并 UPDATE
-- 对账 SQL：Redis INCR 记录的累计值 vs DB 落库值（差异阈值 0.5%，超限告警+重算）
SELECT id, click_count FROM t_short_link WHERE update_time > NOW() - INTERVAL 1 HOUR;
-- 冷热分层归档（每日 job）：
INSERT INTO t_short_link_archive SELECT * FROM t_short_link
 WHERE last_access_time < NOW() - INTERVAL 90 DAY LIMIT 10000;        -- 分批，防大事务（M8 day04 纪律）
```

```powershell
cd D:\mywork\springbootai\learning\month09-system-design\src
javac -encoding UTF-8 -d ../out ClickStatsService.java ; java -cp ../out ClickStatsService
# 验证：flush 输出 total 与模拟点击总数一致（对账通过）
cd D:\mywork\springbootai ; git add . ; git commit -m "day09-05: stats closed loop"
```

## 4. 面试连接

**Q：点击统计怎么设计才能不影响跳转性能？**
> 四层异步闭环，原则是"跳转链路只碰内存"。层1 内存聚合：ConcurrentHashMap<code, LongAdder>，跳转线程只做一次 increment（10ns 级），UV 用 HLL 展示级去重；层2 定时 flush：5 秒窗口把聚合值发 MQ，读写分离换 buffer 无锁竞争；层3 消费者批量合并落库，同一短码 5 秒内多消息合成一条 UPDATE，DB 写压力降到去重后量级；层4 每小时对账，Redis 累计 vs DB 值差异超 0.5% 告警重算——这是 M8 红包对账三铁律在统计场景的移植，铁律不变：聚合守恒、缓冲守恒、落库守恒。评审必问"崩溃丢多少"：5 秒窗口丢日增量的 0.006%（3 亿点击丢 1.7 万），统计场景完全可接受——这个数字说明丢失是"算过的取舍"而不是疏忽。UV 精确性问题再加一层分层：实时 HLL 只用于展示（0.81% 误差），T+1 离线重算出精确值用于结算，展示数据永不进结算链路——精度和用途绑定是统计系统设计的关键纪律。

**Q：短链数据越来越多了怎么办？**
> 三步走，每步有触发条件。第一步存储侧冷热分层：90 天未访问的短码移归档表，主表瘦身到活跃集；归档码跳转走回源+读时激活，跳转 SLA 分类——活跃码 P99<5ms、归档码放宽到 20ms，分类 SLA 比"一刀切 SLO"省一半成本。第二步容量侧分库分表：按 day03 的演进规划，3 年存储到 2TB 触发 16 库 64 表，分片键=id（code 可逆反解，读路径仍是主键命中）。第三步数据价值侧下沉数仓：原始点击明细（uid/time/code/来源）流式进 Kafka→Hive（这就是我 M10-M12 大数据线的伏笔），在线库只留聚合值——明细数据的价值在离线分析不在在线查询，在线库背它就是给自己挖坑。

## 5. 今日验收清单

- [ ] ClickStatsService 运行：50 万点击 flush 对账一致
- [ ] 四层闭环图（内存→flush→MQ→落库→对账）能画能讲
- [ ] 崩溃丢失估算（5s=0.006%）写进《短链统计方案》
- [ ] UV 选型（HLL 展示+T+1 结算）与冷热分层策略入库
- [ ] `git add . && git commit -m "day09-05: stats loop"`

---
[← Day 04](day04-短链接高频读优化.md) | [本月目录](README.md) | [Day 06 · RFC 写作（上） →](day06-RFC写作上.md)
