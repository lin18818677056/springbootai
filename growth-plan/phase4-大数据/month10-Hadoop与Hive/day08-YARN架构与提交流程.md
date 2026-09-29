# Day 08 · YARN：工头、包工头与工人

> **今日目标**：搞懂 YARN 的三大角色（RM/AM/NM）怎么配合跑一个作业——用人话讲：RM 是总包工头（管整个工地的资源账），AM 是每个工程的包工头（管自己这个活的进度），NM 是每个车间的工头（管本车间的机器和工人）。今天画出作业提交全流程图。
> **时长**：三角色与提交流程 2h / 容器与资源模型 1h
> **今日产出**：YARN 作业提交流程手绘图（白板标准）+ WordCount 在 YARN 上重跑观察

## 1. 知识地图

```
问题引入：day06 跑 WordCount 时，谁决定"用哪台机器的多少内存和 CPU"？
集群几十台机器、同时跑几十个任务，不能靠吼——需要一个资源管家，这就是 YARN。
（先建立类比：一个建筑工地——
  ResourceManager（RM）= 总包工头：手里有全工地的资源账（哪台机器空多少）
  NodeManager（NM）= 每个车间的工头：管本车间的机器，向总包工头汇报空位
  ApplicationMaster（AM）= 每个工程的包工头：你这个活来了，先在工地注册个办
    公点，然后找总包工头要资源、盯着自己活的进度、干完了注销走人
  Container = 工位：一份"内存+CPU"的资源包（比如 2GB+1 核），活是在工位上干的）

作业提交全流程（白板七步，面试必画）：
① 客户端把作业 jar 提交给 RM："我要跑个任务"
② RM 找一台有空位的机器，让 NM 起第一个 Container，在里面拉起 AM
③ AM 注册到 RM（"我上工了"），然后拿着自己活的需求（要几个 Map 几个 Reduce）
   向 RM 申请更多 Container
④ RM 按各机器空位情况分配 Container（只是"批条子"），AM 拿着批文找对应 NM
⑤ NM 在本车间起 Container，从 HDFS 拉 jar 和配置，跑 Map/Reduce 任务
⑥ 任务运行中，NM 持续向 RM 汇报本车间资源使用；AM 盯任务进度
⑦ 活干完：AM 向 RM 注销，所有 Container 释放，结果留在 HDFS

三个最容易混淆的点（面试官爱抠）：
- RM 只管资源分配，不管你活干得怎么样（进度由 AM 管）——职责分离
- AM 是"每个应用一个"：跑 WordCount 有一个 AM，跑 Hive 查询有另一个 AM
  （MR 的 AM 叫 MRAppMaster，Spark 的 AM 是 SparkSubmit 拉起的 driver 逻辑）
- Container 是死板的资源包：任务用不完也不会让给别人（不像线程池弹性）——
  所以 Container 配多大是门学问（配大了浪费，配小了 OOM 被 NM 杀）

资源模型与 AM 挂了的后果（联系 M8 知识）：
- 资源单位：内存（默认最小 1024MB，步长递增）+ vCPU
- AM 挂了：RM 会重新起一个 AM，重放任务进度（MR 有 job recovery；
  这就是为什么作业要定期把进度写到 HDFS——状态外置，和 M8 状态机思想同源）
- AM 重试次数默认 2（yarn.resourcemanager.am.max-attempts），超了整个作业失败
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| ResourceManager (RM) | 总包工头（全局资源账本+任务审批，整个集群一个） |
| NodeManager (NM) | 车间工头（每台机器一个，管本机资源+汇报+起杀进程） |
| ApplicationMaster (AM) | 工程包工头（每个应用一个，管自己这个活的资源申请与进度） |
| Container | 工位（一份内存+CPU 资源包，任务在工位里跑） |
| vCPU / Memory | 虚拟核与内存（YARN 资源的两种计量单位） |
| Job History Server | 作业历史馆（作业完成后日志归档到这查） |

## 3. 动手实操：YARN 上跑任务并读流程日志

```powershell
# ① 确认 YARN 活着（Web UI http://localhost:8088 应该能开）
docker exec hadoop-single yarn node -list

