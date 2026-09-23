# Day 26 · 并发性能排查（锁竞争热点 / 伪共享 @Contended 实测）

> **今日目标**：把 month01 的排查武器（jstack/Arthas/JFR/JMH）全部对准并发场景：定位锁竞争热点、用 JMH 亲手实测伪共享的 5~10 倍差距。Docker 复核数据方案附后。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：伪共享 JMH 实测数据 + 锁热点四步排查记录 + Docker 复核报告

## 1. 知识地图

```
并发性能四类问题 × 对应武器：

  ① 锁竞争 Lock Contention（最常见）
     症状：TPS 上不去，CPU 却闲着；线程大量 BLOCKED
     武器：jstack 看 BLOCKED 堆积 → Arthas thread -b 找持锁元凶 → JFR 看锁事件耗时

  ② 伪共享 False Sharing（隐蔽的性能刺客）
     原理：CPU 缓存以"缓存行"（64B）为单位同步。
       两个无关变量若挤在同一缓存行，核 A 改 x → 核 B 的整个缓存行失效
       → 明明无锁却互相"打架"
     武器：JMH 实测 + @Contended/@sun.misc.Contended（需 -XX:-RestrictContended）
           或手工 Padding（前后塞 7 个 long）

  ③ 线程切换 Tax
     症状：us 时间戳 sys 高、vmstat cs 列爆表
     武器：线程数压测（day16 CpuBench 已做）

  ④ 过度同步 Over-Synchronization
     症状：热点方法全在排队（day05 DifferentLock 的教训）
     武器：JFR Method Profiling + 缩小锁粒度/读写锁/无锁化

排查四步法（对 month01 CPU 四步法的并发版）：
  jstack 多抓几次看 BLOCKED → arthas thread -b 找阻塞源 → 
  thread -n 3 看 CPU 前三 → JFR 飞行记录看 Java Monitor Blocked 事件
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| False Sharing | 伪共享 | 无关变量挤同一缓存行互相失效 |
| Cache Line | 缓存行 | CPU 缓存同步最小单位（64B） |
| Padding | 填充 | 手工补位把变量隔开 |
| Contended | 竞争标注 | JDK8 注解，自动前后各补 128B |
| Contention | 竞争 | 多线程争抢同一资源 |
| Monitor Blocked | 监视器阻塞 | JFR 里 synchronized 等待事件 |

## 3. 动手实操

### 3.1 主菜：伪共享 JMH 实测（5~10 倍差距，亲眼看）

```powershell
# ① 复制 month01 的 JMH 4 件套（day23 已下载）
mkdir D:\mywork\springbootai\learning\month02-concurrency\lib -Force
Copy-Item D:\mywork\springbootai\learning\month01-jvm\day23\lib\*.jar `
          D:\mywork\springbootai\learning\month02-concurrency\lib\
# （若路径不同：Get-ChildItem -Path D:\mywork\springbootai\learning -Recurse -Filter jmh-core*.jar 定位）
```

```java
// ContendedBench.java —— 伪共享：挤在一起 vs Padding 隔开 vs @Contended
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Group)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ContendedBench {

    // 版本A：两个 long 挤同一缓存行（默认，伪共享现场）
    long x, y;

    // 版本B：手工 Padding（左右各塞 7 个 long 隔开）
    long p0,p1,p2,p3,p4,p5,p6;
    volatile long padX;
    long q0,q1,q2,q3,q4,q5,q6;
    volatile long padY;
    long r0,r1,r2,r3,r4,r5,r6;

    // 版本C：@Contended 注解（运行时加 -XX:-RestrictContended）
    @sun.misc.Contended("c") long cx;
    @sun.misc.Contended("c") long cy;

    @Group("share")
    @GroupThreads(1) @Benchmark public void ax() { x++; }
    @Group("share")
    @GroupThreads(1) @Benchmark public void ay() { y++; }

    @Group("padded")
    @GroupThreads(1) @Benchmark public void bx() { padX++; }
    @Group("padded")
    @GroupThreads(1) @Benchmark public void by() { padY++; }

    @Group("contended")
    @GroupThreads(1) @Benchmark public void cxA() { cx++; }
    @Group("contended")
    @GroupThreads(1) @Benchmark public void cyB() { cy++; }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(new String[]{"ContendedBench", "-foe", "true"});
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month02-concurrency
# ② 编译（-d 指定输出目录，4 个 jar 进 classpath）
javac -cp "lib/*" -d out ContendedBench.java
# ③ 跑（注意：@Contended 需要 -XX:-RestrictContended 才生效）
java -cp "out;lib/*" -XX:-RestrictContended org.openjdk.jmh.Main "ContendedBench"
# 预期读数（多核机器）：
#   share     组：~几十 ns（两核互踩缓存行）
#   padded    组：~1-2 ns（快 5-10 倍）
#   contended 组：≈ padded（注解等价于自动 Padding）
# ★把三组数字抄进笔记——这是"我实测过伪共享"的硬证据
```

