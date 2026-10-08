# Day 06 · Arthas 排查实战：在线内窥镜

> **今日目标**：学会 Arthas（阿里出品、不改代码不重启的在线诊断神器）；用 trace 命令实测出"留痕同步写"到底偷了多少毫秒——M15 交接卡②的定罪环节。
> **时长**：理论 0.5h / 实操 2h / 输出 0.5h
> **今日产出**：Arthas trace/watch 实测数据 + "同步留痕"的定罪证据

## 1. 知识地图（先讲人话）

```
Arthas 是什么：
  阿里开源的 Java 在线诊断工具。服务正在跑、代码不能停、
  日志又没打够——Arthas 直接"连上去看"，像给行驶中的车
  做内窥镜：不熄火、不拆引擎。
  原理：attach 到 JVM，用字节码增强临时插桩，用完 detach。

四大天王命令（本月够用）：
  dashboard   → 实时仪表盘（线程/CPU/内存总览，先看全貌）
  thread -n 3 → 最忙的 3 个线程在干嘛（CPU 飙高第一现场）
  trace 类名 方法名 '#cost > 10'  → 方法内部每一步耗时
                （只打印超 10ms 的调用，专抓慢调用！）
  watch 类名 方法名 '{params,returnObj}' → 看入参出参（不用加日志）

trace 和火焰图的分工（昨天思考题的答案）：
  火焰图：谁占 CPU（On-CPU）——算力问题
  trace：这一步墙钟耗时多少（含等待）——延迟问题
  Agent 场景大量是"等"（等模型/等磁盘/等数据库），
  所以 trace 是 Agent 优化的主力工具。

今天的"定罪"目标（M15 交接卡②前半）：
  证明 TraceWriter.saveSync 在主链路上，
  单次几十 ms，每轮都来一次——
  拿到数字，day13 的异步化改造才有对比基线。
```

## 2. 核心概念（中英对照）

| 英文/命令 | 中文 | 大白话 |
|------|------|--------|
| Attach | 附加 | 不重启连上运行中的 JVM |
| Bytecode Instrument | 字节码插桩 | 临时给方法装个秒表 |
| '#cost > N' | 耗时过滤 | 只看慢于 N 毫秒的调用（降噪神器） |
| Watchpoint | 观察点 | 盯住方法的入参/出参/this |
| Watch Clock Time | 墙钟时间 | 你掐手表的真实耗时（含所有等待） |

## 3. 动手实操

### 3.1 启动 Arthas 并总览

```powershell
# 下载（第一次）
curl -O https://arthas.aliyun.com/arthas-boot.jar
# 启动（会列出 Java 进程让你选，输 Agent 服务的编号）
java -jar arthas-boot.jar
```

```
# 进到 Arthas 命令行后：
dashboard          # 总览：记下 CPU%、堆使用、最忙线程
thread -n 3        # 最忙 3 个线程的栈——看它们堵在哪一行
thread --state WAITING | Select-String "pool"   # 池子里的线程在等谁
```

### 3.2 定罪：trace 实测同步留痕（今天的重头戏）

```
# 在 Agent 服务跑一个真实提问（或压测低档并发），同时：
trace com.demo.agent.TraceWriter saveSync '#cost > 5'
# 输出示例（数字要你实测）：
# `---[45.32ms] com.demo.agent.TraceWriter:saveSync()
#     +---[2.10ms] buildTraceJson()
#     `---[42.87ms] JdbcTemplate:update()   ← 罪魁：数据库写入！
# 重复 10 次记录：平均 __ms / 最大 __ms
# 再看主链路：trace com.demo.agent.AgentLoop runRound
#   → 确认 saveSync 挂在主链路里（每轮都等它）

# 顺手看一眼入参（验证隐私问题，day18 采样的动机之一）：
watch com.demo.agent.TraceWriter saveSync '{params[0].userId, params[0].think.length()}' -x 1
```

### 3.3 记录台账（定罪证据入册）

```
台账 · 优化点②同步留痕（定罪完成）
  基线：saveSync 平均 __ ms / 最大 __ ms（10 次实测）
  归因：主链路每轮同步等数据库 update（墙钟时间，火焰图看不见）
  改造计划：有界队列 + 批量落盘 + 拒绝策略（day13）
  复测目标：主链路增量 < 1ms（day23 验收）
```

### 3.4 思考题

trace 显示 saveSync 平均 45ms，但数据库监控显示这条 update 只花了 5ms。差在哪？（提示：连接池排队拿连接 + 网络往返 + 事务提交 fsync——trace 给的是"总账"，要拆开就得再 trace JdbcTemplate.update 一层；"45ms 不等于 SQL 慢"是重要认知）

## 4. 面试连接

**Q：线上发现接口变慢，你的排查套路？**
> 先看监控定范围（全部慢还是个别慢，何时开始），然后三板斧：①trace 关键方法看每步耗时定位环节；②thread -n 3 看线程堵在哪（锁/池子/下游）；③jstat 看 GC 是不是在捣乱。工具首选 Arthas——不改代码不重启，attach 上去几分钟出结论。我在 Agent 项目里就是用 trace 定位到留痕同步写偷走了每轮 40+ms。

**Q：Arthas 的原理？有风险吗？**
> attach + 字节码增强临时插桩，detach 后恢复。风险：生产用要谨慎——watch/trace 有开销，别开在高流量核心方法上太久；改字节码类操作（如 mc/redefine）要严格管控。我们的纪律是：只读观测随便用，改写操作必须审批——跟 M15 的"工具三道闸"一个思想。

## 5. 今日验收清单

- [ ] Arthas attach 成功，dashboard/thread 各跑一遍
- [ ] trace 定罪 saveSync：10 次实测数据入台账
- [ ] watch 看到入参（并意识到隐私问题）
- [ ] 能回答"火焰图为什么看不见 saveSync"
- [ ] `git add . && git commit -m "day16-06: arthas & conviction"`
- [ ] 笔记：四大天王命令各记一个使用场景

---
[← Day 05](day05-火焰图JFR.md) | [本月目录](README.md) | [Day 07 · 第一周复盘 →](day07-第一周复盘.md)
