# Day 09 · 三种调度器与队列设计

> **今日目标**：搞懂 YARN 三种调度器的区别（FIFO/Capacity/Fair）——类比"工地派活的三种规矩"；学会为公司设计队列方案（生产队列和离线队列怎么隔离）；配置两级队列实操验证。
> **时长**：三调度器对比 1.5h / 队列设计 1.5h / 实操 1.5h
> **今日产出**：两级队列配置跑通 + 《公司队列设计方案》（面试可讲）

## 1. 知识地图

```
问题引入：day08 的 YARN 默认怎么派活？如果研发的临时查询把整晚的数仓
批量任务全堵死了怎么办？——需要"分排队规矩"，这就是调度器+队列。

三种调度器（三种派活规矩，用人话对比）：
① FIFO（先进先出）——"先来先干，一个队"
  最简单：排头先跑满集群，后面的等着。
  问题：一个十几小时的大任务堵门口，后面十万火急的小查询全饿死。
  适用：单人/单一用途集群（学习环境够用，生产基本不用）。

② Capacity（容量调度器）——"划摊位，各队有保底，闲时可以借"
  把集群资源划成队列：生产队列保底 70%、离线队列保底 30%。
  平时各守各的摊；生产队列闲着时，离线任务可以"借"生产摊位干活；
  但生产任务一来，借的摊位必须还（等正在跑的任务结束或被杀）。
  队内还是 FIFO。Apache Hadoop 默认，国内用得最多。
  核心参数：capacity（保底比例）、maximum-capacity（最多能借到多少）、
           user-limit（单个用户最多占队内多少）。

③ Fair（公平调度器）——"按缺的补，谁饿得慌谁先吃"
  目标是"公平"：空闲资源自动分给"缺额最大"的队列；
  抢占（preemption）可配置：饿太久的队列可以直接抢回来。
  CDH 系默认。适合多团队共享集群、任务大小混合（小查询多）的场景。
  核心概念：缺额（desiredShare - assignedShare）大的优先。

怎么选？一句话决策：国内生产主流是 Capacity——"保底+弹性借用"最符合
"在线业务优先、离线任务填谷"的诉求；多团队吵闹的共享集群用 Fair。

队列设计实战（公司只有一套集群时的标准设计，面试可讲）：
root
├── prod（生产/在线分析，保底 60%，max 90%）
│   ├── prod-realtime（实时风控查询，保底 30%）——绝不能被批任务挤死
│   └── prod-adhoc（运营临时查询，保底 30%）——限单用户占比防一人吃光
├── offline（离线数仓，保底 35%，max 100%——夜间可借 prod 的闲）
│   ├── offline-etl（数仓加工链）
│   └── offline-report（报表统计）
└── dev（研发试验，保底 5%，max 20%）——新人跑飞也伤不了大部队
三条设计原则：①在线与离线物理分开（M8 读写分离思想）②关键业务有硬保底
③试验队列有硬上限。再配 ACL：谁有权限往哪个队列提交（防止乱跑）。
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| FIFO Scheduler | 先来先干（一个队排到底） |
| Capacity Scheduler | 容量调度（队列保底+空闲可借，Apache 默认） |
| Fair Scheduler | 公平调度（缺额大的先得资源，可抢占） |
| Queue / 队列 | 资源分区（每个分区有保底比例） |
| Preemption | 抢占（把资源强行收回来给更需要的） |
| ACL | 提交权限控制（谁能往哪个队列交任务） |

## 3. 动手实操：配置两级队列并验证

```xml
<!-- 容器内 /opt/hadoop/etc/hadoop/capacity-scheduler.xml 核心段（简化版两级队列） -->
<configuration>
  <property><name>yarn.scheduler.capacity.root.queues</name><value>prod,offline</value></property>
  <property><name>yarn.scheduler.capacity.root.prod.capacity</name><value>70</value></property>
  <property><name>yarn.scheduler.capacity.root.prod.maximum-capacity</name><value>90</value></property>
  <property><name>yarn.scheduler.capacity.root.offline.capacity</name><value>30</value></property>
  <property><name>yarn.scheduler.capacity.root.offline.maximum-capacity</name><value>100</value></property>
  <!-- offline 可以借到 100%：夜间 prod 闲时离线任务吃满；白天 prod 忙则归还 -->
  <property><name>yarn.scheduler.capacity.root.prod.acl_submit_applications</name>
    <value>produser,datateam</value></property>
