# Day 30 · 月度复盘与交接：博客⑦ + M12 交接卡

> **今日目标**：对账本月六条验收标准（诚实记录未达项）；写博客⑦大纲；交接 M12 交接卡（Flink 与实时数仓的思考题钩子）；打 tag 收官。
> **时长**：复盘 1.5h / 博客大纲 1h / 交接 0.5h
> **今日产出**：月度对账表 + 博客⑦大纲 + M12 交接卡 + `git tag month11-done`

## 1. 月度验收对账（README 八条逐条兑现）

```
① Kafka 四词模型+保序+吞吐倒推 → day02/04 ✔（key 路由实验+三步三闸）
② Kafka 快的四个原因各带证据 → day03 ✔（顺序写 iostat+sendfile 图+UI 批量）
③ Rebalance 复现+三板斧 → day05 ✔（重启消费者复现+静态成员）
④ 消费语义实验+幂等三件套 → day08 ✔（重复消费复现+模板成文）
⑤ 端到端可靠性三段拼图 → day09 ✔（死信演练跑通）
⑥ Spark 三宗罪对三张牌+UI 验证 → day12/13 ✔（YARN 跑通+Stage 对照）
⑦ cache/checkpoint/广播实验 → day17 ✔（Job 数对比+血缘截断+翻倍陷阱）
⑧ Catalyst 三优化 EXPLAIN+AQE 三能力 → day20/22 ✔（六张证据图）
⑨ 项目链路+双引擎对拍+1 亿压测 → day25/26/27 ✔（三本账一致+优化量化）

诚实记录的三个"未达全真"（延续 M10 的诚实传统）：
· 未达① 单机 Kafka（replication=1）——ISR/unclean 选举只有理论推演，
  没有多 broker 实测（生产三节点集群才能真验）
· 未达② Structured Streaming 只做预告——真正的流处理在 M12 Flink 系统学
· 未达③ Spark on YARN 用的是 M10 的单机伪分布式——多节点资源调度未验
处置：三项均列入 M12+ 的"环境升级清单"（攒一台二手主机组真集群）
```

## 2. 博客⑦大纲：《Kafka 为什么快：一个 Java 后端的消息中间件深度笔记》

```
一、开场（痛点共鸣）：加了 Kafka 压测百万 TPS 就稳了？先搞懂它凭什么快
二、快的四板斧（每节配实验）：
   ① 顺序写：随机写 vs 顺序写的磁盘差距（附 day03 实测）
   ② 零拷贝：sendfile 把 4 次拷贝减成 2 次（附 M3 铺垫呼应）
   ③ 批量+压缩：攒批发货的经济学（附 UI 批量前后指标）
   ④ 分区并行：一个货架变十个（附吞吐倒推法）
三、快是有代价的：页缓存依赖/可靠性参数收紧后的性能衰减（acks=all 代价实测）
四、可靠性三板斧：ISR+acks+幂等（day06/09 精华节选）
五、给 Java 后端的三条迁移建议：顺序 IO/批量/异步——Kafka 思想反哺业务代码
六、结尾：下一个问题——Spark 为什么比 MR 快？博客⑧见
（素材：month11 day03/06/09 + M3 day 交叉引用，两周内成文 3000 字）
```

## 3. M12 交接卡（给下月的自己）

```
你将进入：month12 · Flink 与实时数仓（phase4-大数据）
上月的遗产（直接继承的资产）：
  · 容器群：kafka/spark/hadoop-single/hive-server/hive-mysql 全在 bigdata 网络
  · Kafka 主题 app_event（3 分区）+ 数仓 shop 库（ODS/DWD/DWS/ADS 齐全）
  · 1 亿行数据集 /data/big/order_1e（流批对照实验直接用）
  · 对账方法论：三本账（M8→M10→M11 已验三遍）
  · 倾斜武器库/内存布局/OOM 排查（Spark 侧已通，Flink 对照学）

M11 留给你的两道思考题（M12 day01~03 要正式作答）：
  思考题①：Flink 凭什么做"真"流处理？——事件时间和处理时间差在哪？
    （提示：M11 落地作业按天落 HDFS，T+1 才有数——实时数仓要的是"秒级"）
  思考题②：乱序数据怎么办？——埋点消息因为网络抖动乱序到达，
    "10:59 的订单 11:01 才到"算哪一天的数？（水位线 watermark 伏笔）

预告 M12 骨架：day01 Flink 定位 → 架构与部署 → 时间语义/水印 → 窗口
  → 状态与检查点（对接 M11 checkpoint 思想）→ Exactly-Once（对接 day08/09
  语义拼图）→ Flink SQL → 与 Kafka/数仓实战（实时 GMV 大屏 vs M11 离线 GMV）

环境预告：flink 容器（flink:1.18）接入 bigdata 网络；Kafka 复用本月主题。
```

## 4. 收官动作清单

```
□ git add . && git commit -m "month11: kafka & spark complete(31 files)"
□ git tag month11-done && git push origin master --tags
□ growth-plan/README.md 总导航：month11 行点亮（含文件数 31）
□ 本月资产盘点：三本账对拍记录/1 亿行数据集/治理清单 v2/六张证据图
□ 预习 30 分钟：Flink 是什么（只看官网首页，别深钻——把悬念留给 day01）
```

## 5. 今日验收清单

- [ ] 六条+未达三项对账表成文（诚实传统保持）
- [ ] 博客⑦大纲定稿（两周内成文，素材已链接）
- [ ] M12 交接卡抄录到笔记本第一页
- [ ] tag month11-done 打上并推送
- [ ] 明早开工 M12 day01（Flink 与实时数仓）
- [ ] `git add . && git commit -m "day11-30: month-close"`

---
[← Day 29](day29-M11模拟验收.md) | [本月目录](README.md) | [→ 下月：month12 · Flink 与实时数仓](../month12-Flink与实时数仓/README.md)
