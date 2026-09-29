# Day 23 · TCC 与 SAGA 实战：手写 TCC 骨架防三坑

> **今日目标**：手写迷你 TCC 框架骨架（事务控制表+三接口+防三坑），用"秒杀库存冻结"场景演示；讲清 SAGA 编排 vs 协同并画出秒杀的 SAGA 化改造图。
> **时长**：TCC 编码 2.5h / SAGA 1.5h / 总结 1h
> **今日产出**：TccDemo.java（可运行）+ 秒杀 SAGA 流程图

## 1. 知识地图

```
TCC 的业务语义（对比"直接扣库存"）：
  直接扣：UPDATE stock SET num=num-1 WHERE id=? —— 失败要补偿就得逆向 UPDATE（易错）
  TCC 三步：
    Try    预留：UPDATE stock SET frozen=frozen+1 WHERE id=? AND num-frozen>=1（冻结 1 个）
    Confirm 确认：UPDATE stock SET num=num-1, frozen=frozen-1 WHERE frozen>0（扣冻结）
    Cancel 释放：UPDATE stock SET frozen=frozen-1 WHERE frozen>0（解冻）
  隔离性的来源：Try 之后 Confirm/Cancel 之前，资源处于"冻结"中间态，别人看得见但用不了
  ——这就是 TCC 比 SAGA/消息方案"隔离性好"的原因：资源预留语义

事务控制表 tcc_tx（防三坑的核心，一张表打天下）：
  CREATE TABLE tcc_tx (
    tx_id VARCHAR(64) PRIMARY KEY,        -- 全局事务 ID
    state VARCHAR(16),                    -- TRIED / CONFIRMED / CANCELLED
    created DATETIME, UNIQUE KEY ...)     -- 唯一键 + 状态机 = 幂等根基

  三坑的解法代码化：
  空回滚 Empty Rollback：Cancel 时 tcc_tx 无记录 → 插入 state=CANCELLED 并直接返回成功
                        （插入成功=空回滚生效；插入冲突=Try 已来过，正常走补偿）
  悬挂 Suspension：Try 时发现 tcc_tx 已有 CANCELLED 记录 → 拒绝执行直接返回
                  （因为事务已回滚，迟到的 Try 不能再冻结资源）
  幂等：Confirm/Cancel 可能被重试 → 检查 state，已 CONFIRMED 再来 Confirm 直接成功

  状态机流转：TRIED → CONFIRMED（正向）
                  ↘ CANCELLED（逆向）
  任何非法流转（CANCELLED 后又来 Confirm）直接拒绝+告警

SAGA 对照（不写代码，画图理解）：
  秒杀下单的 SAGA 化（协同版 Choreography）：
    下单服务 --ORDER_CREATED 事件--> 库存服务扣减 --STOCK_DEDUCTED--> 优惠券服务
             --COUPON_USED--> 积分服务；任一步失败 → 发布 X_FAILED 事件 → 前序服务各自补偿
  编排版 Orchestration：中央协调器按状态机驱动（Temporal/Seata Saga），
    显式定义 T1 下单→T2 扣库存→T3 用券，失败逆序 C3→C2→C1
  协同 vs 编排：协同解耦但链路不可见（查问题靠事件溯源）；编排清晰但协调器是焦点
  （秒杀的异步化本质是"协同式 SAGA 的事件部分 + 消息最终一致的可靠性"，两个模型在这里合流）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Try / Confirm / Cancel | 资源预留/确认/释放 |
| Frozen Resource | 冻结资源（中间态的物理载体） |
| Transaction Control Table | 事务控制表（tcc_tx） |
| Empty Rollback | 空回滚（Try 未到 Cancel 先至） |
| Suspension | 悬挂（Cancel 后 Try 才到） |
| SAGA Orchestration | 编排式 SAGA（中央协调器） |
| SAGA Choreography | 协同式 SAGA（事件驱动） |
| Compensating Transaction | 补偿事务（逆向操作） |
| Isolation | 隔离性（TCC 靠冻结获得，SAGA 缺失） |

## 3. 动手实操：手写 TccDemo（迷你框架）

```java
// learning/month05-mq-theory/src/TccDemo.java（纯 JDBC，骨架）
public class TccDemo {
    // === Try：冻结库存（含防悬挂检查）===
    static boolean tryDeduct(Connection c, String txId, int qty) throws Exception {
        // 防悬挂：tcc_tx 已有 CANCELLED → 拒绝（说明已被回滚，别再冻结了）
        // INSERT INTO tcc_tx(tx_id,state) VALUES(txId,'TRIED')
        //   冲突 → 查状态：TRIED=重复Try,返回幂等成功；CANCELLED=悬挂,返回失败
        // UPDATE stock SET frozen=frozen+? WHERE id=? AND num-frozen>=?
        //   affected=0 → 库存不足，Cancel 路径
    }
    // === Cancel：释放冻结（含防空回滚）===
    static boolean cancel(Connection c, String txId) throws Exception {
        // 查 tcc_tx：无记录 → INSERT(txId,'CANCELLED') 返回成功（空回滚已处理！）
        //            已 CONFIRMED → 拒绝+告警（非法流转）
        //            已 CANCELLED → 幂等成功
        // UPDATE stock SET frozen=frozen-? WHERE id=? AND frozen>=?
        // UPDATE tcc_tx SET state='CANCELLED'
    }
    // === Confirm：扣减冻结（幂等）===
    static boolean confirm(Connection c, String txId) throws Exception {
        // tcc_tx 必须 TRIED（否则幂等/拒绝）
        // UPDATE stock SET num=num-?, frozen=frozen-? WHERE frozen>=?
        // UPDATE tcc_tx SET state='CONFIRMED'
    }
    // main 演示五场景：
    //  A 正向：Try→Confirm，库存 num-1 frozen 先+1 后-1，账目平
    //  B 逆向：Try→Cancel，库存不变（frozen 先+1 后-1）
    //  C 空回滚：直接 Cancel（无 Try）→ tcc_tx 多一条 CANCELLED，业务无恙
    //  D 悬挂：Cancel 先执行完，迟到的 Try → 被拒
    //  E 幂等重试：Confirm 连调 3 次 → num 只扣 1 次
    // 每场景打印前后库存与 tcc_tx 状态，贴进笔记
}
```

```powershell
# 建表：
docker exec -it mysql-learning mysql -uroot -proot123 -e "
CREATE TABLE IF NOT EXISTS shop.stock_tcc (
  id VARCHAR(32) PRIMARY KEY, num INT, frozen INT DEFAULT 0) ENGINE=InnoDB;
