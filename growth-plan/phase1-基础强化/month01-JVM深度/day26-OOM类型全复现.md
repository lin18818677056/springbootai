# Day 26 · OOM 类型全复现（六种都亲手造一次）

> **今日目标**：OOM 不止一种！堆/Meta/直接内存/线程数/栈深度各有元凶。今天六种全部最小复现，配"现场识别 → 处置 SOP"，以后看到 OOM 报错 10 秒内判断方向。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：《OOM 处置手册》+ 六种复现记录

## 1. 知识地图

```
六种 OOM 一张图（报错信息 = 指路牌）：

报错关键字                                元凶在哪     一句话处置
────────────────────────────────────────────────────────────────────
① Java heap space                        堆           dump 分析泄漏/扩堆/拆大对象
② GC overhead limit exceeded             堆           堆快满且 GC 白忙（>98% 时间回收<2%）→ 同①
③ Metaspace                              元空间       类加载泄漏（day25 场景⑥）→ 查动态类
④ Direct buffer memory                   堆外         NIO Buffer 泄漏 → -XX:MaxDirectMemorySize 上限
⑤ unable to create new native thread     OS 层        线程数超限 → 查线程池泄漏/ulimit
⑥ StackOverflowError                     线程栈       递归过深/栈小 → 查递归出口或 -Xss

全局准备：任何 OOM 现场必须有的两个配置（day24 模板已含）
  -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=dump/
  -XX:+ExitOnOutOfMemoryError        （容器/K8s 环境快速失败）

处置 SOP（收到 OOM 告警后 15 分钟）：
  ① 保现场：dump 文件在不在？容器有没有被 K8s 干掉重启？
  ② 读报错：六种对号入座（上表）→ 定方向
  ③ 看 GC 日志：崩溃前最后几分钟的触发原因/频率
  ④ dump 分析（①②）或线程 dump（⑤⑥）或 Meta 统计（③）
  ⑤ 修复 + 复跑验证 + 写复盘
```

## 2. 核心概念（中英对照）

| 英文/报错 | 中文 | 要点 |
|-----------|------|------|
| OutOfMemoryError | 内存溢出 | 六种类型的父类（java.lang.OutOfMemoryError） |
| GC overhead limit exceeded | GC 开销超限 | 堆将满未满、GC 做无用功的预警 |
| Direct Buffer | 直接内存缓冲 | NIO 的堆外内存（Unsafe/ByteBuffer.allocateDirect） |
| native thread | 原生线程 | OS 线程创建失败（≠Java 层问题） |
| StackOverflowError | 栈溢出 | 严格说是 Error 不是 OOM，但常一起问 |

## 3. 动手实操（六连复现，目录 day26）

### 3.1 ①② 堆溢出 + GC 开销超限

```java
// OomHeap.java —— 一行就够
import java.util.*;
public class OomHeap {
    public static void main(String[] a) throws Exception {
        List<byte[]> list = new ArrayList<>();
        while (true) list.add(new byte[1024 * 1024]);   // 1MB 无限加
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month01-jvm; mkdir day26 -Force; cd day26
javac OomHeap.java
java -Xmx64m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=. OomHeap
# 报错：java.lang.OutOfMemoryError: Java heap space + 自动生成 .hprof
# （慢速泄漏场景才会触发 ②：把 add 改成高频小对象可体验 GC overhead limit exceeded）
```

### 3.2 ③ Metaspace 溢出（动态类轰炸）

```java
// OomMeta.java —— 用编译 API 疯狂造类（本质：类元数据塞爆元空间）
import java.net.URLClassLoader;
public class OomMeta {
    public static void main(String[] args) throws Exception {
        var tools = java.lang.ProcessHandle.current();   // 占位避免误用导入
        int i = 0;
        while (true) {
            // 每轮新 ClassLoader + defineClass（简化：用 Groovy/asm 的思路）
            var cl = new URLClassLoader(new java.net.URL[0], OomMeta.class.getClassLoader());
            i++;
        }
    }
}
```

