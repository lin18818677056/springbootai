# Day 06 · 消息可靠性与消费幂等：RetryTopic、DLQ 与双保险去重

> **今日目标**：贯通"发送三方式 → 重试机制 → 消费重试 → 死信 DLQ"完整可靠性链路，手写 Redis+DB 双保险幂等组件 IdempotentConsumer，压测重复投递 0 重复处理。
> **时长**：原理 1.5h / 幂等组件 2.5h / 压测 1h
> **今日产出**：IdempotentConsumer 组件（可运行）+ 重复投递压测报告

## 1. 知识地图

```
消息可靠性全景（今天聚焦"消费侧"，发送侧 day10 事务消息再补）：

  发送三方式：
    同步 send()：等 Broker ACK，最可靠，RT 最高
    异步 send(callback)：回调里拿到 SendResult，失败触发重试，高吞吐+可靠兼得
    OneWay：发了就忘（日志埋点场景）

  发送重试：retryTimesWhenSendFailed=2（同步），重试换 Broker 发
    ⚠ 重试可能导致 Broker 存两条（旧版 Broker 未去重）→ 幂等仍需消费端兜底

  消费失败重试链（集群模式）：
    消费返回 RECONSUME_LATER / 抛异常
    → 消息进重试队列 %RETRY%消费者组名（每条有 delayLevel，1s/5s/10s/30s/1m/2m…2h 共 16 级）
    → 重试 16 次仍失败 → 死信队列 %DLQ%消费者组名
    → DLQ 的消息可人工修复后重新投递（Dashboard 一键重发）
    delayLevel 越重越久 = 给下游故障恢复留时间（退避思想，跟 TCP 重传一个味道）

消费幂等（今天的手写重点）——三层防御：
  ① Redis 预判：SETNX 去重键（快，挡 99% 重复）
  ② DB 唯一索引：去重表 uk_msg_key（Redis 失效也挡得住，最终防线）
  ③ 状态机：业务表状态流转校验（已支付不能再扣），兜底防业务级重复
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Retry Topic | 重试队列 `%RETRY%+组名`，每消费者组专属 |
| DLQ (Dead Letter Queue) | 死信队列 `%DLQ%+组名`，重试耗尽的最终去处 |
| Delay Level | 重试/延迟等级（1~18，重试用 1-16） |
| ConsumeStatus | CONSUME_SUCCESS / RECONSUME_LATER |
| Idempotency | 幂等（同一操作执行 N 次 = 执行 1 次的效果） |
| Dedup Table | 去重表（msg_key 唯一索引） |
| Max Reconsume Times | 最大重试次数（默认 16，可按消息设置） |

## 3. 动手实操：手写 IdempotentConsumer（Java）

```java
// learning/month05-mq-theory/src/IdempotentConsumer.java（核心逻辑骨架）
// 依赖：JDBC（lib/mysql-connector-j.jar）+ 可选 Jedis；纯 javac 编译运行
public class IdempotentConsumer {
    // 三层防御：Redis 预判 → DB 唯一索引 → 业务状态机
    static boolean tryConsume(String msgKey, String bizType) throws Exception {
        // ① Redis 预判：EXISTS 去重键（TTL 给 24h，防 Redis 无限膨胀）
        //    Jedis: if (jedis.setnx("dedup:"+bizType+":"+msgKey, "1") == 0) return false;
        // ② DB 唯一索引兜底：INSERT INTO mq_dedup(msg_key, biz_type, consume_time)
        //    主键冲突 = 已消费过 → return false（注意：插入失败要回滚 Redis 键？不，Redis 键留着无妨）
        // ③ 返回 true 后才执行业务逻辑；业务表更新带状态机条件（UPDATE ... WHERE status='INIT'）
        //    affected=0 说明状态已流转 → 视为重复，返回 false
    }
}
-- 建表 SQL（mysql-learning 容器执行）：
CREATE TABLE mq_dedup (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  msg_key   VARCHAR(64) NOT NULL,
  biz_type  VARCHAR(32) NOT NULL,
  consume_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_biz_msg (biz_type, msg_key)      -- 最终防线
) ENGINE=InnoDB;
```

```powershell
# 压测重复投递（验证幂等组件）：
# ① 消费端用 IdempotentConsumer 包裹业务逻辑，业务逻辑=往 orders 表插一条（order_no=msgKey）
# ② 生产端循环发 1000 条，MsgId 1~1000；发完立刻把同一批 1000 条再发一遍（共 2000 条投递）
# ③ 消费者日志统计：收到 2000 次投递，orders 表只有 1000 行
# ④ 再狠一点：消费处理中手动 restart 消费者（制造 Rebalance 重复）→ 表仍是 1000 行
# 预期指标：投递 2000 / 落库 1000 / 去重表 1000 行 / 0 重复订单
```

## 4. 面试连接

**Q：消息重复是 Bug 吗？怎么根治？**
> 不是 Bug，是设计使然：网络是不可靠的，"至少一次"投递（At-Least-Once）是 MQ 保障不丢的必然代价（想"至多一次"就会丢消息，Exactly-Once 要端到端协作）。根治靠消费幂等三板斧：Redis 预判（性能）→ DB 唯一索引（正确性兜底）→ 业务状态机（语义兜底）。关键话术："Redis 只是性能优化层，唯一索引才是正确性保障——所以我从不担心 Redis 挂了导致重复。"（与 M4 秒杀三层幂等呼应）

**Q：死信队列里的消息怎么处理？**
> 监控告警是第一位（DLQ 有消息 = 业务受损，必须 P3 以上告警）。处理流程：定位失败原因（大多是数据问题或下游故障）→ 修复根因 → 用 Dashboard 或 mqadmin 把死信消息重发回原 Topic（可加机器标记防止重试风暴）→ 复盘为什么 16 次重试都没救回来。补充：可按消息设置 maxReconsumeTimes，把"可自动恢复的失败"（如下游瞬时抖动）与"必然失败"（如参数非法）区分开——后者 1 次就进 DLQ 别浪费重试。

## 5. 今日验收清单

- [ ] 可靠性链路图（发送→重试→DLQ）手绘完成
- [ ] mq_dedup 建表 + IdempotentConsumer 编译运行通过
- [ ] 2000 次投递 / 1000 行落库 / 0 重复压测达标
- [ ] 重启消费者制造 Rebalance 重复的实验记录
- [ ] DLQ 告警与人工修复流程能脱稿
- [ ] `git add . && git commit -m "day06: idempotent consumer"`

---
[← Day 05](day05-消费机制与Rebalance.md) | [本月目录](README.md) | [Day 07 · 第一周复盘 →](day07-第一周复盘.md)
