# Day 27 · G1 调优实战（从报告到结论的完整闭环）

> **今日目标**：day13 的《GC 日志分析报告 v1》留了一个待验证项："Humongous 告警 → 调 G1HeapRegionSize 是否有效？"今天完成闭环：提出假设 → 单变量实验 → 数据对比 → 《调优报告 v2》。
> **时长**：理论 0.5h / 实操 2.5h / 输出 0.5h
> **今日产出**：《G1 调优报告 v2》（本月压轴输出物）

## 1. 知识地图

```
调优决策树（先定性再动手）：

GC 不健康？（day13 三指标）
 ├─ YGC 太频繁 → 分配速率高 → 堆太小/对象太多/年轻代太小
 ├─ Humongous 告警 → 大对象 ≥ Region/2 → 拆对象 or 调大 Region（今天）
 ├─ Mixed 后老年代仍满 → 存活对象真多/泄漏 → dump（day19）
 ├─ To-space exhausted → 晋升放不下 → 加大堆或查 Humongous 风暴
 └─ Full GC 出现 → 最后的墙：查晋升失败/元空间/大对象

G1 参数梯队（别乱动第三梯队！）：
  第一梯队（常用）     MaxGCPauseMillis / G1HeapRegionSize / Xms=Xmx
  第二梯队（谨慎）     InitiatingHeapOccupancyPercent(IHOP) / G1ReservePercent
  第三梯队（默认最佳） G1NewSizePercent、G1MixedGCLiveThresholdPercent...
                        ↑ G1 的自适应引擎比你聪明，别手动硬掰

单变量实验法（调优的科学性所在）：
  一次只改一个参数 + 同一份负载 + 同口径指标 → 对比表 → 结论
  指标三选：YGC 次数、最大停顿、Humongous 触发次数（+Full GC 必须为 0）

今天的假设（来自 day13 报告建议项）：
  H1：-XX:G1HeapRegionSize=4m 能让 1MB 大对象不再是 Humongous
      （判定标准：对象 ≥ Region/2 → 1MB ≥ 1MB/2 成立；Region=4m 时 1MB < 2MB 不成立）
  H2：Humongous 消失后，并发标记触发次数显著下降（省 CPU、降停顿）
```

## 2. 核心概念（中英对照）

| 英文/参数 | 中文 | 要点 |
|-----------|------|------|
| Single Variable Experiment | 单变量实验 | 每轮只改一个参数，否则结论不可信 |
| G1HeapRegionSize | Region 大小 | 1~32M，2 的幂；默认堆/2048 向上取整 |
| Humongous Threshold | 巨型对象阈值 | 对象 ≥ RegionSize/2 即 Humongous |
| InitiatingHeapOccupancyPercent | IHOP | 堆占用达此值启动并发标记（默认 45%） |
| Baseline | 基线 | 调优前必须先有"对照轮"数据 |

## 3. 动手实操

### 3.1 准备：大对象负载程序（承接 day11/13 的素材）

```java
// BigAllocDemo.java —— 20MB/s 的大对象分配（模拟订单快照/大 JSON 序列化）
public class BigAllocDemo {
    public static void main(String[] args) throws Exception {
        while (true) {
            byte[] snapshot = new byte[1024 * 1024];   // 1MB 大数组
            Thread.sleep(50);                           // ≈ 20MB/s
        }
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month01-jvm; mkdir day27 -Force; cd day27
javac BigAllocDemo.java
```

### 3.2 三轮单变量实验（每轮 3 分钟，同负载同口径）

