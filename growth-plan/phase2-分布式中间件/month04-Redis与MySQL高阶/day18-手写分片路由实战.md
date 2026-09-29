# Day 18 · 手写分片路由实战（2 库 × 4 表 JDBC 全流程）

> **今日目标**：把 day16 的路由公式和 day17 的 ID 方案焊成真系统：MySQL 建 2 库 8 表，用纯 JDBC 手写"路由→写入→双维查询"全链路，1 万条订单实测分布均匀——亲手体会一遍 ShardingSphere 在幕后干的事。
> **时长**：实操 3h / 输出 0.5h
> **今日产出**：2 库 8 表环境 + ShardDemo 全链路跑通 + 均匀性报告 + 基因查询验证

## 1. 知识地图

```
今天要手工实现"分片中间件"的五个核心步骤（ShardingSphere 的五脏）：
  ① SQL 解析：INSERT INTO t_order ... 哪个字段是分片键？(user_id)
  ② 路由：user_id=42 → db1.t_order_0（路由公式决定）
  ③ SQL 改写：表名替换成物理表 t_order → t_order_0
  ④ 执行：发往对应的库
  ⑤ 归并：跨片结果合并排序分页（今天单片查询体会不到，day19 体会）

环境拓扑（今天建成）：
  mysql-learning 容器（单实例模拟"两个库"）
   ├─ learn_db0 ── t_order_0 / t_order_1 / t_order_2 / t_order_3
   └─ learn_db1 ── t_order_0 / t_order_1 / t_order_2 / t_order_3
  路由公式（day16 定稿）：db = user_id % 2    table = (user_id/2) % 4

基因设计 v2（比 day16 演示版多 1 位，把"库+表"都装进 ID）：
  table 位需要 (user_id>>1)&3（2 bit），db 位需要 user_id&1（1 bit）→ 共 3 bit 基因
  ┌────────seq(高位)────────┬────基因 3bit────┐
  │      全局唯一序列         │ db(1) │ table(2) │
  gene = ((user_id & 1) << 2) | ((user_id >> 1) & 3)
  还原：db = (gene >> 2) & 1      table = gene & 3
  验证样例：user_id=5 → db=1, table=2 → gene=(1<<2)|(5>>1&3)=4|2=6
            还原 db=(6>>2)&1=1 ✓  table=6&3=2 ✓
  效果：order_id 自带"家庭住址"——按订单号查询零广播直达

生产对照：ShardingSphere-JDBC 就是这套逻辑的产品化
  （配置分片算法 → SQL 自动路由改写归并，业务写普通 SQL）
  先手写一遍，你才看得懂它配置里每一项在干什么
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| SQL Parse | SQL 解析 | 提取表/条件/分片键 |
| Route | 路由 | 定位物理库表 |
| SQL Rewrite | SQL 改写 | 逻辑表名→物理表名 |
| Execute | 执行 | 多分片并发执行 |
| Result Merge | 结果归并 | 排序/分页/聚合合并 |
| Logical/Physical Table | 逻辑/物理表 | t_order vs t_order_0 |

## 3. 动手实操

### 3.1 第一步：建 2 库 8 表

```powershell
docker start mysql-learning
# 建 2 个库
docker exec -it mysql-learning mysql -uroot -proot123 -e `
  "CREATE DATABASE IF NOT EXISTS learn_db0; CREATE DATABASE IF NOT EXISTS learn_db1;"
# 每库建 4 张表（外层循环库，内层循环表）
0..1 | ForEach-Object { $d = $_
  0..3 | ForEach-Object { $t = $_
    docker exec -i mysql-learning mysql -uroot -proot123 "learn_db$d" -e @"
CREATE TABLE IF NOT EXISTS t_order_$t (
  id BIGINT PRIMARY KEY,
  user_id INT NOT NULL,
  amount DECIMAL(10,2),
  status TINYINT DEFAULT 1,
  KEY idx_uid (user_id)
) ENGINE=InnoDB;
"@
  }
}
# 验证 8 张表就位
docker exec -it mysql-learning mysql -uroot -proot123 -e `
  "SELECT table_schema, table_name FROM information_schema.tables `
   WHERE table_name LIKE 't_order%' ORDER BY 1,2;"
