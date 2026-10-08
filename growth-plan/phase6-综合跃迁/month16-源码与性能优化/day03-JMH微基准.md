# Day 03 · JMH 微基准：测代码真实速度的尺子

> **今日目标**：明白为什么 `System.nanoTime()` 手掐秒表测出来的"性能"经常是假的；学会用 JMH 正确地测一小段代码；收藏一份"黑坑清单"。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：一个 JMH 基准跑通 + 亲手复现一次"死代码消除"骗局

## 1. 知识地图（先讲人话）

```
为什么手掐秒表不靠谱？三个"裁判作弊"现场：

① 预热问题（Warm-up）：
   JVM 先解释执行（慢），跑热后 JIT 编译成本地机器码（快 10 倍+）。
   你掐的前几次秒表是"冷车成绩"——像 0-100 加速测试没热胎。
   证据：同一个方法第一次跑 500ms，第一百次跑 5ms。

② 死代码消除（Dead Code Elimination）：
   JIT 发现你算出来的结果没人用，直接把整个计算优化没了！
   你测的是"什么都不做"的速度。
   对策：结果必须"消费"掉（返回它/写黑名单字段）。

③ 常量折叠（Constant Folding）：
   输入是编译期常量，JIT 直接把答案算好，运行期不干活了。
   对策：输入每次从"JIT 猜不到的地方"来（随机数/参数注入）。

JMH（Java Microbenchmark Harness，Java 微基准测试框架）：
   专门治上面三种病——自动预热、强制消费结果、参数注入，
   是 OpenJDK 官方出品（写它的人和写 JVM 的是同一拨人）。

微基准 vs 压测（明天讲）的分工：
   微基准：测"一个零件"（一个方法）——零件级 CT
   压测：测"整台机器"（整个服务）——整车路试
   两级都要测：零件快 ≠ 整车快（瓶颈可能在别处）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 大白话 |
|------|------|--------|
| JMH | 微基准框架 | OpenJDK 官方"公平裁判"，自动处理预热等坑 |
| Warm-up Iterations | 预热轮次 | 正式比赛前的热身圈，成绩不计入 |
| Throughput (thrpt) | 吞吐模式 | 一秒能干多少次（次数/秒，越大越好） |
| Average Time (avgt) | 平均时间模式 | 干一次要多久（时间/次，越小越好） |
| Fork | 分叉进程 | 每个基准跑在独立 JVM 里，防止互相污染 |
| @State | 状态对象 | 基准的"输入仓"，隔离可变数据 |

## 3. 动手实操

### 3.1 接入 JMH（Gradle）

```groovy
// build.gradle 里加
dependencies {
    testImplementation 'org.openjdk.jmh:jmh-core:1.37'
    testAnnotationProcessor 'org.openjdk.jmh:jmh-generator-annprocess:1.37'
}
```

### 3.2 复现"死代码消除"骗局（今天的重头戏）

```java
import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)      // 预热 3 轮，成绩不计
@Measurement(iterations = 5, time = 1) // 正式测 5 轮
@Fork(1)                                // 独立 JVM 进程
@State(Scope.Benchmark)
public class DeadCodeBench {
    int x = 1, y = 2;

    /** 骗局版：结果没人用 → JIT 直接删掉计算 */
    @Benchmark
    public void wrongWay() {
        int sum = x + y;    // JMH 会警告：基准返回 void，结果被丢弃
    }

    /** 正确版：返回结果 = 强制消费 */
    @Benchmark
    public int rightWay() {
        return x + y;
    }

    /** 常量折叠版：输入全是字段常量，JIT 可能直接算死 */
    @Benchmark
    public int constantFold() {
        return 1 + 2;
    }
}
// 预期：wrongWay ≈ 1ns 左右（近似啥都没干）；rightWay 稍慢（真算了）；
//       constantFold 可能和 wrongWay 一样快（被折叠成常量 3）
```

```powershell
cd D:\mywork\springbootai
gradlew test --tests "DeadCodeBench"    # 或写个 main 跑 org.openjdk.jmh.Main
# 记录三个数字并解释差异——这就是"你测的可能是空转"的实锤证据
```

### 3.3 一个有用的基准：字符串拼 JSON 的两种写法

```java
@Benchmark
public String plusConcat(State s) { return "a"+s.v+"b"+s.v; }

@Benchmark
public String builderConcat(State s) { return new StringBuilder().append("a").append(s.v).append("b").append(s.v).toString(); }
// 大概率打平甚至 + 更快——"StringBuilder 一定快"是过时结论（编译器会优化 +）
// 面试点破这层 = 真测过而不是背结论
```

### 3.4 思考题

为什么 JMH 要求 `@Fork` 独立进程？两个基准放同一个 JVM 里连续跑会互相影响什么？（提示：JIT 编译决策依赖执行路径历史，前面的基准会让后面的"抢跑"或"被拖累"——profile pollution）

## 4. 面试连接

**Q：你怎么证明一个优化真的有效？**
> 微基准用 JMH（自动预热、强制消费结果、独立 Fork），系统级用压测对比 P99。我在字符串拼接上实测过"+"和 StringBuilder 差距很小——说明"背结论"不如"跑数字"。另外我踩过死代码消除的坑：测了个返回 void 的方法，结果约等于测了个寂寞。

**Q：JMH 的预热是在预热什么？**
> 预热 JVM 的 JIT：让热点代码完成"解释执行→C1 编译→C2 深度优化"的升级过程，同时让类加载、锁膨胀、内联缓存都进入稳态。不预热等于把冷车成绩当正式成绩。

## 5. 今日验收清单

- [ ] JMH 依赖接入并跑通三个基准
- [ ] 复现死代码消除/常量折叠，记录三个数字
- [ ] 字符串基准结果与"常识"对比（很可能打脸）
- [ ] `git add . && git commit -m "day16-03: jmh & dead-code"`
- [ ] 笔记：黑坑清单三条（预热/死代码/常量折叠）各配一句对策

---
[← Day 02](day02-性能指标与P99.md) | [本月目录](README.md) | [Day 04 · 系统压测与监控 →](day04-系统压测与监控.md)
