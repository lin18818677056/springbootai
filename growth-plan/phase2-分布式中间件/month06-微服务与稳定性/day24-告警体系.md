# Day 24 · 告警体系：分级、收敛与可执行

> **今日目标**：部署 Alertmanager；配置 5 条分级告警规则（接 day13 的 RUNBOOK 预案表）；掌握告警三原则——可执行、分级、防疲劳；理解分组/抑制/静默。
> **时长**：部署配置 2h / 告警规则 1.5h / 告警哲学 1.5h
> **今日产出**：5 条分级告警规则 + 告警演练记录 + 告警设计清单

## 1. 知识地图

```
告警的定位：监控大盘是"人看"，告警是"机器找人"——值班的人不能一直盯盘
  告警三原则（Google SRE 思想，比工具重要）：
  ① 可执行（Actionable）：每条告警必须对应"收到后能做的动作"
     好告警："payment 服务 P99>2s 持续 5min——看 RUNBOOK#3：先查下游支付网关"
     坏告警："CPU 85%"——看完不知道该干嘛（CPU 高可能是正常压测/编译/挖矿…）
  ② 分级（Severity）：按"打扰人的方式"分级——P0 电话叫醒 / P1 IM 弹窗 / P2 邮件日报
     分级依据：影响面×资损程度，不是指标类型
  ③ 防疲劳（Fatigue）：告警疲劳 = 狼来了 = P0 也没人看了
     手段：分组（同源告警合并成一条）/ 抑制（高级别触发时压掉低级别）/
          静默（变更窗口手动静默）/ 持续时间门槛（for: 5m 防抖）

Alertmanager 工作流：
  Prometheus 规则评估（PromQL 持续查询，超阈值 → firing）
    → 推给 Alertmanager → 分组（group_by）→ 路由（route 树按 severity/receiver 分发）
    → 抑制（inhibit_rules）→ 静默检查 → 通知（webhook/钉钉/企微/邮件）
  关键参数：group_wait（首次等待 30s 攒批）/ group_interval（同组再通知间隔）/
           repeat_interval（未处理重复通知周期——P0 要短）

分级告警设计（商城实战配置，接 day13 RUNBOOK）：
  P0（电话）：支付成功率<90% 持续 2min / 核心接口错误率>5% / 对账 P0 差异（day19）
  P1（IM）  ：核心接口 P99>1s 持续 5min / 限流触发率>10% / 对账 P1 单边账 / MQ 积压>1万
  P2（邮件）：磁盘>80% / 证书 7 天过期 / JVM FullGC>10 次/小时
  ——每条告警的 annotations 里写 runbook 链接（告警→预案，day13 的表在这里闭环）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Alerting Rule | 告警规则（PromQL 表达式） |
| Pending / Firing | 触发中/已触发（告警状态机） |
| Alertmanager | 告警管理器（分组/路由/静默） |
| Grouping | 分组（同源合并） |
| Inhibition | 抑制（高级别压低级别） |
| Silence | 静默（窗口期免打扰） |
| Severity | 告警级别（P0/P1/P2） |
| Runbook | 处置手册（告警的可执行性来源） |
| Alert Fatigue | 告警疲劳 |

## 3. 动手实操：5 条分级告警

```yaml
# prometheus 规则文件（alert-rules.yml）：
groups:
  - name: mall-core
    rules:
      # P1：核心接口 P99 超 1s（for: 5m 防抖——瞬时毛刺不告警）
      - alert: OrderP99High
        expr: histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{uri="/order/create"}[1m])) by (le)) > 1
        for: 5m
        labels: { severity: P1 }
        annotations:
          summary: "下单接口 P99 超 1s"
          runbook: "RUNBOOK.md#product变慢 —— 先看 SkyWalking 慢调用，再查 DB"
      # P0：支付成功率跌破 90%
      - alert: PaySuccessRateDrop
        expr: sum(rate(mall_pay_result_total{result="success"}[2m]))
            / sum(rate(mall_pay_result_total[2m])) < 0.9
        for: 2m
        labels: { severity: P0 }
        annotations:
          runbook: "RUNBOOK.md#支付异常 —— 检查支付渠道回调，准备降级到备用渠道"
      # P1：限流触发率>10%（day09 blockHandler 的 BlockException 计数）
      - alert: RateLimitHot
        expr: sum(rate(sentinel_block_total[5m])) / sum(rate(http_server_requests_seconds_count[5m])) > 0.1
        for: 10m
        labels: { severity: P1 }
        annotations:
          runbook: "流量异常 or 阈值过低——对照容量压测报告判断"
      # P0：待支付订单堆积（业务积压，比技术指标早暴露）
      - alert: PendingOrderBacklog
        expr: mall_order_pending_count > 10000
        for: 5m
        labels: { severity: P0 }
        annotations:
          runbook: "RUNBOOK.md#订单积压 —— 支付服务挂了？看 MQ 积压面板"
      # P2：FullGC 频繁
      - alert: FullGCFrequent
        expr: increase(jvm_gc_pause_seconds_count{action="end of major GC"}[1h]) > 10
        for: 0m
        labels: { severity: P2 }
