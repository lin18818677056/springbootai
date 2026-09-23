# Day 20 · ThreadLocal 与线程封闭（泄漏复现与 TTL 传递）

> **今日目标**：ThreadLocal 三连问是后端面试必考：结构是什么？为什么会泄漏？线程池下怎么传递？今天亲手画出泄漏链（呼应 month01 day25 场景②）、复现脏数据、用 finally remove 修复、理解 TTL 解决"线程池传值丢失"。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：TLBasic + LeakRepro（脏数据复现+修复）+ TTL 传递验证记录

## 1. 知识地图

```
ThreadLocal 的存储结构（别再说"ThreadLocal 存值"——值存在线程自己身上）：

  Thread 对象                        ThreadLocalMap（每个线程一张）
  ┌──────────────┐                ┌───────────────────────────────┐
  │ threadLocals ───────────────→ │ Entry[弱引用 key, value]       │
  └──────────────┘                │  ┌─────────────────────────┐  │
   每个线程独立的"口袋"             │  │ key: ThreadLocal(弱引用) │  │
                                  │  │ value: 你的值(强引用)     │  │
                                  │  └─────────────────────────┘  │
                                  └───────────────────────────────┘

泄漏链（★必须能默写）：
  线程池线程不死 → Thread 不死 → ThreadLocalMap 一直在
  → Entry.key 是弱引用（GC 后变 null）但 value 是强引用
  → key 没了 value 还挂着 = "永远取不到也删不掉的僵尸值"
  → 元数据含大对象/用户信息 → 内存泄漏 + 线程池脏数据双重事故

修复三板斧：
  ① set 后 finally remove（规范写法）
  ② 弱引用 key 使下次 set/get 时顺带清理 stale entry（JDK 自愈，但依赖再访问）
  ③ 用完即删的最佳实践：try { tl.set(x); 业务 } finally { tl.remove(); }

线程池传值难题（TTL 登场）：
  InheritableThreadLocal：只在"new Thread 时"拷贝父线程值
    → 线程池线程只创建一次，复用时不再拷贝 → 值永远是第一次的（更隐蔽）
  TransmittableThreadLocal（阿里 TTL）：
    用 TtlRunnable/TtlExecutors 包装任务/池
    → 提交时抓快照，执行时回放，执行后还原
    → 链路追踪 traceId、租户上下文跨线程池的标准解法
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Thread Confinement | 线程封闭 | 数据只在单线程内使用，天然无并发问题 |
| Weak Reference | 弱引用 | 只被弱引用的对象下次 GC 必回收 |
| Stale Entry | 僵尸条目 | key 为 null 但 value 还在的 Entry |
| Context Propagation | 上下文传递 | traceId/租户/用户态跨线程传递 |
| TTL | TransmittableThreadLocal | 阿里开源的"可传递"ThreadLocal |
| Snapshot / Replay | 快照/回放 | TTL 提交时捕获、执行时恢复的机制 |

## 3. 动手实操

### 3.1 实验①：ThreadLocal 基本盘（线程封闭验证）

```java
// TLBasic.java —— 每个线程一份独立副本，互不干扰
public class TLBasic {
    static final ThreadLocal<String> USER = new ThreadLocal<>();

    public static void main(String[] args) throws Exception {
        Runnable work = () -> {
            USER.set(Thread.currentThread().getName() + "-的数据");   // 存进"自己的口袋"
            try { Thread.sleep(100); } catch (Exception e) {}
            System.out.println(Thread.currentThread().getName()
                    + " 读到: " + USER.get());                          // 只读到自己的
        };
        new Thread(work, "T-A").start();
        new Thread(work, "T-B").start();
        Thread.sleep(300);
        System.out.println("main 读到: " + USER.get());                 // null：封闭性证明
    }
}
// 预期：T-A 读 T-A 的，T-B 读 T-B 的，main 读 null → 线程封闭的直观感受
```

### 3.2 实验②：泄漏与脏数据复现（month01 day25 场景②展开）

```java
// LeakRepro.java —— 线程池 + 不 remove = 脏数据 + 僵尸值
import java.util.concurrent.*;

public class LeakRepro {
    static final ThreadLocal<byte[]> CTX = new ThreadLocal<>();

    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);   // 线程复用是前提

        // 任务①：set 了一个 10MB 大数组，然后"忘记" remove
        for (int i = 0; i < 2; i++) {
            pool.execute(() -> {
                CTX.set(new byte[10 * 1024 * 1024]);              // 10MB 挂在线程身上
                System.out.println(Thread.currentThread().getName() + " 设置了 10MB 数据（没删）");
            });
        }
        Thread.sleep(500);

        // 任务②：新任务进来读到"别人的值" = 脏数据事故
        for (int i = 0; i < 2; i++) {
            pool.execute(() -> {
                byte[] leaked = CTX.get();
                System.out.println(Thread.currentThread().getName()
                        + " 竟然读到了: " + (leaked != null ? leaked.length / 1024 + "KB 旧值!" : "null"));
                CTX.remove();                                     // 现在才删（晚了但能演示修复点）
            });
        }
        Thread.sleep(500);
        pool.shutdown();
    }
}
// 复现结论：
//  ① 脏数据：任务②在复用线程里读到任务①的 10MB → 用户 A 的请求读到用户 B 的上下文
//  ② 内存泄漏：这些 10MB 强引用在线程存活期间无法回收（线程池线程几乎不死）
```

### 3.3 实验③：修复版（finally remove 是肌肉记忆级规范）

```java
// LeakFixed.java —— 拦截器/过滤器的标准模板（直接抄进项目）
import java.util.concurrent.*;

