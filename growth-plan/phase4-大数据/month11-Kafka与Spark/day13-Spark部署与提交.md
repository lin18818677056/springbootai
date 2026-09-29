# Day 13 · Spark 部署与提交：Driver 是谁、活在哪里

> **今日目标**：搞清 Spark 作业的"人机分工"——Driver（画图纸+盯场的总指挥）、Executor（干活的工人）、Cluster Manager（发工位的物业）；重点吃透 client vs cluster 两种部署模式的区别（这决定你提交完作业能不能关电脑）；把 spark-submit 的资源参数配明白，并挑战一个跨月任务：让 Spark 跑在 M10 的 YARN 上。
> **时长**：架构与角色 1.5h / 提交参数与两种模式 1.5h / Spark on YARN 实战 2h
> **今日产出**：《spark-submit 参数卡》+ Spark on YARN 跑通记录

## 1. 知识地图

```
三个角色（工地类比，和 M10 YARN 的角色对照着记）：
  Driver 程序 = 总包工头：把你的代码翻译成 DAG 图、切 Stage、派 Task、收结果
    ——它是"你的 main 函数"本身！SparkSession 所在的 JVM 就是 Driver
  Executor = 车间工人：每个是集群里一台机器上的 JVM 进程，内部跑 Task 线程
    （day12 牌③：进程常驻，Task 是线程）
  Cluster Manager = 物业：分配资源（YARN/K8s/Standalone 都能当物业）
  对照记忆：Driver≈MR 的客户端+AM 合体（但 Driver 更强势：调度逻辑在它手里），
           Executor≈NM 上的 Container

提交流程七步（对照 M10 YARN 七步，形状几乎一样）：
  spark-submit → CM 申请启动 Executor → Executor 注册到 Driver →
  Driver 切 DAG 成 Stage/Task → 派 Task 给 Executor → 执行+汇报 → 完成注销

Client vs Cluster（高频题：提交完能不能关笔记本）：
  client 模式：Driver 跑在【提交机】（你的笔记本/跳板机）
    ——调试友好：日志直接打到本地控制台；
    致命伤：提交机一断，Driver 死，作业全死（笔记本合盖=事故）
  cluster 模式：Driver 跑在【集群内】（YARN 给它也派个 Container）
    ——生产姿势：提交机只负责"递个申请"就能走人；日志去 YARN 看
  一句话：调试用 client，生产用 cluster——合盖测试是每个新人的成人礼

资源参数三件套（spark-submit 核心参数，先给"经验起步值"）：
  --num-executors 4           开几个工人（YARN 模式）
  --executor-cores 2          每个工人几条胳膊（并行 Task 数；单工人 2~5 核）
  --executor-memory 4g        每个工人的内存（配合 day19 统一内存模型细分）
  总资源 = 4 工人 × (2 核 + 4g) —— 和 YARN 队列容量对齐（别超配）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Driver | 总指挥（main 函数所在 JVM：画 DAG/派 Task/收结果） |
| Executor | 工人进程（跑 Task 线程+缓存数据） |
| Cluster Manager | 物业（YARN/K8s/Standalone 三选一） |
| Deploy Mode: client | Driver 在提交机（调试用，断线即死） |
| Deploy Mode: cluster | Driver 在集群内（生产用，提交机可走） |
| spark-submit | 提交命令（作业的"入职申请表"） |

## 3. 动手实操：Spark on YARN（跨月融合）

```bash
# ===== 步骤 1：把 M10 Hadoop 的配置拷进 Spark 容器（让 Spark 认识 YARN）=====
docker cp hadoop-single:/opt/hadoop/etc/hadoop /tmp/hadoop-conf
docker cp /tmp/hadoop-conf spark:/opt/bitnami/spark/hadoop-conf
# ===== 步骤 2：容器内设环境变量（写入 /etc/profile.d 方便复用）=====
docker exec spark bash -c '
  echo "export HADOOP_CONF_DIR=/opt/bitnami/spark/hadoop-conf" >> /etc/profile.d/hadoop.sh
  echo "export YARN_CONF_DIR=/opt/bitnami/spark/hadoop-conf" >> /etc/profile.d/hadoop.sh'
