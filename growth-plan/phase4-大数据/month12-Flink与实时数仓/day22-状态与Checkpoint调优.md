# Day 22 · 状态与 Checkpoint 调优：状态大了、快照慢了怎么办

> **今日目标**：W4 调优周第一天——解决两个最常见的"慢性病"：状态太大（内存/磁盘吃紧）和 Checkpoint 太慢/超时（反压下的连锁反应）。掌握 RocksDB 增量快照、状态瘦身三板斧、Checkpoint 时长诊断法。
> **时长**：诊断指标 1h / 调优三板斧实验 3h / 输出 1h
> **今日产出**：State Size 与 Duration 的诊断记录 + 《Checkpoint 调优决策树》+ 调优前后对比数字

## 1. 知识地图（先讲人话：两个慢性病对症下药）

```
慢性病①：状态太大（State Size 一路涨）
  症状：TM 内存吃紧/OOM 风险、快照越来越慢
  诊断：UI → Checkpoints 页 State Size 曲线（只涨不跌=无界状态）
  药方三板斧（day06 框架的调优深化）：
    ① 降基数：key 设计从"明细级"改"聚合级"
       （存每 uid 每商品的行为明细 → 只存聚合值，状态差几个量级）
    ② 设 TTL：一切状态问"可以忘吗"（生产：state.ttl 按业务寿命）
       注意：TTL 清理有惰性（读到才清）+ 定期压缩（RocksDB compaction 时清）
    ③ 换后端：HashMap → RocksDB
       —— 状态放本地磁盘，堆内存只留索引，TB 级不 OOM
       —— 开增量 Checkpoint：只上传"变化的部分"（SST 文件级 diff）
          快照时间和量都大降（大状态作业的标配）

慢性病②：Checkpoint 太慢/超时（Duration 曲线飙升）
  症状：CKPT 超时失败 → 快照断档 → 挂了恢复点更旧 → 重放更多
  诊断三看：
    ① Duration vs Interval：Duration 接近甚至超过 Interval=快不过来
    ② 哪个算子慢：UI Checkpoint 页看各算子的 Acknowledge 时间——
       最晚确认的就是瓶颈算子
    ③ 是不是反压：day10 的五步法——反压会让 Barrier 流不动，
       对齐时间（Alignment Time）飙升
  药方决策树：
    反压导致 → 先治反压（day10），再考虑 unaligned CKPT
    大状态导致 → 增量 CKPT + RocksDB
    快照落盘慢 → 快照目录 IO 升级（HDFS/S3 带宽）、并发快照线程
    仍不够 → 间隔放宽（业务可容忍的重放量内）+ min-pause 防连环

参数联动（记住这组"跷跷板"）：
  interval 小 → 数据新，但快照开销大；interval 大 → 省资源，恢复点多旧
  —— 按"业务可容忍丢失窗口"倒推（结算类：1~5 分钟；监控类：可更长）
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| State Size | 状态大小 | 小本本的总厚度（只涨不跌=慢性病） |
| CKPT Duration | 快照时长 | 拍一次合影花多久（逼近 interval=病危） |
| Incremental CKPT | 增量快照 | 只上传变化的那几页（大状态标配） |
| SST File | SST 文件 | RocksDB 的数据文件（增量快照的单位） |
| Alignment Time | 对齐时间 | 等所有输入 Barrier 的时间（反压时飙升） |
| 惰性清理 | Lazy Cleanup | TTL 到了不立刻删，读到/压缩时才删 |

## 3. 动手实操：诊断+三板斧对照实验

```sql
-- ===== 实验①：造一个大状态作业（诊断素材）=====
CREATE TABLE big_state_src (
  uid INT, amt DOUBLE,
  et AS CURRENT_TIMESTAMP,
  WATERMARK FOR et AS et - INTERVAL '3' SECOND
) WITH (
  'connector' = 'datagen', 'rows-per-second' = '50',
  'fields.uid.kind' = 'random',
  'fields.uid.min' = '1', 'fields.uid.max' = '2000000',   -- 200 万基数！
  'fields.amt.min' = '10', 'fields.amt.max' = '500'
);
SET 'execution.checkpointing.interval' = '10s';
INSERT INTO ... SELECT uid, COUNT(*), SUM(amt) FROM big_state_src GROUP BY uid;
-- UI Checkpoints 页：看 State Size 涨势 + Duration（记录基线数字）
-- 预期：状态随 uid 覆盖度增长；Duration 可能从几百 ms 涨到秒级
```

```powershell
# ===== 实验②：三板斧逐个上（每上一个记录一次）=====
# 板斧② TTL（最快见效）：
docker exec -it flink-jm ./bin/sql-client.sh
# SET 'table.exec.state.ttl' = '60 s';  重跑同作业 → State Size 涨到 60s 后趋平