```powershell
# 轮次 A：基线（默认 RegionSize，256m 堆 → Region=1m）
java -Xms256m -Xmx256m -Xlog:gc*:file=a.log:time,uptime BigAllocDemo
# 跑 3 分钟 Ctrl+C，然后统计：
Select-String -Path a.log -Pattern "Humongous Allocation" | Measure-Object | Select-Object Count
Select-String -Path a.log -Pattern "Pause Young" | Measure-Object | Select-Object Count

# 轮次 B：Region 调大（假设 H1 生效则 Humongous 消失）
java -Xms256m -Xmx256m -XX:G1HeapRegionSize=4m -Xlog:gc*:file=b.log:time,uptime BigAllocDemo
Select-String -Path b.log -Pattern "Humongous Allocation" | Measure-Object | Select-Object Count

# 轮次 C：反证——故意把 Region 调回小值（1m 已是默认，用 512k 看恶化）
java -Xms256m -Xmx256m -XX:G1HeapRegionSize=512k -Xlog:gc*:file=c.log:time,uptime BigAllocDemo
Select-String -Path c.log -Pattern "Humongous Allocation" | Measure-Object | Select-Object Count
```

### 3.3 填写对比表（真实数据！）

| 指标 | A 默认(1m) | B Region=4m | C Region=512k |
|------|-----------|-------------|----------------|
| Humongous 触发次数 | （你填，预期多） | （你填，预期≈0） | （你填，爆多） |
| YGC/Pause Young 次数 | | | |
| 并发标记周期次数 | | | |
| 单次 Humongous 日志示例 | （抄一条触发原因原文） | | |

**思考**：Region 调大就一定好？（不是！Region 大 → 每次回收粒度更粗 → 停顿变长；H 区跨 Region 连续空间要求更高。这叫"权衡 trade-off"。）

### 3.4 撰写《G1 调优报告 v2》（day13 v1 的续篇）

```text
一、背景：v1 发现 Humongous Allocation 频繁触发并发标记（建议项未验证）
二、假设：H1 Region=4m 消除 Humongous；H2 并发标记次数下降
三、方法：单变量三轮（A 默认/B 4m/C 512k），同负载 BigAllocDemo 20MB/s，各 3 分钟
四、数据：Humongous 次数 A=xx, B=xx, C=xx；Pause Young A=xx, B=xx...
五、结论：H1 成立/不成立（数据支撑）；Region 调大有停顿代价，生产取 4m 与停顿平衡
六、落地建议：业务侧优先拆大对象（分页序列化）；参数侧 G1HeapRegionSize=4m 作为兜底
七、遗留：IHOP 调整实验（接 month02 或自选加练）
```

## 4. 面试连接

**Q：G1 你是怎么调优的？（完整叙事模板）**
> 测量（GC 日志三指标）→ 定性（Humongous/晋升/停顿哪类问题）→ 单变量实验（基线+改动轮对比表）→ 结论（数据支撑的取舍）→ 落地（业务优先、参数兜底）→ 留监控验证。全程"数据说话"，没有一句玄学。

**Q：Humongous 对象为什么危害大？**
> ① 直接进老年代（可能跳过年轻代压力分担）② 需要 ≥2 个连续 Region，加剧碎片 ③ 大对象高频分配会反复触发并发标记，CPU 白烧。处置：业务拆对象优先，参数调 Region 兜底。

**Q：调优失败过吗？**
> 最加分的问题。答一个真实教训（如：MaxGCPauseMillis 设 10ms 导致 G1 过度紧缩年轻代、吞吐暴跌），说明"参数是权衡，不是免费午餐"的敬畏心。

## 5. 今日验收清单

- [ ] 三轮实验跑完，对比表填真实数据
- [ ] 能解释 Humongous 判定公式（≥Region/2）并推演 Region=4m 为何消除
- [ ] 《调优报告 v2》完成（v1 遗留项闭环）
- [ ] 能说出"Region 调大"的代价（不是免费午餐）
- [ ] `git add . && git commit -m "day27: g1 tuning practice"`

---
[← Day 26](day26-OOM类型全复现.md) | [本月目录](README.md) | [Day 28 · 虚拟线程与 JDK 新特性 →](day28-虚拟线程与JDK新特性.md)
