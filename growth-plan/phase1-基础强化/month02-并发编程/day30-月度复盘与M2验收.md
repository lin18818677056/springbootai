# Day 30 · 月度复盘与 M2 验收（盘点 + month03 预习）

> **今日目标**：并发月收官日。对照 M2 验收 7 条逐项核对 → 五张大图总默写 → 产出全量盘点（代码/博客/题库）→ 20 问抽测自评 → month03 预习任务下发。
> **时长**：核对 1h / 默写 1h / 抽测 1h / 预习规划 0.5h
> **今日产出**：《M2 验收报告》+ 五图默写记录 + 20 问自评分数 + month03 预习清单

## 1. 知识地图

```
30 天并发月的知识总图（能默写出这张，本月就没白学）：

  地基层：JMM / happens-before / volatile / synchronized 演进
     (day01-06)
  通信层：wait-notify / park-unpark / CAS / ABA
     (day08-09)
  同步器层：AQS 三大件 → ReentrantLock / Condition / 三剑客 → 手写迷你 AQS
     (day10-13)
  池化层：线程池七参数 → 参数压测 → 手写迷你池 → CF 异步编排
     (day15-18)
  容器层：CHM putVal / ThreadLocal 泄漏 / TTL
     (day19-20)
  模式层：读写锁 / StampedLock / 不可变 / 两阶段终止 / Future / Balking
     (day22-23)
  工程层：虚拟线程 / bug 动物园 / 锁竞争与伪共享排查
     (day24-26)
  实战层：令牌桶限流器 / 手写连接池 / 聚合接口全链路 / 50 问题库
     (day27-29)

  与 month01 的焊接点：
    Arthas/JFR/JMH（排查工具）→ day26 并发排查直接复用
    MAT 堆分析 → day20 ThreadLocal 泄漏的取证
    虚拟线程入门 → day24 工程化落地
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Acceptance Review | 验收评审 | 对照标准逐条打勾，不凭感觉 |
| Master Checklist | 总清单 | 7 条验收 + 2 篇博客 + 50 问 |
| Mock Interview | 模拟面试 | 20 问抽测 + 口述打分 |
| Gap List | 差距清单 | 没达标项进 month03 优先队列 |
| Onboarding Task | 预习任务 | 下月开课前的前置作业 |

## 3. 动手实操

### 3.1 M2 验收 7 条逐项核对（来自 README §3，一条不落）

```text
验收标准（打勾 + 证据链接/数据）：
- [ ] ① happens-before 判定可见性安全性
      证据：day03 ReorderDemo + 判定练习 4 题 + day14 默写图
- [ ] ② 手写迷你 AQS 通过 100 线程计数（恒 100000）
      证据：day13 MiniAqsLockV2 运行记录 + 两个破坏实验
- [ ] ③ 手写迷你线程池 ≤100 行且对拍一致
      证据：day17 MiniPool（行数___）+ MiniPoolPairTest 双 100
- [ ] ④ 串行 300ms → 并行 100ms 聚合改造留压测数据
      证据：day18 AggCompare 数据（串____ms/并____ms）+ day29 报告 v1
- [ ] ⑤ CHM put 分支流程图 + ThreadLocal 泄漏链讲解
      证据：day19 默写图（六分支）+ day20 泄漏链默写
- [ ] ⑥ 限流器误差 <5% + 连接池无死锁无泄漏
      证据：day27 RateLimitTest（误差____%）+ day28 PoolStressTest 报告
- [ ] ⑦ 博客 2 篇：《一张图讲透 AQS》《手写迷你线程池》
      证据：链接①______ 链接②______

未达标项处理：写进《差距清单》，标注补齐时间（建议 month03 第 1-2 周消化）
```

### 3.2 五张大图总默写（每张 3 分钟，画完自查）

```text
图① JMM 与 happens-before（day03）：主内存/工作内存 + 8 条规则箭头
图② execute 三步决策（day15）：核心→队列→救急→拒绝 + SynchronousQueue 例外
图③ AQS 三大件与 acquire/release（day10/13）：state/队列/park + 快速路径
图④ CHM putVal 六分支（day19）：空桶 CAS/MOVED 帮扩容/synchronized 头节点/树化
图⑤ 聚合接口全链路（day29）：限流→池→CF 扇出→连接池→超时降级→汇合

评分标准：每张图能画 + 能配 1 句"为什么这样设计" = 满分
         只能画不能讲设计意图 = 及格（回炉对应 day 的面试连接段落）
