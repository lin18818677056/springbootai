# Day 08 · Checkpoint 与 Barrier：游戏存档怎么打

> **今日目标**：Flink 的灵魂机制——Checkpoint（检查点）：Barrier（屏障）怎么随流流动、快照怎么打、挂了怎么恢复。对照 M11 Spark checkpoint（截断血缘）和游戏存档类比，做一次完整的"杀进程→恢复"实验。
> **时长**：Checkpoint 流程 2h / 恢复实验 2h / 输出 1h
> **今日产出**：Barrier 流动图（手绘）+ 故障恢复实验记录 + 《Checkpoint 参数卡》

## 1. 知识地图（先讲人话：全班合影的分隔线）

```
问题：常驻作业 7×24 跑，挂了怎么办？
  状态在小本本里（内存/RocksDB），进程一挂全没了——
  必须定期"存档"，挂了读档继续玩。这就是 Checkpoint。

难点：流不停，怎么"给流水拍一张静止的照片"？
  —— Flink 的答案：Barrier 屏障（随流流动的分隔线）

全班合影类比：
  JobManager 定期往流里插一根 Barrier（像插队举牌的人）：
  "Barrier 之前的数据算旧账，Barrier 之后是新账"
  · Barrier 流到每个算子：算子把当前小本本（状态）拍快照存到 HDFS
  · 多输入的算子要【对齐】：等所有输入的 Barrier 都到了才快照
    （合唱队拍照：必须等声部齐了才按快门）
  · 所有算子快照完成 → 本次 Checkpoint 成功
  恢复 = 所有算子从最近快照读回状态 + Source 从快照里的 offset 重放

对照已有知识（跨月连线）：
  · Spark checkpoint：解决"血缘太长重算失控"——定期把 RDD 落 HDFS 截断血缘
    Flink checkpoint：解决"常驻状态丢失"——定期把【状态+offset】快照
    —— 同名不同命：一个截断历史，一个保存现在
  · Barrier 对齐 vs 水位线：两者都是"随流流动的特殊标记"，
    水位线管正确性（何时出结果），Barrier 管容错（何时快照）——互不干扰

代价与权衡（要会说）：
  · Barrier 对齐时上游要"憋流"（等慢的输入通道）——反压下 CKPT 超时
    解法：非对齐 Checkpoint（Unaligned，1.11+）——Barrier 先走，数据暂存快照里
  · 快照太频繁：IO 开销大；太稀：挂了重放多——按"可容忍丢失量"倒推间隔
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| Checkpoint | 检查点 | 定期给状态+offset 拍的全班合影 |
| Barrier | 屏障 | 随流流动的分隔线："线前是旧账线后是新账" |
| Barrier Alignment | 屏障对齐 | 合唱队按快门前等齐所有声部 |
| Snapshot | 快照 | 每个算子小本本的定格照片（存 HDFS/S3） |
| State Backend | 状态后端 | 照片洗在哪（HashMap 内存/RocksDB 磁盘） |
| Unaligned Checkpoint | 非对齐检查点 | 快门先按，晚到的声部照片后补（反压救星） |
| Savepoint | 保存点 | 手动触发的人为存档（升级用，day24） |

## 3. 动手实操：开 Checkpoint + 杀进程恢复

```sql
-- ===== 步骤 1：打开 Checkpoint（SQL 客户端）=====
SET 'execution.checkpointing.interval' = '3s';          -- 每 3 秒一次（实验用，生产 1~5 分钟）
SET 'state.checkpoints.dir' = 'file:///tmp/ckpt';        -- 快照存本地（生产用 hdfs://）

