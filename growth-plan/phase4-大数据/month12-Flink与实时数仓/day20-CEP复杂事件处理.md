# Day 20 · CEP 复杂事件处理：让机器认出"连续登录失败五次"

> **今日目标**：认识 CEP（Complex Event Processing，复杂事件处理）——在事件流里做"模式匹配"（A 后面跟着 B 再跟着 C）；理解它和普通聚合的本质区别（关心"顺序和组合"，不是"汇总"）；用 SQL 的 MATCH_RECOGNIZE 跑一个风控例子。W3 项目周收官。
> **时长**：CEP 概念 1.5h / MATCH_RECOGNIZE 实验 2.5h / 复盘 1h
> **今日产出**：风控模式匹配例子跑通 + 《CEP vs 聚合对照表》+ 项目周总结

## 1. 知识地图（先讲人话：从"算总数"到"认剧本"）

```
普通流计算（前面 19 天学的）：算汇总——每分钟 GMV、每个商品销量
  —— 关心"有多少"，不关心"谁先谁后"

CEP 复杂事件处理：认剧本——事件流里匹配"特定顺序的组合"
  剧本举例（业务场景）：
  · 风控：同一 uid【连续 5 次登录失败】→ 疑似撞库 → 实时封号
  · 营销：用户【浏览商品 A → 加购 → 未支付】超 10 分钟 → 推优惠券
  · 运维：接口【错误率突增 + 延迟突增】同时出现 → 自动扩容
  —— M7 的状态机思想回归：订单状态流转是"单实体的剧本"，
     CEP 是"事件流上的剧本"（跨月连线）

Flink 的 CEP 两条路：
  ① DataStream API 的 FlinkCEP 库（Pattern API）：表达力最强（Java 代码）
  ② SQL 的 MATCH_RECOGNIZE（SQL 标准）：SQL 内写正则式模式——本月用这条
     语法骨架：
       SELECT ... FROM 流 MATCH_RECOGNIZE (
         PARTITION BY uid            -- 每个用户各认各的剧本
         ORDER BY et                 -- 按时间顺序认
         MEASURES ...                -- 匹配到了输出什么
         PATTERN (e1 e2 e2 e2 e2)    -- 剧本：1 次失败后跟 4 次失败（正则味）
         DEFINE e1 AS ..., e2 AS ... -- 每个事件怎么算"匹配"
       )

和窗口的关系（易混点）：
  窗口：按时间切段，段内算汇总——"这一小时卖了多少"
  CEP：按事件序列匹配，可以跨任意长时间——"他这周行为像小偷"
  —— CEP 的状态也是要 TTL 的（剧本等了很久没等齐也要放手）——day06 纪律
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| CEP | 复杂事件处理 | 在事件流里做模式匹配（认剧本） |
| Pattern | 模式 | 剧本：事件的顺序组合规则（正则味） |
| MATCH_RECOGNIZE | 模式识别子句 | SQL 标准的 CEP 写法 |
| PARTITION BY / ORDER BY | 分区/排序 | 每个实体各认各的剧本、按时间顺序认 |
| MEASURES | 度量 | 匹配成功输出什么 |
| AFTER MATCH SKIP | 匹配后跳过 | 认出一段剧本后从哪继续认（防重叠） |
| Partial Match | 部分匹配 | 剧本认到一半（状态驻留，要 TTL） |

## 3. 动手实操：MATCH_RECOGNIZE 认"连续登录失败"

```sql
-- ===== 步骤 1：造登录事件流（datagen 模拟，成功/失败混合）=====
CREATE TABLE login_events (
  uid INT,
  ok BOOLEAN,                      -- true=成功 false=失败
  et AS CURRENT_TIMESTAMP,
  WATERMARK FOR et AS et - INTERVAL '3' SECOND
) WITH (
  'connector' = 'datagen', 'rows-per-second' = '5',
  'fields.uid.kind' = 'random', 'fields.uid.min' = '1', 'fields.uid.max' = '5',
  'fields.ok.kind' = 'random',
  'fields.ok.min' = '0', 'fields.ok.max' = '1'    -- 约一半失败，容易出剧本
);

