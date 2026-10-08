# Day 05 · 火焰图与 JFR：给程序拍一张"CT 片"

> **今日目标**：看懂火焰图（性能优化的"照妖镜"）；学会用 JDK 自带的 JFR 录制 + JMC 分析；把 Agent 循环的热点找出来。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：一份 Agent 服务的 .jfr 文件 + 热点 Top3 记录

## 1. 知识地图（先讲人话）

```
火焰图（Flame Graph）是什么：
  一张"谁在偷 CPU"的合影。每层楼是一个方法调用栈，
  楼的宽度 = 这个方法占用的采样比例（越宽越忙）。
  看图三步法：
  ① 找最宽的"平顶"——塔顶又宽又平，说明它就是大头
    （尖塔不可怕，那只是一条很深的调用链路过了一下）
  ② 顺着宽塔往下读——谁调用了它（父楼层）
  ③ 对照业务代码——确认这是"该忙的"还是"白忙的"

  类比：班级合影里谁最占画面（最宽）谁就最显眼；
  平顶 = 它自己干活干很久；尖顶 = 它只是路过调了别人。

JFR（Java Flight Recorder，飞行记录仪）：
  飞机的黑匣子——平时低开销持续录（<1% 性能损耗），
  出事时把录像倒出来分析。
  JDK 11 起免费开源，JDK 25 全自带，Windows 直接可用。

JMC（Java Mission Control）：看黑匣子的播放器。
  打开 .jfr 文件 → Method Profiling 视图 → 热点方法列表/火焰图。

三个信号量术语（监控高频词）：
  USE 方法：Utilization 使用率 / Saturation 饱和度 / Errors 错误数
  ——看任何资源（CPU/内存/连接池）都从这三问开始
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 大白话 |
|------|------|--------|
| Flame Graph | 火焰图 | 调用栈合影，宽度 = CPU 占比 |
| Sampling Profiler | 采样剖析器 | 定期"偷看"线程在干嘛（不入侵、开销低） |
| JFR | 飞行记录仪 | JVM 内置黑匣子，持续录制低开销 |
| Hot Path | 热点路径 | 被调用最频繁/耗时最集中的代码路径 |
| On-CPU / Off-CPU | 在 CPU 上 / 离开 CPU | 正在算 / 在等（锁、IO、网络）——两类问题查法不同 |

> **关键区分**：火焰图看的是"On-CPU 时间"（谁占着 CPU）；但 Agent 循环的大头是"Off-CPU 等待"（等 Ollama 返回）。这解释了为什么火焰图里模型调用看起来"不忙"——它不占 CPU，它只是在等。**等 ≠ 快，火焰图不是唯一真相**。

## 3. 动手实操

### 3.1 录制 JFR（两种姿势）

```powershell
# 姿势一：启动时就录（适合能重启的服务）
java -XX:StartFlightRecording=duration=120s,filename=agent.jfr -jar app.jar

# 姿势二：运行中动态录（线上急救常用，不用重启！）
jcmd <pid> JFR.start name=agent duration=120s filename=agent.jfr
jcmd <pid> JFR.check           # 看录制状态
# 等 2 分钟自动落盘，然后压测跑起来（day04 的 MiniLoad 并发 5 档）
```

### 3.2 用 JMC 分析

```
打开 JDK 自带的 jmc.exe → Flight Recording → 打开 agent.jfr
① Method Profiling & Hotspots 视图：按 Self Time 排序记 Top3
② Flame Graph 视图：找最宽的平顶
③ GC 视图：暂停时长分布（为 day16 调优做参考）

预期发现（写进台账"瓶颈归因"栏）：
  热点大概率是：JSON 序列化（Jackson 拼 trace）/ HTTP 客户端
  编解码 / Ollama 轮询等待——如果是这些，说明归因对了
```

### 3.3 找平顶实验（理解"宽度=占比"）

```powershell
# 跑个故意烧 CPU 的小程序录制 JFR，验证你能找到它
# HotCpu.java: 三个方法 a()调b()调c()，c()里 for 循环空转
# 录 30 秒 JFR → JMC 火焰图应显示 c() 是最宽的平顶
# 能找到 = 会读火焰图了；找不到 = 回去看三步法
```

### 3.4 思考题

火焰图上 `TraceWriter.saveSync` 很窄，但接口 P99 里它贡献了 80ms。为什么火焰图"看不见"它？（提示：它的时间是 Off-CPU 等待磁盘/数据库，不占 CPU——所以要用 Arthas trace 测"墙钟时间"而不是只看 CPU 时间；day06 就是干这个）

## 4. 面试连接

**Q：火焰图怎么看？**
> 先找宽的平顶（宽度=CPU 占比，平顶=自己干活久），顺着楼层看调用关系，最后对照业务判断是"该忙"还是"白忙"。但我会补一句：火焰图只反映 On-CPU 时间，等锁等 IO 这类 Off-CPU 等待要配合 trace 工具看墙钟——这是我排查 Agent 留痕问题时踩过的认知坑。

**Q：JFR 和普通 profiler 的区别？为什么生产敢开 JFR？**
> JFR 是 JVM 内置的采样式黑匣子，开销 <1%，可以常开——出事时数据已经在手里。普通 profiler 很多是侵入式的，一开服务就抖，只适合开发环境。生产定位套路：JFR 常开保底 + Arthas 现场精查。

## 5. 今日验收清单

- [ ] Agent 服务录出 .jfr 并用 JMC 打开
- [ ] 热点 Top3 记进台账"瓶颈归因"栏
- [ ] HotCpu 实验找到平顶（会读图了）
- [ ] 能讲清 On-CPU vs Off-CPU 的区别
- [ ] `git add . && git commit -m "day16-05: jfr & flame-graph"`
- [ ] 笔记：手画一张"平顶 vs 尖顶"火焰图示意

---
[← Day 04](day04-系统压测与监控.md) | [本月目录](README.md) | [Day 06 · Arthas 排查实战 →](day06-Arthas排查实战.md)
