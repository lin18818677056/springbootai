# Day 23 · 并发设计模式（不可变对象 / 两阶段终止 / Future 模式）

> **今日目标**：把散落的 API 收拢成四个可复用的"模式"。今天动手实现不可变对象、两阶段终止（优雅停机）、Future 模式（day18 的理论根基）、Balking 模式，每个配一句"什么时候用"。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：四个模式各一个可运行实现 + 模式选型速查卡

## 1. 知识地图

```
四个模式的"一句话本质"：

① 不可变对象 Immutability
   本质：既然改不了，就永远不需要同步（final + 无 setter）
   final 的三重保证：字段初始化安全发布（构造器内写 → 别的线程读必可见）
   代表：String/Integer/局部 record；适合：配置、坐标、ID 卡片

② 两阶段终止 Two-Phase Termination
   本质：先打断（ interrupt 置标志）→ 等自然收尾（捕获后清理资源）→ 再终止
   反模式：Thread.stop（直接砍，资源泄漏）；System.exit（全局核弹）
   适合：优雅停机、监控线程退出、任务取消

③ Future 模式
   本质：交订单（提交任务）→ 拿小票（Future）→ 干别的 → 凭票取货（get）
   day18 的 CompletableFuture 就是它的"增强版"（可编排/回调/超时）
   适合：一个耗时子任务 + 主流程还有别的事可以先做

④ Balking（放弃执行）
   本质：不满足条件就直接返回，不排队不等待
   代表：单例（已初始化就不初始化）、幂等标记、"已停止就不再受理"
   实现：synchronized + 条件判断直接 return

  ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐
  │ 不可变   │   │ 两阶段   │   │ Future  │   │ Balking │
  │ "消灭共享"│   │ "礼貌收尾"│   │ "异步取货"│   │ "不再服务"│
  └─────────┘   └─────────┘   └─────────┘   └─────────┘
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Immutability | 不可变性 | final + 私有字段 + 无修改方法 |
| Safe Publication | 安全发布 | final 字段构造完成的可见性保证 |
| Two-Phase Termination | 两阶段终止 | interrupt + 清理 + 自然退出 |
| Future Pattern | Future 模式 | 凭票取货的异步封装 |
| Guarded Suspension | 保护性暂停 | 条件不满足就 wait（day08 的模式化名字） |
| Balking | 放弃模式 | 条件不满足直接返回 |

## 3. 动手实操

### 3.1 模式①：不可变对象（多线程无锁共享）

```java
// ImmutablePoint.java —— final 类 + final 字段 + 无 setter = 天然线程安全
public final class ImmutablePoint {                 // ① 类 final：防子类破坏
    private final int x, y;                         // ② 字段 final：只能构造时赋值

    public ImmutablePoint(int x, int y) { this.x = x; this.y = y; }

    public int getX() { return x; }                 // ③ 只读访问器
    public int getY() { return y; }

    public ImmutablePoint moveBy(int dx, int dy) {  // ④ "修改"= 返回新对象
        return new ImmutablePoint(x + dx, y + dy);
    }
}

// MainImmutable.java —— 任意多线程共享，零锁零原子类
public class MainImmutable {
    public static void main(String[] args) {
        ImmutablePoint p = new ImmutablePoint(0, 0);   // 安全发布给所有线程
        for (int i = 0; i < 5; i++) {
            new Thread(() -> {
                ImmutablePoint local = p.moveBy(1, 1); // 各自拿新对象，互不干扰
                System.out.println(Thread.currentThread().getName()
                        + " 看到 (" + local.getX() + "," + local.getY() + ") 绝对一致");
            }).start();
        }
    }
}
// JDK21+ 彩蛋：record Point(int x, int y) {} 一行顶上面 15 行，同样不可变
```

### 3.2 模式②：两阶段终止（优雅停机模板，生产直接抄）

```java
// MonitorService.java —— 监控线程的优雅退出
public class MonitorService {
    private Thread monitor;

    public void start() {
        monitor = new Thread(() -> {
            while (true) {
                if (Thread.currentThread().isInterrupted()) {   // ② 第二阶段：收尾
                    System.out.println("监控: 正在清理资源（关闭连接/刷盘/释放句柄）...");
                    sleep(300);                                  // 模拟清理耗时
                    System.out.println("监控: 已优雅退出");
                    break;
                }
                System.out.println("监控: 采集一次指标");
                try { Thread.sleep(500); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();          // ★sleep 被打断要补回标志
                }
            }
        }, "monitor");
        monitor.start();
    }
    public void stop() {
        monitor.interrupt();                          // ① 第一阶段：只打断，不硬杀
    }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (Exception e) {} }

    public static void main(String[] args) throws Exception {
        MonitorService svc = new MonitorService();
        svc.start();
        Thread.sleep(2000);
        svc.stop();                                   // 观察输出顺序：打断→清理→退出
    }
}
// 关键细节：catch InterruptedException 后必须重新 interrupt()——
// 因为 sleep 抛异常时中断标志已被清掉，不补回循环条件就感知不到
```

### 3.3 模式③：手写 Future 模式（看懂 JDK 的地基）

```java
// MiniFuture.java —— 手写一个 30 行的 Future 模式（对照 JDK FutureTask）
import java.util.function.Supplier;

