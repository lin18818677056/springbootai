# Day 17 · 分布式 ID：手写雪花算法 + 时钟回拨三方案

> **今日目标**：分库后自增主键作废（两个库都从 1 开始必冲突）。今天手写雪花算法（Snowflake），实测 10 万 ID 唯一性与趋势递增，重点拆解唯一死穴——时钟回拨的三个工程方案，再对照号段模式（Leaf 的思路）。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：SnowflakeIdWorker 可用实现 + 唯一性对拍报告 + 时钟回拨方案卡

## 1. 知识地图

```
为什么自增 ID 在分库后失效：
  db0 和 db1 各自 AUTO_INCREMENT → 两个订单都叫 id=1 → 全局冲突
  附带原罪：ID 连续暴露业务量（竞对每天数你的订单号就知道你生意多好）

雪花算法 64 位结构（★必背位图）：
  ┌─1─┬─────41─────┬──10──┬────12────┐
  │ 0 │ 时间戳(ms)  │机器ID │ 序列号    │
  └───┴────────────┴──────┴──────────┘
  符号位 1b   恒 0（保证正数，DB BIGINT 友好）
  时间戳 41b  当前毫秒 - 起始纪元(如 2020-01-01) → 可用约 69 年
  机器ID 10b  1024 个节点（拆 5b 机房 + 5b 机器）
  序列号 12b  同一毫秒内递增 → 单机单毫秒 4096 个 → 单机峰值 409.6 万/s
  特性：全局唯一 + 时间趋势递增（对 B+ 树友好，InnoDB 顺序插入不分裂页！）

唯一死穴：时钟回拨（NTP 校时/人为调整导致 currentTimeMillis 变小）
  后果：同一毫秒的"时间戳"重复出现 → 序列号从头开始 → ID 重复！
  三方案（面试必考）：
  ① 等待追平：回拨 < 一个小阈值（如 5ms）→ 自旋等时钟赶上
  ② 拒绝服务：回拨超阈值 → 抛异常/告警 + 人工介入（宁可不可用不可重复）
  ③ 逻辑时钟：不信任系统时钟，用"上次发号时间戳"单调递增
     （lastTs = max(lastTs, 当前时间)）→ 天然免疫回拨，代价是 ID 与真实时间弱相关
  生产增强：Redis/ZK 分配机器 ID（防重启后 machineId 变化撞号）

号段模式（Leaf segment，另一种流派）：
  DB 号段表：biz_tag, max_id, step → 服务一次取一段（如 1000 个）在内存发放
  + 不依赖时钟、ID 短       - 依赖 DB、重启丢号段（有空洞）、扩容要配号段
  双 buffer 优化：当前号段用到 10% 就异步预取下一段 → 取号无毛刺
  对比选型：雪花（无依赖、超高频）vs 号段（时钟不可信/ID 要短的场景）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Snowflake | 雪花算法 | 64 位 = 时间+机器+序列 |
| Clock Skew | 时钟回拨 | NTP 校时引发 ID 重复 |
| Epoch | 起始纪元 | 自定义时间原点 |
| Sequence | 序列号 | 毫秒内自增 12 位 |
| Segment/Leaf | 号段模式 | DB 批量取号段 |
| Trend Increasing | 趋势递增 | InnoDB 顺序写友好 |

## 3. 动手实操

### 3.1 主菜：手写雪花算法（含时钟回拨防护）

```java
// SnowflakeIdWorker.java —— 手写雪花（重点：回拨防护三选一都给出）
public class SnowflakeIdWorker {
    // ---------- 位分配 ----------
    private static final long EPOCH = 1577836800000L;   // 2020-01-01 起始纪元
    private static final long SEQ_BITS = 12L, MACHINE_BITS = 10L;
    private static final long MAX_SEQ = ~(-1L << SEQ_BITS);           // 4095
    private static final long TS_SHIFT = SEQ_BITS + MACHINE_BITS;     // 22
    private static final long MACHINE_SHIFT = SEQ_BITS;

