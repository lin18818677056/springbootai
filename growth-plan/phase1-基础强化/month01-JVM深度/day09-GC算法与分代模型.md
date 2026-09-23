# Day 09 · GC 算法与分代模型

> **今日目标**：掌握三大 GC 算法的取舍（清/复/整），理解分代假说与对象晋升规则，用 jstat+日志亲眼验证"动态年龄判定"。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：晋升规则实验记录 + 年龄分布日志解读

## 1. 知识地图（三大算法 = GC 的三张底牌）

```
① 标记-清除 Mark-Sweep：
  [██][  ][██][  ][██]   标记存活 → 直接清死块
  ✗ 碎片！大对象找不到连续空间 → 提前 FullGC
  代表：CMS（已移除）、G1 的 Mixed 阶段局部使用

② 标记-复制 Mark-Copy：
  [Eden██][空空] → 把活的拷走 → [空][活活] 整体清空源区
  ✗ 浪费一半空间；✗ 活对象多时拷贝代价大
  ✓ 活对象少时极快 → 天生适合年轻代（朝生夕死 98%）
  代表：年轻代（所有收集器）

③ 标记-整理 Mark-Compact：
  [██][  ][██][  ] → 活对象向一头移动 → [████][    ] 规整无碎片
  ✗ 移动对象要暂停更久（引用全要改）
  ✓ 适合"活得久、占比高"的老年代
  代表：老年代（Serial Old / Parallel Old / ZGC 的并发整理是升级版）

分代假说（Generational Hypothesis）——一切设计的出发点：
  假说1：绝大多数对象朝生夕死（实测常见 98%）
  假说2：熬过越多次 GC 的对象越可能活到天荒地老
  推论：按"寿命"分区，用不同算法——年轻代复制（快），老年代整理（省）

对象的一生：
  new ─→ Eden ─MinorGC─→ Survivor0 ─MinorGC─→ Survivor1 ─...─→ 年龄≥阈值 ─→ Old
        (出生)   年龄1      年龄2（来回倒腾）          (MaxTenuringThreshold，默认15)
                                             ↑
   超大对象 ──────────────────────────────────────────── 直接进 Old（Humongous/Pretenure）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Mark-Sweep/Copy/Compact | 标记-清除/复制/整理 | 三大基础算法 |
| Generational Hypothesis | 分代假说 | 弱分代+强分代两条经验规律 |
| Tenuring Threshold | 晋升年龄阈值 | 默认 15（对象头只有 4bit 存年龄，上限就是 15！） |
| Dynamic Age Calculation | 动态年龄判定 | Survivor 中同年龄总大小 > 一半 → 该年龄以上全晋升 |
| Promotion | 晋升 | 对象从年轻代移入老年代 |
| Space Guarantee | 空间分配担保 | 老年代放不下晋升对象时的处理（HandlePromotionFailure） |

## 3. 动手实操

### 3.1 晋升观察实验（复用 day05 的 AllocDemo）

```powershell
cd D:\mywork\springbootai\learning\month01-jvm\day05
# 把 MaxTenuringThreshold 降到 2，让晋升加速发生
java -Xms256m -Xmx256m -XX:MaxTenuringThreshold=2 -Xlog:gc*,gc+age=trace AllocDemo 2> promote.log
```

在 promote.log 中找年龄表（gc+age 输出）：

```
- age   1:    1234560 bytes,    1234560 total     ← 1 岁的对象在 Survivor
- age   2:     456784 bytes,    1691344 total     ← 2 岁的
- age   3:          0 bytes,    1691344 total
```

**观察记录**：1) 年龄最高到几？（≈MaxTenuringThreshold）2) 阈值改成 5 后年龄表变化。

### 3.2 动态年龄判定验证实验

```java
// 制造"同龄对象扎堆"：一次性创建一批能活过一次 GC 的中等对象
import java.util.ArrayList;
import java.util.List;

public class DynamicAge {
    public static void main(String[] args) throws Exception {
        List<byte[]> batch = new ArrayList<>();
        for (int i = 0; i < 30; i++) batch.add(new byte[1024 * 100]); // 3MB 同批对象
        for (int i = 0; i < 50; i++) {
            new byte[1024 * 512];       // 持续制造临时压力触发多次 MinorGC
            Thread.sleep(50);
        }
        System.out.println("PID = " + ProcessHandle.current().pid());
        // 配合 -Xms128m -Xmx128m -XX:MaxTenuringThreshold=15 -Xlog:gc*,gc+age=trace
        // 观察：即使阈值是 15，这批 3MB 对象可能 2~3 岁就进了老年代
        // 验证手段：jstat -gcutil 看 O 段突然上涨 + 日志 age 表变化
    }
}
```

**结论写进笔记**：晋升不是只看固定阈值，"同龄扎堆超 Survivor 一半 → 集体提前晋升"。**这解释了线上"没道理的 FullGC"：一批长活中对象挤爆 Survivor，全被提前扔进老年代。**

### 3.3 大对象直达老年代验证

```powershell
# G1 下超大对象进 Humongous 区（day11），这里用 -Xlog 观察
java -Xms512m -Xmx512m -Xlog:gc,gc+humongous=debug -XX:G1HeapRegionSize=1m -version 2>&1 | Select-String humongous
# 写一个分配 2MB 数组的程序配合观察（>region 一半即 Humongous）
```

## 4. 面试连接

**Q：为什么 Survivor 有两块？**
> 复制算法需要"来源区+目的区"轮换；一块的话 MinorGC 后存活对象无处安放（不能放回正被清的 Eden）。两块交替使用，始终保证"从满区复制到空区"。

**Q：为什么晋升阈值最大 15？**
> 对象头 Mark Word 分代年龄字段只有 4 个 bit（day04 的 Mark Word 图），最大 1111=15。想"调大年龄"的同学可以死心了——参数上限就是 15。

**Q：线上老年代增长快，可能是什么原因？**
> 系统性回答：① 动态年龄判定提前晋升（Survivor 太小/流量突增）② 大对象直接进（业务大对象/反序列化）③ 内存泄漏（该死的没死）。对应 day19/25/27 逐一排查——这个"三选一"框架面试官很吃。

## 5. 今日验收清单

- [ ] 三大算法能画图+说出适用区域（复制→年轻代，整理→老年代）
- [ ] promote.log 年龄表解读完成，动态年龄实验跑通
- [ ] 能说出"为什么 Survivor 两块""为什么阈值≤15"
- [ ] `git add . && git commit -m "day09: gc algorithms & promotion"`
- [ ] 笔记：老年代增长快的三原因框架

---
[← Day 08](day08-GC基础与四种引用.md) | [本月目录](README.md) | [Day 10 · 收集器演进与 CMS →](day10-收集器演进与CMS.md)
