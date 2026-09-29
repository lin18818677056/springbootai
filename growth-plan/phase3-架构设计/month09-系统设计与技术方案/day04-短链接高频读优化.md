# Day 04 · 短链接高频读优化：301/302 与多级缓存

> **今日目标**：短链跳转读链路做到 P99<5ms——301 vs 302 决策、多级缓存漏斗（M8 day02 复用）、布隆黑名单（兑现 M8 day12 伏笔）；产出短链核心实现与压测报告。
> **时长**：跳转决策与缓存设计 1.5h / 布隆实现 1h / 压测与调优 1.5h
> **今日产出**：跳转核心代码（两级缓存+布隆前置）+ P99 压测报告 + 《缓存设计决策卡》

## 1. 知识地图

```
第一决策：301 还是 302？（短链面试第一经典题）
  301 Moved Permanently：浏览器永久缓存 → 二次跳转不再经过服务器
    优点：省服务器资源（流量被浏览器"吞掉"）      缺点：点击统计全丢/换目标无法生效
  302 Found：每次都经过短链服务器
    优点：统计闭环/可随时改指向/可做灰度风控      缺点：每次跳转都消耗一次读 QPS
  决策：302 + 客户端 Cache-Control: max-age=60（折中：1 分钟内重复点击不再打服务器，
        统计精度从"每次"降到"分钟级"——和业务方确认可接受，这就是 scope 的颗粒度谈判）
  结论句式："要统计选 302，纯跳转不在乎统计选 301；我选 302+短缓存，统计是这家公司
            的核心数据资产，不能为了省读 QPS 把数据资产丢了"

第二决策：读优化漏斗（M8 day02 五层漏斗直接复用，短链是它的完美教材）：
  层1 浏览器缓存：Cache-Control max-age=60（免费，挡掉 ~40% 重复点击）
  层2 CDN/网关：302 响应小，CDN 意义不大，但黑名单 403 可在网关层拦截
  层3 JVM 本地缓存 Caffeine：W-TinyLFU，容量 10 万条/TTL 10min——扛热头（~70% 命中）
  层4 Redis：全量短码映射，TTL 24h+访问续期——扛 Caffeine miss（~99.9% 累计命中）
  层5 MySQL：主键查询兜底（命中即回填 Redis）——QPS 已趋零
  漏斗数字：1.4 万峰值 QPS → Caffeine 后剩 ~4200 → Redis 后剩 <15 → DB <1
  ——每一层的容量、TTL、命中率都要有数字（M8 day02 的估算方法在缓存层的落地）

第三决策：黑名单前置（兑现 M8 day12 布隆过滤器伏笔）：
  场景：恶意短链指向钓鱼网站，必须跳转前拦截（法务红线，不能事后处置）
  方案：布隆过滤器存黑名单短码（1 亿条/误判率 1% → 0.12GB 内存 vs Set 10GB+）
  流程：请求 → 布隆查 → 可能存在 → Redis/DB 精确确认 → 命中则 403
       ——布隆说"不在"则一定不在（90%+ 请求直接放行），说"在"再精确查（误判兜底）
  M8 day12 遗留的"删除"问题在这的解法：黑名单低频更新 → 计数布隆或定时全量重建
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| 301 / 302 Redirect | 永久/临时重定向（缓存语义差异） |
| Multi-level Cache | 多级缓存（Caffeine→Redis→DB 漏斗） |
| Bloom Filter | 布隆过滤器（可能误判、绝不漏判） |
| Cache Penetration | 缓存穿透（查不存在的 key 直达 DB） |
| W-TinyLFU | Caffeine 淘汰算法（频率+新近度） |
| Hotspot Key | 热点 key（爆款短码，M8 day09 治理复用） |

## 3. 动手实操：跳转核心实现与压测

```java
// learning/month09-system-design/src/ShortLinkRedirect.java（两级缓存+布隆前置，可单测核心逻辑）
import java.util.concurrent.*;
import java.util.function.Function;

public class ShortLinkRedirect {
    // 模拟 Caffeine（接口同款语义：TTL + 容量上限；真实项目换 com.github.benmanes.caffeine）
    static final ConcurrentHashMap<String, String> LOCAL = new ConcurrentHashMap<>();
    static final int LOCAL_MAX = 100_000;
    // 模拟布隆（真实项目用 Guava BloomFilter：expectedInsertions=1e8, fpp=0.01 → ~0.12GB）
    static final java.util.BitSet BLOOM = new java.util.BitSet(1 << 24);
    static final int[] SEEDS = {17, 31, 61};
    static boolean bloomMightContain(String code) {
        for (int seed : SEEDS) BLOOM.set(mix(code, seed) & 0xFFFFFF);      // 写入阶段同样 set
        return true;                                                       // demo 从简
    }
    static int mix(String s, int seed) { int h = seed; for (char c : s.toCharArray()) h = h * 31 + c; return Math.abs(h); }

