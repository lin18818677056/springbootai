# Day 18 · 资金安全与资损防控

> **今日目标**：账户并发安全（余额扣减的并发控制）、资损场景全景图与防控清单；《资损防控清单》产出——支付域的"大促预案"。
> **时长**：并发扣减方案 1.5h / 资损场景推演 2h / 防控清单成文 0.5h
> **今日产出**：BalanceService 代码（三种并发方案对比）+ 《资损防控清单》

## 1. 知识地图

```
资损的定义：钱的状态与真实世界不符且平台受损——多扣用户（客诉）/少收钱/多付商家/重复退款
资损防控 ≠ 对账（day17）：对账是"事后发现"，防控是"事中拦截+事前设计"——三道时间线

并发扣减三方案（账户域核心考题，性能/安全权衡）：
| 方案 | 实现 | 性能 | 安全性 | 适用 |
|------|------|------|--------|------|
| 悲观锁 | SELECT FOR UPDATE | 低（锁等待）| 强 | 冲突率高（热点账户）|
| 乐观锁 | UPDATE ... WHERE balance>=? AND version=? | 高 | 强（失败重试）| 常规账户 ✓ |
| Redis 预扣+异步落账 | Lua 原子扣+MQ 异步记账 | 极高 | 弱（宕机丢预扣）| 秒级高并发场景 |
  决策：常规支付走乐观锁（条件更新，M8 day08 同款）；秒杀类"先占后付"走 Redis 预扣；
  热点账户（平台大户）加"记账缓冲"——汇总 N 笔一次记账（M8 day11 计数三层思想）
  ——关键纪律：余额绝不允许"读-改-写"三步（竞态），必须是单条原子 UPDATE

资损场景全景推演（枚举攻击面，逐个给防控）：
  ① 重复支付：用户双击/回调重放 → 状态机+条件更新+幂等键（day16 全套）
  ② 金额篡改：回调金额≠支付单金额 → 金额核对（day16）+金额用分存储（long，绝不用 float！）
  ③ 并发超扣：余额 100 两笔 80 并发扣 → 条件更新 WHERE balance>=amount（原子判定）
  ④ 重复退款：退款接口重试 → refund_no 唯一约束+可退额度校验（累计退款≤支付金额）
  ⑤ 越权操作：改 payNo 参数查别人的单 → 归属校验（uid 必须匹配）+接口鉴权
  ⑥ 汇率/费率错：配置错误批量资损 → 费率变更走审批+生效前试算（影子计算对比）
  ⑦ 内部作恶：运营改余额 → 所有资金操作留痕（append-only 流水）+敏感操作双人复核
  ——推演方法：沿资金流（充值→支付→退款→结算）逐站问"这里能怎么错？错了谁发现？"

《资损防控清单》结构（支付域的"大促预案"，M8 day19 格式迁移）：
  每条 = 场景 + 防控手段（事前/事中/事后）+ 监控指标 + 应急动作
  事前（设计防御）：幂等键全覆盖/金额分存储/复式记账自检/最小权限
  事中（实时拦截）：余额条件更新/状态机/风控规则（单笔限额/频次限制/黑名单）
  事后（发现恢复）：三方对账（day17）/资损监控大盘（关键指标实时）/应急预案（冻结/回滚）
  资损监控的"哨兵指标"：试算平衡≠0（总账破平）/退款率突增（>3σ）/单账户高频交易
  ——哨兵的意义：任何一条触发=资损已在发生或即将发生，响应分钟级
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Fund Loss | 资损（资金状态与真实不符且平台受损）|
| Conditional Deduction | 条件扣减（WHERE balance>=?） |
| Loss Prevention | 资损防控（事前/事中/事后三道） |
| Sentinel Metric | 哨兵指标（资损先行指标） |
| Append-only Ledger | 只增账本（资金操作不可删改） |
| Dual Review | 双人复核（敏感操作） |

## 3. 动手实操：并发扣减与资损推演

```java
// learning/month09-system-design/src/BalanceService.java（条件更新防超扣：并发验证）
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class BalanceService {
    // 模拟账户表：balance 用 AtomicLong 模拟"单条 UPDATE 的原子性"
    static final Map<String, AtomicLong> accounts = new ConcurrentHashMap<>();
    static final List<String> successLog = Collections.synchronizedList(new ArrayList<>());

    /** 余额扣减：条件更新（真实 SQL：UPDATE account SET balance=balance-? WHERE uid=? AND balance>=?） */
    public static boolean deduct(String uid, long amountCents) {
        AtomicLong bal = accounts.get(uid);
        // CAS 循环模拟"单条原子 UPDATE ... WHERE balance>=?"（真实实现一条 SQL 搞定，无需循环）
        long cur;
        do {
            cur = bal.get();
            if (cur < amountCents) return false;                 // 余额不足（原子判定的一部分）
        } while (!bal.compareAndSet(cur, cur - amountCents));
        successLog.add(uid + "-" + amountCents);
        return true;
    }
    public static void main(String[] a) throws Exception {
        // 场景：余额 100 元（10000 分），100 个并发线程各扣 10 元 → 恰好成功 10 笔，零超扣
        accounts.put("u1", new AtomicLong(10_000));
        var pool = Executors.newFixedThreadPool(50);
        var ok = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < 100; i++) pool.submit(() -> { if (deduct("u1", 1_000)) ok.incrementAndGet(); });
        pool.shutdown(); pool.awaitTermination(1, TimeUnit.MINUTES);
        long finalBal = accounts.get("u1").get();
        System.out.printf("并发 100 笔×10 元 → 成功 %d 笔 / 最终余额 %.2f 元（应=0.00）%n",
                ok.get(), finalBal / 100.0);
        // 输出：成功 10 笔 / 最终余额 0.00 元 ——条件更新的原子判定杜绝超扣
        // 对照反例（错误实现注释）：if (bal.get() >= amt) { bal.set(bal.get() - amt); }
        // ——读-改-写三步在并发下 100 笔可能成功 20+ 笔、余额负数——资损的经典来源

        // 资损推演表（余额分存储的为什么）：
        System.out.println("""
            ══ 资损推演速查（沿资金流逐站）══
            充值站: 回调重放→状态机幂等 | 伪造→验签 | 金额错→核对
            支付站: 并发超扣→条件更新 | 双击→幂等键 | 金额→分存储
            退款站: 重试→refund_no唯一 | 超退→累计可退校验
            结算站: 重复结算→batch_no唯一 | 费率→变更审批+试算
            通用: 哨兵=试算平衡≠0 / 退款率3σ / 高频账户告警""");
    }
}
```

```text
《资损防控清单》（docs/rfc/loss-prevention-checklist.md——支付域预案，day20 RFC §安全引用）：
| # | 场景 | 事前设计 | 事中拦截 | 事后发现 | 应急动作 |
|---|------|---------|---------|---------|---------|
| 1 | 重复支付 | 幂等键=订单号 | 状态机+条件更新 | 对账长款 | 幂等应答 |
| 2 | 并发超扣 | 金额分存储 | WHERE balance>=? | 试算平衡告警 | 冻结账户 |
| 3 | 重复退款 | refund_no 约束 | 累计可退校验 | 退款率 3σ 告警 | 退款熔断 |
| 4 | 金额篡改 | 签名机制 | 回调金额核对 | 三方对账短款 | 悬单人工 |
| 5 | 内部作恶 | 最小权限 | 双人复核 | append-only 审计 | 权限回收 |
每条三要素齐全（场景/手段/发现）——清单不是知识列表，是"每条都可执行"的预案
```

```powershell
cd D:\mywork\springbootai\learning\month09-system-design\src
javac -encoding UTF-8 -d ../out BalanceService.java ; java -cp ../out BalanceService
# 验证：100 并发扣款恰好成功 10 笔、余额归零、无负数
cd D:\mywork\springbootai ; git add . ; git commit -m "day09-18: fund safety"
```

## 4. 面试连接

**Q：余额扣减怎么保证并发安全？（账户域必考）**
> 红线先说：绝不允许读-改-写三步操作——SELECT 出余额、内存判断、再 UPDATE 回去，这个写法在并发下必然超扣，是资损的经典来源。正确姿势是条件更新单条 SQL：UPDATE account SET balance=balance-? WHERE uid=? AND balance>=?——判定和扣减在数据库层原子完成，余额不足 affected=0 直接返回失败，我用 100 并发各扣 10 元实测：余额 100 元恰好成功 10 笔、最终归零、无负数。方案选型按场景：常规支付用乐观锁条件更新（性能好安全性强）；热点账户加记账缓冲——平台大户每秒几百笔交易，逐笔记账行锁冲突严重，汇总 N 笔批量过账（M8 计数三层的老思想）；秒杀类"先占后付"用 Redis Lua 预扣加异步落账，但要清楚它的弱一致性——宕机丢预扣，靠对账兜底，所以只用于"可补偿"场景。金额存储必须用分（long），浮点精度误差在支付域就是资损——0.1+0.2≠0.3 这个道理，在钱的世界里没有"差不多"。

**Q：怎么系统性防止资损？（P8 视角的体系化回答）**
> 沿资金流逐站推演加三道时间线。推演方法：把资金流拆成充值、支付、退款、结算四站，每站问三个问题——这里能怎么错、错了谁发现、发现了怎么办，穷举出攻击面清单。三道时间线：事前是设计防御——幂等键全覆盖、金额分存储、复式记账让每笔自带平衡校验、敏感操作双人复核；事中是实时拦截——余额条件更新、状态机管控、风控规则（单笔限额、频次、黑名单）；事后是发现恢复——T+1 三方对账兜底，加上实时哨兵指标：试算平衡不等于零意味着总账破平（最严重）、退款率突增超 3 倍标准差、单账户高频交易告警——哨兵的意义是资损正在发生时就叫醒你，而不是等对账 T+1 才知道。全部沉淀为《资损防控清单》，每条五列：场景、事前、事中、事后、应急动作，格式继承 M8 大促预案的三要素——预案不是知识列表，是每条都能照着执行的 runbook。体系化防资损的本质：单点技术（幂等/锁/验签）都只是砖，"沿资金流穷举+三道时间线+闭环清单"才是楼。

## 5. 今日验收清单

- [ ] BalanceService 运行：100 并发零超扣验证
- [ ] 并发三方案对比表（悲观/乐观/预扣）+ 选型依据能讲
- [ ] 资金流四站推演（每站三问）完成
- [ ] 《资损防控清单》五场景五列入库
- [ ] `git add . && git commit -m "day09-18: fund safety"`

---
[← Day 17](day17-对账与差错.md) | [本月目录](README.md) | [Day 19 · 退款与异步化 →](day19-退款与异步化.md)
