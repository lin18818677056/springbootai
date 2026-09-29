# Day 25 · 数仓项目（一）：ODS → DWD，把原料收好、洗净、切好

> **今日目标**：正式开工"电商订单数仓"项目。今天完成前半段——把业务数据**原样收进仓库**（ODS 外部表挂载），再**洗菜切配**（DWD 清洗+维度退化），全程用 day21 的《建模 Checklist》把关。
> **时长**：造数与挂载 1.5h / DWD 清洗 2h / DQC 与 Checklist 过检 1.5h
> **今日产出**：数仓前两层（`ods_order_detail` → `dwd_order_detail_di`）+ DQC 强规则跑通

## 1. 知识地图

```
项目全貌：一条 5 站流水线（day15 的厨房四区图落地成真表）
  业务库 MySQL ──同步──▶ ODS 进货区 ──清洗──▶ DWD 洗配区 ──汇总──▶ DWS 备餐区 ──摆盘──▶ ADS 出餐口
                                       （今天干到这）           （day26）          （day26）
餐厅类比（一句话记住两层职责）：
  ODS = 收货员：不挑不拣，供应商送来什么就原样签收入库——出问题能找供应商对质
  DWD = 洗配工：烂叶子扔掉（脏数据）、土豆削皮（单位规范）、
        按菜谱切好（维度退化），装进标准餐盒（ORC+分区）

DWD 清洗四件事（每件都对应一句大白话）：
  ① 去重     —— 同一张订单出现两次（重发/补录），只留一条
  ② 过滤脏行 —— amount<=0、必填字段缺失的行：不能默默扔，要"打标记录后隔离"
  ③ 单位规范 —— 业务库存的是"分"，分析时用"元"：/100 统一，杜绝口径打架
  ④ 维度退化 —— dim_user 就 3 个字段且很少变？直接把 uid+level 拷进行里（day16 退化法），
               下游少 Join 一张表；但"用户积分"这种常变的绝不退化
两个铁规矩（前面 22 天的伏笔今天全部兑现）：
  写入只用 INSERT OVERWRITE 分区（day11 幂等铁律：重跑一百遍结果都一样）
  建表先过 Checklist 15 项（day21：粒度/分层/格式/质量四组）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| ODS (Operational Data Store) | 原样层：业务数据的"备份仓库"，只存不改 |
| DWD (Data Warehouse Detail) | 明细层：洗好切好的"标准半成品"，一行=一次业务动作 |
| 维度退化 | 把小维度表的字段直接拷进事实表（省一次 Join） |
| 脏数据隔离 | 坏行不是扔掉，是挪到"隔离区"表并打标（可追责可回溯） |
| 幂等写 | 同一段 SQL 跑多少次，结果都一样（OVERWRITE 分区） |

## 3. 动手实操：前两层施工

```powershell
# ===== 步骤 1：造 3 天订单数据（容器内 shell 生成，纯英文内容无编码坑）=====
docker exec hadoop-single bash -c "
for d in 25 26 27; do
  f=/tmp/ods_order_2026-09-\$d.csv
  rm -f \$f
  for i in 1 2 3 4 5 6 7 8 9 10; do
    echo \"202609\$d,10\$i,\$((i*1000)),book,2026-09-\$d 10:\$i:00\" >> \$f   # 正常行
  done
  echo \"202609\$d,101,-500,book,2026-09-\$d 11:00:00\" >> \$f               # 脏行：金额为负
  echo \"202609\$d,102,2000,toy,2026-09-\$d 11:01:00\" >> \$f               # 正常行
  echo \"202609\$d,103,3000,book,2026-09-\$d 11:02:00\" >> \$f
  echo \"202609\$d,103,3000,book,2026-09-\$d 11:02:00\" >> \$f               # 脏行：重复
done"
# ===== 步骤 2：挂到 HDFS（每天一个目录=分区）=====
docker exec hadoop-single bash -c "
for d in 25 26 27; do
  hdfs dfs -mkdir -p /data/ods/order_detail/dt=2026-09-\$d
  hdfs dfs -put -f /tmp/ods_order_2026-09-\$d.csv /data/ods/order_detail/dt=2026-09-\$d/
done
hdfs dfs -ls /data/ods/order_detail"
```

```sql
-- ===== 步骤 3：ODS 外部表（TEXTFILE 保真 + 外部表生死权，容器 hive-server 中执行）=====
CREATE EXTERNAL TABLE IF NOT EXISTS shop.ods_order_detail (
  oid        STRING,      -- 订单号（20260925_101 形式）
  uid        STRING,
  amount_fen BIGINT,      -- 原始金额：分（原样保留业务库格式）
  category   STRING,
  order_time STRING
)
PARTITIONED BY (dt STRING)
ROW FORMAT DELIMITED FIELDS TERMINATED BY ','
LOCATION '/data/ods/order_detail';      -- 只挂账本，数据所有权归 HDFS 目录
ALTER TABLE shop.ods_order_detail ADD IF NOT EXISTS
  PARTITION (dt='2026-09-25') PARTITION (dt='2026-09-26') PARTITION (dt='2026-09-27');
