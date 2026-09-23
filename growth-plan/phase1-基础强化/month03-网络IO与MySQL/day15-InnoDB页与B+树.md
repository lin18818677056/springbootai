# Day 15 · InnoDB 页结构与 B+ 树（为什么 3 层能存 2000 万行）

> **今日目标**：进入 MySQL 周。索引不是"魔法加速器"而是磁盘上的数据结构——今天从"数据的物理之家"讲起：16KB 的页、页里的布局、B+ 树怎么用页搭出来，亲手算一遍"为什么 3 层 B+ 树≈2000 万行"这个经典面试题。
> **时长**：理论 1.5h / 实操 1.5h / 输出 0.5h
> **今日产出**：页结构手绘图 + B+ 树手绘图 + 100 万行表的树高估算实证

## 1. 知识地图

```
InnoDB 一切从页开始（Page = 磁盘/内存交互最小单位，默认 16KB）：

一个 16KB 页的布局（自上而下）：
  ┌─────────────────────────────┐
  │ File Header（38B）           │ ← 页号/前后页指针/LSN（页是双向链表）
  ├─────────────────────────────┤
  │ Page Header（56B）           │ ← 记录数/槽位信息
  ├─────────────────────────────┤
  │ Infimum + Supremum          │ ← 虚拟最小/最大记录（边界哨兵）
  ├─────────────────────────────┤
  │ User Records（用户记录区）    │ ← 真实数据行，按主键【有序】排布
  ├─────────────────────────────┤
  │ Free Space（空闲区）          │
  ├─────────────────────────────┤
  │ Page Directory（页目录）     │ ← 二分查找的"槽"，组内再遍历
  ├─────────────────────────────┤
  │ File Trailer（8B）           │ ← 校验和（刷盘完整性）
  └─────────────────────────────┘
  ★页内查找：页目录二分（log n 组）+ 组内顺序 → 很快

B+ 树 = 页套页（非叶子页是"路标页"，叶子页是"数据页"）：
  ┌───────────────────────────┐
  │ 根页（路标）: 键1→P1 键2→P2 │
  └─────────┬─────────┬───────┘
            ▼         ▼
      ┌──────┐   ┌──────┐
      │ 内层页 │···│ 内层页 │        （路标，只放主键+指针）
      └──┬───┘   └──┬───┘
         ▼          ▼
    [叶子]⇄[叶子]⇄[叶子]          （数据页，双向链表）
  两个必答特性：
    ① 非叶子只放键+指针（不存数据）→ 单页能放更多路标 → 树更矮
    ② 叶子双向链表 → 范围查询/排序顺着链走，不用回树顶

树高三层 ≈ 2000 万行的计算题（★面试高频，必须会推）：
  非叶子页每条 = 主键 bigint 8B + 页指针 6B = 14B
    → 单页路标数 ≈ 16KB / 14B ≈ 1170
  叶子页每行假设 1KB → 单页 16 行
  两层路标 × 叶子 = 1170 × 1170 × 16 ≈ 2190 万行
  结论：3 次 IO（其实是 1 次——根页常驻 Buffer Pool）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Page | 页 | InnoDB 最小 IO 单位 16KB |
| Clustered Index | 聚簇索引 | 叶子=整行数据，主键组织 |
| Secondary Index | 二级索引 | 叶子=主键值（明天细讲） |
| Page Directory | 页目录 | 页内二分查找的槽位表 |
| B+ Tree | B+ 树 | 矮胖多叉树，路标+叶子链 |
| Buffer Pool | 缓冲池 | 页的内存缓存（day27 细讲） |

## 3. 动手实操

### 3.1 环境：MySQL 容器开工检查（README 装好的直接跳到 §3.2）

```powershell
docker ps --filter "name=mysql-learning"          # 在跑吗？
docker start mysql-learning                        # 停着就拉起来
docker exec -it mysql-learning mysql -uroot -proot123 -e "SELECT VERSION();"
# 正常打印 8.4.x 就绪。进交互式：docker exec -it mysql-learning mysql -uroot -proot123
```

### 3.2 验证页大小 + 造 100 万行数据（今天的主角）

```sql
-- ① 验证页大小（在 mysql 客户端里执行）
SHOW VARIABLES LIKE 'innodb_page_size';    -- 16384 = 16KB