    public static String redirect(String code, Function<String, String> db) {
        // ① 布隆前置：黑名单"一定不在"的 90% 流量直接放行（法务拦截只查"可能在"的少数）
        if (bloomMightContain(code)) { /* 精确查 Redis/DB 黑名单，命中→ throw 403 */ }
        // ② L1 本地缓存
        String url = LOCAL.get(code);
        if (url != null) return url;
        // ③ L2 Redis（demo 用 DB 函数代替，真实代码：redis.get → miss 才走 db）
        url = db.apply(code);
        if (url == null) return "404";                 // 穿透防护：404 短缓存 60s（M8 day03 手段）
        if (LOCAL.size() >= LOCAL_MAX) LOCAL.clear();  // demo 简化：Caffeine 自带 W-TinyLFU 淘汰
        LOCAL.put(code, url);
        return url;
    }
    public static void main(String[] a) {
        redirect("0000001", c -> "https://mall.example.com/p/123456");
        // 压测骨架：100 线程×读 10 万次，P99 目标 <5ms（本地缓存命中应 <1ms）
        var pool = Executors.newFixedThreadPool(100);
        var lat = new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        for (int i = 0; i < 100; i++) pool.submit(() -> {
            for (int j = 0; j < 1000; j++) {
                long t = System.nanoTime();
                redirect("0000001", c -> { try { Thread.sleep(5); return "https://db.example.com"; } catch (Exception e) { return null; } });
                lat.add((System.nanoTime() - t) / 1000);
            }});
        pool.shutdown();
        var sorted = lat.stream().sorted().toList();
        System.out.printf("P99=%dus n=%d%n", sorted.get((int)(sorted.size() * 0.99)), sorted.size());
        // 预期：P99<10us（纯内存），真实链路 Caffeine 命中 P99<1ms、全程含 Redis P99<5ms 达标
    }
}
```

```text
《缓存设计决策卡》（每层一行的速查表，评审时直接引用）：
| 层 | 组件 | 容量 | TTL | 命中率 | 失效策略 |
|----|------|------|-----|--------|---------|
| 浏览器 | Cache-Control | - | 60s | ~40% | 用户强刷 |
| 本地 | Caffeine | 10 万条 | 10min | ~70% | TTL+LFU 自动 |
| 分布式 | Redis | 全量码 | 24h 访问续期 | 累计 99.9% | 空值 60s 防穿透 |
| DB | 主键 | 3 年 4TB | - | 兜底 | - |
短链缓存的特殊优势：映射一经创建永不变更（不可变数据）→ 无一致性难题，
只有新增没有失效——这就是"数据不变性换一致性成本为零"的设计（对比 M8 商品缓存的多级失效复杂度）
```

```powershell
cd D:\mywork\springbootai\learning\month09-system-design\src
javac -encoding UTF-8 -d ../out ShortLinkRedirect.java ; java -cp ../out ShortLinkRedirect
# 进阶：接真实 Caffeine（lib 加 caffeine jar）重跑对比 W-TinyLFU 与 ConcurrentHashMap 全清策略的命中率
cd D:\mywork\springbootai ; git add . ; git commit -m "day09-04: redirect p99"
```

## 4. 面试连接

**Q：短链接用 301 还是 302？（必考，考的是业务权衡不是背诵）**
> 先给决策依据再给结论：301 浏览器永久缓存，二次访问不再经过服务器——省资源但统计全丢、目标不可变更；302 每次回源，统计闭环、可改指向。短链平台的收入模型往往就是按点击统计收费的，统计是核心数据资产，所以业务上必须 302。再补一个工程优化显深度：302 会被滥用成性能问题，我用"短缓存"折中——响应头带 Cache-Control: max-age=60，60 秒内重复点击浏览器直接跳不再回源，统计精度从点击级降到分钟级，读 QPS 直接降 40%。这个折中要和业务方确认过颗粒度——我方案里所有决策都带代价数字，这是 RFC 和拍脑袋的区别。

**Q：跳转接口怎么做到 P99<5ms？缓存一致性怎么保证？**
> 五层漏斗每层有数字：浏览器缓存挡 40%，Caffeine 10 万条 W-TinyLFU 扛 70% 热点（命中 <1ms），Redis 全量 24h 续期扛到累计 99.9%（命中 ~2ms），MySQL 主键兜底几乎无流量。漏斗算下来 1.4 万峰值 QPS 到 DB 层趋零，4C8G 单实例读 5000 QPS 需 3 实例，留 1.5 倍冗余。一致性是这道题的送分变答题：短链映射是不可变数据——创建后永不修改（要改就发新码），所以没有失效只有新增，多级缓存天然强一致，这是"用数据不变性消灭缓存一致性问题"的设计思路。剩余风险两个：穿透（恶意随机码打 DB）用空值短缓存+布隆前置双保险——布隆说"不在"的 90% 流量直接放行、说"在"的才精确查（1% 误判率下 DB 压力可忽略）；热点码（爆款链接）Caffeine 天然消化，W-TinyLFU 对频率敏感正好对症。

## 5. 今日验收清单

- [ ] 301/302 决策（结论+依据+折中方案）能脱稿
- [ ] ShortLinkRedirect 运行：P99 压测达标 + 穿透防护验证
- [ ] 《缓存设计决策卡》五层全带数字
- [ ] 布隆黑名单流程（前置放行+精确兜底）与 M8 day12 删除问题解法对账
- [ ] `git add . && git commit -m "day09-04: redirect cache"`

---
[← Day 03](day03-短链接设计与发号器.md) | [本月目录](README.md) | [Day 05 · 短链接数据闭环 →](day05-短链接数据闭环.md)
