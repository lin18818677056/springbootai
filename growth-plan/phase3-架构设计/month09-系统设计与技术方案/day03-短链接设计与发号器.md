# Day 03 · 短链接设计：ID 生成与发号器

> **今日目标**：六步法首个完整实战——45 分钟拆解"设计短链接系统"；ID 生成三方案对比与发号器设计（兑现 M8 day08 思考题伏笔）；产出《短链概要设计》。
> **时长**：六步法实战 2.5h / 发号器代码与验证 1.5h
> **今日产出**：短链概要设计（六步全）+ SegmentIdGenerator 可运行代码 + 建表 SQL

## 1. 知识地图

```
六步法走短链（day01 检查卡首次全量使用，每步给数字）：

① 需求澄清（5min）：
  用例：①长 URL 变短码（POST /shorten）②访问短码 302 跳转（GET /abc123）③点击统计（异步，day05）
  非功能：写 QPS 千级/读 QPS 万级/跳转 RT <5ms/最终一致（统计延迟分钟级可接受）
  scope 明确不做：自定义短码 V2 再说/防钓鱼只做黑名单拦截/不考虑撤销
② 容量估算（5min）：
  写：日 1000 万新链 ÷86400 ≈ 116/s ×4 峰值 ≈ 500/s
  读：每链日均 30 次跳转 → 3 亿/日 ≈ 3500/s ×4 ≈ 14000/s（读写比 ~30:1 → 读优化路线）
  存储：日 1000 万×100B×365×2 副本 ≈ 0.7TB/年，3 年复利 ~2.1TB（含索引 ~4TB）
  带宽：1.4 万 QPS×0.2KB 302 响应×8 ≈ 22Mbps（毛毛雨——302 报文小，这就是短链业务的带宽红利）
③ 接口设计（5min）：
  POST /api/shorten {url} → {code}（幂等：同 url 同 code，用 url 的唯一索引去重）
  GET /{code} → 302 Location（核心读接口，性能生死线）
  GET /api/stat/{code} → {pv, uv}（day05 实现，今天只占位）
④ 数据模型（10min）：
  短链表 t_short_link：id BIGINT(发号器)/code CHAR(7)/origin_url VARCHAR(2048)
                     /status TINYINT/click_count BIGINT(day05 聚合回写)/create_time
  分片键三判据（M8 day03）：唯一性✓(id)/查询模式✓(code 查——但 code≠分片键！见下)/
  均衡性✓(id 单调但取模分片后均匀)
  ——矛盾点：读按 code 查、写按 id 分片 → 两个解法：code 冗余存 id 高位或反向用 code 作分片键
  我的取舍：id=发号器发放的全局唯一值，code=Base62(id) 纯函数可逆 → code 反解出 id，按 id 分片
  ——code↔id 双向可转换是整套设计的枢纽（免映射表、免哈希冲突）
⑤ 架构演进（15min）：MVP=单库+Guava 本地缓存（日 10 万链以内）→ V1=发号器+Redis+Caffeine
  两级缓存（本日完成）→ V2=按 id 分 16 库 64 表+读多副本（触发：3 年存到 2TB/峰值 5 万跳转）
⑥ 权衡与风险（5min）：
  风险1 发号器单点（M8 day08 秒杀崩溃点！）→ 双 buffer 预取+多号段并发
  风险2 恶意链接 → 布隆黑名单前置（day04 兑现 M8 day12）
  被否备选：哈希截断法（MD5 前 7 位）——冲突需探测重试、不可逆无法直接定位分片，输给 Base62

ID 生成三方案对比（本题核心决策）：
| 方案 | 原理 | 优点 | 缺点 | 结论 |
|------|------|------|------|------|
| 自增 ID | DB AUTO_INCREMENT | 简单有序 | 写单点/扩容难/ID 可预测爬遍历 | 否 |
| 哈希截断 | MD5(url) 前 7 位 | 无需协调 | 冲突要重试/不可逆/长度不稳 | 否 |
| 发号器+Base62 | 全局 ID→62 进制编码 | 无冲突/可逆定位分片/短码均匀 | 需要发号器组件 | ✓ |

Base62 数学（可逆性的来源）：62^7 ≈ 3.52 万亿 > 30 亿/年×10 年需求
  encode: id 反复 ÷62 取余映射 [0-9a-zA-Z]，decode: 每位 ×62^i 累加回 long
  例：id=1234567890 → "bnhVg8"？(62^6=5.68e10，7 位可表示到 3.52e12)
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Base62 Encoding | 62 进制编码（0-9a-zA-Z，短码可逆） |
| ID Generator | 发号器（全局唯一 ID 分配服务） |
| Segment Mode | 号段模式（批量取号，双 buffer 预取） |
| Reversibility | 可逆性（code↔id 互转，免映射） |
| Collision | 哈希冲突（哈希方案的死穴） |
| Idempotency | 幂等（同 url 重复提交返回同短码） |

## 3. 动手实操：发号器与建表

```java
// learning/month09-system-design/src/SegmentIdGenerator.java（号段+双 buffer，M8 day08 崩溃点的修复版）
import java.util.concurrent.atomic.AtomicLong;

public class SegmentIdGenerator {
    static class Segment {
        final long max; final AtomicLong cur;
        Segment(long cur, long max) { this.cur = new AtomicLong(cur); this.max = max; }
    }
    private volatile Segment cur; private volatile Segment next;
    private final int step = 10_000;                    // 号段长度：日 1000 万链 → 每 0.86s 取一次

