# Day 17 · TCC 框架级实战：Seata TCC 与三坑防御

> **今日目标**：用 Seata TCC 把扣库存 TCC 化（try 冻结/confirm 扣减/cancel 解冻）；对照 M5 手写 TCC，验证框架级三坑防御（幂等/空回滚/悬挂）。
> **时长**：TCC 接入 2h / 三坑验证 2h / 手写 vs 框架对照 1h
> **今日产出**：扣库存 TCC 组件 + 三坑防御验证报告 + 手写/框架对照表

## 1. 知识地图

```
TCC 语义（M5 day23 手写过，现在是框架版）：
  Try：资源预留——库存表加 frozen 字段：frozen += n（可用量不减！预留量）
  Confirm：确认扣减——available -= n, frozen -= n（预留转正式）
  Cancel：取消预留——frozen -= n（把预留还回去）
  ——关键设计：Try 阶段就"占住"资源（冻结），Confirm/Cancel 都只是"结账"，
   所以 Confirm 必须成功（资源已预留，不存在做不了），失败只能重试

Seata TCC 注解模型：
  @TwoPhaseBusinessAction(name="deductStock", commitMethod="confirm", rollbackMethod="cancel")
  接口三方法同名约定；BusinessActionContext 传递业务参数（productId/count/xid）
  TM 侧仍 @GlobalTransactional——TCC 是分支模式，全局事务框架不变

框架级三坑防御（对照 M5 手写版的事务控制表）：
  ① 幂等：Seata 的 TCC 事务表（branch 记录状态）——Confirm/Cancel 重试先查状态，
     已处理过直接返回成功（框架查表，不用自己建事务控制表）
  ② 空回滚：Try 没执行（超时没到）但 Cancel 先到——
     Seata 1.5.1+ 内置防悬挂：Cancel 时若无 Try 记录，插一条"防悬挂记录"，
     后到的 Try 检查到该记录直接拒绝执行（M5 手写版我们自己写的逻辑，框架内置了！）
  ③ 悬挂：同上——Cancel 先占坑，Try 被挡——防悬挂记录一石二鸟
  对照结论：手写 TCC 学的是"为什么要防"，框架 TCC 用的是"它替你防了"
   ——面试先讲三坑原理再讲框架机制，展示深度

冻结字段 vs 扣减反转（TCC 设计经典题）：
  ❌ Try 直接扣 available，Cancel 加回来——并发下"超卖回滚再卖"会负数穿帮
  ✅ Try 冻结（frozen+），Confirm 才真正减——中间态可见、可审计、不超卖
  ——本质：TCC 的 Try 必须设计出"资源预留的中间状态"，没有中间态就没法两阶段
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Try / Confirm / Cancel | 预留/确认/取消 |
| @TwoPhaseBusinessAction | Seata TCC 两阶段注解 |
| BusinessActionContext | 业务上下文（跨阶段传参） |
| Frozen/Reserved Amount | 冻结量（资源预留中间态） |
| Anti-Suspension Record | 防悬挂记录（Cancel 先到占坑） |
| Idempotent Confirm | 幂等确认（重试安全） |
| TCC Branch Mode | TCC 分支模式 |

## 3. 动手实操：扣库存 TCC 化

```java
// mall-product：TCC 接口（Try/Confirm/Cancel 三方法）
@LocalTCC
public interface StockTccAction {
    @TwoPhaseBusinessAction(name = "deductStock",
            commitMethod = "confirm", rollbackMethod = "cancel")
    boolean tryDeduct(BusinessActionContext ctx,
                      @BusinessActionContextParameter(paramName = "productId") Long productId,
                      @BusinessActionContextParameter(paramName = "count") int count);

    boolean confirm(BusinessActionContext ctx);
    boolean cancel(BusinessActionContext ctx);
}

