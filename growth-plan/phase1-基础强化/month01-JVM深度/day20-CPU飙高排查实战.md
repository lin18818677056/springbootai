# Day 20 · CPU 飙高排查实战（top -Hp 四步法 + 两种元凶）

> **今日目标**：CPU 100% 是面试和线上最高频的排查题。今天把"四步法"亲手走通（Docker Linux 容器），并学会区分两种元凶：业务代码热点 vs GC 风暴。
> **时长**：理论 0.5h / 实操 2h / 输出 0.5h
> **今日产出**：完整四步法演练记录 + 《CPU 排查 SOP》

## 1. 知识地图

```
CPU 飙高决策树（先定性，再定位）：

CPU 100%？ ──► 是谁在烧？（top -Hp 看线程名/栈）
   │
   ├─ 业务线程忙（名字像 http-nio/exec/自己起的）
   │     └→ 四步法定位到代码行 → 优化算法/加缓存/限流
   │
   ├─ GC 线程忙（GC Daemon/G1 xxx/VM Thread）
   │     └→ 根因不是 CPU！转内存排查（day13 日志 + day19 dump）
   │        常见：分配过猛 / 频繁 FullGC / 元空间抖动
   │
   └─ 自旋/锁膨胀（WRONG 方法栈、vm thread）
         └→ jstack 看锁竞争 → day02 锁原理 + 热点拆分

Linux 四步法（面试标准答案，必须肌肉记忆）：
  ① top              → 找到 CPU 最高的 JAVA 进程 → 拿到 PID
  ② top -Hp <PID>    → 进程内按线程看 CPU → 拿到最忙的 TID
  ③ printf "%x\n" TID→ 线程号转 16 进制（jstack 用 16 进制记线程）
  ④ jstack <PID> | grep -A 20 "nid=0x<16进制>"
                     → 按 nid 精确匹配线程栈 → 看代码行

Windows 注意：没有 top -Hp！
  本机用 Arthas thread -n 3 直接看（day17），
  四步法在 Docker Linux 容器里练（面试场景就是 Linux）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| TID (Thread ID) | 线程号 | top -Hp 列出的内核线程 id |
| nid (native id) | 原生线程号 | jstack 里 16 进制的线程号，= TID 转十六进制 |
| us / sy | 用户态/内核态 CPU | us 高=应用代码忙；sy 高=系统调用/上下文切换 |
| load average | 负载均值 | 1/5/15 分钟平均，>核数说明排队 |
| GC Storm | GC 风暴 | GC 线程吃满 CPU，本质是内存问题 |

## 3. 动手实操

### 3.1 制造"病人"：CpuDemo.java（4 个计算热点 + 1 个分配压力）

```java
import java.util.*;

public class CpuDemo {
    public static void main(String[] args) throws Exception {
        // 4 个纯计算线程：burner-0..3，会吃满 4 个核
        for (int t = 0; t < 4; t++) {
            final int id = t;
            new Thread(() -> {
                long x = id;
                while (true) x = x * 1103515245L + 12345L;   // 死循环烧 CPU
            }, "burner-" + id).start();
        }
        // 主线程：制造分配压力 → GC 也会忙起来（体验"双元凶"）
        List<byte[]> junk = new ArrayList<>();
        while (true) {
            junk.add(new byte[512 * 1024]);
            if (junk.size() > 300) junk.clear();
            Thread.sleep(1);
        }
    }
}
```

### 3.2 Windows 本机路线：Arthas 一把梭（5 分钟）

```powershell
cd D:\mywork\springbootai\learning\month01-jvm\day20
javac CpuDemo.java; java CpuDemo
# 另开窗口：java -jar arthas-boot.jar（day17 已下载）→ attach CpuDemo
```

```text
[arthas@PID]$ thread -n 3
   → 预期：burner-0..3 霸榜，CPU 接近 100%，栈顶指向 CpuDemo.main lambda
   → 这就是"业务线程忙"的定位结果