-- ===== 步骤 2：模式匹配——同一 uid 连续 3 次失败 → 告警行 =====
SELECT * FROM login_events
MATCH_RECOGNIZE (
  PARTITION BY uid                  -- 每个用户各认各的剧本
  ORDER BY et                       -- 按时间顺序
  MEASURES
    e1.uid AS uid,
    COUNT(e2.et) AS fail_cnt,       -- 连续失败次数
    LAST(e2.et) AS last_fail_time   -- 最后一次失败时间（告警内容）
  ONE ROW PER MATCH                 -- 每匹配成功一段出一行
  AFTER MATCH SKIP TO NEXT ROW      -- 认完从下一行继续（简单防重叠）
  PATTERN (e1 e2{2,})               -- 剧本：1 次失败，后面再跟至少 2 次失败
  DEFINE
    e1 AS e1.ok = false,            -- 开场必须是失败
    e2 AS e2.ok = false             -- 后续也都是失败（连续！）
);
-- 观察：结果流里出现"uid=3, fail_cnt=4"这类行——
-- 机器认出了"这个用户连续失败了 4 次"（截图①）
-- 生产接法：这个结果流写 Kafka 风控主题 → 风控服务消费 → 实时封号/验证码

-- ===== 实验②：改剧本（体会 PATTERN 的正则味）=====
-- PATTERN (e1 e2{2,} e3)：连续失败后跟一次成功 → "解封"剧本
-- DEFINE e3 AS e3.ok = true
-- 观察：失败连续被打断时不再告警，成功后重新开始认（截图②）
-- 思考记录：风控里"连续失败后成功"也可能可疑（试出密码了）——
--   剧本设计是业务问题，CEP 只是引擎
```

```
《CEP vs 聚合对照表》：
  维度      聚合(窗口)          CEP(模式匹配)
  关心什么  有多少（汇总值）      什么顺序（组合剧本）
  输出节奏  窗口关闭出一次        每匹配成功一段出一次
  状态形态  聚合小账本            半成品剧本（等后续事件）
  典型场景  GMV/UV 大屏          风控/营销旅程/智能运维
  共同点    都要管状态生命周期（TTL）！半成品剧本不放手=状态泄漏
```

## 4. 面试连接

**Q：什么是 CEP？举一个你实现的场景？（进阶题，答出加分）**
> CEP 是在无界事件流上做模式匹配——不关心"总量多少"，关心"事件按什么顺序组合出现"，像在数据流上跑一个正则引擎。我做过风控场景：登录事件流里识别"同一 uid 连续 N 次登录失败"，命中就往风控主题发告警，风控服务实时触发验证码/临时封号。实现用 Flink SQL 的 MATCH_RECOGNIZE：PARTITION BY uid 让每个用户独立认剧本，PATTERN (e1 e2{2,}) 定义"一次失败后至少再跟两次失败"，DEFINE 里约束每个事件都是 ok=false，MEASURES 输出失败次数和最后失败时间。三个工程要点：AFTER MATCH SKIP 决定认完一段从哪继续（防重叠误报）；部分匹配的状态要配 TTL（剧本等不齐要放手，否则状态泄漏——day06 纪律在 CEP 的对应物）；剧本设计是业务问题——"连续失败"撞库和"失败后成功"试出密码是两个剧本，引擎只是执行者。和 M7 的状态机连线：订单状态机是"单实体在状态间流转"，CEP 是"事件流上的多步剧本"，本质都是"按预定义的状态转移规则识别过程"。

**Q：CEP 和普通窗口聚合什么时候用哪个？（选型题）**
> 判断标准一句话：需求里有"顺序/组合"语义就 CEP，只有"范围汇总"语义就窗口。每分钟 GMV、每小时 UV——纯汇总，窗口；"先浏览后加购未支付的用户"、"连续失败"、"突增伴随突降"——顺序组合，CEP。还有个混合形态：很多风控规则其实是"窗口聚合+阈值判断"（5 分钟内失败次数 >10），这种用窗口聚合+N 秒窗就够，不用上 CEP——CEP 的状态和复杂度都更高，能简单就简单。我的实践：先问需求方"顺序重要吗"，重要才 CEP——大多数"看起来像 CEP"的需求，窗口+阈值就能覆盖。

## 5. 今日验收清单

- [ ] CEP vs 聚合的本质区别能一句话讲（顺序组合 vs 范围汇总）
- [ ] MATCH_RECOGNIZE 五要素（PARTITION/ORDER/MEASURES/PATTERN/DEFINE）能默写
- [ ] 实验①②跑通（连续失败剧本+打断剧本）
- [ ] 部分 match 的 TTL 纪律能讲
- [ ] "窗口+阈值能覆盖就不上 CEP"的选型观能讲
- [ ] `git add . && git commit -m "day12-20: cep"`

---
[← Day 19](day19-实离对拍.md) | [本月目录](README.md) | [Day 21 · 第三周复盘 →](day21-第三周复盘.md)
