# Day 11 · G1 垃圾收集器全解

> **今日目标**：吃透 G1 的 Region 化设计（它是你 JDK25 的默认收集器，也是生产主流），理解 Humongous、Mixed GC 与停顿预测模型，并第一次完整解读 G1 日志。
> **时长**：理论 1.5h / 实操 1h / 输出 0.5h
> **今日产出**：G1 混合回收日志分析笔记

## 1. 知识地图

```
G1 革命：把连续的堆切成等大 Region（1~32MB，2的幂）
┌────┬────┬────┬────┬────┬────┬────┬────┐
│ E  │ E  │ S  │ O  │ O  │ H  │ H  │ E  │   E=Eden  S=Survivor
├────┼────┼────┼────┼────┼────┼────┼────┤   O=Old   H=Humongous(巨型对象,
│ O  │ E  │ O  │ FREE│ O │ H  │ O  │ S  │     占≥半个Region,连片存放)
└────┴────┴────┴────┴────┴────┴────┴────┘
分代依然存在，但物理上不再连续！每个 Region 随时可变身份——这就是"化整为零"

跨 Region 引用怎么办？（老年代可能引用 Eden 的对象，不可能全堆扫描）
  → RSet（Remembered Set，记忆集）：每个 Region 记录"谁引用了我"
  → 粒度用 Card Table（卡表，512B 一张卡）：引用写时打脏卡标记（写屏障实现）
  代价：RSet 维护占用 5%~20% 内存 ← G1 的主要开销（ZGC 无 RSet 的原因之一）

G1 的回收节奏：
  ① Young GC（Evacuation Pause）：Eden 满 → 存活对象复制到 S/O
     ── STW，但只处理有对象的 Region，天然增量
  ② 并发标记周期（Concurrent Marking Cycle）：
     老年代占比达 IHOP(默认45%) → 全局并发标记（三色标记+SATB）
  ③ Mixed GC：标记完成后，多轮回收"年轻代 + 候选老年代 Region"
     候选 = 垃圾比例高（回收性价比）的 Region ← Garbage First 名字的由来！
     一轮轮收，直到老年代占用达标，回到日常 Young GC

停顿预测模型（G1 独门绝技）：
  -XX:MaxGCPauseMillis=200（目标200ms）
  G1 统计每个 Region 的回收成本 → 每次只挑"目标时间内收得完"的 Region 集合
  → 把"全堆一起收"变成"按预算收"，这就是可预测停顿的原理
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Region | 区域 | G1 的最小回收单位（1~32MB） |
| Humongous Object | 巨型对象 | ≥ Region 一半，直接进连续 Humongous 区 |
| RSet (Remembered Set) | 记忆集 | 记录跨 Region 引用，空间换全堆扫描 |
| Card Table | 卡表 | 写屏障打脏标记的辅助结构（512B/卡） |
| IHOP (Initiating Heap Occupancy Percent) | 并发标记启动阈值 | 默认 45%（自适应调整） |
| Mixed GC | 混合回收 | 年轻代+部分高性价比老年代 Region |
| Evacuation | 疏散/复制 | 存活对象拷到新 Region（G1 的整理方式） |
| Write Barrier | 写屏障 | 引用赋值时插入的钩子（打脏卡/记录 SATB） |

## 3. 动手实操

### 3.1 观察 Humongous 分配

```java
import java.util.ArrayList;
import java.util.List;

public class HumongousDemo {
    public static void main(String[] args) throws Exception {
        System.out.println("PID = " + ProcessHandle.current().pid());
        List<byte[]> keep = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            keep.add(new byte[1024 * 1024]);   // 1MB：RegionSize=2m 时 ≥一半 → Humongous!
            Thread.sleep(200);
        }
        // 配合参数：-Xms512m -Xmx512m -XX:G1HeapRegionSize=2m -Xlog:gc*,gc+humongous=alloc
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month01-jvm; mkdir day11 -Force; cd day11
javac HumongousDemo.java
java -Xms512m -Xmx512m -XX:G1HeapRegionSize=2m -Xlog:gc*,gc+humongous=alloc HumongousDemo 2> hum.log
Select-String -Path hum.log -Pattern "humongous" | Select-Object -First 10
# 看到 regions 分配记录 → 巨型对象独占 Region 的证据
```

### 3.2 触发并解读一次完整 G1 回收

```powershell
# 用 day05 的 AllocDemo，开详细日志跑 2 分钟
cd ..\day05
java -Xms256m -Xmx256m -Xlog:gc* AllocDemo 2> g1full.log
```

逐行拆解一条 Young GC（今天的核心练习，写进笔记）：

```
[info][gc] GC(12) Pause Young (Normal) (G1 Evacuation Pause)
                          │      │         └─ 回收类型：复制疏散
                          │      └─ Young GC
                          └─ 第 12 次 GC
[info][gc] GC(12)   Eden regions: 24(0B)->1(12.0M)          ← Eden 24 个区→剩 1 个
[info][gc] GC(12)   Survivor regions: 2(24.0M)->3(36.0M)    ← 存活对象进 S
[info][gc] GC(12)   Old regions: 5(56.0M)->5(56.0M)         ← 老年代不变
[info][gc] GC(12)   Humongous regions: 1(2.0M)->1(2.0M)
[info][gc] GC(12)  Edenhazard...  256M(23%)->68M(27%)        ← 全堆变化
[info][gc] GC(12) Pause Young (Normal) 68M->30M(256M) 8.123ms ← ★暂停 8.1ms
```

**必练**：找出日志里 MaxGCPauseMillis 的影子（`Using Pause Young ... with target=200ms` 的字样），把"目标 vs 实际"数据记进笔记。

### 3.3 思考题

为什么 G1 要设置 IHOP 提前触发并发标记，而不是等老年代满了再收？（提示：Mixed GC 需要并发标记提供"Region 垃圾占比"情报；等满了就是 Full GC 级别的事故）

## 4. 面试连接

**Q：G1 名字的含义？**
> Garbage First：并发标记后知道每个 Region 的垃圾占比，Mixed 时优先回收"垃圾最多、性价比最高"的 Region——名字本身就是设计思想。

**Q：G1 怎么做到可预测停顿？**
> Region 化 + 每个区回收成本统计 + 停顿预测模型按 MaxGCPauseMillis 预算挑区回收。补一句代价："预算太紧（如 5ms）会导致单次收不完、垃圾积压，G1 默认下限 30ms 左右才现实——想要 1ms 得上 ZGC"。

**Q：Humongous 对象有什么问题？**
> 直接进老年代挤占连续区；回收靠 Mixed/Full，年轻代高速流转帮不了它；大对象突刺（如一次性查 10 万行转大 JSON）会直接打爆 Humongous 区触发 FullGC。**优化方向：拆小对象/流式处理**——这是生产高频事故点。

## 5. 今日验收清单

- [ ] Region 布局图能默画（E/S/O/H/FREE 身份可变）
- [ ] HumongousDemo 跑通，日志含 humongous 记录
- [ ] 逐行解读一条 Young GC 日志（8 个要点全标出）
- [ ] 能回答"G1 为什么叫 Garbage First"
- [ ] `git add . && git commit -m "day11: g1 regions & log"`

---
[← Day 10](day10-收集器演进与CMS.md) | [本月目录](README.md) | [Day 12 · ZGC 与低延迟收集器 →](day12-ZGC与低延迟收集器.md)