INSERT INTO shop.stock_tcc VALUES ('sku-1', 100, 0);
CREATE TABLE IF NOT EXISTS shop.tcc_tx (
  tx_id VARCHAR(64) PRIMARY KEY, state VARCHAR(16),
  created DATETIME DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB;"
# 编译运行同 day22 模式（javac -cp ..\lib\* ...）
```

## 4. 面试连接

**Q：TCC 为什么隔离性好？代价是什么？**
> 隔离性来源：Try 阶段冻结资源，中间态对其他事务"可见但不可用"（库存冻结数单独记账），不会出现 SAGA 那种"已扣款但订单失败"的中间态外泄。代价三层：①业务改造重——每个参与方写三个接口；②数据模型重——要加 frozen 字段/冻结账户；③吞吐损耗——三阶段两倍以上交互。所以 TCC 只用于资金、核心库存等"错不起"的场景，普通异步链路用消息最终一致就够——一致性预算分配的老原则。

**Q：你们的秒杀为什么不用 TCC 扣库存？**
> 场景分析：秒杀库存扣减是"单资源、短事务、高并发"，TCC 的冻结语义有价值但交互成本高；我们用"Redis Lua 预扣（原子）+ 事务消息保障 DB 落账 + 消费幂等 + 对账兜底"，性能与一致性兼得。若业务升级为"下单锁库存 30 分钟再支付"（电商常态），那才是 TCC/预占模式的主场——Try=冻结 30 分钟，超时 Cancel 自动解冻（配延迟消息，正好 day09 的方案）。这个回答展示"按场景选方案"而不是"学了个锤子看啥都是钉子"。

## 5. 今日验收清单

- [ ] tcc_tx + stock_tcc 建表，TccDemo 五场景全部演示
- [ ] 三坑解法代码级能讲（哪一行防哪一坑）
- [ ] 秒杀 SAGA 化流程图（协同版）画完
- [ ] 编排 vs 协同的取舍能讲
- [ ] "秒杀为什么不用 TCC"场景化观点能讲
- [ ] `git add . && git commit -m "day23: tcc saga"`

---
[← Day 22](day22-本地消息表实战.md) | [本月目录](README.md) | [Day 24 · 秒杀二阶段01顺序与轨迹 →](day24-秒杀二阶段01顺序与轨迹.md)
