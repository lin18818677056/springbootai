# Day 22 · JIT 即时编译（代码为什么越跑越快）

> **今日目标**：搞懂"Java 慢"这个老黄历为什么早就翻篇：解释器 + JIT 分层编译 + 内联 + 逃逸分析。实测 -Xint 和 JIT 的性能鸿沟，看懂"预热"的本质。
> **时长**：理论 1.5h / 实操 1.5h / 输出 0.5h
> **今日产出**：四档执行模式性能对比表 + JIT 观察笔记

## 1. 知识地图

```
Java 的"混合模式"（mixed mode，默认）：解释器起步 + JIT 提速

  javac 编译            运行时
.java ──► .class ──► 解释器 Interpreter（逐条解释字节码，启动快）
                        │ 热点探测 Hot Spot Detection
                        │   方法调用计数器 + 回边计数器（循环）攒够阈值
                        ▼
                     JIT 即时编译 Just-In-Time → 生成机器码 → 越跑越快
                     （这就是"JVM 预热 warm-up"的真相）

分层编译 Tiered Compilation（JDK8+ 默认，五层）：
  层0  解释执行（攒 profile 信息）
  层1  C1 编译，无 profiling（快编译，低优化）
  层2  C1 编译，带方法计数
  层3  C1 编译，带全套 profiling（给 C2 喂数据）★主力中转站
  层4  C2 编译，全力优化（内联/逃逸分析/循环优化）★最终形态
  路径：0 → 3 → 4 为主干；C2 编译队列忙时先到 1/2 顶着

C2 的两大杀器：
  ① 方法内联 Inlining：小方法"抄写"进调用点，消除调用开销
     （虚方法多态怎么内联？——基于 profile 的投机内联+去优化 Deopt）
  ② 逃逸分析 Escape Analysis：对象没"跑出"方法就不必真分配
     → 标量替换（拆成局部变量）/栈上分配（思路）/锁消除

验证命令：
  java -version                # 输出 mixed mode 即混合模式
  -Xint    纯解释              -Xcomp   全部先编译再执行（启动极慢，通常更亏）
  -XX:TieredStopAtLevel=1   只用 C1（低延迟启动场景）
  -XX:+PrintCompilation   打印每次 JIT 编译事件（看"谁被编译了"）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| JIT (Just-In-Time) Compiler | 即时编译器 | 运行时把热点字节码编译成机器码 |
| Interpreter | 解释器 | 逐条解释执行，启动快但慢 |
| Tiered Compilation | 分层编译 | C1 快出码 + C2 深度优化，0~4 层 |
| Hot Spot Detection | 热点探测 | 调用计数器 + 回边计数器超阈值触发编译 |
| Inlining | 方法内联 | 把小方法展开进调用点，优化的"之母" |
| Escape Analysis | 逃逸分析 | 不逃逸对象不真分配（标量替换/锁消除） |
| Deoptimization | 去优化 | 投机优化猜错时回退到解释器 |

## 3. 动手实操

### 3.1 实验①：四档执行模式对比（感受 JIT 的威力）

```java
public class FibBench {
    static long fib(int n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }
    public static void main(String[] args) {
        long start = System.nanoTime();
        long r = 0;
        for (int i = 0; i < 100; i++) r += fib(28);   // 重复多次喂饱 JIT
        System.out.println("result=" + r);
        System.out.println("cost=" + (System.nanoTime() - start) / 1_000_000 + " ms");
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month01-jvm; mkdir day22 -Force; cd day22
javac FibBench.java

# 四档各跑一次，记录 cost 填进对比表：
java -Xint FibBench                                  # ① 纯解释
java -XX:TieredStopAtLevel=1 FibBench                # ② 仅 C1
java FibBench                                        # ③ 默认分层（到 C2）
java -XX:+PrintCompilation FibBench 2> jit.log       # ④ 顺带看编译事件
Select-String -Path jit.log -Pattern "FibBench" | Select-Object -First 5
# 预期：fib 被标注 made not entrant / compiled level 4 等事件
```

| 档位 | cost (ms)（示例量级） | 说明 |
|------|---------------------|------|
| -Xint 纯解释 | 数千 ms | 逐条解释，慢 10~50 倍 |
| C1 only | 数百 ms | 快出码、浅优化 |
| 默认（C2） | 数十~百 ms | 深度优化后起飞 |
| 结论 | 预热 1~2 秒 | 服务"起来慢、跑得快"的根因 |

### 3.2 实验②：观察方法内联（JIT 优化之母）

```java
public class InlineDemo {
    static int square(int x) { return x * x; }          // 小方法，内联候选
    public static void main(String[] args) {
        long r = 0;
        for (int i = 0; i < 10_000_000; i++) r += square(i);
        System.out.println(r);
    }
}
```

```powershell
javac InlineDemo.java
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining InlineDemo 2> inline.log
Select-String -Path inline.log -Pattern "square"
# 预期看到：InlineDemo::square ... already inlined（已被内联）
# 把方法故意改大（加一堆无用代码超过内联阈值）再试 → 变成 too big 不内联
```

### 3.3 实验③：逃逸分析之标量替换（对象"消失术"）

```java
public class EscapeTest {
    static class Point { int x, y; Point(int x, int y) { this.x = x; this.y = y; } }
    static long total;
    public static void main(String[] args) {
        long start = System.nanoTime();
        for (int i = 0; i < 100_000_000; i++) {
            Point p = new Point(i, i + 1);   // p 没跑出循环体 = 不逃逸
            total += p.x + p.y;              // JIT：拆成两个局部变量，根本不 new！
        }
        System.out.println(total);
        System.out.println("cost=" + (System.nanoTime() - start) / 1_000_000 + " ms");
    }
}
```

```powershell
javac EscapeTest.java
java -Xlog:gc EscapeTest | Select-Object -Last 2      # ① 默认：GC 几乎为 0（不分配！）
java -XX:-DoEscapeAnalysis -Xlog:gc EscapeTest | Select-Object -Last 2   # ② 关掉逃逸分析
# 对比：②会出现多次 GC（1 亿次真分配，TLAB 也扛不住）且耗时更长
```

### 3.4 思考题

1. 为什么 JMH 基准测试必须"预热"？（day23 揭晓：让代码到达 C2 稳态再测）
2. `-Xcomp` 看似激进，为什么实际常常更慢？（编译队列阻塞启动路径）

## 4. 面试连接

**Q：讲讲分层编译。**
> 五层：0 解释→ 1/2/3 C1（1 无 profiling、2 带计数、3 全 profiling）→ 4 C2 深度优化。C1 快出码保响应，C2 拿着 C3 收集的 profile 做投机优化。JDK8 起默认分层，这就是"预热"的本质。

**Q：逃逸分析有什么用？**
> 分析对象是否"逃出"方法/线程。不逃逸 → 标量替换（拆成局部变量不分配）、锁消除（局部对象上的 synchronized 直接去掉）。这就是很多"教科书优化点"实际测不出差距的原因——JIT 早替你做了。

**Q：什么是去优化（Deopt）？**
> C2 基于 profile 做投机优化（如"这个虚方法实际只有一个实现"），运行中一旦假设被打破（新类加载进来），立即丢弃机器码回退解释执行重新攒 profile。体现 JVM"激进优化 + 可靠回退"的设计。

## 5. 今日验收清单

- [ ] 四档对比表填完（真实数据）
- [ ] PrintInlining 看到 square 被内联的日志
- [ ] 逃逸分析开关对比：GC 次数/耗时的差异记录
- [ ] 能讲清"预热"的本质和分层编译五层
- [ ] `git add . && git commit -m "day22: jit tiered compilation"`

---
[← Day 21](day21-第三周复盘输出.md) | [本月目录](README.md) | [Day 23 · JMH 基准测试 →](day23-JMH基准测试.md)