    private final long machineId;                 // 0~1023（生产从 ZK/配置中心分配）
    private long lastTs = -1L;                    // 上次发号时间戳
    private long seq = 0L;                        // 毫秒内序列

    public SnowflakeIdWorker(long machineId) {
        if (machineId < 0 || machineId > ~(-1L << MACHINE_BITS))
            throw new IllegalArgumentException("machineId 越界");
        this.machineId = machineId;
    }

    public synchronized long nextId() {
        long ts = System.currentTimeMillis();
        // ★时钟回拨三方案（按严重程度分级处理）：
        if (ts < lastTs) {
            long offset = lastTs - ts;
            if (offset <= 5) {                    // ① 小回拨：自旋等待追平
                ts = waitUntil(lastTs);
            } else {                              // ② 大回拨：拒绝发号 + 告警
                throw new IllegalStateException("时钟回拨 " + offset + "ms，拒绝发号");
            }
        }
        if (ts == lastTs) {                       // ③ 同毫秒：序列号兜底
            seq = (seq + 1) & MAX_SEQ;
            if (seq == 0) ts = waitUntil(lastTs + 1);   // 本毫秒 4096 个用尽 → 等下一毫秒
        } else {
            seq = 0L;                             // 新毫秒，序列归零
        }
        lastTs = ts;                              // 记录（下次回拨的判据）
        // 组装 64 位：符号0 | 时间戳 | 机器 | 序列
        return ((ts - EPOCH) << TS_SHIFT) | (machineId << MACHINE_SHIFT) | seq;
    }

    private long waitUntil(long target) {
        long now = System.currentTimeMillis();
        while (now < target) { now = System.currentTimeMillis(); }
        return now;
    }

    /** 从 ID 反解出生成时间（运维排障用：这个订单啥时候生成的？） */
    public static long extractTs(long id) { return (id >> TS_SHIFT) + EPOCH; }