### 3.2 实验②：锁竞争热点四步排查（复用 Arthas）

```java
// LockHot.java —— 一个故意串行的热点：所有线程抢同一把锁
import java.util.concurrent.*;

public class LockHot {
    static final Object LOCK = new Object();
    static Counter c = new Counter();

    static class Counter {
        int v;
        void add() { synchronized (LOCK) {           // 热点：1000 万次进同一扇门
            v++;
        } }
    }
    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 8; i++) {
            pool.execute(() -> { for (int j = 0; j < 10_000_000; j++) c.add(); });
        }
        System.out.println("跑 30 秒，趁现在去另一个终端执行排查命令（PID 见 jps）");
        Thread.sleep(30_000);
        pool.shutdown();
        System.out.println("结果 " + c.v);
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month02-concurrency
javac LockHot.java; java LockHot          # 保持运行
# ---- 另开终端 ----
jps -l                                    # 拿到 LockHot 的 PID
jstack <PID> > s1.txt; Start-Sleep 2; jstack <PID> > s2.txt; jstack <PID> > s3.txt
Select-String -Path s1.txt,s2.txt,s3.txt -Pattern "BLOCKED" | Measure-Object
# 第①步：多次 jstack 都看到一批 BLOCKED(waiting for monitor) → 锁竞争实锤

java -jar D:\mywork\springbootai\learning\month01-jvm\day17\arthas-boot.jar
# （路径不同就 Get-ChildItem -Recurse -Filter arthas-boot.jar 定位）
[arthas]$ thread -b          # 第②步：直接指出"哪个线程持有 BLOCKED 线程们等的锁"
[arthas]$ thread -n 3        # 第③步：CPU 最高的 3 个线程（排除死循环干扰）
[arthas]$ stop

# 第④步（可选进阶）：JFR 看锁事件的量化耗时
jcmd <PID> JFR.start name=locktest duration=20s filename=lock.jfr
jcmd <PID> JFR.stop
jfr view locked --width 120 lock.jfr
# 输出里按"等锁总时长"排序，热点锁一目了然
```

### 3.3 实验③（可选）：Docker Linux 环境复核伪共享数据

```powershell
# Windows 与 Linux 的缓存行为可能有差异，用 Docker Desktop 复核一次（可选）
docker run --rm -it -v D:\mywork\springbootai\learning\month02-concurrency:/work `
  -w /work eclipse-temurin:17-jdk bash
# （容器内，Linux 环境）
apt-get update && apt-get install -y procps    # 补 jps/jstack（temurin 镜像精简过）
javac -cp "lib/*" -d out ContendedBench.java
java -cp "out:lib/*" -XX:-RestrictContended org.openjdk.jmh.Main "ContendedBench"
# ★对比 Windows 宿主机数据，差距趋势一致（padded 远快于 share）即验证成功
# 注意容器里分隔符是冒号 :，Windows 是分号 ;
```

## 4. 面试连接

**Q：什么是伪共享？怎么解决？**
> 定义：两个线程写两个"逻辑无关"的变量，但物理上落在同一 64B 缓存行，一个核写入导致另一个核的缓存行失效（MESI 协议），性能暴跌。证据："我 JMH 实测过，Padding 后快 5~10 倍（报你的数字）"。解法三选一：手工 Padding、@Contended（配 -XX:-RestrictContended）、重排字段布局。典型受害场景：高频计数器数组、RingBuffer 的游标。

**Q：线上 TPS 突然下降，怎么判断是不是锁问题？**
> 四步法：① jstack 连抓 3 次看 BLOCKED 线程是否堆积在同一把锁；② Arthas thread -b 直接定位持锁线程和持锁代码行；③ thread -n 3 排除 CPU 死循环干扰；④ JFR 的 Java Monitor Blocked 事件看等待耗时分布。最后给修复方向：缩小临界区、读写锁、拆锁/无锁化（LongAdder 替换 AtomicLong 也是解锁竞争）。

**Q：LongAdder 为什么能抗竞争？**
> 两句话接 day09：写扩散到 base + Cell[]，线程按自身 hash 打散到不同 Cell；读时 sum 聚合。再接今天："本质就是把一个缓存行上的竞争拆到多个缓存行——和伪共享优化是同一思想的两面。"能把知识串起来是最高分信号。

## 5. 今日验收清单

- [ ] ContendedBench 三组数据记录（share/padded/contended）
- [ ] LockHot 四步排查完整走一遍（每步一句结论）
- [ ] （可选）Docker Linux 复核数据记录
- [ ] 能脱稿讲"伪共享 = 无锁的打架"
- [ ] `git add . && git commit -m "day26: contention & false sharing"`

---
[← Day 25](day25-并发bug复现场.md) | [本月目录](README.md) | [Day 27 · 手写令牌桶限流器 →](day27-手写令牌桶限流器.md)