-- ===== 步骤 2：跑一个有状态的作业 =====
CREATE TABLE ck_src (
  uid INT, amt DOUBLE,
  et AS CURRENT_TIMESTAMP,
  WATERMARK FOR et AS et - INTERVAL '3' SECOND
) WITH (
  'connector' = 'datagen', 'rows-per-second' = '20',
  'fields.uid.kind' = 'random', 'fields.uid.min' = '1', 'fields.uid.max' = '100',
  'fields.amt.min' = '10', 'fields.amt.max' = '500'
);
INSERT INTO ... SELECT uid, SUM(amt) FROM ck_src GROUP BY uid;  -- 提交为作业
-- UI → 该作业 → Checkpoints 页：
--   History：每 3 秒一条记录，Status=COMPLETED（截图①）
--   看 State Size 和 Checkpoint Duration 两个数字并记录
```

```powershell
# ===== 步骤 3：灾难实验——杀掉 TaskManager（作业 host 失联）=====
# 记录当前作业 id（UI URL 里）和 datagen 的吞吐节奏
docker kill flink-tm          # 模拟工人突然倒下（kill -9 级别）
# 观察 UI：作业状态先变 RESTARTING（JobManager 尝试重启）
# Mini 集群里 TM 不会自己爬起来 → 手动拉起：
docker start flink-tm
# 观察：作业回到 RUNNING；Checkpoints 页 History 继续
# —— 从最近成功快照恢复状态，datagen 无 offset 所以数据从头造；
#    生产上 Kafka Source 会从快照的 offset 续着读（day09 验证精确一次）
# 记录：恢复耗时 ____ 秒；恢复后 SUM 值与挂之前是否衔接（截图②）

# ===== 步骤 4：看快照文件（眼见为实）=====
docker exec flink-jm bash -c "ls -R /tmp/ckpt | head -30"
# 能看到 ckpt 目录按 checkpoint id 分目录——快照真的落盘了（截图③）
```

```
《Checkpoint 参数卡》（生产四件套）：
  execution.checkpointing.interval = 60s     间隔：按"可容忍重放量"倒推
  execution.checkpointing.min-pause = 30s    上次完成到下次开始的最小间隔（防连环）
  execution.checkpointing.timeout = 10min    超时：反压下常超时，配合 unaligned
  execution.checkpointing.unaligned = true   反压作业建议开（非对齐）
  state.checkpoints.dir = hdfs://...         生产必放 HDFS/S3（本地盘挂了快照也没了）
```

## 4. 面试连接

**Q：讲讲 Flink Checkpoint 的原理？（本月必考白板题，标准白板题）**
> 一句话：Barrier 驱动的分布式快照。流程五步：JobManager 周期性向 Source 注入 Barrier；Barrier 随数据流经每个算子；算子收到后先做屏障对齐（多输入时等齐所有输入的 Barrier，保证快照切片一致），把当前状态快照写入外部存储（HDFS/S3），再向下游转发 Barrier；所有算子快照完成，本次 Checkpoint 成功；Source 的 offset 也一起进了快照。故障恢复就是全队读档：所有算子恢复到最近成功快照的状态，Source 从快照记录的 offset 重放数据——状态和消费进度是同一时刻的一致性切片，所以恢复后结果既不丢也不乱。代价要主动说：Barrier 对齐会让上游憋流，反压作业容易 Checkpoint 超时，对策是开非对齐 Checkpoint（Barrier 先走、在途数据进快照）；快照间隔按业务可容忍的数据重放量倒推，生产一般 1~5 分钟配 min-pause 防连环快照。跨月对比是加分项：Spark 的 checkpoint 是截断血缘（防重算失控），Flink 的是保存现在（容错恢复）——同名不同命。我做过完整实验：3 秒间隔跑有状态聚合，kill 掉 TaskManager 再拉起，作业自动从快照恢复继续跑。

**Q：Checkpoint 和 Savepoint 的区别？（追问）**
> 同一套 Barrier 机制，用途和特点不同：Checkpoint 是系统自动的、周期性的，为容错服务——挂了自动恢复用，完成可以自动清理（保留最近 N 个）；Savepoint 是人工触发的、为"计划内变更"服务——升级代码、扩缩并行度、迁移集群前打一个，默认永久保留，而且要求算子有 uid（保证升级后状态能对上号）。一句话：Checkpoint 是游戏自动存档，Savepoint 是你打 BOSS 前的手动存档。生产纪律：任何 SQL/代码变更上线前，先 Savepoint stop 再从 Savepoint 启动（day24 升级演练）。

## 5. 今日验收清单

- [ ] Checkpoint 五步流程能白板画（Barrier 注入→对齐→快照→转发→成功）
- [ ] kill/恢复实验完成（恢复耗时+衔接性记录）
- [ ] Spark checkpoint vs Flink checkpoint 对照能讲
- [ ] 《Checkpoint 参数卡》成文（四件套+HDFS 纪律）
- [ ] CKPT vs Savepoint 区别能讲
- [ ] `git add . && git commit -m "day12-08: checkpoint"`

---
[← Day 07](day07-第一周复盘.md) | [本月目录](README.md) | [Day 09 · Exactly-Once 端到端 →](day09-ExactlyOnce端到端.md)
