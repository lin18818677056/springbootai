# Day 22 · 本地消息表实战：可靠消息最终一致七步链路

> **今日目标**：手写本地消息表方案完整链路（LocalMsgDemo）：业务与消息同事务落库 → 扫表投递 → 失败重试 → 对账兜底，并与事务消息方案做全维度对比。
> **时长**：原理 1h / 编码 3h / 对比总结 1h
> **今日产出**：LocalMsgDemo.java（可运行七步链路）+ 双方案对比表

## 1. 知识地图

```
本地消息表（Local Message Table）方案全景：
  核心思想：把"要发消息"这件事本身变成一条数据库记录，
           借助本地事务的原子性保证"业务成功 ⇔ 消息记录存在"

  七步链路：
  ① 业务操作 + 插入 local_msg（status=INIT）在同一个本地事务 —— 原子性根基
  ② 事务提交后，尝试立刻投递 MQ（抢时间，减少延迟）
  ③ 投递成功 → status=SENT；失败/超时 → 不管，留在 INIT
  ④ 定时任务扫表：status=INIT 且 next_retry <= now → 重新投递
  ⑤ 重试退避：retry_count+1，next_retry = now + 2^count 秒（1/2/4/8...上限 10min）
  ⑥ 超过 max_retry（如 16 次）→ status=DEAD，人工介入（告警）
  ⑦ 兜底：对账 Job 对比"业务表成功记录" vs "local_msg=SENT 记录"，找漏网之鱼

  关键设计点：
  - 表 uk_biz 唯一键：同一业务事件只会有一条消息记录（生产端幂等）
  - 扫表要分片：LIMIT + 按 id 范围扫，避免大表全扫锁表；多实例部署要抢任务（乐观锁 UPDATE status=SENDING WHERE id=? AND status='INIT'）
  - payload 存消息体快照：重投时不需要回查业务表（自包含）

  事务消息 vs 本地消息表（day10 vs 今天，全维度对比）：
  维度          | 事务消息(RocketMQ)         | 本地消息表
  原子性保证    | 半消息+回查(中间件协调)     | 本地事务(数据库保证)
  依赖          | 强绑定 RocketMQ            | 只要有 MQ 就行（Kafka 也用）
  业务侵入      | 实现 2 个 Listener          | 建表+扫表任务+状态流转
  消息延迟      | 准实时                      | 扫表周期（秒级，可配置）
  DB 压力       | 小                          | 增加（一业务一消息行+扫表查询）
  运维复杂度    | 中间件功能，开箱即用        | 自建任务+告警+DEAD 处理
  适用          | 已用 RocketMQ 的交易链路    | 任意 MQ/需要审计轨迹/跨 MQ 场景
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Local Message Table | 本地消息表（业务库内的消息发件箱） |
| Transactional Outbox | 事务发件箱模式（本地消息表的学名，同一种东西） |
| Relay Task | 扫表投递任务（消息的"邮差"） |
| Backoff Retry | 退避重试（2^n 秒） |
| Dead Message | 死信状态（DEAD，人工介入） |
| Exactly 链路语义 | 生产端不丢（Outbox）+ 消费端幂等（唯一键）= 最终一致 |
| CDC 替代 | 变更数据捕获（Canal 监听 binlog 替代扫表，进阶方案） |

## 3. 动手实操：手写 LocalMsgDemo 七步链路

```java
// learning/month05-mq-theory/src/LocalMsgDemo.java（纯 JDBC + 线程模拟，骨架）
public class LocalMsgDemo {
    // ① 业务+消息同事务（原子性的关键：一个 Connection 两个 UPDATE）
    static void createOrderWithMsg(Connection conn, String orderId) {
        conn.setAutoCommit(false);
        // INSERT INTO orders(id, status) VALUES(orderId, 'CREATED')
        // INSERT INTO local_msg(biz_key, payload, status, next_retry)
        //        VALUES(orderId, "{...}", 'INIT', NOW())
        conn.commit();                       // 原子提交：两行要么都在要么都不在
    }
    // ③ 立刻投递（尽力而为）
    static boolean trySend(String bizKey, String payload) { /* 发 MQ，返回成败 */ }
    // ④⑤ 扫表重投（定时任务线程，每 5s）
    static void relayTask(Connection conn) {
        // SELECT id,biz_key,payload,retry_count FROM local_msg
        //  WHERE status='INIT' AND next_retry<=NOW() ORDER BY id LIMIT 100
        // 对每行：乐观锁抢占 UPDATE local_msg SET status='SENDING'
        //         WHERE id=? AND status='INIT'（防多实例重复投）
        // trySend 成功 → status='SENT'；失败 → status='INIT',
        //         retry_count+1, next_retry=NOW()+INTERVAL POW(2,retry_count) SECOND
        // retry_count>16 → status='DEAD'（触发告警日志）
    }
    // ⑦ 对账 Job：业务成功的单 vs SENT 消息，差集告警（演示打印即可）
    public static void main(String[] args) throws Exception {
        // 压测流程：
        //  A 正常路径：100 单 → 全部 SENT，消费端 IdempotentConsumer 落库 100
        //  B 故障注入①：trySend 前 kill 进程 → 重启后扫表补投 → 0 丢失
        //  C 故障注入②：MQ 停掉 30s → 消息堆 INIT → MQ 恢复后扫表自动追平
        //  D 故障注入③：把某单 payload 改坏（消费必失败）→ 16 次重试 → DEAD+告警
        // 每个故障的恢复时间与数据核对结果记录成表
    }
}
```

```powershell
# 建表 SQL 已在 day20 建好（local_msg）；补业务表：
docker exec -it mysql-learning mysql -uroot -proot123 -e "
CREATE TABLE IF NOT EXISTS shop.orders_m5 (
  id VARCHAR(64) PRIMARY KEY, status VARCHAR(16),
  created DATETIME DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB;"
# 编译运行（需要 JDBC jar + rocketmq-client jar，classpath 引 lib/）：
cd D:\mywork\springbootai\learning\month05-mq-theory\src
javac -encoding UTF-8 -cp "..\lib\*" LocalMsgDemo.java
java -cp ".;..\lib\*" LocalMsgDemo
```

## 4. 面试连接

**Q：本地消息表和事务消息怎么选？**
> 一句话定调：原子性保证的位置不同——本地消息表靠"业务库本地事务"，事务消息靠"中间件半消息+回查"。选型三问：①MQ 是不是 RocketMQ？不是→本地消息表；②要不要消息审计轨迹/跨 MQ 兼容？要→本地消息表（表本身就是轨迹）；③DB 写入余量够不够？紧张且已用 RocketMQ→事务消息（把扫表压力下沉给中间件）。我们秒杀用事务消息因为"三件套已就位"；如果是 Kafka 生态的订单系统，直接 Outbox+扫表。补充进阶一句：高吞吐场景用 Canal 监听 binlog 替代扫表（CDC 化 Outbox），扫表压力归零。

**Q：扫表任务多实例部署，怎么防止重复投递？**
> 三层：①抢占式更新——UPDATE ... SET status='SENDING' WHERE id=? AND status='INIT'，affected=0 说明别人抢了；②就算重复投了，消费端 IdempotentConsumer 唯一键兜底（端到端幂等是设计前提，不是补救）；③分片扫描——按 id 取模分片，各实例扫不同段，降低抢占冲突。观点收尾："分布式系统里'防重'永远做两层——机制上尽量避免（抢占/分片），语义上必须兜底（消费幂等）。"

## 5. 今日验收清单

- [ ] LocalMsgDemo 七步链路运行通过
- [ ] 三个故障注入实验记录（恢复时间+核对结果）
- [ ] 双方案对比表（8 维度）完成
- [ ] "Transactional Outbox 就是本地消息表的学名"这类术语能对上
- [ ] CDC/Canal 替代扫表的进阶观点能讲
- [ ] `git add . && git commit -m "day22: local msg table"`

---
[← Day 21](day21-第三周复盘与决策树.md) | [本月目录](README.md) | [Day 23 · TCC与SAGA实战 →](day23-TCC与SAGA实战.md)