```

### 3.2 主菜：ShardDemo（路由写入 + 双维直达查询）

```java
// ShardDemo.java —— 手写分片中间件最小实现（五步中的 ②③④）
import java.sql.*;
import java.util.*;

public class ShardDemo {
    static final String[] URLS = {
        "jdbc:mysql://localhost:3306/learn_db0?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
        "jdbc:mysql://localhost:3306/learn_db1?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    };
    static Connection[] DBS = new Connection[2];

    // ---------- 路由公式（day16 定稿）----------
    static int db(int uid)   { return uid % 2; }             // ② 路由：库
    static int tb(int uid)   { return (uid / 2) % 4; }       // ② 路由：表
    static int gene(int uid) { return ((uid & 1) << 2) | ((uid >> 1) & 3); } // 3 位基因
    static long genOrderId(long seq, int uid) { return (seq << 3) | gene(uid); }
    static int dbByOrder(long oid) { return (int) ((oid >> 2) & 1); }        // 基因还原：库
    static int tbByOrder(long oid) { return (int) (oid & 3); }               // 基因还原：表

    public static void main(String[] args) throws Exception {
        for (int d = 0; d < 2; d++) DBS[d] = DriverManager.getConnection(URLS[d], "root", "root123");

        // ========== ① 造 1 万条订单（按 (d,t) 分桶批量写 = SQL 改写的本质）==========
        Map<Integer, List<long[]>> buckets = new HashMap<>();       // key = d*4+t
        long seqBase = System.currentTimeMillis() / 1000;           // 简化版全局序列
        for (int i = 1; i <= 10_000; i++) {
            int uid = (i * 37) % 99_991;                            // 伪随机分布用户
            int d = db(uid), t = tb(uid);                           // ② 路由
            long oid = genOrderId(seqBase + i, uid);                // 带基因的订单 ID（day17 思想）
            buckets.computeIfAbsent(d * 4 + t, k -> new ArrayList<>())
                   .add(new long[]{oid, uid, (long) (Math.random() * 100000)});
        }
        long begin = System.currentTimeMillis();
        for (var e : buckets.entrySet()) {                          // ③④ 改写+执行
            int d = e.getKey() / 4, t = e.getKey() % 4;
            try (PreparedStatement ps = DBS[d].prepareStatement(
                    "INSERT INTO t_order_" + t + " (id,user_id,amount,status) VALUES (?,?,?,1)")) {
                for (long[] row : e.getValue()) {
                    ps.setLong(1, row[0]); ps.setInt(2, (int) row[1]);
                    ps.setDouble(3, row[2] / 100.0); ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        System.out.println("1 万条写入耗时: " + (System.currentTimeMillis() - begin) + "ms");

        // ========== ② 分布均匀性验证（在 DB 侧数行数）==========
        for (int d = 0; d < 2; d++)
            for (int t = 0; t < 4; t++)
                try (Statement st = DBS[d].createStatement();
                     ResultSet rs = st.executeQuery(
                         "SELECT COUNT(*) FROM t_order_" + t)) {
                    rs.next();
                    System.out.println("learn_db" + d + ".t_order_" + t + " 行数: " + rs.getInt(1));
                }
        // 期望：8 张表各约 1250 行（均匀 ✓）

        // ========== ③ 维度一：按 user_id 查（路由直达）==========
        int uid = 42;
        try (PreparedStatement ps = DBS[db(uid)].prepareStatement(
                "SELECT id, amount FROM t_order_" + tb(uid) + " WHERE user_id = ?")) {
            ps.setInt(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                int n = 0; long first = 0;
                while (rs.next()) { n++; if (n == 1) first = rs.getLong(1); }
                System.out.println("按 user_id=" + uid + " 查 → learn_db" + db(uid)
                        + ".t_order_" + tb(uid) + " 命中 " + n + " 条");
                // ========== ④ 维度二：按 order_id 查（基因还原，零广播！）==========
                if (n > 0) {
                    long oid = first;
                    try (PreparedStatement ps2 = DBS[dbByOrder(oid)].prepareStatement(
                            "SELECT user_id FROM t_order_" + tbByOrder(oid) + " WHERE id = ?")) {
                        ps2.setLong(1, oid);
                        try (ResultSet rs2 = ps2.executeQuery()) {
                            if (rs2.next())
                                System.out.println("按 order_id=" + oid + " 查 → 基因直达 learn_db"
                                        + dbByOrder(oid) + ".t_order_" + tbByOrder(oid)
                                        + "，user_id=" + rs2.getInt(1));
                        }
                    }
                }
            }
        }
        for (Connection c : DBS) c.close();
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month04-redis-mysql
# jar 已在 README 环境准备下载（lib\mysql-connector-j.jar）
javac -cp "lib/mysql-connector-j.jar" -d out ShardDemo.java
java -cp "out;lib/mysql-connector-j.jar" ShardDemo
# 记录三件证据：
#   □ 8 张表行数分布（各 ~1250）
#   □ 按 user_id 查询打印（路由直达单库单表）
#   □ 按 order_id 查询打印（基因还原直达，全程零广播）
```

### 3.3 实验记录与分析卡

```text
写入视角：1 万条 → 8 个桶 → 8 个 executeBatch → 分片就是"分组改写"
  （单库写 1 万条要 1 个连接串行；分片后天然 8 路并发可扩展——写入扩展性来源）

查询视角（今天只体会单片路由）：
  按 user_id：WHERE 条件里有分片键 → 单片路由（最理想路径）
  按 order_id：无分片键但 ID 带基因 → 还原路由（基因法收益）
  都没带（如按 amount 查）→ 只能全分片广播 → day19 讲治理

对照 ShardingSphere：你手写的 db()/tb() 就是它的 sharding-algorithms 配置；
  buckets 分组就是它的 SQL 改写器；明天 day19 的归并 = 它的 merge 引擎
```

## 4. 面试连接

**Q：分片中间件的工作原理？**
> 五步管线：SQL 解析（提取逻辑表/分片键/条件）→ 路由（分片算法定位物理库表）→ SQL 改写（逻辑表名替换物理表名、补齐分页参数）→ 多分片执行（并发发往各库）→ 结果归并（排序/分页/聚合的内存合并）。加分句："我用纯 JDBC 手写过 2 库 4 表的路由 Demo：按 user_id 分桶批量写入、8 张表分布均匀、按订单号靠基因还原直达——ShardingSphere 做的就是把这套逻辑配置化。"

**Q：ShardingSphere-JDBC 和 Proxy 模式怎么选？**
> JDBC 版：应用内嵌 jar，零额外部署、性能损耗小，但每种语言都要接一次；Proxy 版：独立进程伪装成 MySQL，语言无关、异构系统友好，但多一跳网络和运维成本。选型：Java 单体/微服务同构 → JDBC 版；多语言/DBA 统一管控 → Proxy。附加观点："先手写理解原理，再上框架——不然配置错了（比如分片键漏传）根本无从排查。"

**Q：什么情况会导致全分片广播？怎么避免？**
> 查询条件不含分片键且无基因可还原（如按手机号/商品名查订单）→ 中间件只能把 SQL 发给全部分片再归并，QPS 放大 N 倍。治理：① 基因法把高频查询维度编码进 ID；② 异构索引表（按手机号再分一套，双写）；③ C 端复杂查询走 ES 拿主键回表；④ 低频运营查询走数仓不碰在线库。原则："广播不可怕，可怕的是高频路径上出现广播——先量频率再定方案。"

## 5. 今日验收清单

- [ ] 2 库 8 表建表完成（information_schema 验证）
- [ ] ShardDemo 三件证据齐全（分布/用户查询/订单查询）
- [ ] 五步管线图手绘（标出自己实现了哪几步）
- [ ] 广播查询场景与治理方案能讲
- [ ] `git add . && git commit -m "day18: shard routing jdbc"`

---
[← Day 17](day17-分布式ID雪花算法.md) | [本月目录](README.md) | [Day 19 · 跨分片查询与深分页 →](day19-跨分片查询与深分页.md)