> 更实用的复现：`javac` 一个生成 10 万个代理类的小程序太绕，直接用场景⑥思路：
> `java -XX:MaxMetaspaceSize=16m` 跑 day25 的类加载器循环版即可看到
> `OutOfMemoryError: Metaspace`。观察命令：`jcmd <pid> VM.metaspace`。

### 3.3 ④ 直接内存溢出

```java
// OomDirect.java
import java.nio.ByteBuffer;
public class OomDirect {
    public static void main(String[] args) {
        while (true) {
            ByteBuffer.allocateDirect(1024 * 1024);   // 1MB 堆外，引用立即丢弃
        }   // Cleaner 异步回收追不上分配速度 → 爆
    }
}
```

```powershell
javac OomDirect.java
java -XX:MaxDirectMemorySize=64m OomDirect
# 报错：java.lang.OutOfMemoryError: Cannot reserve ... / Direct buffer memory
```

### 3.4 ⑤ 线程数溢出

```java
// OomThread.java —— 挂起线程不退出，只增不减
public class OomThread {
    public static void main(String[] args) throws Exception {
        int i = 0;
        while (true) {
            new Thread(() -> { try { Thread.sleep(Long.MAX_VALUE); } catch (Exception e) {} }).start();
            i++;
        }
    }
}
```

```powershell
javac OomThread.java
java OomThread
# 报错：java.lang.OutOfMemoryError: unable to create native thread
# （几秒到几十秒；万一线程把机器卡死，Ctrl+C 结束）
# 生产含义：线程池未复用/无限创建 是真凶，OS ulimit 只是最后的墙
```

### 3.5 ⑥ 栈溢出

```java
// OomStack.java
public class OomStack {
    static int depth = 0;
    static void recurse() { depth++; recurse(); }
    public static void main(String[] args) {
        try { recurse(); } catch (StackOverflowError e) {
            System.out.println("max depth = " + depth);
        }
    }
}
```

```powershell
javac OomStack.java
java -Xss512k OomStack     # 记录深度，再跑 -Xss256m 对比 → 深度随栈大小线性涨
# 面试彩蛋：真实案例多是"递归没出口/JSON 序列化循环引用"
```

### 3.6 沉淀《OOM 处置手册》

```text
模板（notes/oom-playbook.md）：每种 OOM 一行表格
  报错关键字 | 元凶区域 | 现场要抓什么 | 排查工具 | 常见根因 Top3
例：Metaspace | 元空间 | jcmd VM.metaspace、类加载日志 | MAT/类计数 | 动态代理滥用、热部署泄漏
```

## 4. 面试连接

**Q：OOM 有哪些类型？分别怎么处理？**
> 六种对号（报错关键字即指纹）：堆（dump 分析泄漏）、Meta（查动态类）、直接内存（MaxDirectMemorySize+Buffer 泄漏）、线程数（查线程池）、栈（查递归）。能报全六种 + 每种一个处置动作，就超过 90% 候选人。

**Q：线上 OOM 了，你的第一反应是什么？**
> 先保现场（dump 有没有生成？容器会不会被重启清掉？——所以预案要有 HeapDumpPath 挂载到持久卷），再读报错定方向，然后 GC 日志+dump 双线取证。切忌上来就重启销毁证据。

**Q：GC overhead limit exceeded 是什么意思？**
> 堆接近耗尽时，GC 花了 >98% 时间却回收 <2% 空间，JVM 判定"没救了"主动抛错防止活活拖死。本质还是堆溢出的预警形态，处置同堆 OOM。

## 5. 今日验收清单

- [ ] 六种 OOM 至少复现 5 种（Meta 可用简化版）
- [ ] 每种记录报错原文关键字
- [ ] 《OOM 处置手册》成文
- [ ] 能 10 秒内根据报错关键字判断元凶区域
- [ ] `git add . && git commit -m "day26: six oom types"`

---
[← Day 25](day25-内存泄漏六大场景.md) | [本月目录](README.md) | [Day 27 · G1 调优实战 →](day27-G1调优实战.md)