# 注意：检查 hadoop-conf 里 core-site.xml 的 fs.defaultFS 是否写 hadoop:9000（主机名可解析）
# （M10 容器 -h hadoop 设置了主机名，bigdata 网络里 spark 容器能解析）
# ===== 步骤 3：Spark on YARN 跑 Pi（cluster 模式）=====
docker exec spark bash -c '
  source /etc/profile.d/hadoop.sh
  spark-submit --master yarn --deploy-mode cluster \
    --num-executors 2 --executor-cores 1 --executor-memory 1g \
    --class org.apache.spark.examples.SparkPi \
    /opt/bitnami/spark/examples/jars/spark-examples_2.12-3.5.0.jar 100'
# 预期：提交后没有 Pi 输出（cluster 模式 Driver 在 YARN 里跑！）
# 去 M10 的 YARN UI（http://localhost:8088）看到 Spark 应用：
#   点进去 → Logs → stdout → 找 "Pi is roughly 3.14..."
# —— M10 的 YARN + M11 的 Spark 首次握手成功（截图留痕）
# ===== 步骤 4：对照实验：故意把 executor-memory 配超过队列总量 =====
# --num-executors 10 --executor-memory 8g（超出 single 队列 8G）→ 观察应用卡在
# ACCEPTED 状态（资源不够排不上队）——这就是 M10 day09 "配大配小两种死法"的 Spark 版
```

## 4. 面试连接

**Q：client 和 cluster 模式有什么区别？生产用哪个？（必考送分题）**
> 区别就一个：Driver（你的 main 函数）跑在哪。client 模式 Driver 在提交机上——好处是日志、UI、调试都直接在本地，坏处是提交机成了单点：笔记本合盖、SSH 断线，Driver 一死整个作业全死。cluster 模式 Driver 也被 YARN 派进集群里的 Container——提交机只负责递申请，递完就能走，作业生死和提交机无关，日志去 YARN ResourceManager 界面看。生产必用 cluster。我给两个实操证据：第一，我们环境里 cluster 模式提交后本地终端看不到 Pi 输出，必须去 YARN UI 翻 stdout——很多新人第一次会以为作业挂了，其实是输出位置变了；第二，resource 参数超配时作业卡在 ACCEPTED 状态拿不到资源——这是排障的常识信号：RUNNING 才是在跑，ACCEPTED 是在排队。另外补一句选型外的工程细节：client 模式也不是不能用于生产——交互式探索（spark-shell/thrift server 常驻服务）天然就是 client 形态，因为"用户会话"本来就活在客户端连接里。分清"一次性批作业"和"常驻服务"两类工作负载，模式选择就不纠结了。

**Q：讲一下 Spark 作业的提交流程？资源参数怎么配？（流程+调参题）**
> 流程七步，和 M10 的 YARN 提交流程对照着记：spark-submit 提交 → 向 Cluster Manager（YARN）申请启动 Executor → Executor 反向注册到 Driver → Driver 把 DAG 按 Shuffle 边界切成 Stage、Stage 再拆成 Task → 按"数据本地性"把 Task 派给 Executor（优先派给数据所在节点，本地性决定了少搬数据）→ Executor 线程执行并汇报 → 完成后释放资源。资源参数起步公式：单 Executor 2~5 核 + 4~8g 内存，工人数量=总需求÷单工人配置，并且要和 YARN 队列容量对齐——我在单机队列上故意配了 10 个 8g 工人，总量超队列直接卡 ACCEPTED，这就是"配大死法"；反过来每工人 512m，几百个 Task 挤在小工人里反复 GC，是"配小死法"。生产上还要看三个信号再微调：Spark UI 里 Task 的 GC Time 占比高→加内存或减核；Task 大量 pending→加工人；每个 Task 处理数据太大（shuffle read 均值超过 1G）→加分区数。参数没有标准答案，起步值+看 UI 微调才是真实工作流。

## 5. 今日验收清单

- [ ] 三角色+七步流程能白板画（与 M10 YARN 流程对照）
- [ ] client vs cluster 区别+生产选型+ACCEPTED 排障信号能讲
- [ ] Spark on YARN 跑通（YARN UI 看到应用+stdout 找到 Pi）
- [ ] 资源超配实验完成（卡 ACCEPTED 现场留痕）
- [ ] 《spark-submit 参数卡》成文（起步值+三个微调信号）
- [ ] `git add . && git commit -m "day11-13: submit"`

---
[← Day 12](day12-Spark全景.md) | [本月目录](README.md) | [Day 14 · 第二周复盘 →](day14-第二周复盘.md)
