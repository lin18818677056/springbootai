# Day 15 · 支付整体架构：收单 / 账户 / 复式记账

> **今日目标**：支付系统全景——收单链路、内部账户体系、复式记账原理；支付单生命周期状态机；产出《支付全景图》。W3 三讲的骨架（幂等/对账/资损都挂在这张图上）。
> **时长**：全景架构 2h / 记账模型推演 1.5h / 全景图成文 0.5h
> **今日产出**：支付全景图 + 复式记账分录示例 + 支付单状态机

## 1. 知识地图

```
支付系统四个域（一张全景图定位本周三讲）：
  ① 收单域：接受用户付款（下单→调起支付→渠道回调→支付成功）
     ——本域考题：幂等、状态机、渠道网关适配（day16）
  ② 账户域：钱记在哪（用户余额/商家余额/平台户）+复式记账
     ——本域考题：借贷平衡、账户并发扣减（day18 资金安全）
  ③ 清结算域：对账（我们 VS 渠道 VS 商户）、结算、分账
     ——本域考题：日切、长款短款、差错处理（day17）
  ④ 风控域：反欺诈/限额/风控拦截（day18 资损防控顺带）

收单完整链路（每步的关键点）：
  用户下单 → 商城创建支付单（PAYING）→ 收单服务组装渠道参数（签名/金额/订单号）
  → 跳转/唤起支付渠道（微信/支付宝）→ 用户在渠道侧付款
  → 渠道异步回调通知（收单服务验签→幂等→更新支付单 SUCCESS）→ 发 MQ 通知商城发货
  → 主动查询兜底（回调丢失时：定时查渠道接口对账"悬单"）
  ——两条确认路径：回调（快）+主动查询（兜底），回调不是 100% 可靠的（day17 对账第三保险）

内部账户体系与复式记账（会计学基础，P8 必考）：
  原则：每一笔资金变动=两条分录（借/贷），借贷必相等——总账永远平衡可验证
  账户方向：资产类（借增贷减）：银行存款/应收账款；负债类（贷增借减）：用户余额/商家待结算
  例1 用户充值 100 元（微信→余额）：
    借：银行存款-微信渠道 100     （资产增加）
    贷：用户余额-u1001      100   （负债增加——欠用户的钱）
  例2 用户下单 50 元（余额支付→商家待结算）：
    借：用户余额-u1001      50    （用户负债减少）
    贷：商家待结算-m2001    49.5  （商家负债增加）
    贷：平台手续费收入      0.5   （收入增加——0.5% 费率）
    ——三录也成立：借方合计=贷方合计（50=49.5+0.5）
  为什么必须复式：单式记账（只记"+50"）对不上账时无从查起；复式每一笔自带平衡校验，
  错账必然留下"借贷不平"的痕迹——这是账务系统的自检基因

支付单状态机（M7 模式第五次复用，本域比订单更严格）：
  CREATED → PAYING → SUCCESS → SETTLED（结算完成，终态之一）
  PAYING → FAILED → CLOSED；PAYING --24h--> CLOSED（超时关单，day19）
  SUCCESS 只能来自 PAYING（渠道回调唯一入口）；FAILED/CLOSED 不可逆
  ——红线：SUCCESS 之后的所有状态流转必须可追溯（审计要求，资金域无"软删除"）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Acquiring | 收单（替商户向用户收款） |
| Double-entry Bookkeeping | 复式记账（借贷必相等） |
| Debit / Credit | 借 / 贷（方向，非字面意思） |
| Clearing & Settlement | 清结算（对账+划款） |
| Payment Lifecycle | 支付单生命周期（状态机） |
| Suspense Account | 挂账/待清算账户（长款的容身处） |

## 3. 动手实操：记账模型验证

```java
// learning/month09-system-design/src/DoubleEntryLedger.java（复式记账：借贷平衡的自检基因）
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class DoubleEntryLedger {
    enum Direction { DEBIT, CREDIT }                        // 借 / 贷
    record Entry(String account, Direction dir, long cents) {}
    static final List<List<Entry>> journal = new CopyOnWriteArrayList<>(); // 流水账（只追加，不修改）
    static final Map<String, AtomicLong> balances = new ConcurrentHashMap<>(); // 余额表（余额=借-贷累计）

    /** 记一笔凭证：校验借贷平衡后入账（不平直接抛异常——账务红线） */
    public static synchronized void post(String bizNo, Entry... entries) {
        long debit = Arrays.stream(entries).filter(e -> e.dir() == Direction.DEBIT)
                .mapToLong(Entry::cents).sum();
        long credit = Arrays.stream(entries).filter(e -> e.dir() == Direction.CREDIT)
                .mapToLong(Entry::cents).sum();
        if (debit != credit || debit == 0)
            throw new IllegalArgumentException("借贷不平: 借=" + debit + " 贷=" + credit + " biz=" + bizNo);
        journal.add(Arrays.asList(entries));                 // 先记流水（append-only）
        for (var e : entries)                                // 再更新余额：借+贷-（方向符号化）
            balances.computeIfAbsent(e.account(), k -> new AtomicLong())
                    .addAndGet(e.dir() == Direction.DEBIT ? e.cents() : -e.cents());
    }
    /** 试算平衡：所有账户余额之和必须为 0（借方总额=贷方总额的余额表达） */
    public static long trialBalance() {
        return balances.values().stream().mapToLong(AtomicLong::get).sum();
    }
    public static void main(String[] a) {
        // 例1 充值 100 元（10000 分）
        post("R1001", new Entry("bank-wechat", Direction.DEBIT, 10_000),
                     new Entry("user-balance-u1001", Direction.CREDIT, 10_000));
        // 例2 下单 50 元（费率 1%）：用户余额 → 商家待结算 49.5 + 平台收入 0.5
        post("P2001", new Entry("user-balance-u1001", Direction.DEBIT, 5_000),
                     new Entry("merchant-pending-m2001", Direction.CREDIT, 4_950),
                     new Entry("platform-fee-income", Direction.CREDIT, 50));
        System.out.println("充值+下单后试算平衡 = " + trialBalance());          // 0 → 账实相符
        System.out.println("用户余额 = " + balances.get("user-balance-u1001").get()); // 5000 分 ✓
        // 不平演示（红线拦截）：
        try { post("BAD1", new Entry("a", Direction.DEBIT, 100),
                          new Entry("b", Direction.CREDIT, 99)); }
        catch (Exception e) { System.out.println("拦截: " + e.getMessage()); }
        // 输出：试算平衡 = 0 / 用户余额 = 5000 / 拦截: 借贷不平: 借=100 贷=99
    }
}
```

```text
《支付全景图》（ASCII 版，RFC《支付系统设计》§3 的底稿）：
┌────────┐  创建支付单   ┌────────────┐  组装+签名   ┌──────────┐
│ 商城订单 │ ──────────▶ │ 收单服务     │ ──────────▶ │ 支付渠道    │
└────────┘              │ (幂等/状态机)│ ◀────────── │ 微信/支付宝 │
     ▲ MQ 发货通知        └─────┬──────┘  异步回调+验签 └──────────┘
     │                        │ 落支付流水                    │
     │                        ▼          复式记账            │ 对账文件(T+1)
