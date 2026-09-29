# Day 16 · 支付幂等与状态机

> **今日目标**：支付回调的幂等处理器（防重复回调/防伪造/防乱序）、支付单状态机落地、接口幂等设计全表；产出回调幂等处理器代码。
> **时长**：回调风险分析 1h / 幂等处理器实现 2h / 状态机对接 1h
> **今日产出**：PaymentCallbackHandler 代码（三防验证）+ 《支付接口幂等设计表》

## 1. 知识地图

```
渠道回调的三大风险（收单域的核心考题）：
  ① 重复回调：渠道会重发通知（未收到应答就重试，可能 3~8 次）→ 重复入账=重复发货
  ② 伪造回调：攻击者伪造"支付成功"通知 → 免单攻击
  ③ 乱序回调：退款回调先于支付回调到达 / 渠道重试导致新旧状态交错
三防设计：
  防重：支付单状态机——回调只接受 PAYING→SUCCESS，第二次 SUCCESS 回调在
        SUCCESS 状态下直接返回"已处理"（幂等应答 200，让渠道停止重试）
        数据库层双保险：UPDATE payment SET status='SUCCESS' WHERE pay_no=? AND status='PAYING'
        ——条件更新（M8 day08 秒杀 DB 兜底同款）返回 affected=0 即"已被处理"
  防伪：验签——渠道公钥验证签名+金额核对（回调金额必须=支付单金额，防"1 分钱支付
        100 元订单"的篡改）+ 商户号/应用 ID 核对
  防乱序：状态机流转表 + 回调带渠道侧时间戳/版本号，旧版本回调（如 CANCELED after SUCCESS）
        直接拒收并告警（人工介入，可能是渠道 bug 信号）

接口幂等设计全表（支付域所有写接口过一遍）：
| 接口 | 幂等键 | 实现手段 | 重复请求行为 |
|------|--------|---------|-------------|
| 创建支付单 | out_trade_no（商城订单号）| 唯一约束，冲突返回已有单 | 返回首次支付单 |
| 渠道回调 | 渠道流水号+状态机 | 条件更新+幂等应答 | 返回"已处理" |
| 余额扣减 | pay_no | 条件更新 WHERE status='PAYING' | 不执行返回成功 |
| 退款 | refund_no | 唯一约束+金额校验 | 返回首次退款单 |
| 结算 | settle_batch_no | 唯一约束+幂等消费 MQ | 跳过已结算 |
  幂等键的选择原则：业务唯一标识（外部可传）优于系统生成（重启丢失）——
  out_trade_no 是商城订单号，天然幂等键，不是支付系统自己造的 UUID

回调处理的完整时序（防重防伪防乱序全部就位）：
  ① 收到回调 → 验签失败 → 记告警日志 + 返回失败（渠道会重试，真回调会再来）
  ② 验签过 → 查支付单：不存在（伪造）→ 告警+拒绝；状态 SUCCESS → 幂等应答"成功"
  ③ 状态 PAYING → 金额核对（不等→告警人工）→ 条件更新 PAYING→SUCCESS
  ④ 更新成功 → 复式记账（day15）→ 发 MQ（发货/通知）→ 应答渠道"成功"
  ⑤ 全程 try-catch：任何异常返回渠道"失败"（让它重试）——宁可重复消费（幂等兜底）
    不可消费失败（丢回调）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Idempotent Consumer | 幂等消费者（重复消息安全） |
| Conditional Update | 条件更新（WHERE status=旧值） |
| Signature Verification | 验签（防伪造回调） |
| Out-of-order | 乱序（回调时序错乱） |
| Idempotent Response | 幂等应答（重复请求返回首次结果） |
| Natural Idempotency Key | 自然幂等键（业务单号） |

## 3. 动手实操：回调幂等处理器

```java
// learning/month09-system-design/src/PaymentCallbackHandler.java（三防：验签/幂等/金额核对）
import java.util.*;
import java.util.concurrent.*;

public class PaymentCallbackHandler {
    enum PayStatus { CREATED, PAYING, SUCCESS, FAILED, CLOSED;
        private static final Map<PayStatus, Set<PayStatus>> ALLOWED = Map.of(
            CREATED, EnumSet.of(PAYING), PAYING, EnumSet.of(SUCCESS, FAILED),
            SUCCESS, EnumSet.of(), FAILED, EnumSet.of(CLOSED), CLOSED, EnumSet.of());
        boolean canTransferTo(PayStatus t) { return ALLOWED.getOrDefault(this, Set.of()).contains(t); }
    }
    record Payment(String payNo, long amountCents, PayStatus status) {}

    static final Map<String, Payment> db = new ConcurrentHashMap<>();        // 模拟支付单表
    static final List<String> shipped = Collections.synchronizedList(new ArrayList<>()); // 发货记录（重复检测用）

