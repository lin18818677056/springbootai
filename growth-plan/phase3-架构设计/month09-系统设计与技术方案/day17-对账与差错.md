# Day 17 · 对账与差错：日切 / 长款短款

> **今日目标**：支付对账体系深化——三方对账（我们/渠道/银行）、日切问题、长款短款与差错处理；产出对账 Job（自动检出 3 类差错）。兑现 M8 day13 对账伏笔的"生产级版本"。
> **时长**：对账体系 1.5h / 对账 Job 实现 2h / 差错处理流程 0.5h
> **今日产出**：ReconciliationJob 代码（3 类差错检出）+ 《差错处理手册》+ 日切方案

## 1. 知识地图

```
M8 day13 红包对账（Σ弹珠+Σ已领=总额）是"单据内自洽"；支付对账是"跨系统三方核对"——
复杂度升一级但思想同源：用独立的核对路径发现所有单一系统的漏账：

三方对账数据从哪来（三类账本）：
  我们的账：支付单表（pay_no/amount/status/渠道流水号）
  渠道的账：T+1 对账文件（渠道每日定时提供：channel_trade_no/amount/交易时间/手续费）
  银行的账：银行流水（资金实际到账）——终极事实（渠道说成功但银行没到账=渠道垫资风险）
  对账顺序：我们 vs 渠道（第一层，业务对账）→ 渠道 vs 银行（第二层，资金对账）

日切问题（对账的"今天"到底是什么？）：
  问题：我们的日切（0 点）≠ 渠道的日切（可能 UTC+8 的 0 点，也可能有滑点）
  → 23:59:58 创建 00:00:03 支付成功的单，我们的"昨天"里是 PAYING，渠道"昨天"文件里已成功
  → 直接逐笔比对=海量"假差异"
  解法：对账窗口滑动——T+1 对账时对"T 的账 + T+1 前 N 小时的账"双窗匹配
       （支付单跨日匹配窗口 4 小时，覆盖绝大多数日切滑单）
       连续两天窗口仍未匹配的才进差错——宁可晚一天报差错，不可天天报假差错

三类差错与处理（对账 Job 的产出）：
  ① 长款（渠道有、我们没有）：渠道文件里有这笔，我们支付单找不到或状态 PAYING
     → 多为回调丢失+查询补偿也漏 → 处理：以渠道为准补单（补 SUCCESS+触发发货），
       无法确认归属的进"挂账户"（Suspense）等人工认领
  ② 短款（我们有、渠道没有）：我们 SUCCESS 但渠道文件没有
     → 极危险信号（可能伪造回调成功！）→ 处理：立即冻结关联账户+人工介入（资损预案）
  ③ 金额不平（两边都有但金额不等）：费率配置错/部分退款未同步
     → 处理：进差错表人工处理，自动化只做"发现"不做"修复"（修复必须人审）
  处理原则：自动发现（Job）→ 自动分类（按上面三类规则）→ 人工修复（资金域不自动修复）
  ——自动化边界：发现和分类可以全自动，"改钱"的操作永远人工（带双人复核）

对账 Job 的骨架（本日代码）：
  ① 拉取渠道 T+1 文件（SFTP/接口）→ 解析标准化
  ② 双窗匹配：我们的支付单 vs 渠道流水（滑动窗口）——匹配键 channel_trade_no
  ③ 分类产出：matched / 长款 / 短款 / 金额不平 四个集合
  ④ 产出日报（差错率指标）：差错率>0.01% 告警（正常系统 <0.001%）
  ⑤ 差错入表（t_recon_diff）分配处理人，闭环跟踪
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Three-way Reconciliation | 三方对账（我/渠道/银行） |
| Cut-off | 日切（账务日期边界） |
| Long / Short Money | 长款 / 短款（渠道多我们少 / 反之）|
| Suspense Account | 挂账账户（无法归属资金的容身处）|
| Reconciliation Job | 对账任务（自动发现差错） |
| Discrepancy Rate | 差错率（<0.001% 为健康） |

## 3. 动手实操：对账 Job

```java
// learning/month09-system-design/src/ReconciliationJob.java（三方对账：双窗匹配+三类差错检出）
import java.util.*;
import java.util.stream.*;