</configuration>
```

```powershell
# 应用配置并验证（容器内改配置后重启 RM 或热刷新）
docker exec hadoop-single bash -c "yarn rmadmin -refreshQueues"
docker exec hadoop-single yarn queue -status prod
docker exec hadoop-single yarn queue -status offline
# 提交 WordCount 指定队列（-Dmapreduce.job.queuename=offline）
docker exec hadoop-single bash -c "hadoop jar /opt/wc.jar WordCount -Dmapreduce.job.queuename=offline /wc-in /wc-out-q2"
docker exec hadoop-single yarn application -list | Select-String "offline"
# Web UI 8088 → Scheduler 页能看到两个队列及各自容量/已用
# 思考实验：把 offline.capacity 改成 5 再提交大任务——观察 ACCEPTED 卡住（保底生效）
```

## 4. 面试连接

**Q：YARN 三种调度器的区别？生产怎么选？（高频题）**
> 用派活规矩讲。FIFO 一个队排到底，简单但大任务堵门，只适合学习环境。Capacity 是"划摊位"：每个队列有保底容量，队列内 FIFO，空闲资源允许别的队列借用（maximum-capacity 控制借的上限）——核心价值是"在线业务优先、离线填谷"，Apache 社区默认，国内生产主流。Fair 是"按缺的补"：资源分给缺额最大的队列，支持抢占，CDH 系默认，适合多团队共享且小任务多的场景。选型一句话：要"在线优先离线填谷"选 Capacity，要"多团队绝对公平"选 Fair。追问我会补队列设计：我们按 prod/offline/dev 三级设计——在线和离线物理隔离（这是读写分离思想在资源层的映射）、关键实时队列硬保底、试验队列硬上限，再加 ACL 控制提交权限——调度器只是引擎，队列设计才是治理。

**Q：离线大任务把集群吃满了，线上实时查询卡住，怎么救急+怎么根治？（事故题）**
> 先救急后根治。救急三招按快慢：最快是杀——yarn application -kill 把离线任务杀掉释放资源（要评估重跑代价，数仓任务有重跑机制所以敢杀）；次快是调队列——如果配置了 maximum-capacity 收紧离线队列上限并 refreshQueues，正在跑的不受影响但新任务进不来；最慢是扩容——临时加节点。根治四件事：①队列重构：实时查询队列硬保底（比如 30%）且不允许借出；②任务分时：数仓批量任务调度到夜间谷段（day27 的调度基线），白天只跑增量；③资源画像：每个任务打标签记录资源账单，找出"吃资源大户"单独治理；④审批机制：离线大任务上线前要预估资源并报备。这套"救急三招+根治四事"和 M8 流量防护是同构的：限流对应队列保底、降级对应任务分时、容量台账对应资源画像——大数据的稳定性问题最后还是稳定性方法论的题。

## 5. 今日验收清单

- [ ] 三调度器人话对比能脱口而出（一个队/划摊位/按缺的补）
- [ ] 两级队列配置跑通（queue -status 输出贴进笔记）
- [ ] 三级队列设计方案图完成（保底/上限/ACL 三原则）
- [ ] "调度器是引擎，队列设计是治理"这句话能展开讲
- [ ] `git add . && git commit -m "day10-09: scheduler"`

---
[← Day 08](day08-YARN架构与提交流程.md) | [本月目录](README.md) | [Day 10 · Hive 入门 →](day10-Hive入门与架构.md)
