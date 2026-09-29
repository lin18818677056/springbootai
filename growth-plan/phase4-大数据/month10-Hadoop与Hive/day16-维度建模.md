# Day 16 · 维度建模：星型模型与拉链表

> **今日目标**：掌握维度建模方法论（Kimball）——一句话：**事实表记"发生了什么事"（一行一次事件，带数字），维度表记"参与者是谁"（一行一个对象，带描述）**，星型模型就是"中间一张事实表，周围挂一圈维度表"。今天为订单域设计星型模型，并攻克难点拉链表。
> **时长**：维度建模理论 1.5h / 订单域建模实操 1.5h / 拉链表 1.5h
> **今日产出**：订单域星型模型设计图 + SCD Type2 拉链表实操（建表+查询）

## 1. 知识地图

```
分析场景和交易场景的建模思路完全不同：
  交易库（MySQL）在意"改得快"：规范化范式，少冗余，改一处生效；
  数仓在意"查得快"：故意冗余，能不 Join 就不 Join——这就是维度建模。

两个核心角色：
事实表 Fact Table——"事件记录仪"：
  一行 = 一次业务事件（一单/一次点击/一次支付）
  三件套：维度外键（谁/在哪/什么品类）+ 度量（金额、数量——可加的数字）+ 时间
  例：dwd_order_detail（一行一单：uid, sku_id, amount, dt）
维度表 Dimension Table——"花名册"：
  一行 = 一个对象（一个用户/一个商品/一个城市）
  内容全是描述属性：用户表（昵称/注册日期/会员等级）
  例：dim_user, dim_sku, dim_date

星型模型 Star Schema（数仓标配）：
       dim_user
          │
dim_sku—事实表—dim_city      ←事实表居中，维度表像星星围着它
          │
       dim_date
  为什么叫"星型"：Join 结构简单——事实表只 Join 一层维度（全部用宽退化+简单连接）
  对比雪花模型 Snowflake：维度表再拆子表（城市→省→国家，规范化拆分）
  ——雪花省存储但多一层 Join（数仓故意反范式，所以国内实践主流是星型）

难点攻坚：缓慢变化维 SCD（Slowly Changing Dimension）——
"用户把收货地址从北京改到上海，历史订单算哪个城市？"三个策略：
  Type1 直接覆盖：改维度表原值——历史订单跟着"变成上海"（简单但篡改历史）
  Type2 拉链表：维度变化保留历史版本，每行加 start_date/end_date——
    查历史订单时按订单时间对上"当时"的版本（数仓标准答案）
  Type3 加列：只保留"改前/改后"两列——只能记一次变化，很少用

拉链表实操理解（背下这个例子）：
dim_user_zip（拉链表）：
  uid  level  start_date  end_date
  101  普通   2020-01-01  2023-06-30   ←这段日子他是普通会员
  101  黄金   2023-07-01  9999-12-31   ←之后是黄金（9999=现行版本）
  查"2023-08-01 他的等级"：WHERE start_date<=日期<end_date → 黄金
  查"2023-05-01 他的等级"：→ 普通 ——历史可回放！
  更新规则：变化那天，旧行 end_date 改为昨天，新插一行 start_date=今天
  （INSERT OVERWRITE 更新 + 追加新行——day25 数仓项目会写这个任务）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Fact Table | 事实表（一行一次事件，带可加的数字度量） |
| Dimension Table | 维度表（一行一个对象，带描述属性） |
| Star / Snowflake Schema | 星型（一圈维度）/ 雪花（维度再拆层） |
| SCD Type1/2/3 | 缓慢变化维三策略（覆盖 / 拉链保历史 / 加列） |
| 拉链表 Zipper Table | Type2 的实现（start/end 日期开闭区间记版本） |
| 可加性 | 度量的加法属性（金额可加；比率不可直接加=半可加） |

## 3. 动手实操：订单域星型模型 + 拉链表

```sql
-- ① 订单域星型模型设计（DDL 骨架，day25 施工）
CREATE TABLE shop.dim_user (uid BIGINT, nickname STRING, level STRING, city STRING)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE;
CREATE TABLE shop.dim_sku (sku_id BIGINT, sku_name STRING, category STRING, price DOUBLE)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE;
CREATE TABLE shop.dim_date (dt STRING, week INT, is_holiday BOOLEAN)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE;
-- 事实表：维度全用 id，需要名字时 Join 维度表（或 DWD 退化）
CREATE TABLE shop.dwd_order_di (oid BIGINT, uid BIGINT, sku_id BIGINT,
  amount DOUBLE, dt STRING) PARTITIONED BY (dt STRING)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE;

