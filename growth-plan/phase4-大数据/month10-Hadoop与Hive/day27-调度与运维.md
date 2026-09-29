# Day 27 · 调度与运维：让流水线每天自己跑

> **今日目标**：给 26 天攒出来的 SQL 流水线装上"自动传送带"——手写一个 30 行的迷你调度器（依赖顺序+失败重试+日志），再用它讲透生产级调度器（DolphinScheduler）的核心机制。**兑现 M7 状态机伏笔**：任务实例就是一个状态机。
> **时长**：迷你调度器 2h / 状态机与补数 1.5h / DolphinScheduler 部署 1.5h
> **今日产出**：可运行的 `mini-scheduler.sh` + 《任务状态机图》+ 补数 SOP

## 1. 知识地图

```
为什么需要调度器？——26 天的 SQL 是"菜谱"，调度器是"厨房排班表"：
  每天 0 点自动开工、按依赖顺序做（洗配完才能备餐）、一道菜做坏了重做三次、
  三次还不行就整条线停下+喊人（告警）——不能靠人每天手工敲 SQL。

任务实例状态机（M7 订单状态机平移：状态+合法迁移+非法迁移禁止）
  PENDING（排队等上游）
     │ 上游 SUCCESS 且轮到它
     ▼
  RUNNING ──成功──▶ SUCCESS ──▶ 触发下游 PENDING
     │ 失败
     ▼
  RETRY_WAIT ──间隔后──▶ RUNNING（最多 3 次）
     │ 3 次仍失败
     ▼
  FINAL_FAILED ──▶ 全链 STOP（下游全部不启动）+ 告警喊人
  铁律和 M7 订单一样：FINAL_FAILED 的任务，下游"未完成"就是不能跑——
  没有"跳过失败继续跑"这种非法迁移（宁可不产出，不许产错数）

依赖设计的核心思想（面试金句）：
  下游不依赖"几点了"，依赖"上游完成了"——
  事件驱动解耦，上游慢十分钟下游自动等（对比 crontab 写死 1 点跑：上游延迟就产错数）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Scheduler / 工作流 | 排班表+传送带（定任务顺序、失败重试、整线联动） |
| DAG 依赖 | 有向无环图（A 完了才跑 B；不许 A 依赖 B 同时 B 依赖 A） |
| Backfill 补数 | 拿历史日期把流水线重跑一遍（参数化 dt 的价值） |
| 幂等重跑 | 重跑不翻倍（INSERT OVERWRITE 铁律是补数安全的前提） |
| SLA 基线 | 约定"几点前必须产出"（超线即告警，和 M8 SLO 同构） |

## 3. 动手实操：迷你调度器

```bash
# ===== 步骤 1：把 4 段 SQL 存成文件，放进 hive-server 容器 =====
# 本地建 dw/01_dwd.sql 02_dqc.sql 03_dws.sql 04_ads.sql（day25/26 的 SQL 原样搬入，
# 每处日期写成 ${hivevar:dt}），然后：
docker cp dw hive-server:/opt/dw
docker exec hive-server bash -c "mkdir -p /opt/dw/logs"

