# Step 3：分布式事务与最终一致性（Seata AT / TCC / 消息最终一致）

> **本步目标**：为"下单 → 锁库存 → 扣余额"链路落地三种一致性方案并理解取舍：Seata AT（快速兜底）、TCC（资金级强一致）、消息最终一致（非核心链路），外加对账兜底体系。

## 前置术语

| 英文 | 中文 |
|------|------|
| Distributed Transaction | 分布式事务 |
| AT Mode (Auto Transaction) | 自动补偿型事务（Seata 默认模式） |
| TCC (Try-Confirm-Cancel) | 两阶段补偿型事务 |
| Branch Transaction | 分支事务 |
| Global Lock | 全局锁（防脏写） |
| Eventual Consistency | 最终一致性 |
| Reconciliation | 对账 |

## 1. 一致性方案选型（先定调，再动手）

| 链路 | 方案 | 理由 |
|------|------|------|
| 下单锁库存扣余额 | Seata AT | 改造成本低（注解即可），学习/兜底首选；性能损耗可控 |
| 扣余额（资金） | TCC | 资金不允许"中间态不可见"，Try 预冻结资金，肉眼可见可控 |
| 发积分/发券 | 消息最终一致 | 非核心域，允许延迟，绝不阻塞下单主链路 |
| 全局兜底 | 对账 Job | 任何方案都有漏网之鱼，对账是最后一道防线 |

## 2. Seata AT 模式落地

```yaml
# 每个参与者服务引入
seata:
  application-id: mall-order
  tx-service-group: mall_tx_group
  service:
    vgroup-mapping:
      mall_tx_group: default
  registry: { type: nacos, ... }
  config: { type: nacos, ... }
```

每个参与库执行 `undo_log` 建表脚本（Seata 官方提供）。发起方加注解：

```java
@GlobalTransactional(name = "create-order", timeoutMills = 6000, rollbackFor = Exception.class)
public OrderId createOrder(CreateOrderCmd cmd) {
    List<OrderItem> items = productFacade.lockStocks(cmd.items()).data();   // 分支事务1：锁库存
    accountFacade.debit(cmd.userId(), totalOf(items));                      // 分支事务2：扣余额
    Order order = Order.place(...);
    orderRepository.save(order);                                            // 分支事务3：落订单
    return order.id();
}
```

**AT 原理要点（面试高频）**：
1. 一阶段：各分支本地事务直接提交 + 生成 undo_log（前后镜像）+ 注册分支 + 申请全局锁（针对行）。
2. 二阶段提交：异步批量删 undo_log；二阶段回滚：按 undo_log 反向补偿（update 回旧值）。
3. 全局锁防脏写：另一全局事务要改同一行必须等锁 → 这就是 AT 的隔离级别代价（默认读未提交）。
4. 代价：每条 SQL 多两次镜像写入；热点行全局锁竞争会限吞吐 → 这就是资金链路改 TCC 的原因。

## 3. TCC 模式（账户余额）

```java
@LocalTCC
public interface AccountTccAction {
    @TwoPhaseBusinessAction(name = "debitAccount", commitMethod = "confirm", rollbackMethod = "cancel")
    boolean tryDebit(BusinessActionContext ctx,
                     @Param("userId") Long userId, @Param("amount") BigDecimal amount);

    boolean confirm(BusinessActionContext ctx);   // 冻结 → 真扣
    boolean cancel(BusinessActionContext ctx);    // 冻结 → 解冻
}
// try:    UPDATE account SET frozen = frozen + ? , balance = balance - ? WHERE user_id=? AND balance >= ?
// confirm: UPDATE account SET frozen = frozen - ?
// cancel:  UPDATE account SET frozen = frozen - ? , balance = balance + ?
```

**TCC 三大问题的防御（必须全部实现，面试重点）**：

| 问题 | 场景 | 防御 |
|------|------|------|
| 空回滚 | Try 没执行，Cancel 先到 | Cancel 前查事务控制表，无 Try 记录则插入"已回滚"标记 |
| 悬挂 | Cancel 先执行完，Try 后到 | Try 执行前查控制表，已有回滚标记则拒绝 |
| 幂等 | Confirm/Cancel 重试多次 | 全部基于 `xid` 的条件更新（状态机式幂等） |

## 4. 消息最终一致（积分链路）

下单成功后**不再同步调用**营销服务，改发领域事件（RocketMQ 事务消息，项目一已练过）：

```
Order.paid → 发 OrderPaidEvent（事务消息）
mall-marketing 消费 → 幂等消费 → 加积分/发券
失败 → MQ 重试 → 死信 → 人工/自动补偿
```

面试表达模板："核心链路 AT/TCC 保强一致，边缘链路事件驱动保最终一致，中间用对账兜底——**一致性是分层的，不是二选一**。"

## 5. 对账兜底（每天必跑，任何方案的最后防线）

```
对账三表：订单表 ↔ 库存流水 ↔ 账户流水
比对维度：笔数、金额合计、逐笔状态
差异分级：可自动补偿（如补发积分）→ 自动执行；资金类差异 → 冻结 + 工单人工
产出：对账日报（差异笔数/金额/处理结果），差异率 > 阈值即告警
```

## 验收清单

- [ ] AT 演示：扣余额分支抛异常 → 库存自动回滚（日志可见 undo_log 反补偿）
- [ ] AT 压测：对比加/不加 @GlobalTransactional 的 TPS，记录损耗百分比
- [ ] TCC 演示：Try 冻结可见 → Confirm 真扣 / Cancel 解冻；空回滚与悬挂用例各演示 1 次
- [ ] 积分链路：下单成功 2s 内积分到账；营销服务宕机恢复后自动补发（消息重试）
- [ ] 对账 Job：人为制造 1 笔差异 → 被对账发现并产出记录

## 常见坑

1. **@GlobalTransactional 内起子线程**：全局事务上下文靠 ThreadLocal 传递，子线程/线程池会丢 xid，需手动绑定或改异步消息。
2. **undo_log 没建**：回滚时报表不存在，回滚失败变悬挂。
3. **AT 管理大事务**：全局事务里调慢接口（如发短信）超时率高 → 事务尽量短，慢动作出事务。
4. **TCC 的 Try 里做了提交类动作**：Try 只能预留（冻结），不能真扣，否则 Cancel 无法回滚。

> 完成后进入 `step04-稳定性保障-降级熔断与全链路.md`。
