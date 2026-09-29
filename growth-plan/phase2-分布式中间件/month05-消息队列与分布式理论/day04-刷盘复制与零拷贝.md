# Day 04 · 刷盘、复制与零拷贝：可靠性与性能的三道闸门

> **今日目标**：搞清消息不丢的三道闸门（发送 ACK → 刷盘 → 复制），用配置切换实测同步/异步刷盘的性能差异，讲清 mmap 与 sendfile 两种零拷贝在 RocketMQ 里的分工。
> **时长**：原理 2h / 实验 2h / 总结 30min
> **今日产出**：刷盘性能对比数据 + "各级别丢失风险点"图

## 1. 知识地图

```
一条消息从"不丢"的三道闸门（生产端→存储端→副本端）：

  ① 发送闸门：同步发送等 ACK / 异步回调确认 / OneWay 发了就忘
  ② 刷盘闸门：消息进 PageCache ≠ 落盘，断电 PageCache 会丢
       SYNC_FLUSH：写线程阻塞等 fsync 完成才返回（慢但稳）
       ASYNC_FLUSH：立即返回，后台线程定期刷（默认，快）
       ⚠ 断电时 ASYNC_FLUSH 可能丢 1 秒内消息；机器不死只是进程崩，PageCache 还在（OS 兜底）
  ③ 复制闸门：主从之间的同步策略
       SYNC_MASTER：Slave 写完才向 Producer 返回成功（强，慢）
       ASYNC_MASTER：Master 落盘即返回，异步复制（默认，快）
       DLedger（Raft 多副本）：多数派确认，自动切换（day18 的实践版）

风险矩阵（面试必画）：
  配置组合                    | Broker 单机断电 | 磁盘损坏
  ASYNC_FLUSH + ASYNC_MASTER  | 可能丢 <1s      | 可能丢
  SYNC_FLUSH  + ASYNC_MASTER  | 不丢            | 可能丢（Slave 数据略滞后）
  SYNC_FLUSH  + SYNC_MASTER   | 不丢            | 不丢（性能掉到几百 TPS）
  金融交易：SYNC_FLUSH+SYNC_MASTER；一般交易：ASYNC+SYNC_MASTER；日志：全异步

零拷贝 Zero-Copy 两兄弟：
  mmap（内存映射）：把文件映射到用户态地址空间，省"内核态→用户态"拷贝
      RocketMQ 用它读写 CommitLog/ConsumeQueue（MappedFile）
  sendfile：数据直接从页缓存到网卡，全程无用户态拷贝
      Kafka 用它做消费传输；RocketMQ 4.x 消费仍走 mmap+socket（5.x 有 TransientStore 优化）
      ——这也是"Kafka 消费吞吐更高"的底层原因之一
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Flush（刷盘） | PageCache → 磁盘的 fsync 过程 |
| SYNC_FLUSH / ASYNC_FLUSH | 同步刷盘 / 异步刷盘（broker.conf flushDiskType） |
| SYNC_MASTER / ASYNC_MASTER | 同步复制 / 异步复制（brokerRole） |
| mmap | 内存映射文件，用户态直接读写页缓存 |
| sendfile | 系统调用，内核态直接把文件送网卡，零用户态拷贝 |
| Group Commit | 同步刷盘的优化：合并同一时刻多个请求共同等一次 fsync |
| Wakeup | 主从复制中 Slave 等待唤醒通知（HALF 半消息也复用此机制，day10 见） |

## 3. 动手实操：刷盘策略对比实验

```powershell
# ① 当前配置确认（day02 起的 broker 是 ASYNC_FLUSH）
docker exec -it learning-broker-1 sh -c "grep -E 'flushDiskType|brokerRole' /home/rocketmq/conf/broker.conf"

# ② 写一个循环发送的 Java 小程序（learning/month05-mq-theory/src/FlushBench.java）
#    同步发送 1000 条（1KB），统计平均 RT；消息带 Keys 便于查
#    运行：javac FlushBench.java && java FlushBench   （需要 rocketmq-client jar，
#    从秒杀工程 Gradle 缓存找 rocketmq-client-5.x.jar 复制进 lib 或直接在秒杀工程里跑）

# ③ 切同步刷盘：改 broker.conf flushDiskType=SYNC_FLUSH → docker compose restart broker
#    重跑同样的 1000 条，对比平均 RT
# 预期（参考量级，以实测为准）：
#   ASYNC_FLUSH：平均 2~5ms/条
#   SYNC_FLUSH ：平均 10~50ms/条（单线程 fsync 慢，并发下 Group Commit 摊薄）

# ④ 验证"ASYNC 可能丢 <1s"：发送后立刻 docker compose stop broker（模拟断电）
#    再 up 起来查消息（Dashboard 按 Key 查）——最后几百毫秒发的消息可能查不到
#    换 SYNC_FLUSH 重复实验 → 消息都在。把两种结果记录成实验报告

# ⑤ 主从复制（可选加餐）：再起一个 brokerId=1 的 slave 容器（同 brokerName）
#    brokerRole=SYNC_MASTER 重跑对比 → 直观感受"可靠性三档位"的延迟代价
```

## 4. 面试连接

**Q：怎么保证消息不丢？分几段答？**
> 三段式：发送端——同步发送/事务消息，失败重试，本地记录发送状态；存储端——SYNC_FLUSH 或至少异步刷盘+主从复制，金融场景 SYNC_FLUSH+SYNC_MASTER+多副本；消费端——先处理后提交位移（手动 ACK），失败重试+死信人工兜底。再加一句兜底哲学："任何单点手段都可能失效，可靠性=层层设防+对账补偿，没有 100%，只有概率工程化。"（接 M4 秒杀三层幂等的叙事）

**Q：mmap 和 sendfile 有什么区别？各用在哪？**
> mmap：文件映射进用户空间，省"内核缓冲→用户缓冲"一次拷贝，适合读写都频繁的场景，RocketMQ 的存储文件读写都靠它；sendfile：数据全程不进用户态，页缓存直达网卡，适合"读文件发网络"的纯传输场景，Kafka 消费链路用它。所以 Kafka 单分区消费吞吐极高；而 RocketMQ 因为混存设计（读体要回 CommitLog），用 mmap 灵活寻址。答到"两种零拷贝服务于两种存储设计"就是 P7 水平。

## 5. 今日验收清单

- [ ] 三道闸门+风险矩阵手绘完成
- [ ] SYNC/ASYNC 刷盘对比实验有实测数据（记录平均 RT）
- [ ] "断电模拟"实验两种策略结果对比记录
- [ ] mmap/sendfile 区别与各自归属能脱稿讲
- [ ] （选做）搭出主从并测 SYNC_MASTER 延迟
- [ ] `git add . && git commit -m "day04: flush and replication"`

---
[← Day 03](day03-消息存储设计.md) | [本月目录](README.md) | [Day 05 · 消费机制与Rebalance →](day05-消费机制与Rebalance.md)