# ===== 步骤 2：mini-scheduler.sh（30 行浓缩全部调度思想）=====
#!/bin/bash
# 用法: ./mini-scheduler.sh 2026-09-25   （参数=要跑的数据日期，补数的钥匙）
DT=$1
TASKS=("dwd:01_dwd.sql" "dqc:02_dqc.sql" "dws:03_dws.sql" "ads:04_ads.sql")  # 顺序即依赖
for t in "${TASKS[@]}"; do
  name=${t%%:*}; file=${t##*:}; retry=0; ok=0
  while [ $retry -lt 3 ]; do                                  # RETRY 循环（最多 3 次）
    echo "[$(date +%T)] $name attempt $((retry+1)) RUNNING (dt=$DT)"
    if hive -f /opt/dw/$file -hivevar dt=$DT > /opt/dw/logs/${name}_$DT.log 2>&1; then
      echo "[$(date +%T)] $name SUCCESS"; ok=1; break         # SUCCESS → 放行下游
    fi
    retry=$((retry+1)); echo "$name FAILED, retry in 5s"; sleep 5
  done
  if [ $ok -eq 0 ]; then                                      # 3 次全败
    echo "$name FINAL_FAILED -> STOP pipeline (dt=$DT)"       # 整线停止，下游 PENDING 冻结
    echo "ALERT: $name failed 3x on $DT" | mail -s "DW ALERT" oncall@x.com
    exit 1
  fi
done
echo "PIPELINE SUCCESS for $DT"

# ===== 步骤 3：跑通+验证补数 =====
chmod +x mini-scheduler.sh && ./mini-scheduler.sh 2026-09-25   # 正常跑
./mini-scheduler.sh 2026-09-26                                  # 换日期=补数（幂等保障不翻倍）
# 破坏性实验：故意改坏 02_dqc.sql → 观察重试 3 次 → FINAL_FAILED → 03/04 不执行
```

```powershell
# ===== 步骤 4：生产级方案见个真容：DolphinScheduler 单机版 =====
docker run -d --name dolphinscheduler --network bigdata `
  -p 12345:12345 apache/dolphinscheduler-standalone-server:3.2.1
# 浏览器开 http://localhost:12345（admin/dolphinscheduler123）：
# 建项目→建工作流：4 个 SHELL 任务按依赖连线→设失败重试 3 次/间隔 5 分钟→保存上线→手动运行
# 界面上能看到每个任务实例的状态机流转（和上面手写的一一对应）
# 诚实边界：standalone 是单机演示版；生产是多 Master/Worker+ZK+DB 高可用（部署清单记 TODO）
```

## 4. 面试连接

**Q：每天凌晨的批处理任务失败了，你怎么处理？（运维第一问）**
> 分"当场"和"事后"两层讲。当场：调度器自动重试 3 次（间隔 5 分钟）——很多失败是瞬时抖动（网络闪断、某台机器 GC），重试就过了；3 次全败则整条链 STOP，下游任务冻结不启动，同时告警喊人——原则是"宁可不产出，不许产错数"，跳过失败继续跑等于让下游基于半成品数据算数，比不跑更恶劣。事后：拿到告警先看失败任务的日志（我的 mini 调度器里每个任务独立日志文件，生产 DS 界面直接看），定位是资源不够（YARN 队列占满，临时扩容或错峰）、数据问题（上游脏数据触发 DQC 阻断，去查隔离区和上游同步）还是代码 bug（修复后走补数）。修完后用参数化的日期重跑——这就是为什么全链路必须幂等（INSERT OVERWRITE）和参数化（hivevar:dt）：补数的前提是"重跑一百遍结果都一样"。我的状态机设计直接平移自 M7 的订单状态机：状态+合法迁移+非法迁移禁止——这套思维从业务系统搬到数据系统是完全通用的。

**Q：上游数据晚到了，补数怎么做？依赖怎么设计才不出事故？**
> 先说依赖设计的根：下游不应该依赖"几点了"，应该依赖"上游完成了"。crontab 写死"1 点跑 DWS"的方案，上游 1 点半才就绪时，DWS 拿到的是昨天的旧分区——产错数比不产出更可怕。正确做法是依赖事件：调度器里 DWS 节点声明"上游 dqc 节点 SUCCESS 才启动"，上游慢多久都自动等。补数 SOP 三步：①确认补数范围（哪天、哪条链）——用参数化日期把流水线对历史 dt 重跑，这就是建表按天分区+SQL 参数化的回报；②幂等保障——因为写入全是 OVERWRITE 分区，重跑覆盖旧结果不翻倍，不用先手工删数据；③补完做对账——day26 的四连对账再跑一遍，数字对上才宣布补数完成。还有一个容易漏的点：补数要看依赖拓扑从上游往下游按序补，不能只补断掉的那一层（上游重跑了，下游也必须重跑，否则下游是"新上游+旧下游"的缝合数据）。生产级用 DolphinScheduler 的补数功能圈选日期区间自动按拓扑跑，原理和我的 30 行脚本完全一致。

## 5. 今日验收清单

- [ ] 任务状态机图能白板画（6 状态+合法迁移+非法迁移铁律）
- [ ] mini-scheduler.sh 跑通 4 任务+破坏性实验（3 次重试→整线停）
- [ ] 补数实验完成（换 dt 重跑、结果不翻倍）
- [ ] "依赖事件不依赖时刻"+补数 SOP 三步能脱稿讲
- [ ] DolphinScheduler standalone 部署并看到状态流转界面
- [ ] `git add . && git commit -m "day10-27: scheduler ops"`

---
[← Day 26](day26-数仓项目二.md) | [本月目录](README.md) | [Day 28 · 全月大串讲 →](day28-全月大串讲.md)
