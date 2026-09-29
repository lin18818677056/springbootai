# Day 10 · Kafka 运维实战：保留、压缩与扩容

> **今日目标**：学 Kafka 运维三板斧——消息保留策略（Retention：时间/大小/Compact 压实）、压缩算法选型（LZ4/ZSTD）、分区重分配（Reassignment 扩容搬格子）。三个都是"磁盘/容量"这条线的日常操作，做完今天你能回答"Kafka 磁盘要满了怎么办"。
> **时长**：保留策略 1.5h / 压缩选型 1h / 扩容演练 2.5h
> **今日产出**：Compact 实验记录 + 《压缩算法选型卡》+ 分区重分配操作单

## 1. 知识地图

```
① 消息保留（Retention）——"仓库货位是租的，到期就清"
  按时间：log.retention.hours=168（默认 7 天）——过了就删
  按大小：log.retention.bytes（分区上限）——超了删最老的段
  删除的最小单位是【段】不是条！：分区由多个段文件组成（log.segment.bytes
    默认 1GB 一段）——保留时间到了，等整个段过期才删
    （推论：设"保留 1 小时"但段 1GB 写满要 5 小时 → 实际保留约 5 小时；
      要细粒度保留就把段调小 log.segment.bytes）
  Compact 压实（另一种清理姿势）：不按时间删，按 key 保每个 key 的最新一条
    ——像通讯录：每人只留最新地址，老地址划掉
    适用：状态类数据（用户最新配置/最新库存/Kafka 自己的 __consumer_offsets）
    不适用：事件流水（订单事件不能只留最新！每笔都要在）

② 压缩算法选型（端到端：producer 压 → broker 存 → consumer 解）
  gzip：压得狠（4x）但 CPU 贵——低流量归档
  snappy：中庸——老牌默认
  lz4：快+压缩率不错——当前性能首选之一
  zstd：压缩率超 gzip 且速度快——新默认候选（3.5+ 客户端成熟）
  选型一句话：CPU 富余看 zstd，极致低延迟看 lz4，别用 gzip 扛在线流量

③ 分区重分配（Reassignment）——"仓库扩容，把格子搬去新楼"
  场景：加新 broker 后老 Topic 还挤在老机器上 → 搬副本平衡负载
  工具：kafka-reassign-partitions.sh 生成计划→执行→验证
  两个认知（面试易错）：
    ① 扩分区 ≠ 搬老数据：新分区是新的空格子，老分区老 key 还在老地方
       （所以"加了分区老分区还是堵"是正常现象，写新 key 或等待自然稀释）
    ② 重分配是真复制：新副本从 Leader 全量拉数据再切换——大 Topic 迁移
       要限流（throttle 参数），不然打满网卡影响线上
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Retention | 保留策略（时间/大小到线就删） |
| Log Segment | 段（分区的物理文件单元，1GB 一个，删除按段删） |
| Log Compaction | 压实（按 key 保最新一条，通讯录模式） |
| Reassignment | 分区重分配（副本搬家，真实复制+限流） |
| Throttle | 迁移限流（搬家限速，别打满网卡） |

## 3. 动手实操：Compact 与扩容演练

```powershell
# ===== 实验①：Compact 压实实况（通讯录效果亲眼看）=====
docker exec kafka kafka-topics.sh --create --topic user-profile `
  --partitions 1 --replication-factor 1 `
  --config cleanup.policy=compact `
  --config min.cleanable.dirty.ratio=0.01 `
  --config segment.ms=60000 `
  --bootstrap-server localhost:9092
# 同 key 发多条（u1 的地址变更 3 次）：
docker exec kafka bash -c 'echo "u1:addr-A"; echo "u2:addr-X"; echo "u1:addr-B"; echo "u1:addr-C"' | `
docker exec -i kafka kafka-console-producer.sh `
  --topic user-profile --property parse.key=true --property key.separator=: `
  --bootstrap-server localhost:9092
# 先全量看（压实前：4 条都在）：
docker exec kafka kafka-console-consumer.sh --topic user-profile `
  --from-beginning --property print.key=true --bootstrap-server localhost:9092 --max-messages 4
# 等段滚动+压实触发（segment.ms=60s + dirty.ratio 极低）后从头消费：
docker exec kafka kafka-console-consumer.sh --topic user-profile `
  --from-beginning --property print.key=true --bootstrap-server localhost:9092 --max-messages 2