```

### 3.3 全量产出盘点（git + 文件双核对）

```powershell
cd D:\mywork\springbootai\learning\month02-concurrency
git log --oneline | Measure-Object -Line      # 提交数（目标 ≥28）
Get-ChildItem *.java | Measure-Object         # Java 文件数（目标 ≥45）
Get-ChildItem *.java | Select-Object -ExpandProperty Name
# 盘点清单（本周应该全在）：
# day01-07: VisibilityDemo AtomicityDemo StateWatch InterruptDemo DaemonDemo
#           ReorderDemo SingletonDCL VolatileCost SyncDemo WrongLock DifferentLock
#           LockElim LockCoarsen ContentionLevel
# day08-14: WaitRules ParkDemo BoundedBuffer SpinLock ABADemo AdderCompare
#           FairVsUnfair BoundedBufferC InterruptibleLock TryLockDemo
#           InitDemo PoolDemo BatchDemo MiniAqsLockV2
# day15-21: SevenParamsDemo RejectPolicyDemo NamedFactory CpuBench IoBench
#           DynamicPoolDemo MiniPool MiniPoolPairTest AggCompare ChainDemo
#           TimeoutDemo MapBench CounterBug CacheDemo TLBasic LeakRepro
#           LeakFixed TtlDemo
# day22-30: RWCache LockDowngrade StampedDemo ImmutablePoint MainImmutable
#           MonitorService MiniFuture BalkingInit VTBasic VTCompare PinningDemo
#           CheckThenAct ThisEscape ThisSafe UnsafePublish LockHot
#           ContendedBench TokenBucket RateLimitTest BurstTest RateLimit(注解)
#           RateLimitAspect MiniConnPool PoolStressTest LeakScene AggService
```

### 3.4 面试 20 问抽测（从 50 问题库随机抽，口述打分）

```text
抽测规则：请朋友/录音软件抽 20 问（每组 4 问），每问 2 分钟内答完。
评分：每问 0-5 分（5=有原理+有实验证据；3=能讲原理没证据；1=只能背概念）

抽测重点池（建议覆盖）：
  A 组：3/4/6/9     B 组：11/12/13/15/19
  C 组：21/22/24/25/26   D 组：31/32/36/38   E 组：41/43/45/47

自评结果记录：
  总分 ___/100，得分最低的 3 题___/___/___ → 进入 month03 优先复习队列
  ★≥80 分：并发面试基本盘稳了；60-79：查漏补缺即可；<60：把 day14/21 复盘日重做
```

### 3.5 month03 预习任务（下月开课前完成）

```text
month03 主题预告（下月 README 生成后以正式版为准）：
  方向：MySQL 深度 / Spring 原理 / 分布式基础 三选一为主线（按你的短板定）
  预习三件事（总时长 ≤3h）：
    ① 写下 3 个"本月没吃透"的点（从差距清单来）
    ② 预习任务：把 day29 的 50 问 B/C 组各口述一遍（并发的保持成本最低方式）
    ③ 环境检查：MySQL 8（Docker Desktop 一行命令，下月实操会用到）：
       docker run -d --name mysql-learning -p 3306:3306 `
         -e MYSQL_ROOT_PASSWORD=root123 mysql:8.4
       docker exec -it mysql-learning mysql -uroot -proot123 -e "SELECT VERSION();"
  复习节奏：month03 每天 5 问（50 问库第二轮）继续滚动
```

## 4. 面试连接

**Q：系统学过并发吗？怎么证明？**
> 证据链回答：30 天计划（画知识总图）→ 4 个手写件（AQS/线程池/限流器/连接池）→ 2 篇博客（链接）→ 实测数据（伪共享 5-10 倍/限流误差 <5%/聚合 300→100ms）→ 50 问题库滚动复习。四层证据递进，这是"系统学过"的最硬证明。

**Q：这一个月你最大的认知变化？**
> 推荐答法："从背 API 到理解'同步器的本质 = 一个状态 + 一条队列 + 一套挂起唤醒协议'——手写过迷你 AQS 之后，看 ReentrantLock/Semaphore/CHM 全是同一个思想的不同 state 语义。"（这个答案同时展示了学习方法和知识深度。）

## 5. 今日验收清单（= M2 验收报告完成）

- [ ] 验收 7 条逐项打勾，证据齐全，差距清单成文
- [ ] 五张大图默写完成
- [ ] git 提交 ≥28、Java 文件 ≥45
- [ ] 20 问抽测 ≥80 分（或明确差距项）
- [ ] month03 预习三件事完成
- [ ] `git add . && git commit -m "day30: M2 acceptance passed"`

---
[← Day 29](day29-综合演练与并发50问.md) | [本月目录](README.md) | Month03 · 下月开课（目录待生成）
