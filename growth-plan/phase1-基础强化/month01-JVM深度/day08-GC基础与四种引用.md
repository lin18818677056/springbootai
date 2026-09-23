# Day 08 · GC 基础：存活判定与四种引用

> **今日目标**：搞懂 JVM 如何判断"对象死了"（可达性分析 vs 引用计数），亲手复现四种引用强度差异，理解虚引用与堆外内存回收的联动。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：四种引用实验代码 + GC Roots 清单笔记

## 1. 知识地图

```
判断对象存活的两种思路：

方案 A：引用计数（Reference Counting）
  每个对象记"有多少人引用我"，为 0 就回收
  ┌────┐1   1┌────┐
  │ A  │←──→│ B  │   A、B 互相引用，计数都=1
  └────┘     └────┘   但没有第三方引用它们 → 永远收不回来（循环引用）
  ✗ JVM 不采用！Python/COM 采用（配合别的机制缓解）

方案 B：可达性分析（Reachability Analysis）★ JVM 采用
  从 GC Roots 出发向下搜索，走过的路径叫引用链
  搜不到的对象 = 不可达 = 可回收

  GC Roots 是哪些？（背下来）
  ┌─ 虚拟机栈中引用的对象（正在执行的方法里的局部变量）★最常见
  ├─ 静态变量引用的对象          ★次常见（day25 泄漏头号凶手）
  ├─ 常量引用的对象（字符串常量池里的）
  ├─ JNI 引用（native 代码持有的）
  └─ JVM 内部引用（Class 对象/异常对象/类加载器）

引用强度（强→弱）：
强引用 ──→ 软引用 ──→ 弱引用 ──→ 虚引用
Strong    Soft     Weak     Phantom
GC永不收   内存不足收  GC就收    形同虚设,仅作通知(配ReferenceQueue)
(默认)     (缓存)    (WeakHashMap/ThreadLocal.key)  (堆外内存清理器)
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Reachability Analysis | 可达性分析 | 从 GC Roots 搜索判定存活 |
| GC Roots | GC 根集合 | 栈帧局部变量/静态变量/常量/JNI 引用等 |
| Soft/Weak/Phantom Reference | 软/弱/虚引用 | java.lang.ref 包，强度递减 |
| Reference Queue | 引用队列 | 引用对象被回收后，引用本身入队供程序感知 |
| Self Reference | 对象自我拯救 | finalize 中重新挂引用可自救一次（已废弃思路，了解即可） |
| finalize | 终结方法 | JDK18 起 Deprecated for Removal，替代品 Cleaner |

## 3. 动手实操

### 3.1 四种引用强度实验（核心代码）

```java
import java.lang.ref.*;
import java.util.ArrayList;
import java.util.List;

public class ReferenceDemo {
    public static void main(String[] args) throws Exception {
        // ===== 软引用：内存不足才收 =====
        SoftReference<byte[]> soft = new SoftReference<>(new byte[10 * 1024 * 1024]);
        System.out.println("GC前 soft 活着: " + (soft.get() != null));
        System.gc();
        System.out.println("GC后(内存充足) soft 活着: " + (soft.get() != null));  // true！
        // 把上面 byte[10M] 改成循环塞满堆再 GC，它就没了 —— "内存不足才收"

        // ===== 弱引用：GC 就收 =====
        WeakReference<byte[]> weak = new WeakReference<>(new byte[1024]);
        System.gc();
        Thread.sleep(100);
        System.out.println("GC后 weak 活着: " + (weak.get() != null));   // false！
    }
}
```

### 3.2 虚引用 + 引用队列：堆外内存回收原理

```java
import java.lang.ref.*;

public class PhantomDemo {
    public static void main(String[] args) throws Exception {
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        PhantomReference<Object> phantom =
                new PhantomReference<>(new Object(), queue);   // get() 永远返回 null

        System.out.println("phantom.get() = " + phantom.get());   // null，永远
        System.gc();
        Thread.sleep(100);
        Reference<?> ref = queue.poll();                       // 对象死了 → 引用入队
        System.out.println("对象被回收后引用入队: " + (ref == phantom));
        // DirectByteBuffer 的 Cleaner 就是这套机制：虚引用入队 → 触发 freeMemory() 释放堆外
        // ← 这就是 day06 堆外内存回收的底层原理，两个知识闭环了！
    }
}
```

### 3.3 复现"强引用链导致不可回收"

```java
public class StrongRefLeak {
    static final List<byte[]> CACHE = new ArrayList<>();     // 静态变量 = GC Root！

    public static void main(String[] args) {
        for (int i = 0; i < 10; i++) {
            CACHE.add(new byte[1024 * 1024]);                // 每 MB 都被 Root 可达
        }
        System.out.println("PID = " + ProcessHandle.current().pid());
        // 另开终端：jmap -histo:live <pid> | Select-String "byte"
        // 会看到 10MB byte[] 都活着 —— 即使业务上"早就不需要它们了"
    }
}
```

### 3.4 思考题

ThreadLocal 的 key 为什么设计成弱引用？如果 key 是强引用会怎样？（答案要点：线程池中线程长存，key 强引用会导致 ThreadLocal 对象永不回收；弱引用 key 让"无外部引用的 ThreadLocal"下次 GC 自动清除 entry.key，但 value 仍需显式 remove——day25 细讲这个泄漏）

## 4. 面试连接

**Q：JVM 为什么不用引用计数？**
> 循环引用无法回收 + 计数器维护开销（每次赋值都要增减且需并发安全）。可达性分析一次扫描全局判定，无循环引用问题。

**Q：软引用的实际用途？**
> 敏感内存的缓存：图片缓存、MyBatis 一级缓存思想。内存富余时多存提升命中，内存紧张时 GC 自动腾空间——"拿内存换命中率，但绝不 OOM"。配合 -Xmx 规划。

**Q：finalize 为什么被废弃？**
> 执行时机不确定（可能永不执行）、与 GC 竞争导致对象复活问题、性能损耗。JDK9+ 用 Cleaner：显式注册清理动作、由 Reference 机制驱动、行为可预测。

## 5. 今日验收清单

- [ ] ReferenceDemo / PhantomDemo / StrongRefLeak 三段代码跑通并理解输出
- [ ] 能默写 GC Roots 五类来源
- [ ] 能说清虚引用与 day06 堆外内存回收的闭环关系
- [ ] `git add . && git commit -m "day08: gc roots & references"`
- [ ] 笔记：ThreadLocal 弱引用设计题作答

---
[← Day 07](day07-第一周复盘输出.md) | [本月目录](README.md) | [Day 09 · GC 算法与分代模型 →](day09-GC算法与分代模型.md)