# ② 用 YARN 模式重跑 WordCount（day06 是本地模式，这次提交到 YARN）
docker exec hadoop-single bash -c "hadoop jar /opt/wc.jar WordCount /wc-in /wc-out-yarn"
# 注意观察控制台输出里的关键行（对号入座七步流程）：
#   "Submitting application to ResourceManager"        ← ①提交
#   "Application report for application_xxx (state: ACCEPTED→RUNNING)" ← ②AM 起来了
#   "Running job: job_xxx" + "map 100% reduce 50%"     ← ⑤~⑥任务在 Container 里跑

# ③ 从 YARN UI/命令行读这次作业的资源账单
docker exec hadoop-single yarn application -list -appStates FINISHED
docker exec hadoop-single yarn application -status application_xxx 2>$null
# 重点看：Aggregate Resource Allocation（总共用了多少 Container·秒）
# ——这就是"资源账单"，多任务之间比成本就看这个数

# ④ 亲手看 AM 挂掉的恢复（可选进阶：kill AM 进程观察重试）
docker exec hadoop-single bash -c "hadoop jar /opt/wc.jar WordCount /wc-in /wc-out-kill & sleep 20; ps aux | grep MRAppMaster | grep -v grep | awk '{print ```$2}' | xargs -r kill -9; sleep 60; yarn application -list"
# 日志会看到 AM attempt 2 重新拉起——max-attempts 机制实锤
```

## 4. 面试连接

**Q：讲一下 YARN 的架构和一个作业的提交流程？（必考白板题）**
> 三个角色类比着讲最清楚。ResourceManager 是集群唯一的总包工头，手里有全局资源账，只管批资源不管执行；NodeManager 每台机器一个，管本机的 Container 生命周期并向 RM 汇报空位；ApplicationMaster 每个应用一个，是"这个作业自己的管家"——负责向 RM 申请资源、跟 NM 协作起任务、盯进度。流程七步：客户端提交作业给 RM → RM 挑一台机器让 NM 起第一个 Container 跑 AM → AM 注册并按需求申请更多 Container → RM 批条子（只做调度决策）→ AM 拿批文让对应 NM 起任务 → 任务在 Container 里跑，NM 汇报资源、AM 盯进度 → 完成后 AM 注销，Container 释放。两个加分点：一是职责分离——RM 管资源调度、AM 管应用逻辑，所以 YARN 可以跑 MR 也能跑 Spark/Flink，这就是"资源调度和计算框架解耦"的设计价值；二是容灾——AM 挂了 RM 自动重拉并靠 HDFS 上的进度状态恢复，重试超限整个作业才失败，这套"状态外置+有限重试"和业务系统的幂等重试是同一套思想。

**Q：Container 配多大合适？配错了会怎样？**
> 两头都要吃亏才记得住。配太大：比如任务只需要 1GB 你批了 4GB，集群总容量被虚占，实际并行度上不去，资源账单（Aggregate Resource Allocation）虚高，浪费钱。配太小：任务真要 3GB 你只给 2GB，进程超限会被 NM 直接杀掉（物理内存超限，日志关键词 killed by yarn），作业反复失败——这是新人最常踩的坑，报错还特别不直观。配置实践三步：先看单任务的真实峰值内存（History Server 里看上次运行的内存曲线），再对照队列的最小分配单位和步长（比如最小 1GB 步长 512MB，2.3GB 会被向上取到 2.5GB），最后留 10~20% 余量。还有一个体系化视角：这个"资源包刚性分配"的模式和 Kubernetes 的 Request/Limit 一个思想，也和 M2 线程池的参数设计相通——线程池是进程内的资源包，YARN 是机房的资源包，配错的后果和调优的思路是同一门学问。

## 5. 今日验收清单

- [ ] 三角色类比（总包工头/工程包工头/车间工头）+ 七步流程白板可画
- [ ] WordCount 在 YARN 模式重跑成功，能对上日志七步
- [ ] "RM 管资源 AM 管逻辑"的职责分离价值能讲清
- [ ] Container 配大/配小的两种死法+配置三步法能说
- [ ] `git add . && git commit -m "day10-08: yarn"`

---
[← Day 07](day07-第一周复盘.md) | [本月目录](README.md) | [Day 09 · 调度器与队列设计 →](day09-调度器与队列设计.md)
