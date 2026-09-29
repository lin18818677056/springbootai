# Day 03 · 消息存储设计：CommitLog 一把梭的智慧

> **今日目标**：吃透 RocketMQ 存储三件套（CommitLog/ConsumeQueue/IndexFile）与 PageCache，能画出一条消息从 send 到落盘再到被消费的完整路径——这是双 MQ 对比博客（day14）的核心素材。
> **时长**：原理 2h / 源码主线 2h / 画图讲解 30min
> **今日产出**：消息落盘全路径图 + 存储目录实地观察笔记

## 1. 知识地图

```
RocketMQ 存储三件套（对比 Kafka：每个 Partition 一个独立日志文件）：

                    ┌─ CommitLog（所有 Topic 的消息混在一起顺序追加）
  Producer 写入 ────┤     只有一个写入口 = 磁盘顺序写，吞吐王者
                    │
                    ├─ ConsumeQueue（每 Queue 一个索引文件）
                    │     20 字节定长条目：offset(8) + size(4) + tagHash(8)
                    │     消费者按 Queue 读索引 → 再回 CommitLog 取消息体
                    │
                    └─ IndexFile（按 Key/时间查消息）
                          hash 槽 + 链表，用于运营排查（如查某个订单号的消息）

为什么所有 Topic 混写一个 CommitLog？
  磁盘最快的是"顺序写"。Kafka 多 Partition 多文件，写热点分散后可能变随机写；
  RocketMQ 把写收敛到一个文件（默认 1GB 一个 mappedFile 段），永远顺序追加。
  代价：读消息要两次 IO（先查 ConsumeQueue 索引，再回 CommitLog 读体）
  ——用"读多一次跳转"换"写永远顺序"，交易场景写多读少，划算。

一条消息的一生（落盘全路径，面试必画）：
  send(msg) → 选 Queue → 构造消息(含 sysFlag/ bornHost…) 
  → putMessage 写入 CommitLog（写入 PageCache，按刷盘策略决定何时 fsync）
  → 同步/异步线程构建 ConsumeQueue 索引与 IndexFile
  → Consumer 拉取：查 ConsumeQueue → 按索引回 CommitLog 读消息体
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| CommitLog | 提交日志，所有消息物理混存，顺序追加，1GB/段（mappedFile） |
| ConsumeQueue | 消费队列，CommitLog 的逻辑索引，定长 20 字节条目 |
| IndexFile | 索引文件，按消息 Key 哈希查询（500 万条目/文件） |
| PageCache | 操作系统页缓存，写 PageCache 即返回，fsync 才真正落盘 |
| MappedFile | 通过 mmap 映射的文件段，RocketMQ 文件抽象（MappedFileQueue 管理多个） |
| FlushDiskType | 刷盘策略：SYNC_FLUSH（同步）/ ASYNC_FLUSH（异步） |
| TransientStorePool | 堆外内存池，读写分离优化（开 transientStorePoolEnable 后先写堆外） |
| MaxOffset / MinOffset | 最大逻辑偏移 / 最小（未被删除的最旧）偏移 |

## 3. 动手实操：进容器看存储目录

```powershell
docker exec -it learning-broker-1 bash
ls /home/rocketmq/store
# commitlog/     ← 里面是 00000000000000000000 这样的 1GB 段文件
# consumequeue/  ← TEST_TOPIC/0/1/2/3... 每个队列一个目录
# index/         ← 索引文件
# config/ checkpoint/ abort/ ...

ls -l /home/rocketmq/store/commitlog        # 看 1GB 预分配的段文件
ls /home/rocketmq/store/consumequeue/TEST_TOPIC
# 8 个队列目录（默认 8 读 8 写），每个目录里是 300000 条目一个的索引文件

# Dashboard 发两条消息（Topic=TEST_TOPIC），再回来看文件变化：
ls -l /home/rocketmq/store/commitlog        # mtime 变了（CommitLog 追加了）
# 用 mqadmin 查询验证 IndexFile 生效（发送时设置 Keys=order-1001）：
sh mqadmin queryMsgByKey -n namesrv:9876 -k order-1001
sh mqadmin queryMsgById  -n namesrv:9876 -i <上面查到的offsetMsgId>
# 注意 offsetMsgId（Broker 生成，含物理地址）与 uniqKey（客户端 UNIQ_KEY）的区别

# 源码主线（github rocketmq 5.3.x，只看两处别贪多）：
# org.apache.rocketmq.store.DefaultMessageStore#putMessage
#   → commitLog.putMessage → mappedFile.appendMessage（写 PageCache）
#   → flush/commit 线程按策略刷盘；reputMessageService 异步构建 ConsumeQueue
```

## 4. 面试连接

**Q：为什么 RocketMQ 要把所有消息混写一个 CommitLog，而不是像 Kafka 每个分区一个文件？**
> 核心是"顺序写"的极致追求。Topic 多、队列多的场景下，Kafka 的多文件写会分散到多个文件形成近似随机写；RocketMQ 收敛到单文件单写入口，永远顺序追加，还天然支持按段（1GB）滚动删除。代价是读要两跳（ConsumeQueue 索引 → CommitLog 体），但消费读通常命中 PageCache，代价可控。Kafka 则是在"每个分区文件顺序写 + 依赖页缓存"上做了存读一体（日志直接就是消费单元），零拷贝 sendfile 更顺手。两种取舍在 day14 博客里正式对比。

**Q：ConsumeQueue 为什么要做成 20 字节定长？**
> 定长 = 可精确寻址。第 i 条消息的索引位置 = 文件头 + i × 20，免解析直接随机读；300000 条一个文件方便按量切换与重建。tagHash 用于消费端 Tag 过滤预判（避免把消息体拉回来才发现不匹配，但哈希可能误判，所以消费端还要精确比对——一个"两层过滤"的细节，面试加分点）。

## 5. 今日验收清单

- [ ] 画出手绘版"一条消息的一生"全路径图
- [ ] 存储目录三件套实地观察过（commitlog/consumequeue/index）
- [ ] CommitLog vs Kafka 多分区文件的取舍能讲 2 分钟
- [ ] DefaultMessageStore 主线源码走读过（截图或笔记）
- [ ] offsetMsgId 与 uniqKey 区别能说清
- [ ] `git add . && git commit -m "day03: commitlog storage"`

---
[← Day 02](day02-RocketMQ架构与部署.md) | [本月目录](README.md) | [Day 04 · 刷盘复制与零拷贝 →](day04-刷盘复制与零拷贝.md)
