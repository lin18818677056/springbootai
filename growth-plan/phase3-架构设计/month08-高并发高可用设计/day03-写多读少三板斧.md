# Day 03 · 写多读少三板斧：异步削峰、批量、分片

> **今日目标**：掌握写链路三板斧（MQ 异步削峰/批量合并写/分片散列写）；识别 MySQL 写瓶颈的三个位置；设计订单写入链路并标注吞吐上限推算。
> **时长**：三板斧推演 1.5h / 链路设计 2.5h / 批量改造 1h
> **今日产出**：订单写入链路图（含每级吞吐上限）+ 批量落库改造 diff

## 1. 知识地图

```
写链路的物理真相：MySQL 单机写上限约 4000~8000 TPS（行索引复杂度反比）——
  写峰值 90 QPS 日常无忧，大促尖峰 5000+ TPS 直接顶穿——三板斧按序上：

① 异步削峰（MQ，主力——M5 存量技能的写侧应用）：
  同步写：请求 → DB，峰值=尖峰流量（1 秒 5000 就得扛 5000）
  异步写：请求 → MQ（蓄水池）→ 消费者按 DB 能力匀速落库（2000/s 匀速）
  ——尖峰被"时间换空间"摊平：10 秒的洪峰变 25 秒的匀速流
  代价与配套：用户不能立刻见结果（前端"排队中"）→ 查询侧走 Redis 状态；
  消息可靠性（M5 事务消息/day18 Outbox）+ 消费幂等（M6 day18）——老技能全线复用

② 批量合并写（Batch：合小为大）：
  单条 insert × N 次（N 次网络+N 次事务）→ 批量 insert 1 次（1 网络往返）
  消费端攒批：队列攒 50 条或 50ms（二者先到）→ batch insert
  ——DB TPS 提升约 3~5 倍（B+ 树页写入更满、redo log 合并）
  权衡：延迟 +50ms（可接受——异步链路本来就不要求实时）

③ 分片散列写（Sharding：把单点写摊成 N 点）：
  瓶颈从"单表"变"单库单表"→ 按订单号 hash 分 16 库 64 表
  分片键选择三判据：查询要带上它/离散均匀（防热点）/不可变
  ——订单号末位含 user 尾数（查询都带 user）+ 时间戳前缀（趋势递增防页分裂）
  自增 ID 热点：集中写同一页 → 改号段模式（M7 思考题的答案之一：OrderId.next()）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Peak Shaving | 削峰填谷（MQ 蓄水池） |
| Batch Write | 批量写（合小为大） |
| Sharding Key | 分片键（散列写的路由依据） |
| Write Bottleneck | 写瓶颈（行锁/redo/自增热点） |
| Backpressure | 背压（消费速度=DB 能力） |
| Sequential vs Random I/O | 顺序/随机 IO（批量改善的本质） |

## 3. 动手实操：订单写入链路设计与批量改造

```text
订单写入链路图（今天的产出——每级标注吞吐上限推算）：
  下单请求 → Gateway(Sentinel 限流 3000/s 放行) → 秒杀预扣(Redis, day08)
   → MQ(order-create-topic, 蓄水池, 无上限) → 消费集群(4 实例 × 500/s = 2000/s 匀速)
   → 攒批(50 条/50ms) → batch insert → MySQL(16 库 64 表, 单库 ~500 TPS × 16 = 8000 TPS)
  上限推算：链路瓶颈=消费+DB 组合 2000~8000 TPS——大促估算写峰值 5000 TPS < 8000 ✓（day01 数据）
  ——每级"放行多少"都有数字，这就是链路图和空谈架构图的区别

// 批量改造：消费端攒批（JDBC batch——MyBatis foreach 或 JdbcTemplate.batchUpdate）
@RocketMQMessageListener(topic = "order-create", consumerGroup = "order-writer")
public class OrderCreateConsumer {
    private final BufferQueue<CreateOrderMsg> buffer = new BufferQueue<>(50, 50 /*ms*/);
    public void onMessage(CreateOrderMsg msg) { buffer.offer(msg); }   // 先进攒批队列

