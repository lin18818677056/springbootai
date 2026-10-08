# Day 19 · JIT 与逃逸分析：别和翻译官抢活

> **今日目标**：搞懂 JIT 的分层编译；理解逃逸分析带来的"隐形优化"（锁消除/标量替换）；认清为什么"手写优化"经常白干甚至帮倒忙。
> **时长**：理论 1.5h / 实操 1h / 输出 0.5h
> **今日产出**：锁消除实验（JMH 实测）+ "白干优化"清单

## 1. 知识地图（先讲人话）

```
JIT（Just-In-Time Compiler，即时编译器）是什么：
  Java 的同声传译员。程序刚启动时逐句"口译"（解释执行，慢）；
  发现某段代码翻得频繁（热点），就把它整段"背诵下来"
  （编译成机器码）——下次直接背，快 10 倍+。

分层编译（JDK8+ 默认，人话版）：
  解释执行 → C1（快速编译，翻得快但质量一般）
           → C2（深度优化编译，磨得久但质量高）
  热点探测靠两个计数器：
    方法调用计数器（这个方法被调了多少次）
    回边计数器（这个循环转了多少圈）
  都超阈值（万级）→ 触发编译 → 替换原有"口译"

逃逸分析（Escape Analysis）：JIT 的读心术——
  分析对象会不会"逃出"它出生的方法/线程：
  不逃逸（局部使用）→ 三项隐形优化：
  ① 标量替换：对象拆成几个局部变量，根本不 new
    （"你只是要三个零件，干嘛先组装再拆开？"）
  ② 锁消除：对象不可能被别的线程碰到 → 加锁动作直接删
  ③ 栈上分配（理论上的说法，实际由①实现同效果）

"别和 JIT 抢活"清单（手写优化常翻车现场）：
  ① 手动把变量提为 static final？——常量折叠 JIT 也会做，
    还可能破坏内联（大 static 块反而拖慢类加载）
  ② 手写对象池复用小对象？——逃逸分析表示你多此一举，
    还引入共享与同步问题（LeakCache 惨案重演）
  ③ 用 && 短路"省"一次判断？——分支预测早把你优化明白了
  结论：先写清晰代码，让 profiler 找热点，再针对热点动手——
    和 day01 四步法呼应：JIT 的优化你看不见，所以必须测量
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 大白话 |
|------|------|--------|
| JIT | 即时编译 | 同声传译把热门段落背下来 |
| Tiered Compilation | 分层编译 | 先快后好的两级翻译 |
| HotSpot | 热点 | JVM 名字由来：只优化"热"的代码 |
| Escape Analysis | 逃逸分析 | 判断对象会不会"跑出去" |
| Scalar Replacement | 标量替换 | 对象拆成零件，不组装直接用零件 |
| Lock Elision | 锁消除 | 别人碰不到的对象，锁是摆设——删 |

## 3. 动手实操

### 3.1 锁消除实验（亲眼见证 JIT 删锁）

```java
@BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3) @Measurement(iterations = 5) @Fork(1)
public class LockElisionBench {
    @Benchmark
    public long withUselessLock() {
        Object lock = new Object();          // 局部对象，逃不出方法
        long sum = 0;
        synchronized (lock) {                // JIT：这锁没人抢，删！
            for (int i = 0; i < 1000; i++) sum += i;
        }
        return sum;                          // 返回防死代码消除
    }
    @Benchmark
    public long noLock() {
        long sum = 0;
        for (int i = 0; i < 1000; i++) sum += i;
        return sum;
    }
    @Benchmark
    public long withRealLock() {             // 对照组：真锁（对象逃逸）
        SharedCounter c = SharedCounter.INSTANCE;   // static，能逃逸
        long start = System.nanoTime();
        synchronized (c) { c.value += 1; }
        return System.nanoTime() - start + c.value; // 消费防消除
    }
}
// 预期：withUselessLock ≈ noLock（锁被消除）
//       withRealLock 明显慢（真同步）
// 这就是"局部对象加锁不丢性能"的原理——但别故意这么写，是保险不是许可
```

### 3.2 观察编译过程（JIT 的日记）

```powershell
java -XX:+PrintCompilation YourApp
# 每行 = 一次编译决策：时间戳 编译层级 方法名
#   tier 1~3 = C1 各级，tier 4 = C2（"毕业"）
# 同一方法出现多次 = 分层升级（口译→速记→背诵）
```

### 3.3 思考题

为什么说"逃逸分析是乐观的， pessimistic 的代码（保守写法）反而安全"？举一个"逃逸分析失效"的例子。（提示：对象被赋给静态字段/传进未知方法（内联失败看不清去向）/被重写方法接收——JIT 分析不了就让对象真分配，回到普通堆。所以"看不清就当逃逸"，性能上多想不如实测）

## 4. 面试连接

**Q：逃逸分析是什么？带来哪些优化？**
> JIT 分析对象作用域：不逃出方法/线程就做三项优化——标量替换（拆零件不 new）、锁消除（无竞争锁直接删）、栈上分配效果。我实测过锁消除：局部对象 synchronized 循环和裸循环性能打平，说明锁真被删了。这也解释了为什么"手动对象池"常常白干——JIT 已经帮你省了分配。

**Q：什么代码会阻碍 JIT 优化？**
> 太大的方法（超内联阈值，比如 >325 字节码就难内联）、final 没写导致虚调用分派、native 方法和跨模块反射调用（看不清去向）。实践建议：热路径方法保持短小、关键字段标 final、避免热路径上的反射——这些都是"给翻译官留活干"。

## 5. 今日验收清单

- [ ] LockElisionBench 三组跑完，记录并解释差异
- [ ] -XX:+PrintCompilation 观察到分层升级（tier 变化）
- [ ] "白干优化"清单三条能举例
- [ ] `git add . && git commit -m "day16-19: jit-escape"`
- [ ] 笔记：逃逸分析三优化的手绘图

---
[← Day 18](day18-trace采样与冷热分层.md) | [本月目录](README.md) | [Day 20 · 源码阅读方法论 →](day20-源码阅读方法论.md)
