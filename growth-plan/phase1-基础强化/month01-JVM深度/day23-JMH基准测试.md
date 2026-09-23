# Day 23 · JMH 基准测试（科学地比较"谁更快"）

> **今日目标**：System.nanoTime 手搓测速会被 JIT 骗得体无完肤（预热/死码消除/常量折叠）。今天用业界标准 JMH 做"科学测速"，并亲眼见识"死码消除"这个大坑。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：String 拼接 vs StringBuilder 的真实差距报告

## 1. 知识地图

```
为什么手搓测速不可靠？（三个大坑）
  ① 预热缺失：前几轮是解释执行，测到的是"C1/C2 混合物"
  ② 死码消除 DCE：结果没人用 → JIT 直接把你的"被测代码"优化没了
     → 测出 0.0001ns 的"性能神话"
  ③ 常量折叠：入参是常量 → 编译期直接算出答案

JMH（Java Microbenchmark Harness）怎么破：
  @Warmup     强制预热若干轮 → 到达 C2 稳态再开始计时
  Blackhole   返回值/变量喂给"黑洞" → JIT 不敢消除
  @Fork(n)    开 n 个独立 JVM 进程 → 隔离 profile 污染，取统计
  迭代统计     多轮测量 → 出均值/误差，而不是单次魔法数

JMH 基准四要素（写基准类就这四样）：
  @Benchmark          被"科学计时"的方法
  @Warmup(3, 1s)      预热 3 轮 × 1 秒
  @Measurement(5, 1s) 正式测 5 轮 × 1 秒
  @Fork(1)            1 个独立进程（快速实验）；发报告用 3

实验预告：
  ① String += 循环拼接（O(n²)，每次 new 新串） vs StringBuilder（O(n)）
  ② 死码消除现场：不消费结果的基准"快到离谱"
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| JMH (Java Microbenchmark Harness) | 微基准测试框架 | OpenJDK 官方出品 |
| Warmup | 预热 | 喂饱 JIT，达到稳定编译层级后再测 |
| DCE (Dead Code Elimination) | 死码消除 | JIT 删除"结果没被使用"的代码 |
| Blackhole | 黑洞 | 消费返回值防 DCE 的机制 |
| Fork | 分叉 | 每个基准跑在独立 JVM，隔离干扰 |
| Throughput / AverageTime | 吞吐量/平均耗时 | 两种常用基准模式 |

## 3. 动手实操

### 3.1 安装 JMH（手动 4 个 jar，全自动可感）

```powershell
cd D:\mywork\springbootai\learning\month01-jvm; mkdir day23 -Force; cd day23
$base = "https://repo1.maven.org/maven2"
Invoke-WebRequest "$base/org/openjdk/jmh/jmh-core/1.37/jmh-core-1.37.jar" -OutFile jmh-core.jar
Invoke-WebRequest "$base/org/openjdk/jmh/jmh-generator-annprocess/1.37/jmh-generator-annprocess-1.37.jar" -OutFile jmh-generator-annprocess.jar
Invoke-WebRequest "$base/net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar" -OutFile jopt-simple.jar
Invoke-WebRequest "$base/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar" -OutFile commons-math3.jar
# jmh-core 是框架本体；annprocess 是注解处理器（编译期生成适配代码）；
# 后两个是它运行时的依赖（命令行解析/统计）
```

### 3.2 实验①：String += vs StringBuilder

```java
import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)              // 平均耗时模式
@OutputTimeUnit(TimeUnit.MICROSECONDS)        // 结果单位：微秒
@Warmup(iterations = 3, time = 1)             // 预热 3×1s
@Measurement(iterations = 5, time = 1)        // 测量 5×1s
@Fork(1)                                      // 1 个独立 JVM
@State(Scope.Thread)
public class StringBench {

    @Param({"50", "200"})                     // 两种拼接长度，看复杂度差距
    int len;

    @Benchmark
    public String plus() {                    // 反面教材：循环里 +=
        String s = "";
        for (int i = 0; i < len; i++) s += "a";
        return s;                             // 返回值自动进 Blackhole
    }

    @Benchmark
    public String builder() {                 // 正确姿势
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append("a");
        return sb.toString();
    }
}
```

```powershell
javac -cp "jmh-core.jar;jmh-generator-annprocess.jar" -d out StringBench.java
# 注解处理器自动生成 BenchmarkList + *_jmhTest 适配类（可 ls out 看看）
java -cp "jmh-core.jar;jopt-simple.jar;commons-math3.jar;out" org.openjdk.jmh.Main
# 预期量级：len=200 时 plus 约 5000+μs，builder 约 0.5μs → 差 4~5 个数量级
```

### 3.3 实验②：死码消除现场（今天的灵魂实验）

```java
import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class DeadCodeBench {

    @Benchmark
    public void noConsume() {                 // 陷阱写法：结果没人用
        Math.sqrt(1234567.0);
    }
    @Benchmark
    public double returnIt() {                // 正确：返回值交给 JMH
        return Math.sqrt(1234567.0);
    }
}
```

```powershell
javac -cp "jmh-core.jar;jmh-generator-annprocess.jar" -d out DeadCodeBench.java
java -cp "jmh-core.jar;jopt-simple.jar;commons-math3.jar;out" org.openjdk.jmh.Main
# 预期：noConsume ≈ 1~2 ns（几乎被消除）；returnIt ≈ 20~30 ns（真实开销）
# → 这就是"自己测出 XXX 比 HashMap 还快"的真相
```

### 3.4 实用参数速记

```powershell
java -cp "jmh-core.jar;jopt-simple.jar;commons-math3.jar;out" org.openjdk.jmh.Main -h   # 帮助
# 常用：-prof gc    附加 GC profile（看每操作分配字节数，找"分配大户"）
#       -f 3        发正式报告用 3 Fork；-wi 5 -i 5   调整预热/测量轮数
```

## 4. 面试连接

**Q：为什么性能对比要用 JMH，不能自己 nanoTime？**
> 三个坑：预热缺失（测到解释执行）、死码消除（JIT 把被测代码删了）、无统计（单次数据无置信度）。JMH 用 Warmup/Blackhole/Fork 系统性解决。能现场讲出 noConsume 实验的直接封神。

**Q：@Fork 有什么用？**
> 每个基准/每 fork 跑在独立 JVM：profile 信息互不污染、避免其他基准的 JIT 结果影响本轮、支持多次 fork 做统计。偷懒 -f 0 会共用 JVM，结论可能失真。

**Q：怎么测一个"接口"而不是方法？**
> JMH 适合微基准（方法/类级别）。接口/服务级用压测工具（JMeter/wrk/Gatling）。回答时点明层次："微基准比较算法实现，全链路压测验证系统容量，别混用。"

## 5. 今日验收清单

- [ ] 4 个 jar 下载成功，StringBench/DeadCodeBench 均编译运行
- [ ] plus vs builder 差距数据记录（两种 len）
- [ ] 死码消除实验复现并解释（noConsume vs returnIt）
- [ ] 能说出 JMH 破解手搓测速三大坑的机制
- [ ] `git add . && git commit -m "day23: jmh microbenchmark"`

---
[← Day 22](day22-JIT即时编译.md) | [本月目录](README.md) | [Day 24 · JVM 参数与生产模板 →](day24-JVM参数与生产模板.md)