# 板斧③ RocksDB + 增量（重建 TM 带 rocksdb 配置，day06 实验③的姿势）：
docker stop flink-tm; docker rm flink-tm
docker run -d --name flink-tm --network bigdata `
  -e FLINK_PROPERTIES="jobmanager.rpc.address: flink-jm`ntaskmanager.numberOfTaskSlots: 4`nstate.backend: rocksdb`nstate.backend.incremental: true" flink:1.18 taskmanager
# 重跑大状态作业：
# UI 观察：State Size 换算到磁盘视角；增量 CKPT 后 Duration 明显下降
# （记录：HashMap 基线 vs RocksDB+增量 的 Duration 对比数字）
```

```
实验③：Checkpoint 时长诊断走一遍（不真治，练诊断）
  UI → Checkpoints → History 表：
  看 Alignment Time 列——若高=Barrier 在等（反压信号，回 day10）
  看各算子 Start Delay / Ack 时间——最晚的是瓶颈
  把诊断结论写进《Checkpoint 调优决策树》案例区
```

```
《Checkpoint 调优决策树》（贴墙）：
  Duration 飙升？
  ├─ Alignment Time 高 → 反压 → 治反压（day10）→ 不行开 unaligned
  ├─ State Size 大 → 增量 CKPT + RocksDB → 再看（状态瘦身三板斧并行）
  ├─ 快照 IO 慢 → HDFS/S3 带宽、并发线程数
  └─ 都不是 → interval 放宽（业务容忍内）+ min-pause
```

## 4. 面试连接

**Q：Flink 作业状态太大、Checkpoint 越来越慢，怎么治理？（调优必考题）**
> 两个慢性病分开治。状态太大三板斧按顺序上：先降基数——检查 key 设计能不能从明细级改聚合级，这是数量级的差距；再设 TTL——一切状态问"可以忘吗"，按业务寿命设（注意 TTL 是惰性清理，读到或 RocksDB 压缩时才真正释放）；最后换 RocksDB 后端——状态落本地磁盘，堆里只留索引，TB 级状态不 OOM。Checkpoint 慢先诊断再开药：UI 看 Duration 和 Alignment Time 两个指标——Alignment 高说明 Barrier 在等，根因是反压，先按五步法治反压，治不好再开非对齐 Checkpoint；状态大导致的就是增量快照——RocksDB 的 incremental 模式只上传变化的 SST 文件，大状态下快照时长能降一个量级，这是我实测过的数字；快照落盘本身慢就升 IO（HDFS 带宽/并发线程）。最后的权衡要讲清楚：interval 和业务容忍度是跷跷板——间隔按"可容忍丢失窗口"倒推，不能为了快照轻快无脑拉大间隔，那是拿正确性换性能。

**Q：RocksDB 后端的代价是什么？（追问，考权衡意识）**
> 三笔账：性能账——每次状态读写多一层序列化/反序列化和磁盘 IO，单条吞吐比 HashMap 后端低，纯转发型作业（几乎无状态）反而吃亏；运维账——RocksDB 文件要占磁盘配额，容器环境要盯磁盘水位和清理；行为账——增量快照依赖 SST 文件的引用关系，历史快照不能随便删（旧 SST 可能被新快照引用），保留策略要按文档配。所以选型不是"RocksDB 万能"：小状态高频访问用 HashMap 更快，状态上 GB 或需要增量快照才切 RocksDB——我按"预估状态 >几 GB"做分界线，这个数字来自自己实验里两种后端的切换对比。

## 5. 今日验收清单

- [ ] 两个慢性病的症状/诊断/药方能讲
- [ ] 状态瘦身三板斧的顺序（先业务后参数）能讲
- [ ] 实验①②完成（基线 vs TTL vs RocksDB+增量的对比数字）
- [ ] 《Checkpoint 调优决策树》成文
- [ ] RocksDB 三笔代价（性能/运维/行为）能讲
- [ ] `git add . && git commit -m "day12-22: state-tuning"`

---
[← Day 21](day21-第三周复盘.md) | [本月目录](README.md) | [Day 23 · 并行度与资源 →](day23-并行度与资源.md)
