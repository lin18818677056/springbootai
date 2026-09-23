# Day 16 · JFR 飞行记录仪（Java Flight Recorder）

> **今日目标**：JFR 是 JVM 内置的"黑匣子"——开销 <1% 却能持续记录 GC/线程/锁/分配事件。今天掌握三种录制方式，并用 JDK21+ 的 `jfr view` 命令在命令行直接出报告。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：一份 60s 的 profile 录制 + 热点方法报告

## 1. 知识地图

```
JFR 架构：为什么它敢说"随时开着也不影响生产"？

业务线程 ──发事件──► 环形缓冲区 Ring Buffer（内存，写满覆盖最旧）
                        │ 异步 / 批量 / 无锁
                        ▼
                    落盘 .jfr 文件（二进制）
                        │
          ┌─────────────┴──────────────┐
          ▼                            ▼
   jfr view / jfr print           JMC 图形界面
   （JDK21+ 命令行直接看）          （专业分析，选装）

和"老三样"对比着记：
  jmap dump  = 某一瞬间的"照片"（STW，文件大）→ 定位泄漏
  jstack     = 某一瞬间线程"合影"            → 定位死锁/卡死
  JFR        = 全程"行车记录仪"（异步低开销）→ 看趋势/找热点/抓偶发

两种预设：
  settings=default  核心事件，开销极低（可常驻生产）
  settings=profile  全开 + 方法采样 ~20ms/次，找热点用它
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| JFR (Java Flight Recorder) | 飞行记录仪 | JVM 内置事件记录框架，JDK11 起开源免费 |
| JMC (JDK Mission Control) | 任务控制台 | JFR 的图形化分析客户端（选装） |
| Event Type | 事件类型 | GC/方法采样/锁竞争/IO 等数百种 |
| Method Sampling | 方法采样 | 周期性抓线程栈，按次数统计热点方法 |
| Ring Buffer | 环形缓冲区 | 事件先写内存，异步批量刷盘（低开销的根） |
| `jfr view` | 命令行视图 | JDK21+ 新子命令，不装 GUI 也能出报表 |

## 3. 动手实操

### 3.1 方式一：启动参数直接开录（适合自己可控的程序）

```powershell
cd D:\mywork\springbootai\learning\month01-jvm; mkdir day16 -Force; cd day16
Copy-Item ..\day05\AllocDemo.java .
javac AllocDemo.java

# 启动即录 60 秒（profile 预设 = 方法采样全开）
java -XX:StartFlightRecording=duration=60s,filename=rec.jfr,settings=profile AllocDemo
# 控制台会打印：Recording 1 starts... Use jcmd ... 提示语
```

### 3.2 方式二：jcmd 随时开录（生产首选，不重启进程）

```powershell
# 另起一个长跑目标：day15 的 DiagTarget（有泄漏+死锁，素材丰富）
Copy-Item ..\day15\DiagTarget.java .
javac DiagTarget.java
java DiagTarget          # 记下它自己打印的 PID

# 开录：120 秒自动结束，或手动停
jcmd <PID> JFR.start name=rec1 duration=120s filename=rec2.jfr settings=profile
# 中途导出"到目前为止"的快照（录制继续）：
jcmd <PID> JFR.dump name=rec1 filename=snapshot.jfr
# 手动停止：
jcmd <PID> JFR.stop name=rec1
```

### 3.3 `jfr view`：命令行出报表（JDK21+，你的 JDK25 直接可用）

```powershell
jfr view rec.jfr                      # 总览（聚合页）
jfr view hot-methods rec.jfr          # ★ 热点方法排行（今天的主角）
jfr view allocation-by-site rec.jfr   # ★ 按分配位点统计（找大对象来源）
jfr view gc rec.jfr                   # GC 汇总（次数/耗时/回收量）
jfr view threads rec.jfr              # 线程事件摘要
jfr view exception rec.jfr            # 异常统计
# 想看原始事件流（研究用）：
jfr print --events jdk.ExecutionSample rec.jfr | Select-Object -First 40
```

**把 `jfr view hot-methods` 输出贴进笔记，圈出前三名方法**——这就是这段程序的"流量画像"。

### 3.4 观察点（记录到 notes/week03/jfr.md）

1. hot-methods 里 AllocDemo 的栈帧占比多少？和直觉一致吗？
2. allocation-by-site 排第一的是哪一行代码？（应指向 `new byte[1024]`）
3. `jfr view gc` 的次数/耗时，和 day13 用 `-Xlog:gc` 统计的对得上吗？

## 4. 面试连接

**Q：JFR 和 jmap dump 都能查内存，怎么选？**
> 定位泄漏用 dump（精确到对象引用链，但 STW、文件大）；看趋势/找分配热点用 JFR（低开销可持续，能看到"谁在分配"）。生产组合拳：JFR 常态低频录制，出事再 dump 取证。

**Q：JFR 为什么开销这么小？**
> 三个设计：事件先写内存环形缓冲区（不等磁盘）、异步批量刷盘、二进制紧凑格式。方法采样是"定期抓栈计数"，不是字节码插桩。

**Q：线上偶发卡顿，事后没留日志，怎么办？**
> 这正是 JFR 的主场：`-XX:StartFlightRecording=dumponexit=true,maxsize=1g` 常态开着或定时循环录制，出事拿最近几分钟的记录复盘。补一句"我们用定时任务每 10 分钟滚动保留 1 小时"直接加分。

## 5. 今日验收清单

- [ ] 三种操作都动手：参数开录 / jcmd JFR.start / JFR.stop
- [ ] `jfr view` 六个视图都看过
- [ ] hot-methods 报告截图存 notes/week03/
- [ ] 能说清 JFR vs dump vs jstack 的定位差异
- [ ] `git add . && git commit -m "day16: jfr flight recorder"`

---
[← Day 15](day15-命令行工具全家桶.md) | [本月目录](README.md) | [Day 17 · Arthas 入门 →](day17-Arthas入门.md)
