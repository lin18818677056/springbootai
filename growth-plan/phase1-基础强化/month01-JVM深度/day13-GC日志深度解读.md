# Day 13 · GC 日志深度解读

> **今日目标**：GC 日志是 JVM 的"体检报告"。今天学会统一日志语法、逐行拆解 G1 混合回收日志，并用 GCEasy 产出你的第一份自动化分析报告。
> **时长**：理论 1h / 实操 1.5h / 输出 1h
> **今日产出**：《GC 日志分析报告》第一版（day27 调优报告的底稿）

## 1. 知识地图

```
统一日志 Unified Logging 语法（JDK9+，旧 PrintGCDetails 已废）：
  -Xlog:<what>[:<output>[:<decorators>[:<options>]]]

生产推荐配置（背下来直接用）：
  -Xlog:gc*:file=logs/gc-%t.log:time,uptime,level,tags:filecount=5,filesize=50m
        │    │              │                      │
        │    └ 输出到文件     └ 前缀装饰：时间/运行时长/级别/标签
        └ gc* = gc 及其子标签（gc+age, gc+humongous...全开）
  滚动：5 个文件 × 50MB，防磁盘打爆

GC 健康三指标（面试标准答案）：
  ① GC 频率：Young GC 每 10~30 秒一次算正常；几秒一次=分配过猛/堆太小
  ② 单次停顿：Young < 50ms 常见；Mixed 偶发 100~200ms 可接受；Full > 1s = 告警
  ③ 回收效果：每次回收后堆占用应显著回落；回收完还是高 ≈ 泄漏或活对象真多

日志里的危险信号（看见就警觉）：
  ⚠ Pause Full (G1 Compaction Pause)      → FullGC！查晋升/泄漏/Humongous
  ⚠ "To-space exhausted"                  → Survivor/老年代放不下晋升对象
  ⚠ Humongous regions 持续增长            → 大对象风暴（day11）
  ⚠ Pause ... 2000ms+                     → 停顿超标，考虑换 ZGC/查 swap
  ⚠ Metaspace 持续增长不回落              → 类加载器泄漏（day25）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Unified Logging (UL) | 统一日志 | JDK9+ 的 -Xlog 体系，取代 PrintGCDetails |
| Decorator | 日志装饰器 | time/uptime/level/tags 等前缀 |
| GCEasy / GCViewer | GC 日志分析工具 | 上传日志自动出图表与建议 |
| Promotion Failed | 晋升失败 | 老年代放不下晋升对象，FullGC 元凶之一 |
| Allocation Rate | 分配速率 | 单位时间分配量，决定 YGC 频率的核心变量 |

## 3. 动手实操

### 3.1 采集一份"有故事"的日志（15 分钟）

```powershell
cd D:\mywork\springbootai\learning\month01-jvm; mkdir day13 -Force; cd day13
# 用压力最大的组合：小堆 + 大对象（必出 Mixed/Humongous 事件）
Copy-Item ..\day05\AllocDemo.java .
javac AllocDemo.java
java -Xms256m -Xmx256m `
  -Xlog:gc*:file=gc-%t.log:time,uptime,level,tags:filecount=3,filesize=20m `
  AllocDemo
# 跑 5 分钟后 Ctrl+C 停止
Get-ChildItem gc-*.log        # 确认日志文件生成
```

### 3.2 逐行拆解 Mixed GC（把这段抄进笔记并标注）

```
[2026-09-23T10:15:32.123+0800][12.345s] GC(45) Pause Young (Concurrent Start) (G1 Humongous Allocation)
                                            │        │               └ 触发原因：巨型对象分配！
                                            │        └ 这次 Young GC 顺带启动并发标记周期
[2026-09-23T10:15:32.130+0800][12.352s] GC(45) Pause Young ... 68M->30M(256M) 6.2ms
[2026-09-23T10:15:32.131+0800][12.353s] GC(46) Concurrent Mark Cycle 145.6ms   ← 并发，不暂停
[2026-09-23T10:15:33.301+0800][13.523s] GC(49) Pause Young (Prepare Mixed) ...  ← 准备混合回收
[2026-09-23T10:15:33.500+0800][13.722s] GC(50) Pause Cleanup ...                 ← 标记收尾
[2026-09-23T10:15:33.510+0800][13.732s] GC(51) Pause Mixed (G1 Evacuation Pause) ...
                                             └ 混合回收：清高性价比老年代 Region
[2026-09-23T10:15:34.101+0800][14.323s] GC(55) Pause Young (Normal)             ← 标记周期结束
```

### 3.3 GCEasy 自动化分析（5 分钟出报告）

```powershell
# 打开浏览器访问 https://gceasy.io  → 上传 gc-*.log
```

**报告中必看四张图/表**（记录进你的分析报告）：
1. GC Duration 曲线 → 有无停顿尖刺（>200ms 的点）
2. Heap After GC / Before GC 折线 → 回收后基线是否持续抬升（泄漏信号）
3. Key Statistics 表 → Young GC 次数/总耗时、Full GC 次数（理想=0）
4. GCEasy 给出的 Recommendations → 与你自己的判断对照

### 3.4 撰写《GC 日志分析报告 v1》（day27 的模板）

```
一、环境：JDK25 / G1 / -Xms256m -Xmx256m / AllocDemo（1MB/s 分配）
二、采集：15:00-15:05，gc-2026-09-23_10-15.log
三、关键数据：YGC 12 次（均 6.8ms）｜Mixed 2 轮｜Full 0 次｜Humongous 告警 3 次
四、发现：① Humongous Allocation 触发并发标记 ×3 → 1MB 数组是主因
        ② 回收后基线稳定在 68M → 无泄漏迹象
五、建议：拆大对象 or 调 G1HeapRegionSize=4m（day27 验证）
```

## 4. 面试连接

**Q：怎么判断 GC 是否健康？**
> 三指标框架：频率（YGC 周期）、单次停顿（P99）、回收效果（基线回落）。再补充"危险信号清单"（To-space exhausted/FullGC/Metaspace 不回落）——结构化回答完胜背参数。

**Q：线上 GC 频繁怎么排查？**
> 第一步永远是"拿到日志"（-Xlog:gc* 或已配置的滚动日志）→ 看触发原因字段（Evacuation/Humongous Allocation/Metadata GC Threshold）→ 对症：分配速率高（查大对象/缓存）vs 类加载（查反射/动态代理）。**触发原因字段**就是日志给你的第一线索，很多人不知道它存在。

## 5. 今日验收清单

- [ ] 生产级日志配置命令能默写（含滚动）
- [ ] Mixed GC 全流程日志逐行标注完成
- [ ] GCEasy 报告生成并截图
- [ ] 《GC 日志分析报告 v1》完成并提交 git
- [ ] 能说出 5 个危险信号

---
[← Day 12](day12-ZGC与低延迟收集器.md) | [本月目录](README.md) | [Day 14 · 第二周复盘 →](day14-第二周复盘输出.md)
