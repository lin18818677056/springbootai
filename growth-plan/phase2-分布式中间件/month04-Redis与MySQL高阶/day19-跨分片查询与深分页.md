# Day 19 · 跨分片查询与深分页（二次查询法 / 游标 / 异构索引）

> **今日目标**：分片后的两大查询之痛：① 深分页——`LIMIT 1000000,10` 为什么让 DB 疯狂；② 跨分片归并——每片取 TopN 再内存合并的真实成本。今天在 500 万行表上实测退化曲线，学会三大解法并画出二次查询法的完整时序。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：深分页退化实测数据 + 游标分页改造记录 + 二次查询法时序图 + 治理决策卡

## 1. 知识地图

```
深分页为什么慢（本质：OFFSET 的代价是"白翻页"）：
  SELECT * FROM t ORDER BY id LIMIT 1000000, 10
  = 排序后【真的扫过 100 万行、扔掉它们】再给你 10 行
  跨分片更惨：每个分片都要 OFFSET 100 万 → 8 片就是扫 800 万白翻页
  （month03 day20 治理过单库深分页——今天升级到分片版）

解法一：游标分页（keyset pagination，★首选）
  WHERE id > :lastId ORDER BY id LIMIT 10
  = 直接从 lastId 的位置继续（B+ 树定位 O(logN)，零白翻页）
  前提：id 有序 → 雪花算法趋势递增（day17）在这里兑现价值
  局限：只能"下一页"不能跳页；跳页场景用解法二/三
  跨分片兼容性：按 id 排序时天然兼容（各片取 > lastId 前 N，归并后再取 N）

解法二：二次查询法（精准分页，不用 ES 时的正解）
  目标：全局第 100 万~100 万+10 名（按 amount 排序，跨 8 片）
  朴素法：每片 LIMIT 1000010 → 归并 800 万行进内存 → 排序取 10 → 内存爆炸
  二次法（把"精确的 OFFSET"换成"边界值+第二轮"）：
  第一轮：每片 LIMIT 1000010 只取【最小 amount 边界值】MIN_B（聚合后取全局第 N 的值）
          （聚合可以下推：SELECT MIN(amount) ... 每片只回传一个数）
  第二轮：每片 WHERE amount >= MIN_B ORDER BY amount LIMIT 小N
          → 归并小结果集精确截取（各片只需几百行）
  代价：两次往返；收益：内存占用从 800 万行 → 几千行
  （理解关键：用"值"定位代替"行数"定位）

解法三：异构索引 / ES（复杂条件的终极归处）
  场景：多条件组合 + 排序 + 深分页（运营后台标配）
  方案：写入时同步 ES（canal/MQ 保证最终一致）→ 复杂查询走 ES
        → ES 返回主键列表 → 回 DB 点查组装（走基因/分片键路由）
  代价：双写一致性、ES 运维成本 → 只给"搜索型查询"用，别全量上

归并的四类操作（分片中间件的 merge 引擎，抄进笔记）：
  排序归并：各片有序流 → 多路归并（堆）→ 全局有序
  分页归并：全局 OFFSET 映射到每片 OFFSET（所以 OFFSET 大 = 全片放大）
  聚合归并：SUM/MAX 直加直比；AVG 不能直加平均（要 SUM+COUNT 归并后再算！）
  分组归并：相同 key 的组在内存二次聚合
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Deep Pagination | 深分页 | 大 OFFSET 的性能黑洞 |
| Keyset Pagination | 游标分页 | lastId 定位，零白翻页 |
| Two-Phase Query | 二次查询法 | 边界值定位 + 精确截取 |
| Merge | 归并 | 跨片结果的合并引擎 |
| Heterogeneous Index | 异构索引 | ES 承接复杂查询 |
| Fan-out | 扇出 | 一条 SQL 发 N 片 |

## 3. 动手实操

### 3.1 主菜：深分页退化实测（month03 的 500 万行表）

```powershell
docker start mysql-learning
# ① 深分页基准测试：页码越深越慢（记录每组耗时）
docker exec -it mysql-learning mysql -uroot -proot123 learn -e "
SET profiling = 1;
SELECT id, amount FROM big_order ORDER BY id LIMIT 10 OFFSET 0;
SELECT id, amount FROM big_order ORDER BY id LIMIT 10 OFFSET 100000;
SELECT id, amount FROM big_order ORDER BY id LIMIT 10 OFFSET 1000000;
SELECT id, amount FROM big_order ORDER BY id LIMIT 10 OFFSET 4000000;
SHOW PROFILES;"
# 记录曲线：OFFSET 0≈0ms → 100 万≈几百 ms → 400 万≈1s+（线性恶化）
# ② 看它到底干了多少脏活（扫描行数证据）
docker exec -it mysql-learning mysql -uroot -proot123 learn -e "
EXPLAIN ANALYZE SELECT id FROM big_order ORDER BY id LIMIT 10 OFFSET 4000000\G"
#   关注：actual rows 读了几百万行（全为扔掉而读！）
```

```sql
-- ③ 游标分页改造：同样取"第 400 万名之后"的 10 条
SELECT id, amount FROM big_order WHERE id > 4000000 ORDER BY id LIMIT 10;
-- 用 EXPLAIN ANALYZE 对比：rows examined 从 400 万 → 10 行（400 倍差距）
-- 业务含义：App 的"下一页/无限滚动"全部应该这么写
```

### 3.2 跨分片分页模拟：8 张表的多路归并（Java 版）

```java
// ShardPageMerge.java —— 模拟分片中间件的"排序归并"（day18 的 8 张表上场）
import java.sql.*;
import java.util.*;

