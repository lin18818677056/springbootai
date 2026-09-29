# month12 · Flink 与实时数仓（第 12 月）

> **一句话目标**：搞懂"真流处理"凭什么成立（事件时间/水位线/状态/Checkpoint 四大件），用 Flink SQL 搭一条**实时数仓链路**：Kafka 埋点 → Flink 秒级加工 → 实时 GMV/TopN → 和 M11 的离线结果**实离对拍**——上个月你的数据 T+1 才有数，这个月让它 1 分钟内出数。
> **写法承诺**：所有概念先讲人话（类比先行），每个术语大白话解释，每个面试题口语化作答，实验全部可在你现有的 Docker 容器里复现。
> **前置资产**：month11 的容器群（kafka/hadoop-single/hive-server/hive-mysql/spark）+ shop 数仓 + 三本账对拍方法论——本月全部直接复用。

## 本月验收标准（8 条，day30 逐条对账）

1. 能讲清"真流 vs 微批"的本质差异，说清 Flink 凭什么做真流（兑现 M11 思考题①）
2. 事件时间/处理时间/水位线能白板讲，乱序数据归属实验做完（兑现 M11 思考题②）
3. Checkpoint 流程+Barrier 对齐能脱稿画图，故障恢复实验做一次（kill 掉再拉起）
4. 端到端 Exactly-Once 三段拼图能和 M11 Kafka 语义拼图对上
5. Flink SQL 三板斧：滚动窗口聚合+TopN+Lookup Join 各有一个可运行例子
6. 反压/积压五步排查法能讲（这是实时作业的第一故障）
7. 实时数仓项目跑通：实时 GMV 与 M11 离线 GMV **实离对拍一致**
8. 《实时作业上线检查单》成文（对照 M11 的《治理清单 v2》风格）

## 30 天课程地图

### 第 1 周 · Flink 入门与时间语义（day01~07）

| Day | 主题 | 核心问题（人话版） | 产出 |
|-----|------|------------------|------|
| 01 | Flink 全景与定位 | 真"流水线"和"一桶一桶倒"差在哪？（M11 思考题①） | 对照实验记录 |
| 02 | 运行时架构 | 工头/工人/工位：JobManager/TaskManager/Slot | 架构图 |
| 03 | 第一个流作业 | 部署+跑通第一个 DataStream 作业 | 容器跑通记录 |
| 04 | 时间语义与水位线 | 10:59 的订单 11:01 才到，算哪天的数？（M11 思考题②） | 乱序实验 |
| 05 | 窗口 | 班车固定间隔发车：滚动/滑动/会话窗口 | 三窗口实验 |
| 06 | 状态管理 | 小本本记账：键控状态/状态后端/状态 TTL | TTL 实验 |
| 07 | 第一周复盘 | 六线串讲+自测+《水位线调参卡》 | 复盘+清单 |

### 第 2 周 · 可靠性与 Flink SQL（day08~14）

| Day | 主题 | 核心问题 | 产出 |
|-----|------|---------|------|
| 08 | Checkpoint 与 Barrier | 游戏存档怎么打：屏障随流流动 | Checkpoint 实验 |
| 09 | Exactly-Once 端到端 | 三段拼图：源可重放+状态一致+输出幂等 | 语义对照卡 |
| 10 | 反压与积压排查 | 下游堵车一路堵到上游：五步排查法 | 排查演练 |
| 11 | Flink SQL 入门 | 用 SQL 写流：源表/汇表/流式查询 | SQL 跑通 |
| 12 | SQL 窗口与 TopN | 实时 GMV 和实时热销榜的写法 | 两个 SQL |
| 13 | 维表关联与 CDC | 维表怎么查不把数据库打死 | Lookup Join |
| 14 | 第二周复盘 | 串讲+《Flink 生产 Checklist v1》 | 复盘+清单 |

### 第 3 周 · 实时数仓项目（day15~21）