-- ② 建演示表（后面 6 天都用它）
CREATE DATABASE IF NOT EXISTS learn;
USE learn;
DROP TABLE IF EXISTS t_index;
CREATE TABLE t_index (
    id      BIGINT PRIMARY KEY AUTO_INCREMENT,
    name    VARCHAR(32) NOT NULL,
    status  TINYINT     NOT NULL DEFAULT 0,
    created DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_status (status)
) ENGINE=InnoDB;
```

```powershell
# ③ 造数脚本走文件（100 万行约 1~3 分钟，正好泡杯茶）
#    PowerShell 不支持 "<" 重定向，用管道把 SQL 喂给容器
@'
USE learn;
DELIMITER $$
DROP PROCEDURE IF EXISTS gen_data $$
CREATE PROCEDURE gen_data(IN total INT)
BEGIN
    DECLARE i INT DEFAULT 0;
    WHILE i < total DO
        INSERT INTO t_index(name, status)
        SELECT CONCAT('user-', i), i % 10;
        SET i = i + 1;
    END WHILE;
END $$
DELIMITER ;
'@ | Set-Content -Encoding UTF8 gen.sql
Get-Content -Raw gen.sql | docker exec -i mysql-learning mysql -uroot -proot123

# ④ 批量执行插入（每 1 万行一个事务，避免超大事务）
1..100 | ForEach-Object {
    "CALL learn.gen_data(10000);" | docker exec -i mysql-learning mysql -uroot -proot123
}
docker exec -it mysql-learning mysql -uroot -proot123 -e "SELECT COUNT(*) FROM learn.t_index;"
# 应返回 1000000
```

### 3.3 实证：100 万行表的树高估算

```sql
-- 用 InnoDB 内置统计查主键索引占了多少页
SELECT index_name, stat_value AS pages, stat_description
FROM mysql.innodb_index_stats
WHERE table_name = 't_index' AND stat_name = 'size';
-- 预期输出（数值因行长略有出入）：
--   PRIMARY    约 16000~20000 页（叶子+内层+根）
--   idx_status 约 100~200 页（只放 status+主键，矮很多）

-- 口算树高（把数字套进 §1 公式）：
--   每行约 50B（name~12B+status 1B+时间戳 8B+行头+间隙）
--   → 叶子页每页约 250+ 行 → 100 万行 ≈ 4000 叶子页
--   4000 < 1170² → 内层 1 层就够 → 树高 = 3（根/内层/叶子）
--   查一次 = 最多 3 次页读取，而根页+内层页基本常驻内存 → 常 1 次磁盘 IO
```

### 3.4 手绘任务：两张图（对着 §1 抄一遍，合上默画一遍）

```text
图① 16KB 页布局（7 段，标注 User Records 有序 + Page Directory 二分）
图② 三层 B+ 树（标注：非叶子=键+指针 / 叶子=数据+双向链表 / 三层 2000 万推导）
默写验收：能对空气讲清"一条 SQL WHERE id=42 在树上怎么走"
  → 根页二分定位内层页 → 内层页二分定位叶子页 → 叶子页内二分+组内扫描 → 命中
```

## 4. 面试连接

**Q：为什么 InnoDB 用 B+ 树而不是 B 树/红黑树/哈希？**
> 四连答：① 红黑树是二叉，100 万行高 20 层=20 次 IO，B+ 树多叉矮胖 3 层搞定；② B 树非叶子也放数据→路标少→树更高，且范围查询要中序回溯；③ B+ 叶子链表天然支持范围扫描和 ORDER BY；④ 哈希等值快但不支持范围/排序/最左前缀，Memory 引擎才默认哈希。加分句："我造过 100 万行的表用 innodb_index_stats 算过页数，套 1170×1170×16 公式验证了三层两千万。"

**Q：InnoDB 为什么按页而不是按行读写？**
> 磁盘局部性原理：一次 IO 的成本大头是寻道/旋转，读 16B 和 16KB 差别不大——干脆按页批量搬运，命中率靠空间/时间局部性撑起来。页也是 Buffer Pool 的管理单位、锁与日志的单位。这一答顺带解释了"预读"为什么有效。

**Q：页内记录是有序的，插入新行为什么不慢？**
> 有序是逻辑有序：行在物理上可能乱序移动，页目录只记槽位；页满就分裂（中间分裂点，留一半空位给后续插入），分裂由 B+ 树自动平衡。坑：随机主键（如 UUID）造成频繁页分裂+碎片，所以 InnDB 实践推荐递增主键——这个话题明天结合回表继续展开。

## 5. 今日验收清单

- [ ] MySQL 容器跑通，`innodb_page_size` = 16384 确认
- [ ] 100 万行 t_index 造完（COUNT=1000000）
- [ ] innodb_index_stats 页数查询记录 + 树高口算过程
- [ ] 两张图默画通过（对着空气讲清 id=42 的查找路径）
- [ ] `git add . && git commit -m "day15: innodb page & b+tree"`

---
[← Day 14](day14-手写迷你RPC下与第二周复盘.md) | [本月目录](README.md) | [Day 16 · 聚簇/回表/覆盖/联合索引 →](day16-聚簇回表覆盖与联合索引.md)
