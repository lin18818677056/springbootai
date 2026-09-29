# Day 08 · 位移提交与消费语义：进度条什么时候拨

> **今日目标**：搞懂 Kafka 可靠性最容易错的一环——**offset 提交时机**。同一个消费程序，"先拨进度条再干活"和"先干活再拨进度条"是两种完全不同的安全性：一个丢消息、一个重消息。三种消费语义（至多/至少/精确一次）今天全部落地，并亲手复现一次重复消费。
> **时长**：提交时机与语义 1.5h / 重复消费复现 1.5h / 幂等与事务 2h
> **今日产出**：重复消费复现记录 + 《消费语义决策卡》+ 消费端幂等代码模板

## 1. 知识地图

```
一句话抓住本质：offset 提交（拨进度条）和处理消息（干活）是两个动作——
  谁先谁后，决定了你是丢消息还是重消息：

  先提交后处理（At-Most-Once 至多一次）：
    拉到消息 → 立刻提交 offset → 再慢慢处理
    处理中途崩溃 → 重启从新进度条开始 → 崩溃时没处理完的那批【丢了】
    —— 不重复，但可能丢：适合"丢一条无所谓"的场景（极少）

  先处理后提交（At-Least-Once 至少一次）：
    拉到消息 → 处理完 → 再提交 offset
    处理完但提交前崩溃 → 重启从旧进度条开始 → 已处理过的那批【重做】
    —— 不丢，但可能重：绝大多数业务的选择（重复靠下游幂等兜住）

  精确一次（Exactly-Once）两条路：
    路A 消费端幂等：照常 at-least-once，但业务处理可重入——
       唯一业务键去重（DB 唯一索引/Redis SETNX/状态机检查"已支付就不重复扣"）
       —— M8 的幂等三件套原样平移！实战中最常用（Kafka 事务只管它自己域内）
    路B Kafka 事务（0.11+）：消费-处理-生产三步包进一个事务
       （典型场景：从 Topic A 读 → 加工 → 写 Topic B，要么全成功要么全回滚，
         下游用 isolation.level=read_committed 只读已提交的）
       —— 只覆盖"Kafka 到 Kafka"，写到 DB 的部分还得靠路 A

自动提交的坑（enable.auto.commit=true 默认开）：
  后台线程每 5 秒自动拨一次进度条——拨的是"当前已拉取"的位置，不管处理完没完
  场景：poll 拉 500 条开始处理，处理到第 300 条时进程崩了：
    → 若提交线程恰好已把位置拨到 500：重启从 500 开始 → 剩余【丢了】
    → 若还没来得及拨：重启从上一轮提交位置开始 → 全部【重做】（重复）
    —— 自动提交的语义"看运气"：丢和重都可能，窗口=提交间隔
  结论：严肃业务一律手动提交，时机自己掌控（先干后拨=至少一次）
  真正的丢消息姿势：手动控制时"先 commitSync 再 process"（把语义改成 at-most-once）
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Offset Commit | 拨进度条（把消费位置汇报给 broker） |
| At-Most-Once | 至多一次（先拨后干：不重但可能丢） |
| At-Least-Once | 至少一次（先干后拨：不丢但可能重） |
| Exactly-Once | 精确一次（幂等兜底或事务包裹） |
| Idempotent Consumer | 幂等消费（重复来了也不产生副作用） |
| Transactional Producer | 事务生产者（多分区写入原子提交） |
| read_committed | 只读已提交（消费者不看事务未提交的脏消息） |

## 3. 动手实操：复现重复消费+幂等模板

```java
// ===== 实验①：复现重复消费（手动提交+处理中崩溃的时序）=====
// 核心演示：poll → process(慢) → [此时 kill 进程] → commit 没执行 → 重启重做
Properties props = new Properties();
props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
props.put(ConsumerConfig.GROUP_ID_CONFIG, "semantics-demo");
props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);   // 关自动提交（教学姿势）
KafkaConsumer<String, String> c = new KafkaConsumer<>(props);
c.subscribe(List.of("order-log"));
while (true) {
    ConsumerRecords<String, String> rs = c.poll(Duration.ofSeconds(1));
    for (ConsumerRecord<String, String> r : rs) {
        System.out.println("process " + r.offset() + " -> " + r.value());
        Thread.sleep(2000);                     // 模拟慢处理：此时 kill 进程
    }
    c.commitSync();                             // 处理完才提交（at-least-once 姿势）
}
// 验证流程：发 5 条 → 启动消费者 → 杀在第 3 条处理中 → 重启
// 观察输出：第 3、4、5 条被【重新处理】——重复消费现场实锤（记录 offset 序列）
```

```java
// ===== 实验②：消费端幂等模板（路 A，业务可重入）=====
// 思想：重复不可怕，重复产生副作用才可怕——让处理变成"再跑一次也没事"
// 三件套（M8 平移）：唯一键约束 / 条件更新 / 状态机检查
// DB 唯一键版（最简最稳）：
//   INSERT INTO consume_record(msg_key, status) VALUES(?, 'done')  -- 唯一索引挡重
//   冲突 → 说明处理过 → 直接 ack 跳过
// 状态机版（订单域）：
//   UPDATE orders SET status='PAID' WHERE order_id=? AND status='CREATED'
//   影响行数=0 → 状态不是 CREATED → 已处理过 → 跳过（天然防重复扣款）
// Redis SETNX 版（高频轻量去重，注意 TTL）：
//   SET dedup:{msg_key} 1 NX EX 86400   -- 成功才处理，失败说明 24h 内处理过
// ===== 实验③：事务生产者配置要点（记录配置清单即可）=====
// props.put("transactional.id", "order-processor-1");  // 事务 ID（跨重启可识别）
// producer.initTransactions(); producer.beginTransaction();
// ... send ...  producer.commitTransaction();          // 或 abortTransaction()
// 消费端：props.put("isolation.level", "read_committed");  // 只看已提交
```

## 4. 面试连接

**Q：Kafka 怎么做到"消息不丢不重"？三种语义分别怎么实现？（必考题）**
> 先拆"不丢"：三段各自负责。生产段 acks=all+重试+幂等生产者；broker 段副本+min.insync.replicas；消费段先处理后提交。再拆"不重"：重复恰恰是"不丢"的代价——at-least-once 的先干后拨进度条，崩溃窗口内必然重。所以精确一次没有银弹，两条路：一是消费端幂等，业务可重入（唯一键/状态机/条件更新），重复来了也不产生副作用——这是我实战的默认选择，因为它对 Kafka 没有依赖、跨任何 MQ 都成立，本质是 M8 接口幂等三件套的平移；二是 Kafka 事务（0.11+），把"读 A-加工-写 B"包进一个事务，配 isolation.level=read_committed 保证下游只看见完整事务——但它只覆盖 Kafka 域内，写到数据库的那条腿还是得靠幂等。最后给一个工程结论：不要追"全链路 exactly-once"的执念，架构上接受"至少一次+幂等消费"——这在分布式系统里是成本最低的组合，我只在纯 Kafka 流水线（Kafka Streams 端到端）里才用事务语义。

**Q：线上发现消息重复消费了，怎么应急和根治？（事故处理题）**
> 先分清重复的原因再动手，三种常见姿势对应三种修法。原因一：自动提交开着且处理慢——poll 回一批还在处理，后台线程按 5 秒间隔把位置提交了，之后消费者崩溃或 rebalance，重启从新位置开始，看似"跳过"，但另一面 rebalance 前 offset 已提交而处理未完的消息会被新 owner 重新拉取——重复窗口=提交间隔。应急：关自动提交，改手动 commitSync（处理完再交）。原因二：rebalance 时没做"提交前收尾"——正确姿势是订阅 rebalance 监听器，在 partitionsRevoked 回调里同步提交一次已处理完的 offset，把窗口压到最小。原因三：处理逻辑本身不可重入，一条消息重放就产生两条业务副作用（重复扣款/重复发货）——这是最危险的，根治靠幂等三件套：唯一索引挡底、状态机条件更新、Redis 去重辅助。应急顺序：先看影响面（哪些业务键重复了）→ 用幂等键反查业务表评估副作用 → 手工冲正 → 再补根治。事故报告里我会写清"重复窗口"和"影响键范围"两个量化数字——这和 M8 支付对账的复盘纪律一脉相承。

## 5. 今日验收清单

- [ ] "先拨后干 vs 先干后拨"时序图能白板画（两种语义各自的丢失/重复窗口）
- [ ] 重复消费复现实验完成（kill 时序+offset 序列留痕）
- [ ] 幂等三件套模板落档（唯一键/状态机/Redis 版）
- [ ] 事务生产者配置清单+read_committed 语义能讲
- [ ] 《消费语义决策卡》成文（分域：日志/订单/流式加工）
- [ ] `git add . && git commit -m "day11-08: offset semantics"`

---
[← Day 07](day07-第一周复盘.md) | [本月目录](README.md) | [Day 09 · 端到端可靠性拼图 →](day09-端到端可靠性.md)