```

```yaml
# alertmanager.yml（路由+抑制）：
# route:
#   receiver: email-default
#   group_by: [alertname, severity]
#   routes:
#     - match: { severity: P0 }
#       receiver: phone-webhook      # 电话网关 webhook
#       repeat_interval: 5m          # P0 每 5 分钟催一次
#     - match: { severity: P1 }
#       receiver: im-webhook         # 钉钉/企微机器人
# inhibit_rules:                     # 抑制：服务挂了（P0）时压掉它的 P99 告警（P1）
#   - source_match: { severity: P0 }
#     target_match: { severity: P1 }
#     equal: [application]
```

```powershell
# 演练实验：
# ① 压测把 P99 打上 1.5s → Prometheus Alerts 面板看 pending→firing（for:5m 生效）
# ② 5 分钟后 IM 收到告警卡片（webhook 调试：先配 httpbin.org 抓报文看结构）
# ③ 制造支付失败（人为改回调）→ 支付成功率跌破 90% → P0 通道触发
# ④ 抑制验证：停掉一个服务触发 P0 → 对应 P1 告警被抑制不再弹
# ⑤ 静默验证：控制台创建 silence（演练窗口）→ 告警不再通知
# 体验记录：告警从"指标异常"到"IM 弹窗"的完整链路截图（这是一条可写进简历的链路）
```

## 4. 面试连接

**Q：你们的告警体系怎么设计的？怎么避免告警泛滥？**
> 设计按三原则：可执行——每条告警 annotations 带 runbook 链接，值班收到即知道第一步动作（我们 RUNBOOK 里场景-指标-动作-验证四列，day13 那张表）；分级——P0 电话（支付成功率/核心错误率/对账资损差异）、P1 IM（P99 超 1s/限流热/MQ 积压）、P2 邮件（FullGC/磁盘）；防疲劳——group_by 合并同源告警、inhibit 规则让"服务挂了"压掉"它 P99 高"的衍生告警、for: 5m 防瞬时毛刺、变更窗口手动静默。收尾哲学："我们定过铁律——每个 P0/P1 告警上线前必须演练一次'收到后怎么办'，答不上来的告警不准上线；告警是给人和流程用的，不是给dashboard 截图用的。"

**Q：一条告警从指标异常到通知到人，中间发生了什么？**
> 完整链路：服务暴露指标 → Prometheus 每 15s 抓取 → 告警规则持续评估 PromQL，超阈值进 Pending（等 for 指定的持续时间，比如 5m，防抖）→ 持续超阈值转 Firing → 推送 Alertmanager → 按 route 树匹配 severity 选 receiver → group_wait 攒批分组（30s）→ 过抑制规则（P0 存在时压制同应用 P1）→ 过静默列表 → 发通知（电话 webhook/IM 机器人）。追问"告警延迟多少"：抓取 15s+评估 15s+for 5m+group_wait 30s ≈ 6 分钟——所以 P0 场景我会把抓取间隔和 group_wait 调小，"延迟与防抖是天平，按业务容忍度调"。

## 5. 今日验收清单

- [ ] 5 条分级告警配置完成（P0×2/P1×2/P2×1）
- [ ] pending→firing→通知全链路演练截图
- [ ] 抑制与静默实验通过
- [ ] 告警三原则能结合实例讲
- [ ] "告警必须配 runbook"写进团队规范笔记
- [ ] `git add . && git commit -m "day24: alerting"`

---
[← Day 23](day23-Prometheus与RED.md) | [本月目录](README.md) | [Day 25 · EFK日志体系 →](day25-EFK日志体系.md)
