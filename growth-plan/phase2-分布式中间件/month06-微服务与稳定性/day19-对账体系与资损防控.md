# Day 19 · 对账体系与资损防控：最终一致的最后防线

> **今日目标**：理解"为什么对账是最终一致的标配"；搭准实时对账 Job（订单↔积分↔库存三向比对）；做差异分级与处置流程；人为制造差异验证对账能抓到。
> **时长**：对账理论 1.5h / 对账 Job 开发 3h / 资损体系 0.5h
> **今日产出**：对账 Job（可运行）+ 差异分级表 + 一次"抓差异"实验记录

## 1. 知识地图

```
为什么必须有对账（final consistency 的最后一环）：
  消息会丢（MQ 磁盘故障/代码 bug 漏发）/ 消费会漏（幂等表误判/补偿任务挂了）
  ——所有"尽力保证"的手段都有残次品率，对账是唯一能"事后发现"的兜底
  金融级铁律：资金链路可以不一致（短暂），但不允许"不一致而不知道"

对账的两种节奏：
  准实时对账（分钟级）：增量比对最近 N 分钟的数据——早发现早修，用户无感
  T+1 离线对账（日切）：全量对昨天的账——兜住准实时漏掉的，出具"日结报告"
  两者关系：准实时抓"当下"，T+1 抓"漏网+验证准实时本身没瞎"

对账模型（通用三件套）：
  ① 数据源：A 表（订单支付流水）↔ B 表（积分流水）
  ② 对账键：业务单号（不是主键！跨系统的唯一业务标识）
  ③ 比对维度：有无（A 有 B 无/反之）+ 一致（金额/数量相等）
  实现：拉取双方流水按单号 join（SQL 全外连接 or 内存 Map 对齐）→ 差异集合

差异分级与处置（别把所有差异都报警！会疲劳）：
  P0 资损级：金额不一致/库存负数——电话告警+冻结相关业务+人工修数
  P1 不一致：单边账（A 有 B 无）超过 1h 未自愈——IM 告警+自动补偿重试
  P2 可自愈：延迟类差异（消息还在路上）——静默等待下一轮对账（避免误报）
  自愈设计：先自动补偿（重发消息/补偿任务）→ 下一轮对账验证 → 未收敛才升级人工

资损防控体系（对账只是其中一环）：
  事前：资损评审（新链路上线前过一遍守恒公式）
  事中：守恒校验（day17 的 available+frozen==初始）/ 幂等 / 限流
  事后：对账（发现）+ 修数工具（补偿）+ 复盘归档
  ——面试金句："对账不是补救，是度量——它告诉你前面所有手段的残次品率"
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Reconciliation | 对账 |
| Near-Realtime / T+1 Recon | 准实时/日切对账 |
| Recon Key | 对账键（业务单号） |
| One-Sided Record | 单边账（一边有一边无） |
| Difference Grading | 差异分级（P0/P1/P2） |
| Auto-Healing | 自愈（自动补偿后下轮验证） |
| Fund Loss Prevention | 资损防控 |
| Conservation Check | 守恒校验（不变量） |

## 3. 动手实操：三向对账 Job

```java
// mall-order：准实时对账 Job（每 5 分钟，比对最近 30 分钟数据）
@Scheduled(fixedDelay = 300_000)
public void reconcile() {
    LocalDateTime since = LocalDateTime.now().minusMinutes(30);
    // ① 拉双方流水，按业务单号建索引
    Map<String, OrderPay> orders = orderMapper.findPaidSince(since).stream()
            .collect(toMap(OrderPay::getOrderNo, identity()));
    Map<String, PointRecord> points = pointMapper.findSince(since).stream()
            .collect(toMap(PointRecord::getOrderNo, identity()));
    // ② 三类差异检出
    List<String> orderOnly = orders.keySet().stream()          // A有B无：加了订单没加积分
            .filter(k -> !points.containsKey(k)).toList();
    List<String> pointOnly = points.keySet().stream()          // B有A无：反向单边（更严重！）
            .filter(k -> !orders.containsKey(k)).toList();
    List<String> amountDiff = orders.entrySet().stream()       // 双边但金额不一致
            .filter(e -> points.containsKey(e.getKey())
                    && points.get(e.getKey()).getAmount() != e.getValue().getAmount())
            .map(Map.Entry::getKey).toList();
    // ③ 分级处置：反向单边+金额差=P0 告警；正向单边=P1 先补偿再观察
    pointOnly.forEach(no -> alertP0("积分多于订单！单号:" + no));     // 多给用户钱=资损
    amountDiff.forEach(no -> alertP0("金额不一致！单号:" + no));
    orderOnly.forEach(no -> { mqResendOrderPaid(no); alertP1("已补偿重发:" + no); });
    reconcileLog.save(orderOnly, pointOnly, amountDiff);      // 差异留痕（复盘依据）
}
```

```powershell
# 抓差异实验（验证对账真的能工作）：
# ① 正常下单 5 单 → 对账 Job 跑一轮 → 0 差异（基线正确）
# ② 人为制造差异：停掉 marketing 消费者 → 下单 3 单（积分没加）
#     → 对账 Job 报 3 条"正向单边"（P1）+ 自动补偿重发
# ③ 重启 marketing → 补偿消息被消费 → 下一轮对账 0 差异（自愈闭环验证）
# ④ 制造 P0：手动 UPDATE 积分表金额 +100 → 对账报"金额不一致 P0"（电话/IM 告警模拟）
# ⑤ 日切对账（SQL 版）：昨天全量订单 LEFT JOIN 积分流水 WHERE 积分为 NULL OR 金额不等
# T+1 巡检 SQL（贴进 RUNBOOK）：
# SELECT o.order_no FROM order_pay o LEFT JOIN point_record p
#   ON o.order_no = p.order_no AND o.pay_date = CURDATE() - INTERVAL 1 DAY
# WHERE p.id IS NULL;
```

## 4. 面试连接

**Q：你们的最终一致怎么保证不丢数据？**
> 分四层讲：发送可靠（事务消息半消息/本地消息表，M5 落地）、消费可靠（重试+死信+幂等表）、对账兜底（准实时 5 分钟增量对账抓单边与金额差+T+1 全量日切）、自愈处置（P1 差异自动补偿重发，下轮对账验证收敛，未收敛升级 P0 人工）。加一句实话："所有可靠手段都有残次品率——我们统计过对账月报，准实时+补偿的自愈率 99%+，真正人工修数的月均个位数。对账的价值不是修数据，是让'最终一致'的'最终'变成可度量的数字。"（给数字=高级感）

**Q：对账发现差异后怎么处理？会不会误报？**
> 先分级再处置：P0 资损级（金额不一致/反向单边=多给用户钱）直接电话告警冻结业务；P1 单边账先自动补偿（重发消息/补偿任务）+下轮对账验证；P2 延迟类静默——关键设计是"等一轮再报"：对账周期 5 分钟，消息正常也在秒级到，先观察下轮是否自愈，避免把"在路上的数据"误报成事故。防疲劳原则："告警必须配'处置动作'，响铃不处置的告警两周后大家就不看了——所以每条 P1 告警背后挂着自动补偿，P0 才人工。"

## 5. 今日验收清单

- [ ] 对账 Job 运行（准实时 5 分钟一轮）
- [ ] 抓差异实验全过（正向单边/反向单边/金额差三类）
- [ ] 自愈闭环验证：差异→补偿→下轮归零
- [ ] T+1 巡检 SQL 贴进 RUNBOOK
- [ ] 差异分级 P0/P1/P2 表 + 处置策略
- [ ] `git add . && git commit -m "day19: reconciliation"`

---
[← Day 18](day18-消息最终一致整合.md) | [本月目录](README.md) | [Day 20 · 全链路灰度发布 →](day20-全链路灰度发布.md)