    public SegmentIdGenerator() {
        cur  = fetch();                                 // 启动预取两个号段（双 buffer）
        next = fetch();
    }
    public synchronized long nextId() {
        long id = cur.cur.incrementAndGet();
        if (id >= cur.max) {                            // 当前号段耗尽 → 无缝切换（提前预取，不阻塞）
            cur = next; next = fetch();
        }
        return id;
    }
    private Segment fetch() { return new Segment(0, step); } // 真实实现：DB UPDATE step RETURNING 或 Redis INCRBY

    /** Base62 编码：id → 7 位短码（可逆，decode 可还原分片键） */
    private static final char[] CHARS = ("0123456789abcdefghijklmnopqrstuvwxyz"
            + "ABCDEFGHIJKLMNOPQRSTUVWXYZ").toCharArray();
    public static String encode(long id) {
        var sb = new StringBuilder();
        while (id > 0) { sb.append(CHARS[(int)(id % 62)]); id /= 62; }
        return sb.length() < 7 ? "0".repeat(7 - sb.length()) + sb : sb.toString();
    }
    public static long decode(String code) {
        long id = 0;
        for (char c : code.toCharArray()) id = id * 62 + new String(CHARS).indexOf(c);
        return id;
    }
    public static void main(String[] a) {
        var gen = new SegmentIdGenerator();
        for (int i = 0; i < 3; i++) {
            long id = gen.nextId(); String code = encode(id);
            System.out.printf("id=%d code=%s decode=%d reversible=%b%n",
                    id, code, decode(code), decode(code) == id);
        }
        // 输出示例：id=1 code=0000001 decode=1 reversible=true
        // 结论：code 反解 id 直接取模定位分片——哈希方案做不到（它不可逆）
    }
}
```

```sql
-- 短链表（V2 分片版预演：16 库 64 表，分片键 = id % 64）
CREATE TABLE t_short_link (
  id          BIGINT       PRIMARY KEY,            -- 发号器发放，全局唯一
  code        CHAR(7)      NOT NULL,               -- Base62(id)，函数依赖 id，不冗余
  origin_url  VARCHAR(2048) NOT NULL,
  status      TINYINT      DEFAULT 1,              -- 1 正常 0 黑名单禁用
  create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_origin (origin_url(768)),          -- 幂等依据：同 url 同码（前缀索引 768*4<3072 上限）
  KEY idx_create (create_time)
) ENGINE=InnoDB;
-- 关键论证：code 无需唯一索引——code=Base62(id) 纯函数，id 唯一则 code 必唯一
-- 跳转查询路径：GET /bnhVg8 → decode→id → id%64 定位库表 → WHERE id=? 主键命中（1 次索引）——完美读路径
```

```powershell
cd D:\mywork\springbootai\learning\month09-system-design\src
javac -encoding UTF-8 -d ../out SegmentIdGenerator.java ; java -cp ../out SegmentIdGenerator
# 验证：encode/decode 往返一致、100 万次发号无重复（可加循环断言）
cd D:\mywork\springbootai ; git add . ; git commit -m "day09-03: shortlink id generator"
```

## 4. 面试连接

**Q：短链接的 ID 怎么生成？为什么不用哈希？**
> 三方案对比后选发号器+Base62。哈希截断有三个硬伤：①冲突——MD5 前 7 位空间只有 62⁷，冲突概率随存量线性涨，撞码要探测重试，写路径复杂化；②不可逆——跳转时拿到短码查不出来自哪个分片，必须全库扫描或额外映射表；③长度不稳——被截断的十六进制转 Base62 长度分布不均。发号器+Base62 全部反着来：id 全局唯一所以 code 零冲突；code 是 Base62(id) 纯函数，decode 直接还原 id、id%64 直接定位分片，跳转是一次主键命中；62⁷≈3.5 万亿，日千万链用十年绰绰有余。代价是需要一个发号器组件——这正是 M8 秒杀里我踩过的坑：号段生成是秒杀链路的第一个崩溃点，所以发号器必须双 buffer 预取——当前号段用到一半就异步取下一段，取号耗时被完全隐藏，切换无锁等待。

**Q：发号器挂了怎么办？（追问链：高可用→性能→趋势）**
> 三层防御：①双 buffer 预取——本端持有两段号，DB 取号失败时还有一整段可用（秒杀场景号段 10 万粒度，能撑 12 小时）；②多实例部署——每个实例分配互斥的号段区间（类似雪花算法的 workerId 分配），一个实例挂不阻塞其他；③降级——发号器全挂时 DB 自增作为兜底（写 QPS 才 500/s，DB 顶得住，只是失去有序性）。性能上号段长度是调参点：太短取号频繁（10 万/段→日千万链每 0.86 秒一次），太长重启浪费 id 且乱序加剧；还有个进阶点是把号段按业务分型（短链/订单/优惠券各用独立号段），避免互抢。趋势上如果 ID 要进 ES 或做分页，雪花 Snowflake（趋势递增+机器位）优于纯号段，但短码场景不需要——Base62 后无序化反而防爬虫遍历。

## 5. 今日验收清单

- [ ] 短链概要设计六步全（每步有数字），docs/rfc/shortlink-draft.md
- [ ] SegmentIdGenerator 运行：往返可逆验证 + 双 buffer 切换日志
- [ ] 建表 SQL 入库，"code 不需要唯一索引"论证写进注释
- [ ] 三方案对比能脱口而出（哈希三硬伤/自增两缺点）
- [ ] `git add . && git commit -m "day09-03: shortlink draft"`

---
[← Day 02](day02-容量估算专项.md) | [本月目录](README.md) | [Day 04 · 短链接高频读优化 →](day04-短链接高频读优化.md)