# 预期：u1 只剩 addr-C（最新），u2 保留——通讯录效果实锤（offset 会重排，正常）
# ===== 实验②：保留时间调小观察段删除（记录操作要点）=====
# docker exec kafka kafka-configs.sh --alter --entity-type topics --entity-name perf-p3 `
#   --add-config retention.ms=60000,segment.ms=60000 --bootstrap-server localhost:9092
# 等待后 hdfs/ls 段目录（容器内 /bitnami/kafka/data）看旧段消失
# ===== 实验③：分区重分配操作单（单机演练：把 perf-p3 的副本"搬"到同机不同目录是
# 单机极限；记录完整三步命令序列作为生产操作单模板）=====
# 步骤1 生成计划：kafka-reassign-partitions.sh --generate（--topics-to-move + --broker-list）
# 步骤2 执行计划：--execute --reassignment-json-file plan.json --throttle=50000000
# 步骤3 验证完成：--verify（看到 "completed successfully"）
# 生产要点：先在低峰执行/带 throttle 限流/盯 ISR 不要在迁移中缩水
```

## 4. 面试连接

**Q：Compact 和普通保留策略有什么区别？分别什么场景用？（高频对比题）**
> 普通保留（delete）是"仓库租约"：按时间或大小删最老的段，数据的"历史价值"随时间归零——事件流水类（订单事件、埋点日志）用这个，因为每一笔都要留档分析。Compact 是"通讯录"：不删历史进程，只保证每个 key 留最新一条——状态类数据（用户最新配置、最新库存快照、服务注册表）用这个，下游重启后从头消费能重建全量最新状态，这正是 Compact 的核心价值：**把"回放全部历史"变成"回放出最新快照"**。三个工程细节加分：①Compact 的清理发生在"段"级别——活跃段（正在写的）不会被压实，所以参数上要配 segment.ms 让段快点滚动，否则压实迟迟不触发（我实验里把 segment.ms 调到 60 秒+dirty.ratio 0.01 才看到效果）；②Compact 后 offset 不连续（被划掉的记录消失），消费代码不能假设 offset 连续；③Compact 和 delete 可以共存（cleanup.policy=compact,delete）——Kafka 自己的 __consumer_offsets 就是这么配的：组没了按时间清，组还在的按 key 留最新进度。一句话总结：看"数据的最新值有没有意义"——有就 Compact，只有历史过程有意义就 delete。

**Q：Kafka 磁盘快满了，你怎么处理？（应急题）**
> 三步走，从止血到根治。第一步止血：找最大占用 Topic 收紧保留——kafka-configs 动态改 retention.ms/retention.bytes（不用重启），让过期段尽快清；注意删除按段进行，若段太大（比如 1GB 一段写了很多天），把 segment.bytes 也临时调小加速过期。第二步判因：磁盘满是"正常增长"还是"异常堆积"。正常增长=流量涨了，该扩容（注意 Kafka 按时间删数据不看消费进度，单纯消费积压不会撑爆磁盘，但会吃掉"可回放窗口"）。异常堆积常见两类——死信/重试 Topic 没人管（day09 的 SLA 失效，这是治理问题）；Compact Topic 的压实不触发（活跃段一直不滚动，老版本记录越积越多，查 segment.ms 配置）。还有一个隐蔽项：历史 Topic 当初拍脑袋设的超长保留（如 90 天），流量涨十倍后容量必然爆——所以存量 Topic 的保留配置要定期审计。第三步根治：加 broker + 分区重分配搬家——生成计划、带 throttle 限流执行、verify 验证，全程盯 ISR（迁移中副本追赶慢，ISR 可能缩水，min.insync.replicas 会拒写，所以大 Topic 迁移要错峰+限流双保险）。预防机制上：磁盘水位 80% 告警、每个 Topic 建档时写明预期速率和保留时长、每月容量复盘——这些都在我的治理清单 D 组里。

## 5. 今日验收清单

- [ ] Compact vs delete 的"通讯录 vs 租约"对比能脱稿讲（含三个工程细节）
- [ ] Compact 实验完成（u1 只剩 addr-C 现场留痕）
- [ ] 《压缩算法选型卡》成文（四种算法两维度：CPU vs 压缩率）
- [ ] 分区重分配三步操作单成文（generate/execute+throttle/verify）
- [ ] "磁盘满了"三步应急（止血/判因/根治）能完整展开
- [ ] `git add . && git commit -m "day11-10: ops retention"`

---
[← Day 09](day09-端到端可靠性.md) | [本月目录](README.md) | [Day 11 · Kafka Streams 初识 →](day11-KafkaStreams初识.md)