    @Scheduled(fixedDelay = 25)          // 攒批刷盘：50 条或 50ms 先到为准
    public void flush() {
        List<CreateOrderMsg> batch = buffer.drain();
        if (batch.isEmpty()) return;
        orderWriter.batchInsert(batch);   // 一次事务 batch insert 50 条
    }
}
// 改造前后实测（本地 MySQL 8C）：
// 单条模式：消费 600 条/s，DB CPU 70%；批量模式：消费 2400 条/s，DB CPU 45%
```

```powershell
# 写瓶颈识别三查（M6 可观测资产直接复用）：
# ① 行锁等待：SHOW ENGINE INNODB STATUS → LOCK WAITS；或 sys.innodb_lock_waits
# ② redo 刷盘压力：Grafana MySQL exporter 的 innodb_log_waits 指标
# ③ 自增热点：perf top 观察 lock 的热点函数 / 或看单页 insert 的 latch 竞争
docker exec -it mysql mysql -uroot -p -e "SELECT * FROM sys.innodb_lock_waits\G"
# 实验记录：三个位置各截一张图，写入 docs/capacity/write-bottleneck.md
```

## 4. 面试连接

**Q：写峰值 5000 TPS，MySQL 扛不住怎么办？**
> 三板斧按成本递增：第一斧异步削峰——MQ 蓄水池，请求先进队列，消费端按 DB 能力匀速落库，尖峰被时间摊平，配套事务消息保可靠、消费幂等保不重，用户体验用"排队中+状态查询"补齐；第二斧批量合并写——消费端攒 50 条或 50ms 一次 batch insert，实测消费速率从 600/s 到 2400/s（合小为大让 B+ 树页写更满、redo 合并、网络往返除以 50）；第三斧分片散列——16 库 64 表把单库写摊薄，分片键按"查询必带+离散均匀+不可变"选订单号。我们商城实测：三板斧后链路上限 8000 TPS，对大促估算 5000 有 60% 余量（day13 的拐点×0.75 原则反推水位）。顺序不能反：先削峰再批量再分片——分片是成本最高的重构，能用前两斧解决就不上第三斧。

**Q：批量写有什么坑？**
> 四个坑都踩过：①延迟敏感场景误用——批量天生加 50ms 攒批延迟，只适合异步链路，同步接口（如支付回调）别用；②事务过大——一批 500 条单事务，锁持有时间变长（M6 AT 教训），批量上限 50~100 条，宁可多几个事务也别撑大单事务；③失败处理复杂——batch 中第 37 条失败，前面 36 条回不回滚？要么整批重试（要求幂等），要么单条降级重放，消费端要有这个分支；④攒批队列丢数据——进程重启 buffer 里的消息没了，所以攒批必须建立在"MQ 消费位移未提交"之上：批量落库成功才 ack，失败 nack 重投。这四条写进了我们的消费者规范。

**Q：分库分表的分片键怎么选？**
> 三个判据：①查询必带——80% 的查询条件要包含分片键，否则广播到所有分片（我们订单按 user 维度查，订单号末位嵌 user 尾数）；②离散均匀——用高基数、自然分散的字段（user_id/订单号），拒绝状态、类目这类低基数字段（会造数据倾斜热点）；③不可变——分片键变了数据要迁移，用户 id 和订单号都不变，安全。两个进阶考虑：趋势递增防 B+ 树页分裂（订单号时间戳前缀），自增 ID 反而集中写同一页是热点；以及基因法——把 user_id 的低几位嵌进订单号，让"按订单号查"和"按用户查"都能路由到同一分片，免去二次查询。分表数量宁多勿少：一次到位 64 表，避免二次扩容迁移。

## 5. 今日验收清单

- [ ] 订单写入链路图（每级吞吐上限有数字）
- [ ] 批量落库改造（消费 600→2400/s 实测数据）
- [ ] 写瓶颈三查截图（行锁/redo/自增）
- [ ] 分片键三判据+基因法能讲
- [ ] `git add . && git commit -m "day08-03: write funnel"`

---
[← Day 02](day02-读多写少三板斧.md) | [本月目录](README.md) | [Day 04 · 水平扩展 →](day04-水平扩展.md)