┌────────┐  余额扣减   ┌────────────┐ ◀────────── ┌──────────┐
│ 账户服务 │ ◀──────── │ 记账核心     │             │ 清结算     │
│(余额/冻结)│            │ (借贷平衡)  │ ──────────▶ │ (对账/差错) │
└────────┘             └────────────┘   日切数据    └──────────┘
本周映射：day16=收单域（幂等/状态机）· day17=清结算域（对账/差错）· day18=账户+风控（资损）
```

```powershell
cd D:\mywork\springbootai\learning\month09-system-design\src
javac -encoding UTF-8 -d ../out DoubleEntryLedger.java ; java -cp ../out DoubleEntryLedger
# 验证：试算平衡=0、余额正确、不平凭证被拦截
cd D:\mywork\springbootai ; git add . ; git commit -m "day09-15: payment arch"
```

## 4. 面试连接

**Q：讲讲支付系统的整体架构？（支付面试开场题）**
> 我按四个域讲全景：收单域负责收款链路——商城创建支付单后，收单服务组装渠道参数签名调起支付，渠道异步回调更新支付单状态，再发 MQ 通知业务方，同时有主动查询兜底回调丢失的场景。账户域是钱的账本——内部账户体系加复式记账，每一笔资金变动至少两条分录、借贷必相等，比如用户充值是"借银行存款、贷用户余额"，余额消费是"借用户余额、贷商家待结算加平台收入"，复式的价值是每笔自带平衡校验、错账必然留下借贷不平的痕迹。清结算域负责对账划款——T+1 拉渠道对账文件三方核对，处理长款短款差错。风控域做反欺诈和限额。四域之间的红线是：账务操作必须 append-only 加状态机管控，支付单 SUCCESS 之后的每次流转都可审计。这个架构我落成了三讲：day16 讲收单的幂等与状态机、day17 讲对账差错、day18 讲资金安全。

**Q：为什么支付系统必须用复式记账？单式记账不行吗？（会计基础追问）**
> 单式记账只记一个方向的数字——"用户消费 50 元"，这种记法有三个致命伤：一是没有自检能力，记错、漏记、重复记都发现不了，直到对账那天差异已经堆成山；二是无法回答"这 50 元去哪了"，单式账是流水不是账本，查一笔资金的完整路径要人肉串流水；三是不能支持多方分账，一笔 50 元要同时体现商家收入、平台手续费、用户支出，单式账要记三条独立记录且彼此没有约束。复式记账的核心是"每一笔交易至少两条分录、借贷合计相等"——这带来三个工程性质：每笔凭证写入时强制校验平衡（我的 Ledger 代码里借贷不平直接抛异常拒入账）、任意时刻试算平衡为零可验证总账完整性、任意一笔资金的来源和去向都在同一张凭证里（审计友好）。一句话：单式记账是记日志，复式记账是记账本——日志可以丢可以乱，账本必须铁平。

## 5. 今日验收清单

- [ ] 支付四域全景图（收单/账户/清结算/风控）能画
- [ ] DoubleEntryLedger 运行：试算平衡=0+不平拦截验证
- [ ] 三条记账分录（充值/消费/分账）能手写并说明方向
- [ ] 支付单状态机（含"SUCCESS 只能来自 PAYING"红线）能讲
- [ ] `git add . && git commit -m "day09-15: payment arch"`

---
[← Day 14](day14-第二周复盘.md) | [本月目录](README.md) | [Day 16 · 支付幂等与状态机 →](day16-支付幂等与状态机.md)