public class LeakFixed {
    static final ThreadLocal<String> USER = new ThreadLocal<>();

    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int req = 1; req <= 4; req++) {
            final String user = "用户" + req;
            pool.execute(() -> {
                try {
                    USER.set(user);                               // 入口 set（如过滤器）
                    handle();                                     // 业务链路里任何地方都能 get
                } finally {
                    USER.remove();                                // ★出口必删（如过滤器 finally）
                }
            });
        }
        pool.shutdown();
    }
    static void handle() {
        System.out.println(Thread.currentThread().getName() + " 业务处理: " + USER.get());
    }
}
// 预期：每个任务只读到自己的用户，绝不会串——"set/get/remove 三件套配对"是铁律
```

### 3.4 实验④：TTL——线程池里的上下文传递（选做但强烈建议）

```xml
<!-- pom.xml 依赖（github.com/alibaba/transmittable-thread-local） -->
<dependency>
    <groupId>com.alibaba</groupId>
    <artifactId>transmittable-thread-local</artifactId>
    <version>2.14.5</version>
</dependency>
```

```java
// TtlDemo.java —— 普通参数 ThreadLocal vs TTL
import com.alibaba.ttl.TransmittableThreadLocal;
import com.alibaba.ttl.threadpool.TtlExecutors;
import java.util.concurrent.*;

public class TtlDemo {
    static final TransmittableThreadLocal<String> TRACE = new TransmittableThreadLocal<>();

    public static void main(String[] args) throws Exception {
        ExecutorService rawPool = Executors.newFixedThreadPool(1);
        ExecutorService ttlPool = TtlExecutors.getTtlExecutorService(rawPool); // ★包装

        TRACE.set("trace-001");                        // 父线程设置 traceId
        ttlPool.execute(() ->                          // 提交子任务（另起一个 trace 也行，可改值试）
                System.out.println("TTL池 读到 traceId: " + TRACE.get()));   // trace-001 ✓
        rawPool.execute(() ->
                System.out.println("原生池 读到 traceId: " + TRACE.get()));  // 也可能 001？
        // 区别在"复用第二次提交"：TTL 每次提交都抓最新快照；原生池拿的是线程首次创建时的值
        TRACE.set("trace-002");                        // 改值再提交，差异立刻显现
        ttlPool.execute(() -> System.out.println("TTL池 第二次: " + TRACE.get()));   // trace-002 ✓
        rawPool.execute(() -> System.out.println("原生池 第二次: " + TRACE.get()));  // 仍旧值 ✗
        Thread.sleep(500);
        rawPool.shutdown();
    }
}
// 结论：线程池复用线程 → 原生 ThreadLocal/InheritableThreadLocal 都会传错或传旧
// → TTL 的"提交抓快照 + 执行回放"是链路追踪/租户上下文的标准答案
```

## 4. 面试连接

**Q：ThreadLocal 为什么会内存泄漏？**
> 按链讲：值存在 Thread.threadLocals（ThreadLocalMap）里，线程池线程不死 → Map 不死 → Entry 的 key 是弱引用（GC 后变 null）但 value 是强引用 → key 为 null 的僵尸条目挂着大对象收不回。修复：finally remove；JDK 会在 set/get 时顺带清理 stale entry 但不可依赖。

**Q：key 为什么设计成弱引用而不是强引用？**
> 假如强引用：外部已不再使用这个 ThreadLocal 变量（如局部变量出栈），但线程池线程的 Map 还强引用着它 → 变量永远无法回收，泄漏更彻底。弱引用让"变量本身"可回收，把泄漏面缩小到 value，再靠 remove + 自愈清理兜底。

**Q：线程池下 ThreadLocal 传值丢失怎么办？**
> 先讲病因：InheritableThreadLocal 只在 new Thread 时拷贝，池化线程只创建一次，复用时值是旧的。再讲方案：阿里 TTL，用 TtlRunnable/TtlExecutors 包装，提交时抓快照、执行时回放、完成后还原。落地场景：traceId 全链路追踪、多租户上下文过异步线程池。

## 5. 今日验收清单

- [ ] 泄漏链默写（Thread→Map→Entry→弱 key 强 value）
- [ ] LeakRepro 亲手复现脏数据（读到"别人的 10MB"）
- [ ] LeakFixed 模板抄进个人片段库
- [ ] TtlDemo 第二次提交差异记录（TTL 新值 vs 原生旧值）
- [ ] `git add . && git commit -m "day20: threadlocal leak & ttl"`

---
[← Day 19](day19-ConcurrentHashMap深度.md) | [本月目录](README.md) | [Day 21 · 第三周复盘输出 →](day21-第三周复盘输出.md)
