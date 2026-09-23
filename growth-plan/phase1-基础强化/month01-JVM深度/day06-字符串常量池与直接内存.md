# Day 06 · 字符串常量池与直接内存

> **今日目标**：终结"String s = new String("a") 创建几个对象"这类经典题；搞懂直接内存（堆外内存）是什么、为什么 Netty 非它不可，并复现一次 Direct buffer OOM。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：intern 实验记录 + 堆外 OOM 复现

## 1. 知识地图

```
字符串的三个"池"别搞混：
┌─────────────┐  ┌──────────────────┐  ┌─────────────────────┐
│ class文件常量池│→│ 运行时常量池(元空间) │  │ 字符串常量池 String   │
│ Constant Pool │  │ Runtime Constant  │  │ Table（物理上在堆里， │
│ 编译期确定     │  │ Pool              │  │ JDK7起！存放String   │
└─────────────┘  └──────────────────┘  │ 实例的引用)          │
                                        └─────────────────────┘

new String("a") 的对象数（经典题）：
编译期："a" 进 class 常量池
运行期 main 首次执行：
  ① "a" 若常量池没有 → 堆里创建 "a" 对象 + 引用进 StringTable   (1个)
  ② new String() → 堆里再创建一个新 String 对象               (1个)
  答案：1 或 2 个（取决于 "a" 是否已在池中）——面试要答出"条件式答案"

intern() 的语义（JDK7+ 关键变化）：
  s.intern()：池里有等值串 → 返回池中引用；没有 → 把【当前对象引用】入池
  JDK6：没有 → 复制一份到永久代（池在永久代）→ 结果引用不同
  JDK7+：没有 → 直接存堆中引用 → 结果引用相同   ← 这就是那道 true/false 题的考点

直接内存 Direct Memory（堆外）：
┌────── JVM堆 ──────┐      ┌──── 操作系统 ────┐
│ Heap ByteBuffer    │ copy │  网卡/文件         │
│   ↓               │      │        ↑          │
│ DirectByteBuffer ──┼─引用─→│ 堆外内存(off-heap) │ ← 免去堆内外来回拷贝
└───────────────────┘      └──────────────────┘
  分配：ByteBuffer.allocateDirect(n) → Unsafe.allocateMemory
  大小限制：-XX:MaxDirectMemorySize（默认≈-Xmx）
  回收：Cleaner（JDK9+ 替代 finalize），PhantomReference 机制（day08 呼应）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| String Table | 字符串常量池 | 哈希表结构，JDK7 起移到堆，可参与 GC |
| intern() | 入池方法 | 返回池中规范化引用 |
| Off-heap / Direct Memory | 堆外/直接内存 | 本机内存，不受堆 GC 管理，受 MaxDirectMemorySize 限制 |
| Zero-Copy | 零拷贝 | 堆外内存让数据免于"堆↔系统"反复拷贝（Kafka 的 sendfile 是另一类零拷贝，day-bigdata 呼应） |
| Cleaner | 清理器 | JDK9+ 取代 finalize 的资源回收钩子 |

## 3. 动手实操

### 3.1 实验一：intern 的 true/false 之谜

```java
public class InternDemo {
    public static void main(String[] args) {
        // 场景 1：先字面量后 new
        String s1 = "abc";                    // "abc" 入池
        String s2 = new String("abc");        // 新建堆对象
        System.out.println(s1 == s2);                  // false：两个对象
        System.out.println(s1 == s2.intern());         // true：intern 返回池中已有引用(就是s1)

        // 场景 2：先 new 后 intern（JDK7+ 名场面）
        String s3 = new StringBuilder("ja").append("va").toString();  // 堆新对象，池中没有 "java"? 注意："java"字面量启动时已在池中！换成其他串
        String s4 = new StringBuilder("计算机").append("技术").toString();
        System.out.println(s3.intern() == s3);   // false："java" 启动时已入池（历史原因）
        System.out.println(s4.intern() == s4);   // true：池中没有 → 存了 s4 的引用
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month01-jvm; mkdir day06 -Force; cd day06
javac InternDemo.java; java InternDemo
# 把 4 个输出与注释逐一核对，讲给想象中的面试官听（费曼法）
```

### 3.2 实验二：字符串常量池会 GC 吗？

```powershell
# 观察字符串表统计
java -XX:+PrintStringTableStatistics -version | Select-String "StringTable"
# -XX:StringTableSize=1000003 可调桶数（大量 intern 场景调大减少哈希碰撞）
```

### 3.3 实验三：复现 Direct buffer OOM（今天的重头戏）

```java
import java.nio.ByteBuffer;

public class DirectOOM {
    public static void main(String[] args) {
        System.out.println("PID = " + ProcessHandle.current().pid());
        java.util.List<ByteBuffer> list = new java.util.ArrayList<>();
        int i = 0;
        while (true) {
            list.add(ByteBuffer.allocateDirect(1024 * 1024));  // 每次 1MB 堆外
            System.out.println("已分配 " + (++i) + "MB");
        }
    }
}
```

```powershell
javac DirectOOM.java
java -Xmx32m -XX:MaxDirectMemorySize=32m DirectOOM
# 预期：java.lang.OutOfMemoryError: Direct buffer memory
# 记录：分配了多少 MB 后炸 → 32MB 附近（说明 MaxDirectMemorySize 生效）

# 关键实验：把 list 释放再分配，堆外会复用吗？
# 修改代码：分配 100 次后 list.clear() + System.gc()，再分配 100 次 → 不炸
# 结论：堆外内存由 Cleaner 在 GC 时清理（GC 不主动跑就会占着——这就是 Netty 主动 release 的原因）
```

### 3.4 思考题

为什么 Netty/Redis 客户端/消息中间件都偏爱堆外内存做 IO 缓冲？（提示：堆内 byte[] 传给操作系统的 write() 前，必须先拷到一块"GC 不敢动的内存"，否则 GC 挪动对象后内核写的是废地址）

## 4. 面试连接

**Q：String s = new String("a") 创建几个对象？**
> 条件答案：最多 2 个（池中一个 + 堆中一个）；"a" 已在池中则 1 个。能讲出 JDK7 字符串池从永久代移到堆、intern 行为差异的是 P7 信号。

**Q：堆外内存怎么监控和排查？**
> NMT（Native Memory Tracking，`-XX:NativeMemoryTracking=summary` + `jcmd <pid> VM.native_memory`）看 JVM 视角；操作系统的泄漏排查（day29 会用到）。MaxDirectMemorySize 一定要显式设，否则默认等于 Xmx 可能超预期。

**Q：什么场景必须用堆外？**
> 高频 IO 缓冲（Netty）、大块缓存想避免 GC 压力（缓存系统）、进程间共享。同时必答代价：分配释放慢、要手动管理生命周期、泄漏更难查。

## 5. 今日验收清单

- [ ] InternDemo 四个输出全部对上并能解释（特别是 s3 的 false）
- [ ] DirectOOM 复现成功，记录 MaxDirectMemorySize 生效证据
- [ ] 能回答"为什么 IO 框架偏爱堆外内存"
- [ ] `git add . && git commit -m "day06: string table & direct memory"`
- [ ] 笔记：画"三个池"的关系图

---
[← Day 05](day05-对象创建与内存分配.md) | [本月目录](README.md) | [Day 07 · 第一周复盘 →](day07-第一周复盘输出.md)
