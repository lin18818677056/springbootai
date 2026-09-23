# Day 03 · JMM 与 happens-before（判断线程安全的标尺）

> **今日目标**：JMM（Java Memory Model，Java 内存模型）是并发世界的"交通法规"。今天掌握主内存/工作内存抽象、happens-before 八大规则，并亲手观测指令重排的存在——从此判断线程安全不再靠感觉。
> **时长**：理论 1.5h / 实操 1.5h / 输出 0.5h
> **今日产出**：JMM 交互图 + happens-before 判定练习答案 + 重排观测数据

## 1. 知识地图

```
JMM 抽象结构（屏蔽所有硬件/OS 差异的统一模型）：

   线程 A 工作内存        主内存 Main Memory       线程 B 工作内存
  ┌──────────────┐      ┌──────────────┐       ┌──────────────┐
  │ flag 的副本   │◄────►│  flag = true │◄─────►│ flag 的副本   │
  └──────────────┘ save │  (唯一真身)   │ load  └──────────────┘
      write/read        └──────────────┘
  线程只操作副本 → 副本不回写/不刷新 = 可见性问题（day01 复现过的根）

happens-before（先行发生）八大规则——"如果 A hb B，则 A 的结果对 B 可见"：
  ① 程序顺序规则    单线程内，前面的操作 hb 后面的（按代码顺序）
  ② 监视器锁规则    解锁 hb 后续对同一把锁的加锁
  ③ volatile 规则   volatile 写 hb 后续对它的读 ★day04 主角
  ④ 线程启动规则    Thread.start() hb 该线程内任何操作
  ⑤ 线程终止规则    线程内所有操作 hb join() 返回 / isAlive() 检测
  ⑥ 线程中断规则    interrupt() 调用 hb 被中断线程检测到中断
  ⑦ 对象终结规则    构造结束 hb finalize() 开始
  ⑧ 传递性          A hb B 且 B hb C → A hb C
  记法：③②是"同步武器"，④⑤⑥⑦是"线程生命周期"，①⑧是"底层公理"

as-if-serial 语义：单线程内重排不能改变执行结果（所以单线程永远感觉不到重排）
指令重排的三层来源：编译器优化 / CPU 乱序执行 / 内存系统重排（写缓冲）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| JMM (Java Memory Model) | Java 内存模型 | 定义线程与主内存的交互规则 |
| Main / Working Memory | 主内存/工作内存 | 共享变量真身 / 线程本地副本 |
| happens-before | 先行发生 | 可见性的判定规则集（不是时间先后！） |
| as-if-serial | 串行语义 | 单线程视角结果不变（重排的前提约束） |
| Data Race | 数据竞争 | 并发访问共享变量且无同步措施 |
| Memory Barrier | 内存屏障 | 禁止特定重排的 CPU 指令（day04 展开） |

## 3. 动手实操

### 3.1 画 JMM 交互图（10 分钟，拍照存档）

```
场景：线程 A 执行 a=1; flag=true;  线程 B 执行 if(flag) print(a)
画出：a 和 flag 的副本流动、A 的 write、B 的 read、可能看不到的路径
      → 标注"没有任何 happens-before 边连接 A 和 B" → 数据竞争成立
```

### 3.2 实验：观测指令重排（Dekker 模式）

```java
// ReorderDemo.java —— 两个线程各写各的，结果可能"双双为 0"
public class ReorderDemo {
    static int x, y, a, b;

    public static void main(String[] args) throws Exception {
        int weird = 0;
        for (int i = 0; i < 100_000; i++) {
            x = y = a = b = 0;
            Thread t1 = new Thread(() -> { a = 1; x = b; });   // 期望：x 要么0要么1
            Thread t2 = new Thread(() -> { b = 1; y = a; });
            t1.start(); t2.start(); t1.join(); t2.join();
            if (x == 0 && y == 0) weird++;    // 正常时序不可能双 0！
        }
        System.out.println("双 0 出现次数: " + weird + " / 100000");
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month02-concurrency
javac ReorderDemo.java; java ReorderDemo
# 预期：出现几十~几千次双 0（x=0,y=0）
# 归因：可能是 ②③ 与 ①④ 重排，也可能是线程交错执行——两种都算"乱象"
#       （严格归因需 JCStress 工具，知道有这个层面即可）
# 加 volatile 修饰 a,b → 双 0 归零（volatile 禁止了相关重排，day04 接上）
```

### 3.3 happens-before 判定练习（先自己判，再对答案）

```text
题1：线程 A 写 x=1；线程 B（A start 的）读 x。安全吗？
    → 安全。规则④：start() hb 子线程内所有操作。
题2：线程 A 写 x=1 后退出；B 调 A.join() 后读 x。安全吗？
    → 安全。规则⑤+⑧。
题3：A：synchronized(lock){ x=1; }  B：synchronized(lock){ r=x; } 安全吗？
    → 安全。规则②：A 的解锁 hb B 的加锁。
题4：A：x=1; flag=true（flag 无 volatile） B：while(!flag); r=x。安全吗？
    → 不安全！没有任何 hb 边（无锁/volatile/线程关系）→ day04 加 volatile 修复。
```

## 4. 面试连接

**Q：为什么要设计 JMM 这种抽象？**
> 硬件内存架构（多级缓存/写缓冲/乱序）千差万别，JMM 在 Java 层定义统一规则，把差异"压平"：对开发者提供 happens-before 保障，对 JVM 提供插入内存屏障的依据。一句话："JMM 是跨平台可见性的合同文本"。

**Q：happens-before 是时间上的先后吗？**
> 不是！它是可见性保证的偏序关系——"A hb B"意思是 A 的操作结果对 B 可见（A hb B 可以发生在时间上同时甚至乱序执行）。这题答对直接区分背书党和理解党。

**Q：怎么判断一段代码是否线程安全？**
> 套路：找共享变量 → 找并发访问路径 → 逐对操作找 happens-before 边（八大规则）→ 有边则安全，无边则数据竞争。现场用题 4 的例子演示这个套路。

## 5. 今日验收清单

- [ ] JMM 交互图手绘完成
- [ ] 八大规则能默写（至少标题级别）
- [ ] ReorderDemo 跑出非零双 0，加 volatile 后归零
- [ ] 判定练习 4 题全对（说出用哪条规则）
- [ ] `git add . && git commit -m "day03: jmm & happens-before"`

---
[← Day 02](day02-线程基础与生命周期.md) | [本月目录](README.md) | [Day 04 · volatile 深度剖析 →](day04-volatile深度剖析.md)
