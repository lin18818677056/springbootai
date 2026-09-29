# Day 15 · 何时分库分表（先救火，再拆家）

> **今日目标**：Week3「分库分表」开篇。最重要的一课反而是"什么时候不该拆"：今天先做单表膨胀实验看性能退化曲线，再给出一套"先优化后拆分"的决策清单——面试官最爱考察"你凭什么判断要拆表"。
> **时长**：理论 1.5h / 实操 2h / 输出 0.5h
> **今日产出**：500 万行膨胀实验记录 + 决策清单 + 拆分四象限图

## 1. 知识地图

```
先泼冷水：分库分表是【最后手段】，不是炫技（决策漏斗从上往下试）：
  ① 索引优化/SQL 改写        （month03 Week3 全部武器）
  ② 读写分离                 （读瓶颈先行，写不动才谈拆写）
  ③ 冷热分离/归档            （历史数据搬走：3 年前的订单进归档库）
  ④ 缓存削读                 （day12 三大问题治理后读 QPS 大降）
  ⑤ 升级硬件/换 NVMe         （ Sometimes 最便宜的方案）
  ⑥ 还不行 → 分库分表
  面试金句："我先确认他们试过 ①-⑤，再谈拆分——拆分引入的问题比解决的多。"

单表多少行该警觉（经验值，要有原理支撑）：
  InnoDB B+ 树 3~4 层是甜点区（month03 day15：3 层≈2000 万行）
  行数 × 行宽 决定页数：500 万行×200B ≈ 1GB；2000 万行 ≈ 4GB+ → Buffer Pool 装不下热页
  退化本质：不是"行多"，是【索引树高 + 内存命中率】的联合恶化
  （小表 1 亿行也可能没事——短行宽+纯点查；大字段宽表 500 万就该拆）

拆分四象限（横：库内/跨库，纵：垂直/水平）：
            垂直（按维度切）            水平（按行切）
  库内     垂直分表：大字段拆出          水平分表：t_order_0..3（同库）
           （商品详情 TEXT 单独表）       （减少单表行数，IO 分散）
  跨库     垂直分库：订单库/用户库/       水平分库+分表：db0..1 × t0..3
           商品库（按业务域隔离）         （★终极形态：容量与写入双扩展）

拆分代价清单（day16-20 逐个解决，先立牌坊）：
  ✗ 跨库 JOIN 没了           → 业务层组装 / 字段冗余
  ✗ 跨库事务没了（ACID 降级） → 最终一致（month05 分布式事务）
  ✗ 全局唯一 ID 没了         → 雪花算法（day17）
  ✗ 跨分片查询/分页麻烦      → 二次查询法（day19）
  ✗ 扩容要搬数据             → 一致性 hash/翻倍扩容（day20）
  ✗ 运维复杂度×N             → 分片中间件/监控补齐
  结论：每拆一层，都是在用【开发复杂度】换【单点性能】

四大拆法的适用场景对号：
  垂直分表：订单表里有详情大 TEXT → 拆主表+详情表（列宽瘦身）
  垂直分库：微服务化顺势而为（订单服务配订单库）
  水平分表：单库写够用、只是表太大（过渡方案）
  水平分库：写入 QPS 单库顶不住（终极方案，day18 实战）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Vertical Split | 垂直拆分 | 按列/按业务域切 |
| Horizontal Split | 水平拆分 | 按行哈希切 |
| Sharding | 分片 | 水平拆的通用叫法 |
| Data Archiving | 数据归档 | 冷数据搬家，先于分表 |
| Read/Write Splitting | 读写分离 | 读瓶颈的第一解 |
| Partitioning | 分区 | MySQL 表内分区（不是分表！） |

## 3. 动手实操

### 3.1 主菜：500 万行膨胀实验（复用 mysql-learning 容器）

```powershell
docker start mysql-learning   # month03 的容器
docker exec -it mysql-learning mysql -uroot -proot123 -e "CREATE DATABASE IF NOT EXISTS learn"
docker exec -it mysql-learning mysql -uroot -proot123 learn -e "
CREATE TABLE IF NOT EXISTS big_order (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id INT, amount DECIMAL(10,2),
  status TINYINT, created_at DATETIME);"
```

```sql
-- 递归 CTE 造 500 万行（MySQL 8 玩法，比存储过程快）
INSERT INTO big_order (user_id, amount, status, created_at)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < 5000000
)
SELECT 1 + (n % 100000), ROUND(RAND()*1000,2), FLOOR(RAND()*4),
       NOW() - INTERVAL n SECOND