public class MiniFuture<T> {
    private T result;
    private volatile boolean done = false;

    public MiniFuture(Supplier<T> task) {
        new Thread(() -> {                            // 真正干活在子线程
            result = task.get();
            done = true;
            synchronized (this) { notifyAll(); }      // 好了叫醒取货人
        }, "mini-future-worker").start();
    }
    public synchronized T get() throws InterruptedException {
        while (!done) wait();                         // 保护性暂停：没好就等
        return result;
    }
    public boolean isDone() { return done; }

    public static void main(String[] args) throws Exception {
        System.out.println("main: 下单（耗时任务后台跑）");
        MiniFuture<String> ticket = new MiniFuture<>(() -> {
            try { Thread.sleep(1000); } catch (Exception e) {}
            return "计算结果";
        });
        System.out.println("main: 拿到小票，先干别的（预计立刻打印）");
        Thread.sleep(200);
        System.out.println("main: 凭票取货: " + ticket.get());
    }
}
// 对照 JDK：FutureTask = MiniFuture + CAS 状态机 + 异常传递 + 可取消
// 你的 30 行版把"凭票取货"四步演得一清二楚
```

### 3.4 模式④：Balking（不满足就放弃）

```java
// BalkingInit.java —— 只初始化一次：后来的线程直接返回，不排队
public class BalkingInit {
    private static boolean inited = false;

    public static synchronized void init() {
        if (inited) {                                  // 已经做过了 → 直接放弃
            System.out.println(Thread.currentThread().getName() + ": 已初始化，我走了（Balking）");
            return;
        }
        System.out.println(Thread.currentThread().getName() + ": 执行初始化...");
        sleep(200);
        inited = true;
    }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (Exception e) {} }

    public static void main(String[] args) {
        for (int i = 0; i < 5; i++) new Thread(BalkingInit::init, "T" + i).start();
    }
}
// 对比：生产者消费者是"等条件满足"（wait），Balking 是"条件不满足就不等了"（return）
```

### 3.5 模式选型速查卡

| 场景 | 模式 | 关键 API |
|------|------|---------|
| 配置/坐标等只读数据跨线程共享 | 不可变对象 | final / record |
| 停后台线程并保证资源清理 | 两阶段终止 | interrupt + isInterrupted |
| 提交耗时任务后先干别的 | Future 模式 | FutureTask / CompletableFuture |
| 只做一次/条件不满足就放弃 | Balking | synchronized + 提前 return |
| 一个线程等另一个的结果 | 保护性暂停 | wait/notify（day08） |

## 4. 面试连接

**Q：不可变对象为什么线程安全？**
> 两个保证：① 状态不可变（final 字段 + 无修改途径），不存在"写到一半被读"；② final 字段有安全发布语义——构造函数内对 final 字段的写入，在对象引用发布给其他线程后必定可见（JMM 专门为 final 定的规则）。落地：record 类、String、不可变集合 List.of。收益：零锁零 CAS，天然可缓存可共享。

**Q：怎么优雅停一个线程？**
> 标准答两阶段终止：interrupt 置位 → 线程在安全点自己检查 isInterrupted → 执行清理（刷盘/还连接/关流）→ 自然退出。强调细节：sleep 里的 InterruptedException 要重新 interrupt；如果线程在 BLOCKED（synchronized）里 interrupt 无效（day02/day11 伏笔——这也是选 ReentrantLock.lockInterruptibly 的场景理由）。

**Q：Future 和 CompletableFuture 的关系？**
> Future 是模式（凭票取货），CompletableFuture 是增强实现（可编排任务图：thenApply 链、allOf 汇合、orTimeout 超时）。可以亮手写 MiniFuture："我用 30 行实现过模式本体，所以清楚 JDK 加的三层增强：状态机、异常传递、回调编排。"

## 5. 今日验收清单

- [ ] ImmutablePoint + record 版各跑通
- [ ] MonitorService 优雅退出（输出：打断→清理→退出）
- [ ] MiniFuture 跑通并说出与 FutureTask 的三层差距
- [ ] BalkingInit 跑通（只有 1 个线程执行初始化）
- [ ] `git add . && git commit -m "day23: concurrency patterns"`

---
[← Day 22](day22-读写锁与StampedLock.md) | [本月目录](README.md) | [Day 24 · 虚拟线程工程化 →](day24-虚拟线程工程化.md)
