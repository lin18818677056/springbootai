# Day 05 · synchronized（上）：字节码与 Monitor

> **今日目标**：从字节码层面看清 synchronized 到底做了什么（monitorenter/monitorexit vs ACC_SYNCHRONIZED），理解 monitor（监视器）的数据结构，并回答"它锁的到底是什么"。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：三种用法的字节码对照笔记 + 锁对象选择原则表

## 1. 知识地图

```
synchronized 三种用法 → 锁的是哪个对象（必须张口就来）：
  ① 同步实例方法   synchronized void m()     → 锁 this（当前实例）
  ② 同步静态方法   static synchronized void m() → 锁 Class 对象（全类唯一）
  ③ 同步代码块     synchronized(obj) { }      → 锁 obj（你指定的）

字节码层（javap -c 亲眼看）：
  同步代码块：
     monitorenter                 ← 竞争尝试获取 monitor
       ... 业务代码 ...
     monitorexit                  ← 正常路径释放
     astore_2 / monitorexit       ← ★异常路径也要释放（两条 exit 的原因）
  同步方法：
     方法没有 monitorenter！靠 flags 里的 ACC_SYNCHRONIZED 标志
     → JVM 调用方法时隐式获取/释放 monitor

monitor（监视器）是什么？——每个 Java 对象天生自带一个"门禁系统"：
   ┌──────────────────────────────────┐
   │ ObjectMonitor（C++ 实现）          │
   │  _owner      当前持锁线程          │
   │  _recursions 重入计数（可重入的根）  │
   │  _EntryList  抢锁失败的排队线程     │
   │  _WaitSet    调 wait() 去的休息室   │
   └──────────────────────────────────┘
  加锁 = CAS 把 _owner 设为自己（成功进入 / 失败去 EntryList 排队）
  释放 = _owner 置空 + 从 EntryList 唤醒一个
  可重入 = 进来时 _recursions+1，退出时 -1，为 0 才真释放

month01 衔接：这些状态存在哪？→ 对象头的 Mark Word（day04 JOL 见过 64bit 布局）
  无锁/轻量级时 Mark Word 直接编码锁状态；重量级时指向 ObjectMonitor 指针
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Monitor / Mutex | 监视器/互斥量 | 操作系统概念，Java 对象头的"门禁" |
| monitorenter / monitorexit | 监视器进入/退出 | 同步块的字节码指令（成对，exit 有两条） |
| ACC_SYNCHRONIZED | 同步方法标志 | 同步方法不插指令，靠方法访问标志 |
| Critical Section | 临界区 | 锁保护的代码段 |
| Reentrancy | 可重入性 | 同一线程可重复获取同一把锁（_recursions） |
| Lock Object | 锁对象 | synchronized 括号里那个对象（选它有讲究） |

## 3. 动手实操

### 3.1 实验①：字节码亲眼看（5 分钟出真相）

```java
// SyncDemo.java —— 三种用法集齐
public class SyncDemo {
    private final Object lock = new Object();
    private int counter;

    public synchronized void syncMethod() { counter++; }          // ① 实例方法

    public static synchronized void syncStatic() { }             // ② 静态方法

    public void syncBlock() {                                     // ③ 同步块
        synchronized (lock) { counter++; }
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month02-concurrency
javac SyncDemo.java
javap -c -p SyncDemo
# 观察：
#  ① syncMethod → flags: ACC_SYNCHRONIZED（无 monitorenter！）
#  ② syncStatic → 同上，但 static + ACC_SYNCHRONIZED = 锁 Class 对象
#  ③ syncBlock  → monitorenter ... monitorexit ... monitorexit（两条！异常路径）
javap -v SyncDemo | Select-String "ACC_"     # 看标志位全集
```

### 3.2 实验②：锁对象选错的代价（经典事故复现）

```java
// WrongLock.java —— 锁错了对象 = 白锁
public class WrongLock {
    private int count = 0;
    public void add() {
        synchronized (new Object()) {        // ★每次 new 新锁 → 无互斥效果！
            count++;
        }
    }
    public static void main(String[] a) throws Exception {
        WrongLock w = new WrongLock();
        Runnable task = () -> { for (int i = 0; i < 10_000; i++) w.add(); };
        new Thread(task).start(); new Thread(task).start();
        Thread.sleep(1000);
        System.out.println("期望 20000，实际 " + w.count);   // < 20000
    }
}
// 修正：锁 this / 锁 final 字段 / 或直接用 synchronized 方法
// 锁对象选择三原则表：
//   ① 全生命周期唯一（final 或单例） ② 私有（防外部恶意持有）③ 粒度合适（别全锁一个）
```

### 3.3 实验③：锁对象不同 → 并行不互斥（验证理解）

```java
// DifferentLock.java —— 两线程锁不同对象，counter 照样翻车
public class DifferentLock {
    private static final Object LOCK_A = new Object();
    private static final Object LOCK_B = new Object();
    private static int count = 0;

    public static void main(String[] args) throws Exception {
        Runnable t1 = () -> { for (int i = 0; i < 100_000; i++) synchronized (LOCK_A) { count++; } };
        Runnable t2 = () -> { for (int i = 0; i < 100_000; i++) synchronized (LOCK_B) { count++; } };
        new Thread(t1).start(); new Thread(t2).start();
        Thread.sleep(1000);
        System.out.println("期望 200000，实际 " + count);   // 锁不同 → 无互斥
    }
}
// month01 day15 的死锁 Demo 就是"锁 A 又锁 B"的交叉版——原理今天打通了
```

### 3.4 观察点

1. javap 里同步块为什么有两条 monitorexit？（try 正常退出 + catch 异常退出）
2. 用 month01 的 jstack 抓 WrongLock 修正版 + 两线程竞争：能看到 BLOCKED 吗？
3. counter 逻辑相同，锁对象不同 → 结果不同，说明"互斥的前提是同一把锁"

## 4. 面试连接

**Q：synchronized 锁的是什么？**
> 锁的是对象，不是代码。三种用法对应 this / Class 对象 / 指定对象。底层是对象头的 Mark Word 状态 + JVM 内的 ObjectMonitor 结构（owner/recursions/EntryList/WaitSet）。能把"代码锁"讲到"对象门禁"这一层，就超过多数候选人。

**Q：同步方法和同步块的字节码区别？**
> 同步块插入 monitorenter/monitorexit 指令（且 exit 有两条，保证异常路径也释放）；同步方法不插指令，靠 ACC_SYNCHRONIZED 访问标志，由 JVM 在方法调用协议层隐式处理。这是"看过字节码"的直接证据。

**Q：synchronized 是可重入的吗？怎么实现的？**
> 可重入。同一线程再次进入时 monitor 的 _owner 已是自己，只递增 _recursions 计数；退出时递减，减到 0 才真正释放。不重入就会自己锁死自己（回调场景必死）。

## 5. 今日验收清单

- [ ] javap 输出三种用法截图/摘录
- [ ] 能解释两条 monitorexit 的原因
- [ ] WrongLock / DifferentLock 复现并修复
- [ ] ObjectMonitor 四字段能画出来
- [ ] `git add . && git commit -m "day05: synchronized bytecode & monitor"`

---
[← Day 04](day04-volatile深度剖析.md) | [本月目录](README.md) | [Day 06 · synchronized 下 →](day06-synchronized下-锁升级与优化.md)
