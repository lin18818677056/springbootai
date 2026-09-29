# Day 26 · 综合项目双保险：评测守门 + 可观测仪表盘

> **今日目标**：把 day25 总图上的"两条旁路"落到工单系统里——保险①评测回归守门（改动不翻车），保险②可观测日报（运行状况一眼看清）；再做一次端到端演练：故意改坏 Prompt → 看守门员拦住 → 回滚恢复。
> **时长**：脚本 2h / 演练 1.5h / 输出 0.5h
> **今日产出**：`run_eval.ps1` 回归脚本 + `daily_report.ps1` 日报脚本 + 端到端演练记录
> **对照教程**：`ai-learning/07-AI应用架构与安全.md`

## 1. 知识地图（先讲人话）

```
"双保险"不是两个防火墙，是两班人马各干各的活：

保险① 评测回归 = 质检员（管"改动"）
  类比：工厂改了流水线一道工序，质检员拿同一批标准件重测一遍，
        不合格整批拦下——绝不带病出厂。
  工单版：任何人动了 Prompt/模型/参数 → 自动重跑 50 条考卷（day19）
        → 分数和上次比 → 掉分超线就 FAIL，变更不放行。

保险② 可观测日报 = 仪表盘（管"运行"）
  类比：汽车仪表盘——油量/速度/水温，不用你打开引擎盖检查。
  工单版：每天扫一遍 call_log（day20 埋的记账表），出一张日报：
        成本三数 + 四指标（成功率/延迟 P95/内容拦截率/降级触发次数）。

两条保险的关系（面试金句）：
  评测守"上线前的改动"，可观测守"上线后的运行"；
  一个防患于未然，一个亡羊补牢——缺一个都是裸奔。

今天的端到端演练（把保险跑出真金白银的证据）：
  改坏 Prompt（删 Constraint）→ 回归脚本报 FAIL → 回滚 → PASS
  —— 从此"AI 应用迭代怕翻车"变成"有守门员，敢迭代"。
```

## 2. 核心概念（中英对照+大白话）

| 英文 | 中文 | 大白话解释 |
|------|------|-----------|
| Regression Eval | 回归评测 | 每次改动重跑同一套考卷，防止"改好一处坏三处" |
| Baseline | 基线 | 上一次的分数存档，这次的分和它比 |
| Dashboard | 仪表盘 | 关键指标一屏看完（今天先做日报表） |
| SLI / SLO | 指标/目标 | SLI=实际测的数；SLO=承诺的线（如成功率 ≥95%） |
| Audit Log | 审计日志 | call_log 兼职：出了事能回放全过程 |
| Change Gate | 变更闸门 | 评测不过就不许上线的制度（工具+纪律） |

## 3. 动手实操

### 3.1 保险①：回归守门脚本（评测旁路落地）

```powershell
# run_eval.ps1 —— 50 条考卷回归（把 day19 的手跑流程脚本化）
# 思路：读 eval-set.tsv（50 条），逐条调模型，算正确率，和 baseline.txt 比

$pass = 0; $total = 0
Get-Content eval-set.tsv | ForEach-Object {
    $f = $_ -split "`t"              # 每行：工单文本 <TAB> 标准答案
    $resp = curl.exe -s http://localhost:11434/v1/chat/completions `
        -d (转换成请求体的 JSON)      # Prompt 用 day16 最终版
    $pred = 从响应里抠出 category 字段
    if ($pred -eq $f[1]) { $pass++ }
    $total++
}
$score = [math]::Round($pass / $total * 100, 1)
$base  = [double](Get-Content baseline.txt)
"本次得分：$score % | 基线：$base %" | Tee-Object eval-history.txt -Append
if ($score -ge ($base - 2)) { "PASS"  } else { "FAIL - 变更拦截，回滚！" }
```

```
纪律三条：
  ① 基线存档 baseline.txt 只在"评测通过"时才更新（不许随手改）
  ② 容忍线 -2%：小抖动放行（模型本身有随机性 day01），大跳水拦截
  ③ 脚本进 git——守门员自己也要有版本，不然"谁改了考卷"说不清
```

### 3.2 保险②：每日日报脚本（可观测旁路落地）

```powershell
# daily_report.ps1 —— 扫 call_log 出日报（表结构见 day20）
# 成本三数
SELECT SUM(cost) FROM call_log WHERE dt = CURDATE();                    -- 日均
SELECT SUM(cost)/COUNT(*) FROM call_log WHERE dt = CURDATE();           -- 单均
SELECT prompt_head, SUM(cost) FROM call_log WHERE dt=CURDATE()
  GROUP BY prompt_head ORDER BY 2 DESC LIMIT 5;                          -- TOP5
# 四指标
成功率 = status='ok' 的占比 | 延迟 = PERCENTILE_CONT(latency, 0.95)
内容拦截率 = blocked 占比      | 降级触发次数 = status='fallback' 计数
```

```
《日报红灯线》（SLO 落地版，红灯亮了当天处理）：
  成功率 < 95% 红 | P95 延迟 > 8s 红 | 拦截率 > 5% 红 | 降级 > 10 次/天 红
  单均成本连续 3 天涨 > 20% 黄（有人乱调 Prompt 了？）
```

### 3.3 端到端演练：让守门员真拦一次

```powershell
# 第 1 步：故意改坏 —— 把 day16 Prompt 里的 Constraint 段整段删掉
# 第 2 步：跑回归
.\run_eval.ps1
# 预期：得分从 90% 掉到 70% 上下（没了输出约束，category 抠不出来/乱猜）
# 输出：FAIL - 变更拦截，回滚！

# 第 3 步：git checkout 回滚 Prompt → 再跑 → PASS
# 第 4 步：把演练记录（分数曲线+结论）写进项目 README 的"质量保障"节
```

```
演练结论模板：
  时间 / 改动内容 / 基线分 / 回归分 / 判定 / 动作
  → 这张表就是"AI 迭代有质量保障"的面试证据（比空口说"我们很规范"硬一百倍）
```

## 4. 面试连接

**Q：AI 应用迭代快、行为又不确定，你们怎么保证不翻车？（M13 大题）**
> 靠双保险。保险一是评测回归守门：50 条业务考卷基线存档，任何 Prompt、模型、参数的改动必须先过回归，掉分超过容忍线直接拦截回滚——相当于把"上线前怕出事"变成制度，我们实测把分类 Prompt 的输出约束删掉，分数从 90 掉到 70，守门员当场拦下，三分钟回滚恢复。保险二是可观测日报：每天从调用日志出成本三数和四个指标——成功率、延迟 P95、内容拦截率、降级次数，各配红灯线，红灯当天处理，成本连涨三天会亮黄灯提醒查 Prompt。一句话总结：评测管改动，可观测管运行；一个防患未然，一个亡羊补牢。这套东西没有黑科技，就是把传统软件的 CI 和监控平移到 AI 域——AI 应用行为不确定，所以更要用确定性的流程管住它。

## 5. 今日验收清单

- [ ] `run_eval.ps1` 跑通，能输出得分+PASS/FAIL
- [ ] `daily_report.ps1` 跑通，日报四指标齐全
- [ ] 端到端演练完成（改坏→拦截→回滚→恢复，留分数记录）
- [ ] 红灯线四条+黄灯一条能默写
- [ ] `git add . && git commit -m "day13-26: eval-gate-and-dashboard"`
- [ ] 笔记：把"双保险"画在 day25 总图的两条旁路上（图完成闭环）

---
[← Day 25](day25-AI应用架构总览.md) | [本月目录](README.md) | [Day 27 · 压测与降级演练 →](day27-压测与降级演练.md)