SELECT COUNT(*) FROM shop.ods_order_detail WHERE dt='2026-09-25';   -- 14 行（12 正常+2 脏）

-- ===== 步骤 4：DWD 清洗（四件事一次做完 + ORC + 幂等写）=====
CREATE TABLE IF NOT EXISTS shop.dwd_order_detail_di (
  oid        STRING,
  uid        STRING,
  amount     DECIMAL(10,2),   -- ③ 规范：分→元
  category   STRING,          -- ④ 退化：category 直接存，下游免 Join 类目表
  order_time STRING,
  dt         STRING
)
PARTITIONED BY (dt STRING)
STORED AS ORC
TBLPROPERTIES ('orc.compress'='SNAPPY');
INSERT OVERWRITE TABLE shop.dwd_order_detail_di PARTITION (dt='2026-09-25')
SELECT oid, uid, CAST(amount_fen/100 AS DECIMAL(10,2)), category, order_time
FROM shop.ods_order_detail
WHERE dt='2026-09-25' AND amount_fen > 0
  AND oid IN (SELECT oid FROM (SELECT oid, ROW_NUMBER() OVER(PARTITION BY oid ORDER BY order_time) rn
                               FROM shop.ods_order_detail WHERE dt='2026-09-25') t WHERE rn=1);  -- ① 去重
-- ===== 步骤 5：脏行隔离区（不是扔，是打标留存）=====
INSERT OVERWRITE TABLE shop.dwd_order_bad_di PARTITION (dt='2026-09-25', bad_type='neg_amount')
SELECT oid, uid, amount_fen, 'amount<=0' FROM shop.ods_order_detail
WHERE dt='2026-09-25' AND amount_fen <= 0;
-- ===== 步骤 6：DQC 强规则（day18 兑现）=====
SELECT COUNT(*), COUNT(DISTINCT oid) FROM shop.dwd_order_detail_di WHERE dt='2026-09-25';
-- 两个数相等=主键唯一强规则过；行数记录进基线表，明天波动超 0.5~2 倍即告警
```

## 4. 面试连接

**Q：ODS 层为什么强制用外部表+TEXTFILE？（项目第一问）**
> 两个理由，都和"后悔药"有关。第一是外部表的生死权：外部表删表只是删 Hive 里的账本，HDFS 上的数据文件原地不动——如果上游同步出了问题或者我 DWD 加工写错了，随时可以重建表重新挂载，原料永远是安全的；内部表删表就陪葬了。第二是 TEXTFILE 保真：ODS 的职责是"原样备份"，格式必须和业务库导出的格式一致，不做任何转换——将来要排查"数据到底长什么样"时，拿原始文件和对账最直观。代价是查询慢、压缩率低，但 ODS 本来就不给分析师直接查，只作为加工源头，慢一点无所谓。一句话：ODS 是保险柜不是货架——安全第一，取用方便第二。

**Q：DWD 清洗具体洗哪些东西？脏数据你怎么处理？（清洗决策题）**
> 四件事：去重（ROW_NUMBER 按 oid 分组取一条，重复单只留最早的）、补规范（金额分转元、时间统一格式）、过滤明显非法行（金额<=0）、维度退化（类目这种稳定小维度直接拷进明细行，下游少一次 Join）。脏数据的处理原则是"隔离不销毁"：我建了 `dwd_order_bad_di` 隔离区表，按 bad_type 打标存放，每行带原始字段——这样 DQC 报警时能直接查隔离区对质，业务方要补数时原始证据还在。反面教训是把脏行直接 WHERE 掉扔进黑洞：上游问"我这批单子怎么少了 200 条"时没人说得清。另外所有清洗规则都登记在口径表里（金额<=0 算脏、重复保留首条），规则变更要评审——清洗逻辑本身就是业务口径的一部分，不是技术私货。

## 5. 今日验收清单

- [ ] 5 站流水线图能白板画，前两层职责一句话各说清
- [ ] ODS 外部表挂载完成（3 个分区各 14 行）
- [ ] DWD 清洗四件事落地（去重/规范/过滤/退化各指着 SQL 说出来）
- [ ] 脏行隔离区有数据、DQC 主键唯一规则跑通
- [ ] Checklist 15 项对 DWD 表逐项过检（截图或勾选记录）
- [ ] `git add . && git commit -m "day10-25: dw project ods-dwd"`

---
[← Day 24](day24-倾斜治理二.md) | [本月目录](README.md) | [Day 26 · 数仓项目二 →](day26-数仓项目二.md)
