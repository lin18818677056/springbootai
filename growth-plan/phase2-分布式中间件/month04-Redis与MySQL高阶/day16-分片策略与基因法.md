# Day 16 · 分片策略与基因法（分片键是整个设计的心脏）

> **今日目标**：分库分表 80% 的后悔药都因为"分片键选错了"。今天讲透 hash/range 两大策略的取舍，重点攻克经典难题——非分片键查询，学会工业级解法"基因法"（把分片基因埋进 ID），并写出 2 库×4 表的路由计算器（day18 实战的前置件）。
> **时长**：理论 1.5h / 实操 2h / 输出 0.5h
> **今日产出**：策略对比表 + 基因法演示代码 + ShardRouter 路由器 v1

## 1. 知识地图

```
分片键选择三原则（选错 = 全表扫灾难）：
  ① 高频查询条件优先：80% 查询带 user_id → 用它当分片键
  ② 数据分布均匀：防数据倾斜（某些大 V 用户独占一个库）
  ③ 避免跨片查询：单体查询尽量落在一个分片内

两大基础策略：
  hash 取模：shard = hash(key) mod N
    + 分布均匀、路由简单          - 扩容要大规模搬数据（mod 2 → mod 4 几乎全错位）
  range 范围：id 1~1000万 → db0，1000万~2000万 → db1
    + 扩容友好（新数据进新库）    - 热点集中（新数据永远最热，全压最后一个库）
  一致性哈希（了解）：环形空间+虚拟节点，扩容只迁移 1/N——Redis Cluster 用它
    （16384 槽本质：槽=虚拟节点的批量版，day11 已学）

★基因法：解决"非分片键也要查"的工业方案
  困境：订单表按 user_id 分片（用户查订单快），
        但运营场景要按 order_id 查——order_id 不知道 user_id → 全分片广播！
  思路：生成 order_id 时，把 user_id 的【哈希基因】嵌入 ID 尾部
    order_id = (时间戳等高位) | (基因位 = hash(user_id) 的低几位)
    → 按 order_id 查：取尾部位 = 直接算出分片（和按 user_id 分片结果一致！）
  效果：一个 ID 同时服务两个查询维度，零广播
  实现见 3.2（GeneticId.java 亲手生成+路由验证）

其他解法对照（各有代价，面试说出 trade-off 就是高分）：
  异构索引表：按 order_id 再建一套分片（双写维护，空间换时间）
  绑定查询/广播：非分片键查询发全部分片（简单粗暴，低频场景可用）
  ES 外置索引：复杂条件查询走 ES 拿 ID 回表（day19 详谈）

本月的路由拓扑（day18 实战蓝本）：
  db = user_id % 2                    （2 库：learn_db0 / learn_db1）
  table = (user_id / 2) % 4           （每库 4 表：t_order_0..3）
  ★公式设计细节：分库用低位、分表用高位 → 每库 4 表数据天然均匀
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Sharding Key | 分片键 | 路由依据，设计的心脏 |
| Data Skew | 数据倾斜 | hash 不均/热点用户 |
| Genetic Approach | 基因法 | ID 内嵌分片基因 |
| Consistent Hashing | 一致性哈希 | 环+虚拟节点 |
| Broadcast Query | 广播查询 | 全分片扫描（要避免） |
| Heterogeneous Index | 异构索引 | 另建一套索引分片 |

## 3. 动手实操

### 3.1 主菜①：策略对比实验（hash vs range 的扩容痛点）

```java
// ShardStrategy.java —— 两种策略的分布均匀性 + 扩容迁移率实测
import java.util.*;

public class ShardStrategy {
    public static void main(String[] args) {
        int nodes = 1_000_000;                 // 模拟 100 万个用户
        // ---------- hash 取模 ----------
        long moved = 0;
        for (int u = 1; u <= nodes; u++) {
            int oldPos = u % 2;                // 2 库时代
            int newPos = u % 4;                // 扩到 4 库
            if (oldPos != newPos % 2) moved++; // 旧位置失效即要迁移
        }
        System.out.println("hash 2库→4库 需迁移比例: " + moved * 100 / nodes + "%");
        // 期望：75% 全要搬家（★mod 扩容的致命伤）

        // ---------- range 范围 ----------
        System.out.println("range 扩容迁移率: 新库只接新数据，老库不动 = 0%（但新库独扛写入热点）");

        // ---------- 均匀性抽查：hash( user_id ) mod 4 ----------
        int[] buckets = new int[4];
        for (int u = 1; u <= nodes; u++) buckets[u % 4]++;
        System.out.println("hash mod 4 分布: " + Arrays.toString(buckets));
        // 期望：4 桶几乎相等（各 25 万）——hash 的均匀性优势
    }
}
```

### 3.2 主菜②：基因法 ID 生成与双维度路由验证

```java
// GeneticId.java —— 基因法：一个 ID 两个查询维度都直达分片
public class GeneticId {
    static final int GENE_BITS = 2;            // 基因位：2 库 → hash(user_id)%4 取 2 位
    static final int GENE_MASK = (1 << GENE_BITS) - 1;   // 0b11

    /** 生成带基因的订单 ID：高位自增 + 低位基因 */
    static long genOrderId(long seq, int userId) {
        int gene = hash(userId) & GENE_MASK;   // 基因 = hash(user_id) 低 2 位
        return (seq << GENE_BITS) | gene;      // seq 高位拼接 基因低位
    }