FROM seq;
-- 约 1-2 分钟。验证：SELECT COUNT(*) FROM big_order;  → 5000000
```

```powershell
# 三连测：记录每次耗时（毫秒级体感）
# ① 点查（主键）——永远快，B+树 O(logN)
docker exec -it mysql-learning mysql -uroot -proot123 learn -e `
  "SELECT * FROM big_order WHERE id = 4999999;"
# ② 索引范围查——先建索引再测
docker exec -it mysql-learning mysql -uroot -proot123 learn -e `
  "CREATE INDEX idx_uid ON big_order(user_id); SELECT COUNT(*) FROM big_order WHERE user_id = 42;"
# ③ 无索引过滤——全表扫 500 万行（慢查询现场）
docker exec -it mysql-learning mysql -uroot -proot123 learn -e `
  "SELECT COUNT(*) FROM big_order WHERE status = 3 AND amount > 900;"
# ④ 看数据体积（这就是"Buffer Pool 装不下"的元凶）
docker exec -it mysql-learning mysql -uroot -proot123 learn -e "
SELECT table_name, ROUND(data_length/1024/1024) AS data_mb,
       ROUND(index_length/1024/1024) AS idx_mb
FROM information_schema.tables WHERE table_schema='learn';"
# 结论记录：500 万行约 300MB+ 索引 → 2000 万行时超 1GB，热数据翻脸
```

### 3.2 决策清单自查卡（抄进笔记）

```text
接手一张"大表"的 6 问（全 NO 才有资格谈拆）：
  □ 慢 SQL 都治理过了吗？（EXPLAIN 过 Top20 吗）
  □ 读写分离上了吗？（读占比 >70% 时收益巨大）
  □ 冷数据归档了吗？（订单类 6 个月前的搬到归档库，通常瘦身 60%+）
  □ 缓存挡读了吗？（热点查询命中率 >90% 吗）
  □ 索引/表结构还有优化空间吗？（冗余字段/大字段外置）
  □ 预估增长曲线：半年/一年后到多少行？（预留 2 年水位）
全 NO → 拆！进入 day16 选分片键
```

### 3.3 分区 vs 分表 vs 分库 辨析（面试常混）

```text
MySQL 分区（PARTITION BY）：单库单表内部，按规则切文件
  优点：SQL 无感；缺点：仍单实例，CPU/连接数瓶颈不解决，生产用得少
分表：单实例内多表 → 解决单表行数/树高问题，不解决实例瓶颈
分库：多实例 → 解决容量/写入 QPS，但引入分布式问题（本月的代价清单）
一句话：分区治"文件"，分表治"索引树"，分库治"实例"
```

## 4. 面试连接

**Q：什么时候需要分库分表？**
> 先反向展示决策漏斗：索引优化→读写分离→归档→缓存→硬件，都不行才拆。量化信号：① 单表行数逼近 2000 万且行宽大（B+ 树从 3 层涨 4 层、Buffer Pool 命中率下降）；② 单实例写入 QPS 到瓶颈（一般几千到万级）；③ 磁盘容量到水位。加分句："我在 500 万行表上做过膨胀实验：点查恒定快，但全表扫和 Buffer Pool 压力随行数线性恶化——所以关键指标不是行数，是【热数据是否还装得进内存】。"

**Q：垂直拆和水平拆分别解决什么问题？**
> 垂直分表治"行太宽"（大字段拖累页密度与 Buffer Pool 效率）；垂直分库治"业务耦合"（微服务边界）；水平分表治"行太多"（树高与扫描量）；水平分库治"实例瓶颈"（容量+写入 QPS）。顺序一般是：垂直分表 → 水平分表 → 水平分库分表，每步都有明确代价（跨库 JOIN/事务/ID/分页），我能在白板上列出代价清单和对应解法。

**Q：分库分表后最大的坑是什么？**
> 不再是 SQL 问题，而是"分布式税"：跨库 JOIN 消失（业务层组装/冗余字段）、本地事务消失（最终一致+消息表，month05）、全局 ID（雪花，day17）、跨分片分页（二次查询法，day19）、扩容迁移（双写方案，day20）。我会说："这些坑每个都有成熟解法，但都要提前设计——所以我的原则是确有必要才拆，并预留平滑扩容方案（比如基因法分片键从第一天就埋好）。"

## 5. 今日验收清单

- [ ] 500 万行实验四连测记录（含表体积查询）
- [ ] 决策漏斗六问卡默写
- [ ] 拆分四象限手绘 + 代价清单
- [ ] 分区/分表/分库一句话辨析能讲
- [ ] `git add . && git commit -m "day15: when to shard"`

---
[← Day 14](day14-分布式锁与第二周复盘.md) | [本月目录](README.md) | [Day 16 · 分片策略与基因法 →](day16-分片策略与基因法.md)