-- ② 拉链表实操（亲手做一次 SCD Type2）
CREATE TABLE shop.dim_user_zip (uid BIGINT, level STRING,
  start_date STRING, end_date STRING)
  ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE;
INSERT INTO shop.dim_user_zip VALUES
 (101,'normal','2020-01-01','2023-06-30'),
 (101,'gold','2023-07-01','9999-12-31'),
 (102,'normal','2021-03-01','9999-12-31');
-- 查"当时"：历史两天的会员等级
SELECT '2023-05-01' AS q, level FROM shop.dim_user_zip
  WHERE uid=101 AND start_date<='2023-05-01' AND end_date>'2023-05-01'
UNION ALL
SELECT '2023-08-01' AS q, level FROM shop.dim_user_zip
  WHERE uid=101 AND start_date<='2023-08-01' AND end_date>'2023-08-01';
-- 结果：normal / gold —— 历史回放成功

-- ③ 模拟"等级再变一次"的更新（拉链滚动）
INSERT INTO shop.dim_user_zip VALUES (101,'platinum','2026-09-01','9999-12-31');
-- 生产里旧行的 end_date 要改成 2026-08-31（这里用 OVERWRITE 重写演示完整版）
INSERT OVERWRITE TABLE shop.dim_user_zip
SELECT uid, level, start_date,
  CASE WHEN uid=101 AND level='gold' THEN '2026-08-31' ELSE end_date END
FROM shop.dim_user_zip;
SELECT * FROM shop.dim_user_zip WHERE uid=101 ORDER BY start_date;
-- 三行版本链：normal→gold(已关闭)→platinum(现行) ——完整可回放历史
```

## 4. 面试连接

**Q：什么是维度建模？星型和雪花模型怎么选？（建模必考）**
> 维度建模是 Kimball 提出的分析库建模方法，核心两个角色：事实表一行记录一次业务事件，内容是维度外键加可加的度量（一单一行，带金额数量）；维度表一行记录一个对象的描述属性（一个用户一行花名册）。事实表居中、一圈维度表围着它，Join 关系像星星，所以叫星型模型——它的好处是任何分析都是"事实表 Join 一层维度"，SQL 简单、优化器友好。雪花模型是把维度表再规范化拆层（城市表再挂省表、国家表），省存储但多一层 Join——数仓场景存储便宜、Join 昂贵，所以故意反范式，国内实践主流是星型加维度退化（day15 讲过：常用维度字段直接冗余进事实表，连这一层 Join 都省）。选型结论一句话：数仓默认星型，只有维度属性爆炸到难以维护时才考虑局部雪花。我会补充一个实战判断点：度量要注意可加性——金额可以跨订单加，但"客单价"这种比率不能直接加（要 GMV 除以人数重新算），评审别人模型时先看度量定义有没有可加性问题。

**Q：拉链表是什么？什么场景用？怎么更新？（难点题，考真做过）**
> 拉链表是 SCD Type2 的标准实现：维度表每行加 start_date 和 end_date 两个字段，变化时旧行关闭（end_date 改为昨天）、新行开启（start_date 今天，end_date 填 9999 表示现行）——像拉链一齿一齿记录每个时期的版本。解决的核心问题是"历史回放"：用户 2023 年 7 月从普通会员升黄金，那 6 月的历史订单按业务必须算"普通会员的订单"，直接覆盖（Type1）历史就篡改了。适用场景：变化频率低的维度（等级、地址、组织架构），变化频繁的（实时库存）拉链表会疯狂膨胀，不合适。更新流程我实操过：先重写旧行关链（INSERT OVERWRITE 加 CASE 把命中行 end_date 改为 T-1），再追加新行开链——两个动作放进同一个天级任务，配合 INSERT OVERWRITE 的幂等性可以安全重跑。查的时候就是经典的区间条件：start_date<=查询日<end_date。一句话总结：拉链表用两个日期字段换来了完整的时间旅行能力，是数仓里"用结构换功能"的典范设计。

## 5. 今日验收清单

- [ ] 事实表/维度表定义+三件套能讲（含可加性）
- [ ] 星型 vs 雪花选型结论与理由能展开
- [ ] 拉链表三行版本链实验完成（历史回放+滚动更新都跑过）
- [ ] SCD 三策略一句话对比（覆盖/拉链/加列）
- [ ] 订单域星型模型 DDL 入库（day25 直接用）
- [ ] `git add . && git commit -m "day10-16: dim model"`

---
[← Day 15](day15-数仓分层.md) | [本月目录](README.md) | [Day 17 · 事实表类型 →](day17-事实表类型.md)
