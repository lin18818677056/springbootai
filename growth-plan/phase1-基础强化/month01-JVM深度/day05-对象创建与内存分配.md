# Day 05 · 对象创建与内存分配（TLAB）

> **今日目标**：搞懂 `new` 一个对象 JVM 背后的五个步骤，理解 TLAB 如何让"百万对象/秒的分配"快到无感，并用 jstat 亲眼看到对象在 Eden/Survivor/Old 之间流转。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：分配压力实验 + jstat 观察记录（本月第一次 GC 实战观察）

## 1. 知识地图

```
new Object() 的五步（面试必背）：
① 类加载检查 ──→ ② 分配内存 ──→ ③ 零值初始化 ──→ ④ 设置对象头 ──→ ⑤ 执行 <init>
  (day02)        分配方式↓        字段设零值       Mark Word等       你写的构造器

② 的两种分配方式（取决于堆是否规整，即 GC 是否带压缩）：
规整(复制/标记整理后) → 指针碰撞 Bump-the-Pointer：挪动分界指针，快！
不规整(标记清除后)   → 空闲列表 Free List：维护"哪里有空位"的清单

并发分配的竞态怎么解决？
方案1：CAS + 失败重试（粗暴但可用）
方案2：TLAB（本地线程分配缓冲）★ 主流
┌─────────── Eden ────────────┐
│ [TLAB-线程A: ████████      ] │ ← 每个线程在 Eden 预租一块私有区域
│ [TLAB-线程B: ██████        ] │    线程在自己 TLAB 内分配 = 无锁！
│ [TLAB-线程C: ████          ] │    TLAB 用完才 CAS 申请新 TLAB
└─────────────────────────────┘
   占比小(约1%)，-XX:+UseTLAB 默认开启

对象去哪了（分代分配）：
   小对象 → TLAB → Eden → Eden满 → MinorGC → 存活→Survivor(年龄+1)
   大对象 → 跳过TLAB → 老年代（避免复制开销，-XX:PretenureSizeThreshold）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| TLAB (Thread Local Allocation Buffer) | 本地线程分配缓冲 | Eden 中的线程私有分配区，无锁分配 |
| Bump-the-Pointer | 指针碰撞 | 规整内存的 O(1) 分配方式 |
| Minor GC / Young GC | 年轻代回收 | Eden 满触发，复制存活对象，**暂停业务**（STW） |
| Pretenure | 提前晋升 | 大对象直接进老年代 |
| Escape Analysis | 逃逸分析 | day22 细讲：不逃逸对象可能栈上分配/标量替换 |

## 3. 动手实操

### 3.1 准备分配压力程序 AllocDemo.java

```java
import java.util.ArrayList;
import java.util.List;

public class AllocDemo {
    // 模拟业务：每轮生成 100 个 1KB 的"订单"，其中偶数轮的保留在 list（晋升老年代）
    static final List<byte[]> retained = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        System.out.println("PID = " + ProcessHandle.current().pid());  // 打印自己 PID
        for (int round = 0; round < 1000; round++) {
            for (int i = 0; i < 100; i++) {
                byte[] order = new byte[1024];        // 1KB 临时对象 → Eden
                if (round % 2 == 0 && retained.size() < 200) {
                    retained.add(order);              // 长活对象 → 晋升老年代
                }
            }
            Thread.sleep(100);                        // 每秒约 10 轮 = 1MB/s 分配压力
        }
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month01-jvm; mkdir day05 -Force; cd day05
javac AllocDemo.java
java -Xms256m -Xmx256m -Xlog:gc AllocDemo
# 另开一个 PowerShell 窗口（按输出的 PID）：
jps -l                          # 确认 PID
jstat -gcutil <pid> 1000        # 每 1 秒采样一次！核心观察命令
```

### 3.2 jstat 输出怎么读（今天的核心技能）

```
  S0     S1     E      O      M     YGC    YGCT   FGC  FGCT
  0.00  45.31  88.76  12.40  95.02    12    0.089    0   0.000
  │      │      │      │      │      │      │       │     │
  Survivor0使用率 Survivor1 Eden使用率 老年代 元空间  年轻代GC次数 YGC总耗时 FullGC次数 FGC总耗时
```

**观察记录**（写进笔记）：
1. Eden 使用率会冲到 ~100% 然后骤降 → 一次 Minor GC 发生了
2. E 从 0 涨到 100 的时间 ≈ Eden 容量 ÷ 分配速率（验证 1MB/s）
3. O（老年代）呈阶梯状缓慢上涨 → 每两轮有 100KB 被晋升
4. YGC 次数持续增加而 FGC 保持 0 → 健康！老年代只进不出才会 FGC

### 3.3 实验二：GC 日志对照

```powershell
java -Xms256m -Xmx256m -Xlog:gc*,gc+age=trace AllocDemo 2> gc.log
# 打开 gc.log 找到一条 Pause Young (Normal) (G1 Evacuation Pause)
# 看三样东西：触发原因 / 256M->xxxM 的堆变化 / 暂停时长
# (gc+age=trace) 会打印"年龄分布表"——对象在 Survivor 里的年龄统计，day09 细读
```

### 3.4 思考题（写进笔记）

把 `-Xms256m -Xmx256m` 改成 `-Xms64m -Xmx64m` 再跑：YGC 频率怎么变？为什么大促前建议把 `Xms = Xmx`？（提示：避免堆动态扩容时的停顿与抖动）

## 4. 面试连接

**Q：对象一定分配在堆上吗？**
> 绝大多数在堆（TLAB→Eden），但 JIT 逃逸分析后，未逃逸对象可"标量替换"在栈上分配（day22 实验验证）。答出"不一定 + 逃逸分析"即 P6+/P7 水平。

**Q：TLAB 是什么？为什么需要它？**
> Eden 中线程私有的分配缓冲区。没有它，多线程并发分配对象每次都要 CAS 竞争堆顶指针；有了它，分配 = 指针挪动，无锁。TLAB 满了才 CAS 申请新的。它是"Java 分配快"的头号功臣。

**Q：大对象为什么直接进老年代？**
> 短命大对象走"Eden→Survivor 复制"成本高（复制大块内存），且容易挤满 Survivor 引发提前晋升。参数 -XX:PretenureSizeThreshold 控制（Parallel 收集器 / Serial 下有效）；G1 有专门的 Humongous（巨型对象）区域，day11 细讲。

## 5. 今日验收清单

- [ ] AllocDemo 跑通，jstat 采样 ≥1 分钟并截图
- [ ] 笔记完成四条观察记录（Eden 波动/晋升阶梯/YGC-FGC 关系）
- [ ] 64m vs 256m 对比实验做完，能回答思考题
- [ ] `git add . && git commit -m "day05: allocation & TLAB"`
- [ ] 能默画"new 五步 + TLAB 图"

---
[← Day 04](day04-对象内存布局JOL.md) | [本月目录](README.md) | [Day 06 · 字符串常量池与直接内存 →](day06-字符串常量池与直接内存.md)
