# Day 14 · 第二周复盘：可靠性与 SQL 武装完毕

> **今日目标**：把 W2 六天（Checkpoint/Exactly-Once/反压/SQL 入门/窗口 TopN/维表 CDC）串成一条线；12 题自测；产出《Flink 生产 Checklist v1》——项目周上线前逐项打勾。
> **时长**：串讲 1h / 自测 1.5h / 清单 0.5h
> **今日产出**：W2 六线关系图 + 12 题自测成绩 + 《生产 Checklist v1》

## 1. 六线串讲（一条故事线）

```
第 1 站【day08 Checkpoint】：Barrier 随流流动=全班合影的分隔线；
  对齐等齐所有输入才快照；恢复=全员读档+Source 重放；
  生产四件套：interval/min-pause/timeout/unaligned，快照必放 HDFS
第 2 站【day09 Exactly-Once】：Checkpoint≠端到端！
  三段拼图：Source 可重放+状态一致+Sink 幂等/事务；
  upsert 是大屏首选（重复无害），事务通用但可见性绑 CKPT 间隔；
  对照 M11：Kafka 的 EO 是组装货，Flink 的 EO 是整机
第 3 站【day10 反压】：下游慢→憋流→Lag 上涨；
  五步法：发现(Lag/UI)→定位(busy%)→归因(状态重/Sink 慢/热点)
  →处置(并行度/批量化/加盐)→验证(Lag 归零+CKPT 恢复)
第 4 站【day11 Flink SQL】：动态表=不断 INSERT 的表；
  Append 流(只增) vs Update 流(会变)→Sink 要配套；
  连接器=表的插座（datagen/print/kafka/jdbc/CDC 格式）
第 5 站【day12 窗口 TopN】：窗口 TVF 现代写法；
  TopN=先聚再排（降基数是命门）；输出是持续更新的 changelog
第 6 站【day13 维表 CDC】：广播(小表)/Lookup+缓存(大表)/CDC(频更)三选一；
  FOR SYSTEM_TIME AS OF 时态关联；新鲜度是业务约束

串联理解：W1 回答"怎么算对"（时间/窗口/状态），
  W2 回答"怎么算得稳、怎么好写"（容错/语义/排障/SQL 武器库）。
  项目周（W3）就是把两周三股绳拧成一条实时数仓链路。
```

## 2. 12 题自测（题号即天号）

```
 1. Barrier 流程五步+对齐为什么必要？（day08）
 2. Checkpoint 生产四件套参数？（day08）
 3. "开了 Checkpoint 就是 Exactly-Once"错在哪？（day09）
 4. upsert 和事务两种 Sink 路线的取舍？（day09）
 5. 反压和 Lag 的因果链？（day10）
 6. busy%/backpressured% 怎么读？（day10）
 7. 动态表是什么？Append/Update 流怎么区分落地？（day11）
 8. 为什么聚合查询写 append Kafka sink 会报错？（day11）
 9. TopN 为什么必须"先聚再排"？（day12）
10. TopN 的输出为什么是 changelog？（day12）
11. 维表三方案怎么选？各自的代价？（day13）
12. Lookup Join 缓存两参数+回源量推演？（day13）

判分：带实验/演练证据=通过；背概念=回炉。
```

## 3. 《Flink 生产 Checklist v1》（项目周上线前逐项打勾）

```
【正确性】
□ 源表 WATERMARK 声明+乱序度=P99 上报延迟
□ idle-timeout 配置（防闲分区卡水位线）
□ 状态 TTL 全覆盖（每个有状态的作业都问过"可以忘吗"）
□ Sink 语义对齐：Update 流→有主键 sink；幂等键设计过
【稳定性】
□ Checkpoint 四件套+快照目录=HDFS（非本地盘）
□ 状态后端评估（>几 GB → RocksDB+增量）
□ 反压预案：Lag 监控+busy% 告警接了
□ key 基数评估（热点 key 加盐预案）
【可运维】
□ 算子 uid 设置（Savepoint 升级的门票，day24）
□ Savepoint 升级 SOP 成文
□ 五步排查卡贴墙
```

## 4. 面试连接

**Q：你们的 Flink 作业上线前会做哪些检查？（工程素养题，直接背 Checklist）**
> 我按正确性、稳定性、可运维三组走清单。正确性组：源表 WATERMARK 声明（乱序度按埋点 P99 定）、idle-timeout 防闲分区卡水位线、所有有状态作业配 TTL（先问业务"这个状态可以忘吗"）、Sink 和流类型对齐（聚合的 Update 流必须有主键 sink）。稳定性组：Checkpoint 间隔按可容忍重放量倒推、快照放 HDFS、状态后端按预估大小选、Lag 和 busy% 接告警、热点 key 有加盐预案。可运维组：算子设 uid（Savepoint 升级的门票）、升级 SOP 成文（先 Savepoint stop 再起新版）、排障手册在墙。这套清单不是背来的——每个条目背后都踩过坑或演练过：TTL 是 day06 看着状态无界增长后加的，uid 是知道 Savepoint 恢复对不上号的坑后加的。面试官听到"每条都有出处"就知道是真上过线。

## 5. 今日验收清单

- [ ] 六线串讲能脱稿
- [ ] 12 题自测完成，错题回炉
- [ ] 《Checklist v1》成文（项目周第一天就打印）
- [ ] 三组分类（正确性/稳定性/可运维）能讲
- [ ] `git add . && git commit -m "day12-14: week2-review"`

---
[← Day 13](day13-维表关联与CDC.md) | [本月目录](README.md) | [Day 15 · 实时数仓架构 →](day15-实时数仓架构.md)