[arthas@PID]$ dashboard
   → 观察 GC 次数也在快速增长（分配压力制造了第二元凶）
```

### 3.3 Linux 四步法（Docker 容器内完整走一遍，面试就在这里）

```powershell
# ① 启动 Linux 容器（挂载 day20 目录，--cpus=2 限制核数更接近生产）
docker run -it --name cpu --rm `
  -v D:\mywork\springbootai\learning\month01-jvm\day20:/app `
  eclipse-temurin:17-jdk bash
```

```bash
# ── 容器内 ────────────────────────────────────────
cd /app && javac CpuDemo.java
java CpuDemo &                       # 后台跑
# （若容器缺工具：apt-get update && apt-get install -y procps）

# 四步法：
top                                  # ① 找到 java 进程 PID（如 123）
top -Hp 123                          # ② 找到 CPU 最高的线程 TID（如 130）
printf "%x\n" 130                    # ③ 转 16 进制 → 0x82
jstack 123 | grep -A 20 "nid=0x82"   # ④ 按 nid 匹配 → 看到 burner-2 的栈！
```

### 3.4 体验"GC 元凶"分支（改参数让 GC 当主角）

```bash
# 容器内：只留分配压力 + 限制小堆（-Xmx64m 逼 GC 疯狂工作）
kill %1                              # 停掉刚才的后台任务
java -Xms64m -Xmx64m CpuDemo &
top -Hp $(pgrep -f CpuDemo | head -1)
# 观察：占用最高的不再是 burner，而是 "G1 xxx" / "VM Thread" 等 GC 线程
# → 结论：CPU 飙高要先看"谁在烧"，GC 线程忙 → 转内存排查！
```

### 3.5 沉淀《CPU 排查 SOP》（贴工位）

```text
1. top 定进程 → top -Hp 定线程 → printf 转 hex → jstack 匹配 nid
2. 栈顶是业务代码 → 算法/缓存/限流（火焰图 day18 看占比）
3. 栈是 GC 线程 → jstat -gcutil / GC 日志（day13）→ 内存问题清单
4. 栈是 IO/wait → 看下游（DB/RPC）耗时
5. 全程截图留证，事后写复盘（格式用 day19 报告骨架）
```

## 4. 面试连接

**Q：线上 CPU 100%，你怎么排查？（标准答案模板）**
> 先分型再定位：top -Hp 找最忙线程，printf 转 16 进制，jstack 匹配 nid 看栈。栈是业务线程 → 定位代码行优化；栈是 GC 线程 → 转内存问题（GC 日志 + dump）。再补"平时留好后手：GC 日志滚动、OOM 自动 dump、Arthas/JFR 可随时上"体现体系化。

**Q：为什么 jstack 里要用 16 进制找线程？**
> jstack 的 nid（native thread id）是 16 进制表示，而 top -Hp 给的是 10 进制 TID，printf "%x" 转换后才能精确匹配，不然几千行栈里肉眼找不对应线程。

**Q：us 和 sy 哪个高说明什么？**
> us（用户态）高 = 应用代码在烧 CPU（算法热点/正则/序列化）；sy（内核态）高 = 频繁系统调用或上下文切换（锁竞争/线程过多/IO 密集）。sy 高重点查线程数和锁。

## 5. 今日验收清单

- [ ] CpuDemo 双元凶现象都复现（burner 霸榜 / GC 线程霸榜）
- [ ] Docker 容器内四步法完整走通一遍
- [ ] Arthas thread -n 3 路线走通一遍
- [ ] 《CPU 排查 SOP》成文
- [ ] `git add . && git commit -m "day20: cpu troubleshooting"`

---
[← Day 19](day19-MAT内存分析与泄漏定位.md) | [本月目录](README.md) | [Day 21 · 第三周复盘 →](day21-第三周复盘输出.md)