public class ShardPageMerge {
    public static void main(String[] args) throws Exception {
        String[] urls = {
            "jdbc:mysql://localhost:3306/learn_db0?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
            "jdbc:mysql://localhost:3306/learn_db1?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
        };
        List<Connection> conns = new ArrayList<>();
        for (String u : urls) conns.add(DriverManager.getConnection(u, "root", "root123"));

        // 目标：全局按 amount 倒序的前 10 条（朴素法：每片 LIMIT 10，归并后取 10）
        // —— 单片只取 N 条的前提：只看全局前 N；页码深了单片就得取 N+offset（退化点！）
        List<long[]> all = new ArrayList<>();          // [amount, id]
        int queried = 0;
        for (int d = 0; d < 2; d++)
            for (int t = 0; t < 4; t++)
                try (Statement st = conns.get(d).createStatement();
                     ResultSet rs = st.executeQuery(
                         "SELECT id, amount FROM t_order_" + t + " ORDER BY amount DESC LIMIT 10")) {
                    while (rs.next()) {
                        all.add(new long[]{(long) (rs.getDouble(2) * 100), rs.getLong(1)});
                        queried++;
                    }
                }
        all.sort((a, b) -> Long.compare(b[0], a[0]));   // 内存排序归并
        System.out.println("全局 Top10（朴素法）：归并输入 " + queried + " 行（8 片×10）");
        for (int i = 0; i < 10; i++)
            System.out.println("  #" + (i + 1) + " id=" + all.get(i)[1] + " amount=" + all.get(i)[0] / 100.0);
        // 关键观察：只要全局前 10 → 每片 LIMIT 10 就够（成本低）
        // 但要全局第 100 万~100 万+10 → 每片 LIMIT 1000010 → 朴素法爆炸
        // → 这正是"二次查询法"存在的理由（见知识地图，画时序图）
        for (Connection c : conns) c.close();
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month04-redis-mysql
javac -cp "lib/mysql-connector-j.jar" -d out ShardPageMerge.java
java -cp "out;lib/mysql-connector-j.jar" ShardPageMerge
```

### 3.3 二次查询法时序图（手绘任务）

```text
目标：跨 8 片按 amount 全局第 1000001~1000010 名
  第一轮（探边界）：
    client ──→ [8 片并发] SELECT amount ... ORDER BY amount DESC LIMIT 1000010
           ←── 每片只回传 1 个数：本片第 1000010 名的 amount（边界值 Bi）
    client 取 min(Bi) = MIN_B ← 全局第 100 万名的保底值
  第二轮（精收割）：
    client ──→ [8 片并发] WHERE amount >= MIN_B ORDER BY amount DESC LIMIT 小N
           ←── 每片只回传几百行（从边界附近开始的部分）
    client 内存归并 → 精确截取第 1000001~1000010 名
  对比：朴素法传输+排序 800 万行 → 二次法 < 1 万行
  （面试画这张图 + 说清 min(Bi) 为什么是保底：任何片全局前 N 都不可能低于它）
```

### 3.4 治理决策卡（抄进笔记）

```text
| 查询形态               | 方案            | 备注                     |
|------------------------|-----------------|--------------------------|
| App 无限滚动/下一页     | 游标分页        | 首选，零白翻页           |
| 后台跳页（浅分页≤1万） | 朴素归并        | 能忍                    |
| 后台深分页（精确）     | 二次查询法      | 两次往返换内存安全       |
| 多条件+模糊+深分页     | ES 异构索引     | 搜索型查询的归宿         |
| 报表统计               | 走数仓/从库     | 别碰在线分片库           |
```

## 4. 面试连接

**Q：分库分表后分页查询怎么处理？**
> 分层答：① 游标分页（首选）：WHERE id > lastId，要求 ID 有序——雪花算法趋势递增正好兑现；② 精确跳页：二次查询法——第一轮每片探"全局第 N 名边界值"，第二轮按边界收割，传输量从百万行降到千行（我画过完整时序图）；③ 复杂条件深分页：ES 异构索引承接，回表用基因路由。最后补一句认知："分页的本质矛盾是 OFFSET 把'取 10 条'变成'扫过 N+10 条'——所有方案都在消灭这个 N。"

**Q：跨分片的聚合（比如全局 AVG）怎么做？**
> AVG 不能各片平均再平均（数学错误：各片行数不同权重不同）。正确姿势：归并 SUM 和 COUNT，全局 AVG = 总和/总行数——这就是分片中间件"聚合归并"存在的意义。COUNT/SUM/MAX/MIN 可下推直合并；DISTINCT/ORDER BY 要内存归并去重排序。加分观察："我手写过多路归并 Demo，8 片各取 Top10 归并出全局 Top10——只要全局前 N，单片取 N 即可，这是分页归并的成本下界。"

**Q：ES 回表是什么流程？怎么保证一致性？**
> ES 存 ID+检索字段，查询命中后拿主键列表回 MySQL 点查（有基因/分片键就零广播路由）。一致性走最终一致：binlog（Canal）→ MQ → 同步 ES，失败重试+对账兜底（day20 的对账思想）。绝不推荐业务双写同步调 ES——失败补偿逻辑会污染业务代码，旁路同步才是工程正解。

## 5. 今日验收清单

- [ ] 深分页四档耗时曲线记录（OFFSET 0/10万/100万/400万）
- [ ] 游标分页 EXPLAIN ANALYZE 对比（rows 400 万→10）
- [ ] ShardPageMerge 跑通（全局 Top10 归并）
- [ ] 二次查询法时序图手绘
- [ ] `git add . && git commit -m "day19: shard paging"`

---
[← Day 18](day18-手写分片路由实战.md) | [本月目录](README.md) | [Day 20 · 数据迁移与双写 →](day20-数据迁移与双写.md)