| Day | 主题 | 核心问题 | 产出 |
|-----|------|---------|------|
| 15 | 实时数仓架构 | 分层照旧，链路换"常开的水管" | 架构图 |
| 16 | 项目一·明细流 | Kafka→Flink 清洗→DWD 明细流 | 链路跑通 |
| 17 | 项目二·实时 GMV | 分钟级 GMV：秒级大屏的数据从哪来 | 实时 GMV 表 |
| 18 | 项目三·热销 TopN | 近 1 小时热销榜：先聚再排 | TopN 大屏 |
| 19 | 实离对拍 | 实时账 vs 离线账：数字必须对上 | 对拍报告 |
| 20 | CEP 复杂事件处理 | "连续登录失败"怎么被机器识别 | CEP 例子 |
| 21 | 第三周复盘 | 项目周串讲+自测 | 复盘 |

### 第 4 周 · 调优运维与收官（day22~30）

| Day | 主题 | 核心问题 | 产出 |
|-----|------|---------|------|
| 22 | 状态与 Checkpoint 调优 | 状态太大怎么办：RocksDB+增量快照 | 调优记录 |
| 23 | 并行度与资源 | 并行度怎么定：上限是源分区数 | 资源模板 |
| 24 | 生产运维与升级 | 改代码怎么不停机：Savepoint 升级 | 升级演练 |
| 25 | 流批一体对照 | Flink vs Spark：一张对照表说清选型 | 选型表 |
| 26 | 综合项目·双链路 | 实时保新鲜+离线保精确的双链路数仓 | 项目总结 |
| 27 | 压测与故障演练 | 杀掉 TaskManager：看它自己爬起来 | 演练记录 |
| 28 | 全月大串讲 | 《实时数仓全景图》+12 题自测 | 全月地图 |
| 29 | M12 模拟验收 | 五轮 30 问+白板三件套 | 得分表 |
| 30 | 月度复盘与博客 | 博客⑧+M13 交接卡+tag 收官 | 复盘+交接 |

## 跨月伏笔清单（本月要兑现的"欠账"）

| 伏笔 | 埋在哪 | 兑现在 |
|------|--------|--------|
| M11 思考题①：Flink 凭什么做真流处理 | M11 day30 交接卡 | day01 正式作答 |
| M11 思考题②：乱序数据算哪天的数 | M11 day30 交接卡 | day04 正式作答 |
| Checkpoint 思想（Spark 截断血缘版） | M11 day17 | day08 对照升级 |
| Exactly-Once 三段拼图（Kafka 版） | M11 day09 | day09 流版三段拼图 |
| 三本账对拍方法论 | M8→M10→M11 | day19 实离对拍 |
| 消费重试阶梯+死信 | M11 day09 | day10 反压处置 |
| 状态机（M7 订单/Rebalance） | M7/M11 day05 | day20 CEP 模式匹配 |
| 线程池吞吐倒推 | M2→M11 day04 | day23 并行度倒推 |

## 实验环境（容器命令）

```powershell
# Flink 集群：1 个 JobManager + 1 个 TaskManager（接入现有 bigdata 网络）
docker run -d --name flink-jm --network bigdata -p 8081:8081 `
  -e FLINK_PROPERTIES="jobmanager.rpc.address: flink-jm" flink:1.18 jobmanager
docker run -d --name flink-tm --network bigdata `
  -e FLINK_PROPERTIES="jobmanager.rpc.address: flink-jm
taskmanager.numberOfTaskSlots: 4" flink:1.18 taskmanager
# 验证：浏览器开 http://localhost:8081 看到 1 个 TM、4 个 Slot 即成功

# Flink SQL 客户端（实验主战场）：
docker exec -it flink-jm ./bin/sql-client.sh
# 内置造数器（不需要任何外部依赖，所有窗口实验用它）：
# CREATE TABLE t (id INT, amt DOUBLE, ts TIMESTAMP_LTZ(3)) WITH ('connector'='datagen', ...)

# Kafka 对接（day16+ 需要 connector jar，可选）：
# 下载 flink-sql-connector-kafka-3.0.2-1.18.jar 后：
docker cp flink-sql-connector-kafka-3.0.2-1.18.jar flink-jm:/opt/flink/lib/
docker restart flink-jm flink-tm
# —— 若网络受限下载不了：day16 起用 datagen 造流替代，结论不受影响（文档写明降级路径）
```

## 导航

- [← 上月：month11 · Kafka 与 Spark](../month11-Kafka与Spark/README.md)
- [→ growth-plan 总导航](../../README.md)

---
> 开工动作：先把容器拉起来（上面两行命令），打开 localhost:8081 看到 Web UI，再进 [Day 01](day01-Flink全景与定位.md)。
