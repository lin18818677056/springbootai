# Day 18 · Arthas 进阶与火焰图（watch/trace/tt + async-profiler）

> **今日目标**：掌握方法级诊断三剑客（watch/trace/tt）；用 Docker Linux 容器 + Arthas profiler 生成你的第一张火焰图（async-profiler 不支持 Windows，容器是标准做法）。
> **时长**：理论 0.5h / 实操 2h / 输出 0.5h
> **今日产出**：flamegraph.html 火焰图 + 三剑客操作记录

## 1. 知识地图

```
三剑客：不改一行代码，给任意方法装上"监控探头"

watch   观察窗口：方法入参/返回值/异常，调用即打印
        watch 类名 方法名 '{params, returnObj, throwExp}' -x 2
trace   耗时地图：方法内部每一层子调用花多久，一树看尽
        trace 类名 方法名 '#cost > 50ms'   ← 只看慢调用（重要！）
tt      时间隧道：把每次调用"录像"存起来，事后回放
        tt -t 类名 方法名 → tt -l 列表 → tt -p -i 1000 回放第 1000 号

火焰图：把"CPU 时间花在哪"画成一座山

┌──────────────────────────────────────┐
│              main() 100%             │  纵轴 = 调用栈深度
│  ┌───────────────────┐ ┌──────────┐  │  横轴 = 采样次数占比
│  │   bubbleSort 70%  │ │ 其他 30% │  │       （宽 = 烧 CPU）
│  │  ┌─────────────┐  │ └──────────┘  │
│  │  │ inner loop  │  │               │
│  └──┴─────────────┴──┴───────────────┘
读图三步：找最宽的"平顶" → 顺栈往下看是谁调的 → 对照代码优化
（平顶 = 自身开销大；宽但尖 = 开销在它的子调用里）

工具链：async-profiler（C++ 采样引擎）←Arthas 的 profiler 命令就是它
  ⚠ 只支持 Linux/macOS！
  Windows 本机 → Docker Linux 容器跑（今天主线）
  不想用容器  → JFR 热点视图（day16 的 jfr view hot-methods，文字版火焰图）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Flame Graph | 火焰图 | Brendan Gregg 发明的剖析可视化 |
| async-profiler | 异步采样器 | Arthas profiler 命令的底层引擎 |
| watch / trace / tt | 观察/追踪/时间隧道 | Arthas 方法级三剑客 |
| Sampling | 采样 | 每 ~20ms 抓一次栈，按频次估算耗时 |
| CPU mode / Wall mode | CPU/墙钟模式 | 只计计算时间 / 等 IO 也计入 |

## 3. 动手实操

### 3.1 准备三剑客的"靶子"：ProbeApp（方法会被反复调到）

```java
public class ProbeApp {
    public static String order(int id) throws Exception {
        Thread.sleep(id % 5 == 0 ? 120 : 5);   // 逢 5 的倍数就"慢调用"
        if (id % 7 == 0) throw new IllegalStateException("bad id " + id);
        return "order-" + id;
    }
    public static void main(String[] args) throws Exception {
        int i = 0;
        while (true) {
            try { order(i++); } catch (Exception e) { /* 吞掉继续，制造真实感 */ }
            Thread.sleep(50);
        }
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month01-jvm\day18
javac ProbeApp.java
java ProbeApp
# 另开窗口：java -jar arthas-boot.jar → attach ProbeApp
```

### 3.2 三剑客实操（对照输出理解）

```text
[arthas@PID]$ watch ProbeApp order '{params, returnObj, throwExp}' -x 2
   ① 每次调用打印：入参/返回值/异常，-x 2 = 对象展开 2 层
      想只看异常：watch ProbeApp order '{throwExp}' -e

[arthas@PID]$ trace ProbeApp order '#cost > 50ms'
   ② ★只显示耗时超 50ms 的调用，打印内部耗时树
      预期：逢 5 的调用，树里 Thread.sleep 占 ~120ms

[arthas@PID]$ tt -t ProbeApp order
   ③ 开始"录像"：每次调用记录一条（INDEX 编号），Ctrl+C 停止录制
[arthas@PID]$ tt -l                       列出全部录像
[arthas@PID]$ tt -p -i 100                回放第 100 号调用（验证修复的神器）
[arthas@PID]$ tt -w -i 100 '{#cost, throwExp}'   查看第 100 号的耗时/异常
```

### 3.3 火焰图（Docker Linux 容器，今天的重头戏）

**热点程序 HotLoop.java**（放同目录，故意用低效算法制造热点）：

```java
import java.util.Random;

public class HotLoop {
    static double[] data = new double[50_000];
    static Random random = new Random();

    static void bubbleSort(double[] a) {          // 热点1：O(n²) 排序
        for (int i = 0; i < a.length - 1; i++)
            for (int j = 0; j < a.length - 1 - i; j++)
                if (a[j] > a[j + 1]) { double t = a[j]; a[j] = a[j + 1]; a[j + 1] = t; }
    }
    static double heavyMath(double x) {           // 热点2：纯计算
        double r = x;
        for (int i = 0; i < 50; i++) r = Math.sin(r) * Math.cos(r) + Math.sqrt(Math.abs(r) + 1);
        return r;
    }
    public static void main(String[] args) throws Exception {
        while (true) {
            for (int i = 0; i < data.length; i++) data[i] = random.nextDouble();
            bubbleSort(data);
            for (int i = 0; i < 1000; i++) heavyMath(i);
            Thread.sleep(20);
        }
    }
}
```

**容器内跑火焰图**（PowerShell 执行第 1 步，其余在容器里）：

```powershell
# ① 启动 Linux 容器，把 day18 目录挂载进去
docker run -it --name flame --rm `
  -v D:\mywork\springbootai\learning\month01-jvm\day18:/app `
  eclipse-temurin:17-jdk bash
```

```bash
# ── 以下在容器内 ──────────────────────────────────
cd /app
javac HotLoop.java
java HotLoop &                     # 后台跑起来

curl -O https://arthas.aliyun.com/arthas-boot.jar
java -jar arthas-boot.jar          # 选 HotLoop 进程

# ── Arthas 内 ──
profiler start                     # 开始 CPU 采样
# ……晾 30 秒，让采样攒够样本
profiler stop --format html
# 输出：Flame graph is saved to: /app/flamegraph.html
stop                               # 退出 Arthas（还原字节码）
exit                               # 退出容器（--rm 自动清理）
```

```powershell
# ② 回到 Windows：火焰图就在挂载目录里，浏览器直接打开
Start-Process D:\mywork\springbootai\learning\month01-jvm\day18\flamegraph.html
```

### 3.4 读图验证 + 无 Docker 备选

- 预期：`bubbleSort` 是最大的平顶（70% 上下），点它可下钻看内层循环
- `heavyMath` 占比明显但远小于排序 → 体会"热点占比"概念
- 备选路线（Windows 本机）：`java -XX:StartFlightRecording=duration=30s,filename=hot.jfr,settings=profile HotLoop` 然后 `jfr view hot-methods hot.jfr` → 得到"文字版火焰图"，数据同源

## 4. 面试连接

**Q：CPU 100% 怎么定位热点代码？两条完整路径。**
> 路径 A（线程视角）：top -Hp 找线程 → printf '%x' 转 16 进制 → jstack 里按 nid 匹配 → 看线程栈（day20 详练）。
> 路径 B（画像视角）：Arthas profiler start/stop 出火焰图 → 最宽平顶就是热点，还能看完整调用链上下文。
> 一句话收尾："A 定位是哪个线程，B 定位是哪段逻辑，两者互补。"

**Q：火焰图为什么"平顶"最可疑？**
> 平顶 = 该函数自身消耗了大量采样且它是最内层（下面没有更深的消耗）。宽而不平，说明开销在其子调用里。看图先找平顶，一眼锁定元凶。

**Q：watch 和 trace 的本质区别？**
> watch 是"点"——一次调用的输入输出；trace 是"树"——一次调用内部每层的耗时分布。查"结果错"用 watch 看值，查"接口慢"用 trace 找层。

## 5. 今日验收清单

- [ ] ProbeApp 上三剑客全部实操（watch/trace/tt 各至少 2 条命令）
- [ ] Docker 容器内生成 flamegraph.html 并浏览器打开
- [ ] 能在火焰图指出 bubbleSort 平顶并解释占比
- [ ] JFR 备选路线跑通一次 hot-methods
- [ ] `git add . && git commit -m "day18: watch-trace-tt & flamegraph"`

---
[← Day 17](day17-Arthas入门.md) | [本月目录](README.md) | [Day 19 · MAT 内存分析与泄漏定位 →](day19-MAT内存分析与泄漏定位.md)