@Component
public class StockTccActionImpl implements StockTccAction {
    // Try：冻结库存（available 不动，frozen 增加）——幂等：冻结前查 frozen 是否已含本次
    public boolean tryDeduct(BusinessActionContext ctx, Long productId, int count) {
        int rows = stockMapper.freeze(productId, count);   // UPDATE stock SET frozen=frozen+#{count} WHERE id=#{id} AND available-frozen>=#{count}
        return rows > 0;                                    // 余量不足：Try 失败→全局事务回滚
    }
    // Confirm：预留转正式（幂等由 Seata 事务状态表保证，业务侧再加"是否已扣"判断更稳）
    public boolean confirm(BusinessActionContext ctx) {
        Long productId = ctx.getActionContext("productId", Long.class);
        int count = ctx.getActionContext("count", Integer.class);
        stockMapper.deductFrozen(productId, count);        // available-=count, frozen-=count
        return true;
    }
    // Cancel：解冻（防悬挂由框架：无 Try 记录时自动占坑拒绝后续 Try）
    public boolean cancel(BusinessActionContext ctx) {
        Long productId = ctx.getActionContext("productId", Long.class);
        int count = ctx.getActionContext("count", Integer.class);
        stockMapper.unfreeze(productId, count);            // frozen-=count
        return true;
    }
}
// mall-order 调用方（TM）不变：@GlobalTransactional 里调 Feign → product 的
// TCCController → stockTccAction.tryDeduct(...)——框架接管 Confirm/Cancel 调度
```

```powershell
# 三坑防御验证实验（对照 M5 手写版的注入方式）：
# ① 幂等：拦截 Confirm 让它超时 → TC 重试 Confirm → 验证库存只扣一次（日志看重试+结果不变）
# ② 空回滚：Try 里 Thread.sleep 模拟超时未达 → Cancel 先执行 →
#     观察日志 "无 Try 记录，防悬挂占坑" → 后到 Try 被拒 → 全局事务标记回滚
# ③ 业务正确性：并发 200 压同商品 → 最终 available+frozen == 初始库存（守恒检查 SQL）
# SELECT available + frozen AS total FROM stock WHERE id=1001;   -- 恒等于初始值！
```

## 4. 面试连接

**Q：TCC 的空回滚、悬挂、幂等是什么？怎么防？**
> 空回滚：Try 没执行 Cancel 先到（网络分区/超时），Cancel 找不到预留记录——防法：Cancel 先查事务表无 Try 记录则登记"已空回滚"再返回成功。悬挂：Cancel 空回滚后 Try 才姗姗来迟执行了预留、再没人 Cancel 它——资源永久冻结；防法：Try 执行前查"已空回滚"标记则拒绝。幂等：Confirm/Cancel 由 TC 超时重试驱动可能重复调用——防法：事务状态表+先查后做。收尾升华："M5 我手写过带事务控制表的 TCC，这三个坑是自己一坑一坑踩的；用 Seata 后发现它 1.5.1 内置了防悬挂记录机制——手写让我懂原理，框架让我少写 300 行防御代码，两段经历拼起来才是完整的 TCC 认知。"

**Q：TCC 的 Try 怎么设计？为什么不能直接扣减？**
> Try 要构造"资源预留的中间态"：冻结字段是标准做法。直接扣减的坑：A 扣 1（库存 99→98），B 查到 98 继续扣……A 回滚加回 1——并发窗口里出现"扣了又还"的交错，审计困难且容易负数穿帮；冻结方案里 available 永不回滚，只有 frozen 增减，不变量"available+frozen==初始库存"始终守恒，对账 SQL 一条就能验资损。追问"不是所有资源都有天然冻结态怎么办"：常见手段——金额冻结（余额表 frozen 列）、名额占位（插入 status=PENDING 的记录）、状态机前置态（订单 CREATED）；没有中间态可造的业务不适合 TCC，退到 SAGA/消息最终一致。

## 5. 今日验收清单

- [ ] StockTccAction 三方法落地（冻结/扣减/解冻）
- [ ] 三坑验证实验全过（幂等/空回滚+悬挂/守恒检查）
- [ ] "available+frozen==初始"守恒 SQL 验证通过
- [ ] 手写 TCC vs Seata TCC 对照表完成
- [ ] Try 的中间态设计能举例（冻结/占位/状态机）
- [ ] `git add . && git commit -m "day17: seata tcc"`

---
[← Day 16](day16-AT深入与压测.md) | [本月目录](README.md) | [Day 18 · 消息最终一致整合 →](day18-消息最终一致整合.md)