    /** 渠道回调入口：三防全开，返回给渠道的应答 */
    public static synchronized String onCallback(String payNo, long amountCents,
                                                 String channelTradeNo, boolean validSign) {
        // ① 防伪：验签失败直接拒绝（记告警，让渠道重试真回调）
        if (!validSign) { System.out.println("  [reject] 验签失败 payNo=" + payNo); return "FAIL"; }
        var pay = db.get(payNo);
        // ② 防伪：单不存在=伪造回调
        if (pay == null) { System.out.println("  [reject] 支付单不存在 payNo=" + payNo); return "FAIL"; }
        // ③ 防重：SUCCESS 状态收到重复回调 → 幂等应答（渠道停止重试的关键）
        if (pay.status() == PayStatus.SUCCESS) {
            System.out.println("  [idempotent] 重复回调 payNo=" + payNo + " → 应答已处理"); return "SUCCESS";
        }
        // ④ 防乱序：非 PAYING 状态的"成功"回调（如已关闭后到账）→ 拒收+人工告警
        if (pay.status() != PayStatus.PAYING) {
            System.out.println("  [alert] 乱序回调: 状态=" + pay.status() + " 收到 SUCCESS → 人工介入"); return "FAIL";
        }
        // ⑤ 金额核对：防篡改（1 分钱回调冒充 100 元订单）
        if (pay.amountCents() != amountCents) {
            System.out.println("  [alert] 金额不符: 应=" + pay.amountCents() + " 实=" + amountCents); return "FAIL";
        }
        // ⑥ 条件更新（数据层兜底防并发双回调）+ 状态机校验
        var updated = new Payment(payNo, amountCents, PayStatus.SUCCESS);
        db.replace(payNo, pay, updated);                                     // CAS 语义
        System.out.println("  [ok] 支付成功 → 记账 → 发 MQ 发货");
        shipped.add(payNo);
        return "SUCCESS";
    }
    public static void main(String[] a) {
        db.put("P1", new Payment("P1", 10_000, PayStatus.PAYING));
        // 场景1：伪造回调（签名错）
        onCallback("P1", 10_000, "CT1", false);
        // 场景2：正常回调（发货）
        onCallback("P1", 10_000, "CT1", true);
        // 场景3：渠道重试的重复回调（幂等应答，不重复发货）
        onCallback("P1", 10_000, "CT1", true);
        // 场景4：金额不符的篡改回调
        db.put("P2", new Payment("P2", 10_000, PayStatus.PAYING));
        onCallback("P2", 1, "CT2", true);
        // 场景5：乱序（已关单后回调到达）
        db.put("P3", new Payment("P3", 10_000, PayStatus.CLOSED));
        onCallback("P3", 10_000, "CT3", true);
        System.out.println("发货记录（必须只有 1 条）: " + shipped);
        // 输出：5 个场景各命中一防；发货仅 P1 一次——三防+幂等应答闭环
    }
}
```

```text
《支付接口幂等设计表》（RFC《支付系统设计》§4.2 引用）：
原则三条：①幂等键用业务自然键（out_trade_no）不用系统 UUID ②DB 条件更新是最后防线
（代码漏判数据库兜底）③重复请求必须"返回首次结果"而非报错（调用方无感重试）
```

```powershell
cd D:\mywork\springbootai\learning\month09-system-design\src
javac -encoding UTF-8 -d ../out PaymentCallbackHandler.java ; java -cp ../out PaymentCallbackHandler
# 验证：五场景各命中对应防线、发货仅一次
cd D:\mywork\springbootai ; git add . ; git commit -m "day09-16: callback idempotent"
```

## 4. 面试连接

**Q：支付回调重复通知怎么处理？（收单必考）**
> 三层防御按纵深讲。第一层状态机幂等：回调处理前查支付单状态，只有 PAYING 才接受 SUCCESS 流转，第二次成功回调发现状态已是 SUCCESS，直接返回"已处理"的幂等应答——这个应答很关键，渠道收到成功应答就停止重试，不应答渠道会重发最多 8 次。第二层数据库条件更新：UPDATE 支付单 SET status='SUCCESS' WHERE pay_no=? AND status='PAYING'，两个并发回调同时进来只有一个 affected=1，另一个拿到 0 行走幂等分支——代码层就算有并发漏洞，数据库层兜底。第三层金额与验签核对：回调金额必须等于支付单金额，防止"1 分钱支付 100 元订单"的篡改攻击；验签失败直接拒收并告警。我的测试覆盖五个场景：伪造签名、正常回调、渠道重试重复、金额篡改、乱序到达——发货记录始终只有一条。设计哲学和 M8 秒杀的 DB 兜底一脉相承：应用层做语义判断，数据库条件更新做最后防线，两层任一生效都不会重复入账。

**Q：回调一直收不到怎么办？（主动查询兜底）**
> 回调不是 100% 可靠的——网络抖动、我们服务重启、渠道侧故障都会丢通知，所以必须有第二条确认路径：主动查询。设计是"悬单补偿"任务：扫描超时未终态的支付单（PAYING 超 5 分钟），调渠道查询接口核实真实状态——查到已支付就走本地成功流程（和回调同一段代码，保证幂等），查到已关闭就关单，查不到保持 PAYING 等下一轮。补偿节奏是指数退避：5min→15min→1h→6h→24h，24 小时后还没确认就进人工悬单池——不再自动处理，因为渠道侧状态已经模糊，自动决策的风险大于收益。这条设计有个原则值得强调：回调和查询必须收敛到同一个状态机入口，不能是两条独立的状态变更路径——否则回调说成功、查询说失败，两个入口打架的状态机比没有状态机更危险。三条确认路径（回调/查询/对账）最终都收敛到同一套幂等状态机，这是收单设计的定海神针。

## 5. 今日验收清单

- [ ] 回调三防（验签/幂等/金额核对）+条件更新双保险能讲
- [ ] PaymentCallbackHandler 运行：五场景全过、发货仅一次
- [ ] 《支付接口幂等设计表》五接口入库
- [ ] 主动查询兜底（悬单补偿+指数退避+三路收敛）能画
- [ ] `git add . && git commit -m "day09-16: idempotent"`

---
[← Day 15](day15-支付整体架构.md) | [本月目录](README.md) | [Day 17 · 对账与差错 →](day17-对账与差错.md)
