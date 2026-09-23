# Day 03 · 运行时数据区与栈帧

> **今日目标**：掌握 JVM 内存全图（这是整个月最重要的一张图），理解栈帧结构，并亲手复现 StackOverflowError。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：手绘内存区域大图 + SOF 复现实验

## 1. 知识地图（本月最重要的一张图，day07 要默写）

```
                    JVM 运行时数据区
┌─────────────────────────────────────────────────────┐
│ 线程私有（每线程一份，随线程生灭）                       │
│ ┌────────────┐ ┌────────────┐ ┌───────────────┐     │
│ │ 程序计数器   │ │ 虚拟机栈    │ │ 本地方法栈      │     │
│ │ (Program   │ │ (JVM Stack)│ │ (Native Stack)│     │
│ │  Counter)  │ │ 栈帧×N      │ │ native 方法用  │     │
│ │ 当前指令地址 │ │ ↓见下方     │ │              │     │
│ └────────────┘ └────────────┘ └───────────────┘     │
├─────────────────────────────────────────────────────┤
│ 线程共享（OOM 的主战场）                               │
│ ┌─────────────────────────┐ ┌────────────────────┐ │
│ │ 堆 Heap                  │ │ 方法区(元空间实现)    │ │
│ │ ┌─────┐ ┌────────┐ ┌──┐ │ │ Metaspace          │ │
│ │ │Eden │ │Survivor│ │Old│ │ │ 类元信息/运行时常量池 │ │
│ │ └─────┘ └────────┘ └──┘ │ │ (JDK8+ 使用本地内存) │ │
│ │   年轻代        老年代     │ └────────────────────┘ │
│ └─────────────────────────┘   直接内存 Direct Memory │
│                                 (NIO 堆外，非 JVM 规范)│
└─────────────────────────────────────────────────────┘

栈帧（Stack Frame）——方法调用的"工位"：
┌─────────────────────────────┐
│ 局部变量表 Local Variables    │ ← int a 就存在这（slot 为单位）
├─────────────────────────────┤
│ 操作数栈 Operand Stack       │ ← 计算的草稿纸：a+b 先 push 再 iadd
├─────────────────────────────┤
│ 动态链接 Dynamic Linking     │ ← 符号引用 → 运行时常量池的直接引用
├─────────────────────────────┤
│ 方法返回地址 Return Address   │ ← 方法结束后 PC 跳回哪
└─────────────────────────────┘
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 关键点 |
|------|------|--------|
| Program Counter Register | 程序计数器 | 唯一不会 OOM 的区域；线程切换后恢复执行位置的依据 |
| Java Virtual Machine Stack | 虚拟机栈 | 抛 `StackOverflowError`（栈深超限）或 `OutOfMemoryError` |
| Local Variable Table | 局部变量表 | slot 复用影响 GC（叶节点置 null 的原理） |
| Metaspace | 元空间 | JDK8+ 方法区实现，用本地内存，默认无上限（受物理内存限制） |
| Direct Memory | 直接内存 | 不属于运行时数据区规范，但 OOM 会算它头上 |

**内存区域 → 异常对照表**（面试高频）：

| 区域 | 异常 | 典型场景 |
|------|------|---------|
| 堆 | `OutOfMemoryError: Java heap space` | 对象太多/泄漏 |
| 元空间 | `OutOfMemoryError: Metaspace` | 动态生成类过多 |
| 虚拟机栈 | `StackOverflowError` | 递归过深/无出口 |
| 直接内存 | `OutOfMemoryError: Direct buffer memory` | NIO 大量堆外分配 |

## 3. 动手实操

### 3.1 复现 StackOverflowError（看懂报错里的数字）

```java
public class StackSOF {
    private int depth = 0;

    private void recurse() {          // 无出口递归
        depth++;
        recurse();
    }

    public static void main(String[] args) {
        StackSOF s = new StackSOF();
        try {
            s.recurse();
        } catch (StackOverflowError e) {
            System.out.println("栈最大深度 = " + s.depth);
        }
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month01-jvm; mkdir day03 -Force; cd day03
javac StackSOF.java
java StackSOF                          # 默认 -Xss1m（1MB 栈），记下深度
java -Xss256k StackSOF                 # 栈改小 → 深度变小
java -Xss4m StackSOF                   # 栈改大 → 深度变大
```

**记录三组数字**（结论：栈容量 ≈ 栈帧大小 × 深度；单个栈帧越大，能叠的层数越少）。

### 3.2 看懂局部变量表与操作数栈

复用 day01 的 HelloJVM：

```powershell
cd ..\day01
javap -l -c HelloJVM
```

对照看 `add(int, int)` 方法：局部变量表有 3 个 slot（this/a/b）；`iadd` 指令把操作数栈顶两个 int 弹出相加再压回——**栈帧就是"方法执行的草稿纸"**。

### 3.3 观察 Metaspace 的真实存在

```powershell
# 用 jcmd 看一个运行中 JVM 的元空间占用（jcmd 全家桶 day15 细讲，今天先热身）
jps -l                 # 找到 pid
jcmd <pid> VM.metaspace | Select-Object -First 20
```

## 4. 面试连接

**Q：程序计数器为什么是线程私有的？唯一不 OOM 的区域？**
> 多线程通过 CPU 时间片轮转切换，切换后必须恢复到正确执行位置，每个线程需要独立的"当前指令地址"。它只存一个地址/undefined，无法存放对象，天然不 OOM。

**Q：方法区放什么？为什么 JDK8 用 Metaspace 替换永久代？**
> 类元信息、运行时常量池、静态变量（JDK8 起静态变量随 Class 对象在堆）。替换动机：永久代大小受 -XX:MaxPermSize 固定上限易 OOM，且与堆 GC 耦合；Metaspace 用本地内存、可自动扩容、独立 GC（类卸载）——动态代理类多的系统（Spring/反射狂魔）因此受益。

**Q：栈上抛 SOF 后，JVM 进程死了吗？**
> 不一定。SOF 是 Error 但如果被 catch（如 3.1 的代码）进程继续活。面试提醒：catch Error 通常不该这么做，实验除外。

## 5. 今日验收清单

- [ ] 白纸手绘运行时数据区大图（拍照存到 notes/，day07 默写比对）
- [ ] StackSOF 三组 -Xss 数据记录完毕，能说出"深度与栈容量、栈帧大小的关系"
- [ ] 能背出"内存区域 → 异常"对照表
- [ ] `git add . && git commit -m "day03: runtime data areas"`
- [ ] 笔记：为什么叶节点置 null 能帮 GC（提示：局部变量表 slot 复用 + 栈帧未出作用域）

---
[← Day 02](day02-类加载与双亲委派.md) | [本月目录](README.md) | [Day 04 · 对象内存布局 →](day04-对象内存布局JOL.md)
