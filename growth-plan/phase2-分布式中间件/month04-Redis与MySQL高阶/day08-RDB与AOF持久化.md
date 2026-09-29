# Day 08 · RDB 与 AOF 持久化（kill -9 恢复实验）

> **今日目标**：Redis 数据在内存，宕机怎么办？今天拆解两大持久化：RDB（快照）与 AOF（追加日志），做一次真实的 kill -9 恢复实验，算清"everysec 到底丢多少"——并与 month03 day25 的 MySQL WAL 对照，持久化设计思想一次贯通。
> **时长**：理论 1.5h / 实操 1.5h / 输出 0.5h
> **今日产出**：RDB/AOF 对比表 + 恢复实验记录 + 持久化选型卡

## 1. 知识地图

```
RDB（Redis Database）= 内存快照（二进制紧凑文件）：
  触发：save 900 1 / 300 10 / 60 10000（条件规则）或 BGSAVE 手动
  核心机制 fork + COW（Copy-On-Write 写时复制）：
    主进程 fork 子进程 → 子进程共享主进程内存页（只读）
    子进程遍历内存生成 dump.rdb；主进程继续服务
    主进程写数据时内核才复制对应页（COW）→ "冻结"的是快照瞬间的数据
  优点：文件紧凑恢复快；缺点：两次快照间的数据会丢
  ★bgsave 期间扩容阈值提高（day02 呼应：负载因子≥5）

AOF（Append Only File）= 写命令日志（文本协议 RESP 格式）：
  写入流程：命令 → aof_buf → 按策略 fsync
  三档 fsync：
    always   每命令刷盘 —— 不丢但吞吐暴跌（对照 MySQL 双 1）
    everysec 每秒刷盘（默认）—— 最多丢 1 秒 ★"怎么算"见下
    no       交给 OS —— 性能好，丢多少看天
  AOF 重写（Rewrite）：日志只记最终状态（SET k final_v 替代一百次 SET k）
    同样 fork+COW；重写期间新命令写 aof_buf + 重写缓冲，最后追加
  4.0+ 混合持久化（aof-use-rdb-preamble yes）：
    重写时 RDB 头（全量快照）+ 增量 AOF 尾 —— 恢复快 + 丢失少

everysec 最多丢 1 秒的推算（★面试推演）：
  命令先进 aof_buf；后台线程每秒 fsync 一次
  最坏场景：fsync 刚做完的下一毫秒宕机 → 上次 fsync 后的全部命令丢失
    = 距上次刷盘最多 1 秒 + 本次阻塞期间的增量 ≈ 1~2 秒
  （若 fsync 阻塞超 2s，主线程会强制写盘——细节加分）

RDB vs AOF 决策表：
  | 维度     | RDB            | AOF              |
  |----------|----------------|------------------|
  | 数据安全 | 分钟级丢失     | 秒级(everysec)   |
  | 文件大小 | 紧凑二进制     | 大(重写可瘦身)   |
  | 恢复速度 | 快(直接载入)   | 慢(逐条重放)     |
  | 性能影响 | fork 瞬间抖动  | 持续 fsync 开销  |
  生产默认：两者都开 + 混合持久化 —— "快恢复+低丢失"双保险
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Snapshot | 快照 | RDB 某时刻全量 |
| Copy-On-Write | 写时复制 | fork 后共享页，写才复制 |
| fsync | 强制刷盘 | 数据真正落盘的系统调用 |
| AOF Rewrite | AOF 重写 | 日志瘦身为最终态 |
| Hybrid Persistence | 混合持久化 | RDB 头 + AOF 尾 |
| Durability | 持久性 | 宕机不丢的程度 |

## 3. 动手实操

### 3.1 RDB 实验台：配置、触发、验证

```powershell
docker exec -it redis-learning redis-cli
#   CONFIG GET save                        # 默认 3600 1 300 100 60 10000
#   CONFIG SET save "60 100"               # 实验条件：60 秒内 100 次变更
#   CONFIG GET dir                         # RDB 存放目录
#   DBSIZE
#   BGSAVE                                 # 手动触发（观察返回 Background saving started）
#   INFO persistence | Select-String rdb   # rdb_last_save_time / rdb_last_bgsave_status
# fork+COW 观察点：
#   INFO stats 里 mem_fragmentation_ratio 在 bgsave 期间可能升高（页复制）
#   大实例 bgsave 瞬间 latency 抖动 = fork 的成本（生产在从库做 RDB 的原因）
```

### 3.2 AOF 实验台：开启、验证内容、重写

```powershell
docker exec redis-learning redis-cli CONFIG SET appendonly yes
docker exec redis-learning redis-cli CONFIG SET appendfsync everysec
docker exec redis-learning redis-cli SET aof:test hello
docker exec redis-learning redis-cli INCR aof:counter
# 看 AOF 文件内容（RESP 文本格式——day06 学的协议肉眼可见）：
docker exec redis-learning sh -c "tail -5 /data/appendonlydir/appendonly.aof.* 2>/dev/null || ls /data/appendonlydir/"
#   应看到 *3 $3 SET ... 的 RESP 字节流 ★
# 手动触发重写（对比文件大小）：
docker exec redis-learning redis-cli BGREWRITEAOF
docker exec redis-learning redis-cli INFO persistence | Select-String aof
#   aof_current_size / aof_rewrite_in_progress / aof_last_bgrewrite_status
```

### 3.3 主菜：kill -9 恢复实验（数据到底丢多少）

```powershell
# AOF everysec 下：连续灌 10 秒数据 → kill -9 → 重启 → 数存活 key
$pipe = (1..3000 | ForEach-Object { "SET k:$_ $('v'*100)`r`n" }) -join ""
$pipe | docker exec -i redis-learning redis-cli
docker exec redis-learning redis-cli DBSIZE          # 灌完总数（记下）
# 立即强杀（模拟断电，不走 shutdown 流程）：
docker kill --signal=SIGKILL redis-learning
docker start redis-learning
Start-Sleep -Seconds 3
docker exec redis-learning redis-cli DBSIZE          # 恢复后总数（记下）
# 记录：丢失 = 灌完数 - 恢复数 ≈ 最后 ~1 秒的写入 ★everysec 的丢失实证
# 对照组：CONFIG SET appendfsync always 重做一遍（灌慢一点）→ 丢失 ≈ 0 但吞吐显著下降
# RDB-only 对照：appendonly no + save "15 1"，等 15 秒快照后 kill → 丢失最多 15 秒
# 三组数字写进笔记：always/__ everysec/__ rdb-only/__ —— 选型从此有据
```

### 3.4 持久化选型卡（抄笔记）

```text
| 场景                | 推荐配置                       | 理由                    |
|---------------------|--------------------------------|-------------------------|
| 纯缓存(可重建)      | 关 AOF，RDB 低频               | 丢了从 DB 慢慢回填      |
| 数据型 Redis        | AOF everysec + 混合 + RDB 兜底 | 秒级丢失可接受          |
| 金融/强一致场景     | 不用 Redis 当真库！            | 持久化只是减损不是保证  |
| 从库做 RDB 备份     | 主关 RDB，从开                 | 避免 fork 抖动主库      |
红线：持久化≠备份——RDB/AOF 在同机磁盘，机器没了全没；要异地+备份策略
（对照 month03 day27 容量账：Redis 持久化文件也要进磁盘预算）
```

## 4. 面试连接

**Q：RDB 和 AOF 怎么选？什么是混合持久化？**
> 先给对比表四维（安全/大小/恢复速度/性能影响），再给场景结论：纯缓存 RDB 低频、数据型 AOF everysec+混合。混合持久化：4.0+ 的 aof-use-rdb-preamble，AOF 重写时文件头是 RDB 全量、尾部是增量命令——载入时 RDB 秒级加载 + 增量少量重放，"恢复快+丢失少"双赢，生产默认。加分："我做过多组 kill -9 实验：everysec 实测丢最后 1~2 秒，always 丢 0 但吞吐减半。"

**Q：BGSAVE 的 fork 为什么要用 COW？会带来什么问题？**
> 全量快照若冻结主进程做，亿级内存要秒级停服——不可接受。fork 让子进程共享内存页只读执行快照，主进程照常服务；仅当主进程写某页时内核复制该页（COW）——快照一致性由"页级时间旅行"保证。问题：① 写入高峰期页复制放大内存（极端翻倍，容量规划要留）；② fork 本身拷页表，大实例瞬间阻塞（几十 ms~百 ms 级，正是大实例禁止主库 RDB 的原因）。

**Q：Redis 的持久化和 MySQL 的 redo log 思想差在哪？（跨月神题）**
> 对照答：共同点都是 WAL 家族思想——先记日志保住"变更事实"，数据页延后处理。差异三点：① AOF 记的是协议层命令（逻辑日志），redo 记页级物理改动；② AOF 重放靠重新执行命令，redo 重放是页内修补（更快）；③ MySQL 双 1 是事务语义的一部分，Redis everysec 是可调的性能档位。收尾："逻辑日志可读可跨版本，物理日志快而绑定存储格式——两边做了相反选择，因为一致性要求不同。"这个答案是月度串讲的最佳素材。

## 5. 今日验收清单

- [ ] RDB/AOF 配置与文件内容观察（RESP 字节流截图）
- [ ] kill -9 三组实验：always/everysec/RDB-only 丢失数字记录
- [ ] everysec 丢 1 秒的推算过程能讲
- [ ] 选型卡默写；COW 的问题能展开
- [ ] `git add . && git commit -m "day08: rdb/aof & kill-9 recovery"`

---
[← Day 07](day07-第一周复盘输出.md) | [本月目录](README.md) | [Day 09 · Redis 主从复制 →](day09-Redis主从复制.md)