    public static void main(String[] args) throws Exception {
        SnowflakeIdWorker w1 = new SnowflakeIdWorker(1);
        // ① 唯一性对拍：10 万个 ID
        long[] ids = new long[100_000];
        long begin = System.currentTimeMillis();
        for (int i = 0; i < ids.length; i++) ids[i] = w1.nextId();
        java.util.Set<Long> set = new java.util.HashSet<>();
        for (long id : ids) set.add(id);
        System.out.println("生成 10 万耗时: " + (System.currentTimeMillis() - begin)
                + "ms，唯一数: " + set.size() + "（必须=100000）");
        // ② 趋势递增验证（雪花对 B+ 树的善意）
        boolean increasing = true;
        for (int i = 1; i < ids.length; i++)
            if (ids[i] <= ids[i - 1]) { increasing = false; break; }
        System.out.println("趋势递增: " + increasing);
        // ③ 反解时间戳演示
        System.out.println("ID " + ids[0] + " 生成于: "
                + new java.sql.Timestamp(extractTs(ids[0])));
        // ④ 双机位对比：不同 machineId 生成不冲突
        SnowflakeIdWorker w2 = new SnowflakeIdWorker(2);
        System.out.println("机器1: " + w1.nextId() + "  机器2: " + w2.nextId()
                + "  （机器位不同 → 永不撞车）");
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month04-redis-mysql
javac -d out SnowflakeIdWorker.java; java -cp out SnowflakeIdWorker
# 记录：唯一数=100000 / 递增=true / 双机位正常
```

### 3.2 号段模式演示（对照流派）

```java
// SegmentIdGenerator.java —— 号段模式（Leaf segment 思想，内存模拟）
import java.util.concurrent.atomic.AtomicLong;
import java.util.*;

public class SegmentIdGenerator {
    static final Map<String, Long> DB_MAX = new HashMap<>();     // 模拟 DB 号段表 max_id
    static final int STEP = 1000;                                 // 每段 1000 个
    final AtomicLong current = new AtomicLong(-1);                // 当前号段游标

    public synchronized long nextId(String biz) {
        long cur = current.get();
        if (cur < 0 || cur % STEP == STEP - 1) {                  // 段用尽 → 取新段
            long max = DB_MAX.getOrDefault(biz, 0L);
            DB_MAX.put(biz, max + STEP);                          // UPDATE 号段表
            current.set(max);                                     // 新段起点
            System.out.println("[号段] 领取 " + biz + " 段: " + (max + 1) + " ~ " + (max + STEP));
        }
        return current.incrementAndGet();
    }

    public static void main(String[] args) {
        SegmentIdGenerator gen = new SegmentIdGenerator();
        for (int i = 0; i < 2500; i++) gen.nextId("order");       // 2500 个 → 只去 DB 3 次
        System.out.println("2500 个 ID 只访问 DB 3 次（1000/段）→ DB 压力极低");
        System.out.println("代价：重启丢当前号段（ID 有空洞，业务可接受）；依赖 DB 可用性");
    }
}
```

```powershell
javac -d out SegmentIdGenerator.java; java -cp out SegmentIdGenerator
```

### 3.3 方案对比卡（抄进笔记）

```text
| 方案       | 有序性     | 依赖     | 吞吐        | 适用场景             |
|------------|-----------|----------|-------------|---------------------|
| 自增主键   | 严格递增  | 单库     | 高          | 单库时代（已失效）   |
| UUID       | 完全无序  | 无       | 极高        | 非主键场景（B+树杀手）|
| 雪花算法   | 趋势递增  | 时钟+机器位 | 极高(409万/s) | 分库主键首选       |
| 号段模式   | 段内递增  | DB       | 高(双buffer) | 时钟不可信/ID要短   |
| Redis INCR | 全局递增  | Redis    | 中          | 已有 Redis 且量中   |
```

## 4. 面试连接

**Q：分库分表后全局 ID 怎么生成？**
> 主推雪花算法：64 位 = 1 符号 + 41 时间戳 + 10 机器 + 12 序列，单机每毫秒 4096 个，趋势递增对 InnoDB 聚簇索引友好（顺序插入不页分裂）。坦白死穴：时钟回拨导致重复——我实现里分三级处理：小回拨自旋等待、大回拨拒绝发号并告警、生产可上逻辑时钟（lastTs 单调）。补充流派：号段模式（Leaf）依赖 DB 取段但免时钟，双 buffer 消毛刺。选型："雪花为主，ID 需要短/时钟不可信场景用号段。"

**Q：为什么 UUID 不适合做 MySQL 主键？**
> 三个伤：① 无序 → 聚簇索引随机插入，页频繁分裂、Buffer Pool 命中率崩（对照 month03 day15 页结构）；② 36 字符太长，所有二级索引叶子都存主键 → 索引膨胀 3 倍；③ 无业务语义难排障。雪花 ID 8 字节 BIGINT + 趋势递增，全避开。加分句："这就是为什么大厂规范里'主键必须有序 BIGINT'——不是教条，是 B+ 树的物理规律。"

**Q：雪花算法的机器 ID 怎么分配？**
> 静态配置（小规模）：启动参数/配置中心手动分配，风险是抄错配置撞号。动态分配（生产推荐）：① ZK 顺序节点自动注册拿序号；② Redis SETNX 抢占 machineId；③ 数据库分配表+心跳续约。关键洞察：机器 ID 重启后若被别的实例抢走，会出现"两个实例同一 machineId"——所以要租约续期或固定实例绑定（K8s StatefulSet 的 pod 序号天生稳定）。

## 5. 今日验收清单

- [ ] SnowflakeIdWorker 跑通（唯一=100000 / 递增=true）
- [ ] 时钟回拨三方案卡默写（等待/拒绝/逻辑时钟）
- [ ] SegmentIdGenerator 跑通（2500 个 ID 只触 DB 3 次）
- [ ] 雪花 64 位位图手绘
- [ ] `git add . && git commit -m "day17: snowflake id"`

---
[← Day 16](day16-分片策略与基因法.md) | [本月目录](README.md) | [Day 18 · 手写分片路由实战 →](day18-手写分片路由实战.md)