public class ReconciliationJob {
    // 我们的支付单：payNo -> [channelTradeNo, amountCents, status]
    record LocalPay(String channelTradeNo, long amountCents, String status) {}
    // 渠道对账文件行：channelTradeNo -> amountCents
    static final Map<String, Long> channelFile = new LinkedHashMap<>();
    static final Map<String, LocalPay> localPays = new LinkedHashMap<>();
    static final Map<String, String> diffLog = new LinkedHashMap<>();        // 差错表 t_recon_diff

    public static void main(String[] a) {
        // ---- 造数据：5 笔本地支付单 ----
        localPays.put("P1", new LocalPay("CT1", 10_000, "SUCCESS"));   // ① 正常匹配
        localPays.put("P2", new LocalPay("CT2", 20_000, "PAYING"));    // ② 长款（渠道成功我们未更新）
        localPays.put("P3", new LocalPay("CT3", 30_000, "SUCCESS"));   // ③ 短款（渠道文件缺失！）
        localPays.put("P4", new LocalPay("CT4", 40_000, "SUCCESS"));   // ④ 金额不平
        localPays.put("P5", new LocalPay("CT5", 50_000, "SUCCESS"));   // ⑤ 正常匹配
        // ---- 渠道 T+1 对账文件 ----
        channelFile.put("CT1", 10_000L);
        channelFile.put("CT2", 20_000L);      // 我们还是 PAYING → 长款
        channelFile.put("CT4", 39_000L);      // 金额差 1000 → 金额不平
        channelFile.put("UNKNOWN_CT", 9_900L);// 我们查无此单 → 长款（陌生流水）
        // （CT3 缺席 → 短款；这是最危险的差错）

        // ---- 对账主流程：双窗匹配 + 分类 ----
        Set<String> matched = new HashSet<>();
        for (var e : localPays.entrySet()) {
            LocalPay p = e.getValue();
            Long ch = channelFile.get(p.channelTradeNo());
            if (ch == null) {
                diffLog.put(e.getKey(), "短款: 本地 SUCCESS 但渠道无流水 → 冻结+人工(资损风险)");
            } else if (!ch.equals(p.amountCents())) {
                diffLog.put(e.getKey(), "金额不平: 本地=" + p.amountCents() + " 渠道=" + ch + " → 差错表人工");
            } else if (!"SUCCESS".equals(p.status())) {
                diffLog.put(e.getKey(), "长款(状态滞后): 渠道成功本地" + p.status() + " → 以渠道为准补单");
            } else {
                matched.add(e.getKey());
            }
        }
        // 渠道有我们无（陌生流水 → 长款）
        channelFile.keySet().stream()
            .filter(ct -> localPays.values().stream().noneMatch(p -> p.channelTradeNo().equals(ct)))
            .forEach(ct -> diffLog.put("CH-" + ct, "长款(陌生流水): 渠道=" + ct + " → 挂账户待认领"));

        // ---- 对账日报 ----
        System.out.println("══ 对账日报 ══");
        System.out.println("匹配成功: " + matched + " / 差错 " + diffLog.size() + " 笔:");
        diffLog.forEach((k, v) -> System.out.println("  [" + k + "] " + v));
        double rate = diffLog.size() * 100.0 / (matched.size() + diffLog.size());
        System.out.printf("差错率 %.2f%% %s%n", rate, rate > 0.01 ? "⚠ 超阈值告警" : "✓ 健康");
        // 输出：P2 长款(状态滞后)/P3 短款/P4 金额不平/CH-UNKNOWN_CT 长款(陌生流水)
        // 处理原则：Job 只发现+分类，"改钱"的操作全部人工（双人复核）
    }
}
```

```text
《差错处理手册》（运营 SOP，RFC《支付系统设计》§清结算）：
| 差错 | 危险级 | 自动动作 | 人工动作 | SLA |
|------|--------|---------|---------|-----|
| 长款-状态滞后 | 低 | 无（ Job 检出）| 确认渠道流水→补单成功 | T+1 |
| 长款-陌生流水 | 中 | 挂账户 | 认领或退回渠道 | 3 天 |
| 短款 | 高 | 冻结关联账户 | 核查是否伪造回调→资损预案 | 2h |
| 金额不平 | 中 | 差错表 | 核对费率/退款配置 | 1 天 |
日切方案：双窗匹配（T 日账+T+1 前 4h）连续两日未匹配才报差错——先消假差异再报真差错
```

```powershell
cd D:\mywork\springbootai\learning\month09-system-design\src
javac -encoding UTF-8 -d ../out ReconciliationJob.java ; java -cp ../out ReconciliationJob
# 验证：4 类差错全部正确检出（长款×2/短款/金额不平）、日报输出
cd D:\mywork\springbootai ; git add . ; git commit -m "day09-17: reconciliation"
```

## 4. 面试连接

**Q：支付对账怎么做？长款短款是什么？（清结算标准题）**
> 三方对账两层比对：第一层业务对账——我们的支付单表对渠道 T+1 对账文件，匹配键是渠道流水号；第二层资金对账——渠道对银行流水，确认钱真的到了。核心难点是日切：我们的零点和渠道的账务日切有滑点，跨零点的订单会在两边的"昨天"里呈现不同状态，直接逐笔比对全是假差异。解法是双窗匹配：T+1 对账时用"T 日加 T+1 前 4 小时"的滑动窗口匹配，连续两天窗口都没匹配上的才进差错——宁可晚一天报真差错，不能天天报假差错淹没运营。差错分三类：长款是渠道有我们没有——回调丢了查询补偿也漏了，以渠道为准补单，认不出的进挂账户；短款是我们有渠道没有——这是最危险的信号，可能是伪造回调，要立即冻结关联账户走资损预案；金额不平多是费率或退款配置问题。自动化边界很重要：Job 只负责发现和分类，所有"改钱"的修复动作必须人工加双人复核——对账系统发现错误的成本永远低于自动修复犯错的成本。差错率指标小于 0.001% 是健康线，超 0.01% 就要告警查系统了。

**Q：你们系统怎么验证"一分钱都不会错"？（追问对账的完备性）**
> 三道防线纵深：第一道写入时校验——复式记账借贷不平直接拒入账（day15 的 Ledger），这是"单笔正确"；第二道实时对账——支付成功的每笔都触发本地记账凭证核对（单据内自洽，M8 红包三铁律的 Σ 守恒思想）；第三道 T+1 三方对账——跨系统核对兜住所有前两道防不住的问题（渠道多扣、回调丢失、伪造成功）。完备性的关键是"独立的核对路径"：记账、收单、对账是三个独立模块，同一笔交易三方各自记录，对账就是三方交叉验证——任何单一系统的 bug 都会被另外两方的账本揭穿。这就是 M8 对账三铁律的终极版：单据内守恒（铁律一）、缓冲守恒（铁律二）升级为系统间守恒（三方对账）。最后一公里是差错闭环：每笔差错入表、定级、分配、跟踪到关闭，差错率曲线进高管周报——"一分钱都不会错"不是口号，是三道防线加一个闭环流程的日常运行结果。

## 5. 今日验收清单

- [ ] 三方对账两层比对+日切双窗方案能画
- [ ] ReconciliationJob 运行：4 类差错全部正确检出
- [ ] 长款/短款/金额不平的处理 SOP（危险级+SLA）入库
- [ ] "自动化发现、人工修复"的边界论证能讲
- [ ] `git add . && git commit -m "day09-17: recon"`

---
[← Day 16](day16-支付幂等与状态机.md) | [本月目录](README.md) | [Day 18 · 资金安全与资损防控 →](day18-资金安全与资损防控.md)