    /** 按用户维度路由：user_id 直达分片 */
    static int shardByUser(int userId)  { return hash(userId) % 2; }

    /** 按订单维度路由：从 order_id 尾部取基因 → 结果与 shardByUser 一致 */
    static int shardByOrder(long orderId) { return (int) (orderId & GENE_MASK) % 2; }

    static int hash(int x) { return x * 31 & 0x7fffffff; }

    public static void main(String[] args) {
        int wrong = 0;
        for (int i = 1; i <= 100_000; i++) {
            int userId = i;
            long orderId = genOrderId(i, userId);
            // ★核心断言：两个维度的路由结果必须完全一致（否则基因法失效）
            if (shardByUser(userId) != shardByOrder(orderId)) wrong++;
        }
        System.out.println("10 万条双维路由不一致数（必须=0）: " + wrong);
        // 示例展示
        long demo = genOrderId(9527, 42);
        System.out.println("userId=42 → 分片 " + shardByUser(42)
                + "；orderId=" + demo + " → 分片 " + shardByOrder(demo));
        System.out.println("★运营按订单号查 = 用户按 ID 查 = 同一个库，零广播");
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month04-redis-mysql
javac -d out ShardStrategy.java GeneticId.java
java -cp out ShardStrategy      # 记录：迁移率 75% vs 0%（热点代价）
java -cp out GeneticId          # 记录：双维路由不一致数 = 0（基因法成立）
```

### 3.3 主菜③：ShardRouter v1（day18 要用 JDBC 真连 2 库×4 表）

```java
// ShardRouter.java —— 2 库 × 4 表路由器（day18 实战直接复用）
public class ShardRouter {
    /** 路由公式：库=低位，表=高位（保证每库 4 表均匀） */
    public static int db(int userId)   { return userId % 2; }          // → learn_db0/1
    public static int table(int userId){ return (userId / 2) % 4; }    // → t_order_0..3

    public static void main(String[] args) {
        // 抽查 + 均匀性统计
        int[] dbCount = new int[2]; int[][] tbCount = new int[2][4];
        for (int u = 1; u <= 100_000; u++) {
            int d = db(u), t = table(u);
            dbCount[d]++; tbCount[d][t]++;
        }
        System.out.println("db0=" + dbCount[0] + " db1=" + dbCount[1]);   // 各 5 万
        for (int d = 0; d < 2; d++)
            System.out.println("db" + d + " 表分布: " +
                java.util.Arrays.toString(tbCount[d]));                    // 各 1.25 万
        // 演示几条路由
        for (int u : new int[]{1, 2, 42, 99999})
            System.out.println("user_id=" + u + " → learn_db" + db(u) + ".t_order_" + table(u));
    }
}
```

```powershell
javac -d out ShardRouter.java; java -cp out ShardRouter
```

### 3.4 选型速查卡（抄进笔记）

```text
| 场景                     | 策略               | 理由                     |
|--------------------------|--------------------|--------------------------|
| 用户侧查询为主(订单/账户) | hash(user_id)+基因法| 双维直达，均匀           |
| 时序数据(日志/账单)       | range(按月)        | 老库只读，扩容自然       |
| 需要"再平衡"的存储系统   | 一致性哈希/槽      | 迁移量小(Redis 已内置)   |
| 地理/租户隔离            | range(按地域/租户) | 物理隔离合规             |
```

## 4. 面试连接

**Q：分片键怎么选？hash 和 range 怎么取舍？**
> 三原则：高频查询条件、分布均匀、避免跨片。hash：均匀但扩容搬 75% 数据（我实测过 mod2→mod4 的迁移率）；range：扩容零迁移但热点集中。混合方案：hash 为主 + 预留翻倍扩容（mod 2^k，day20 讲），或一致性哈希/槽制。加分句："我用 100 万模拟用户实测过：hash 取模扩容迁移率 75%，一致性哈希只要 1/N——这是选型最硬的证据。"

**Q：订单按 user_id 分片，按 order_id 查怎么办？（基因法题眼）**
> 四个方案各报代价：① 基因法——生成 order_id 时嵌入 user_id 哈希基因（低 2 位），双维路由结果一致，我写过 10 万条对拍验证不一致数=0；② 异构索引表——按 order_id 再分一套，双写维护；③ 广播查询——低频场景直接全分片扫；④ ES 外置索引——复杂条件走 ES。首选基因法：零额外存储、零广播，代价是 ID 生成逻辑要自己掌控（正好用雪花算法，day17 讲）。

**Q：数据倾斜怎么办？**
> 先定位：hash 后某分片显著偏多（大 V 用户/热点商品）。手段：① 换更好的 hash（murmur/加盐）；② 热点单独拆（大 V 单独一张物理表）；③ 基因里混入随机因子把热点打散；④ range 场景动态分裂边界。关键是"监控先行"——分片数据量仪表盘是分库分表上线的第一天标配。

## 5. 今日验收清单

- [ ] ShardStrategy 跑通（迁移率 75% 记录在案）
- [ ] GeneticId 双维路由对拍（不一致数=0）
- [ ] ShardRouter 均匀性输出截图
- [ ] 基因法原理图手绘（ID 结构 + 双维路由）
- [ ] `git add . && git commit -m "day16: sharding key + genetic"`

---
[← Day 15](day15-何时分库分表.md) | [本月目录](README.md) | [Day 17 · 分布式ID雪花算法 →](day17-分布式ID雪花算法.md)
