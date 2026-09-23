# Day 15 · 命令行工具全家桶（jps/jstat/jmap/jstack/jcmd）

> **今日目标**：掌握 JDK 自带的六大诊断命令，每个都亲手打在"活着的进程"上，产出一份你自己的速查表。
> **时长**：理论 0.5h / 实操 2h / 输出 0.5h
> **今日产出**：《JVM 命令速查表 v1》（以后贴工位那种）

## 1. 知识地图

```
诊断路径总览（遇到问题按这个顺序走）：
  找进程        看概况         看细节          抓现场
┌────────┐  ┌──────────┐  ┌───────────┐  ┌──────────────┐
│ jps -l  │→│ jstat -gc │→│ jmap/jstack│→│ jcmd / dump  │
│ 谁在跑   │  │ GC健康吗   │  │ 内存/线程  │  │ 文件留证     │
└────────┘  └──────────┘  └───────────┘  └──────────────┘
                                ↑
                     jcmd 是"瑞士军刀"：以上所有 + 更多

六大工具一句话定位：
  jps   —— "列出所有 JVM 进程"（= ps 专门为 java 优化）
  jstat —— "GC 心跳监护仪"（周期采样，day05 已用过）
  jmap  —— "内存照相机"（-histo 类直方图 / -dump 全量快照）
  jstack—— "线程合影"（某一刻所有线程在干嘛，查死锁/卡顿）
  jinfo —— "参数查看器"（-flag 查看/运行时改部分参数）
  jcmd  —— "全功能遥控器"（JDK 推荐，功能超集）
```

## 2. 核心概念（中英对照）

| 命令 | 中文定位 | 最常用姿势 |
|------|---------|-----------|
| jps | JVM 进程列表 | `jps -l`（含主类全名） |
| jstat | GC 统计采样 | `jstat -gcutil <pid> 1000` |
| jmap | 内存映射/快照 | `jmap -histo:live` / `jmap -dump` |
| jstack | 线程转储 | `jstack <pid>`（看 BLOCKED/deadlock） |
| jinfo | 配置信息 | `jinfo -flags <pid>` |
| jcmd | JVM 控制命令 | `jcmd <pid> help` 看全部能力 |

> 注意：`jmap -dump` 会触发一次 FullGC（`:live` 时），大堆服务慎用——生产优先 `-XX:+HeapDumpOnOutOfMemoryError` 事后取证或 JFR（day16）。

## 3. 动手实操

### 3.1 准备"受害者"程序（含内存/线程两种症状）

```java
import java.util.*;
public class DiagTarget {
    static Map<String, byte[]> CACHE = new HashMap<>();
    static final Object LOCK_A = new Object();
    static final Object LOCK_B = new Object();

    public static void main(String[] args) throws Exception {
        System.out.println("PID = " + ProcessHandle.current().pid());
        new Thread(() -> {                       // 线程1：先 A 后 B
            synchronized (LOCK_A) { sleep(100); synchronized (LOCK_B) {} }
        }, "worker-1").start();
        new Thread(() -> {                       // 线程2：先 B 后 A → 死锁！
            synchronized (LOCK_B) { sleep(100); synchronized (LOCK_A) {} }
        }, "worker-2").start();

        int i = 0;
        while (true) {                           // 主线程：缓慢泄漏
            CACHE.put("key" + i++, new byte[1024 * 100]);
            Thread.sleep(200);
        }
    }
    static void sleep(long ms){ try{Thread.sleep(ms);}catch(Exception e){} }
}
```

### 3.2 六连击实操（按顺序执行并记录输出）

```powershell
cd D:\mywork\springbootai\learning\month01-jvm; mkdir day15 -Force; cd day15
javac DiagTarget.java; java DiagTarget        # 记下 PID

# ① jps：进程在哪
jps -l

# ② jstat：GC 健康吗（30 秒采样看 O 段爬升）
jstat -gcutil <PID> 1000 30

# ③ jmap -histo：谁占内存（Top10）
jmap -histo:live <PID> | Select-Object -First 15
# 预期 byte[] 一骑绝尘 → 结合业务猜"是谁的缓存"

# ④ jstack：线程都在干嘛（重点找死锁！）
jstack <PID> > thread.txt
Select-String -Path thread.txt -Pattern "Found one Java-level deadlock" -Context 0,15
# JDK 的死锁检测会直接告诉你 worker-1 和 worker-2 互相等待 → 报告原文截图

# ⑤ jinfo：看参数
jinfo -flags <PID>
jinfo -flag MaxHeapSize <PID>

# ⑥ jcmd：瑞士军刀（等效以上所有）
jcmd <PID> help                    # 列出所有能力
jcmd <PID> GC.heap_info            # 堆概况（= jstat 的加强版）
jcmd <PID> Thread.print | Select-String "deadlock" -Context 0,10
jcmd <PID> GC.class_histogram | Select-Object -First 12
```

### 3.3 抓一次 heap dump 并测量大小

```powershell
jmap -dump:live,format=b,file=dump.hprof <PID>
Get-Item dump.hprof | Select-Object Length     # 记录大小（day19 用 MAT 打开它）
```

### 3.4 制作《命令速查表 v1》

按下面模板完成（存 notes/cheatsheet-jvm.md，以后持续扩充）：

| 症状 | 第一步 | 第二步 | 深挖 |
|------|--------|--------|------|
| 疑似内存泄漏 | jstat -gcutil 看 O 段 | jmap -histo:live | jmap -dump → MAT |
| 接口卡顿/死锁 | jstack 找 BLOCKED | 查 Found deadlock | Arthas thread（day17） |
| GC 频繁 | jstat -gcutil | -Xlog:gc 日志 | 触发原因分析（day13） |
| 进程消失 | hs_err_pid*.log | 系统日志 | OOM dumper 配置 |

## 4. 面试连接

**Q：线上怎么发现死锁？**
> `jstack <pid>` 底部自动输出 "Found one Java-level deadlock" 并给出互等链路；或 Arthas `thread -b`（blocked 一键定位）。顺带说出"避免死锁：统一加锁顺序/tryLock 超时"补全答案。

**Q：jmap dump 会有什么影响？**
> `:live` 触发 FullGC（STW）；dump 期间整个 JVM 挂起、文件与堆同大。所以大堆生产环境优先"故障时自动 dump（HeapDumpOnOutOfMemoryError）"或低峰手工，别在高峰现场抓全量。

## 5. 今日验收清单

- [ ] DiagTarget 跑通，六命令输出全部截图/摘录
- [ ] 死锁报告原文找到并读懂互等链
- [ ] dump.hprof 已生成（保留，day19 用）
- [ ] 《命令速查表 v1》完成
- [ ] `git add . && git commit -m "day15: cli toolkit"`

---
[← Day 14](day14-第二周复盘输出.md) | [本月目录](README.md) | [Day 16 · JFR 飞行记录仪 →](day16-JFR飞行记录仪.md)
