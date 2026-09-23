# Day 25 · 并发 bug 复现场（竞态 / this 逸出 / 不安全发布）

> **今日目标**：会写并发代码不够，要能在 code review 里一眼认出并发 bug。今天搭一个"bug 动物园"：三类高危 bug 各复现一次，每种配"症状→根因→修复"三段式，沉淀成自己的排查清单。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：三个 bug 复现 Demo + 《并发 bug 排查清单 v1》

## 1. 知识地图

```
并发 bug 动物园（今天的三只"动物"）：

① 竞态条件 Race Condition
   症状：低并发正常，压测/上线后偶发错数、重复处理、超卖
   根因：check-then-act（查了再做）或 read-modify-write 的两步被插队
   例：if(!map.containsKey(k)) map.put(k,v);   ← 两步之间别人塞了
   修复：单步原子 API（putIfAbsent/merge）、锁、CAS

② this 逸出 This Escape
   症状：别的线程读到一个"半初始化"对象（字段还是默认值）
   根因：构造器没跑完，this 就被别人看见了
   两种典型：
     a) 构造器里 new Thread(this::xx).start() / 注册监听器
     b) 静态方法返回"未构造完的对象"（内部类隐式持有外围 this）
   修复：私有构造 + 静态工厂（构造完再启动）；监听器在 start() 里注册

③ 不安全发布 Unsafe Publication
   症状：读到的对象字段值"违反直觉"（构造顺序对不上）或永久为 null
   根因：对象引用可见了，但字段的写入还没保证可见（缺 final/volatile）
   修复四姿势：final 字段 / volatile 引用 / 静态初始化器 / 锁内发布

  排查视角（复习 month01 + 本月工具箱）：
    jstack 看线程状态 → Arthas watch 观测变量 → JFR 看锁事件
    → 这些工具是为"线上撞到 bug"准备的；今天的复现是"主动养 bug"
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Race Condition | 竞态条件 | 结果依赖线程执行时序 |
| Check-Then-Act | 先查后做 | 检查与动作不原子 |
| This Escape | this 逸出 | 构造完成前 this 被外部可见 |
| Safe Publication | 安全发布 | 对象与字段写入一起可见 |
| Final Field Semantics | final 字段语义 | 构造完成的可见性保证 |
| Double-Checked Locking | 双重检查锁 | day04 的 DCL，今天从 bug 视角再看 |

## 3. 动手实操

### 3.1 bug①：check-then-act 竞态（超卖的最小复现）

```java
// CheckThenAct.java —— 库存扣减：查库存→判断→扣减，两步被插队
import java.util.concurrent.*;

public class CheckThenAct {
    static int stock = 100;                       // 故意用普通 int

    public static void main(String[] args) throws Exception {
        int buyers = 200;                         // 200 人抢 100 件 → 必然超卖
        CountDownLatch latch = new CountDownLatch(buyers);
        for (int i = 0; i < buyers; i++) {
            new Thread(() -> {
                try {
                    if (stock > 0) {              // ① 查
                        Thread.sleep(1);          // 放大时间窗（模拟业务耗时）
                        stock--;                  // ② 扣（①②之间被插队！）
                    }
                    latch.countDown();
                } catch (Exception e) {}
            }).start();
        }
        latch.await();
        System.out.println("剩余库存: " + stock + "（0 才正确，负数=超卖）");
    }
}
// 修复三连（各改一版跑通）：
//   a) synchronized 扣减方法（简单粗暴）
//   b) AtomicInteger.decrementAndGet() + 自旋到 >=0（无锁）
//   c) 生产姿势：SQL 条件更新 UPDATE stock SET n=n-1 WHERE id=? AND n>0（数据库原子）
```

### 3.2 bug②：this 逸出（读到 x=0 的半初始化对象）

```java
// ThisEscape.java —— 构造器里启动线程：其他线程可能看到 x=0
public class ThisEscape {
    private int x;

    public ThisEscape() {
        new Thread(() -> System.out.println("逸出线程读到的 x = " + x)).start();
        x = 42;                                   // ★构造器还没跑完，this 已经跑了
    }

    public static void main(String[] args) throws Exception {
        new ThisEscape();
        Thread.sleep(100);                        // 多跑几次，大概率读到 0
    }
}

// ThisSafe.java —— 修复版：私有构造 + 静态工厂（构造完再启动）
public class ThisSafe {
    private int x;

    private ThisSafe(int x) { this.x = x; }       // 构造器干干净净（也可设为 private）

    public static ThisSafe create(int x) {
        ThisSafe obj = new ThisSafe(x);           // ① 先构造完
        obj.afterCreate();                        // ② 再发布/启动（此刻字段已就绪）
        return obj;
    }
    private void afterCreate() {
        new Thread(() -> System.out.println("安全线程读到的 x = " + x)).start();
    }

    public static void main(String[] args) throws Exception {
        ThisSafe.create(42);
        Thread.sleep(100);                        // 永远读到 42
    }
}
// 修复思路一句话：构造器只做赋值，把“启动线程/注册监听”挪到静态工厂的构造完成之后
```

### 3.3 bug③：不安全发布 vs 四种安全姿势

```java
// UnsafePublish.java —— 没有 final/volatile，读线程可能"永远"看到 holder=null
import java.util.concurrent.*;

public class UnsafePublish {
    static Holder holder;                          // 无 volatile：发布不安全

    public static void main(String[] args) throws Exception {
        new Thread(() -> {                          // 写线程
            holder = new Holder(42);                // 普通赋值（无 happens-before）
        }).start();
        new Thread(() -> {                          // 读线程
            Holder h = holder;
            if (h != null) System.out.println("读到 value=" + h.value);
            else System.out.println("holder 还是 null");
        }).start();
        Thread.sleep(100);
    }
    static class Holder {
        int value;
        Holder(int v) { value = v; }
    }
}
// 四种安全发布姿势（逐一改造验证）：
//   ① final 字段：final int value（构造完必可见）
//   ② volatile 引用：static volatile Holder holder
//   ③ 静态初始化器：static Holder H = new Holder(42);（类加载锁保证）
//   ④ 锁内发布：写读都进同一把锁（synchronized/AQS 全家都行）
```

### 3.4 输出：《并发 bug 排查清单 v1》（放进笔记，code review 用）

```text
□ 共享可变状态有没有？
    无共享（ThreadLocal/局部/不可变）→ 安全
    有共享 → 继续查
□ 共享变量是否 immutable / final / volatile / 受锁保护？
    全不是 → 竞态或可见性 bug 候选
□ 是否存在 check-then-act / read-modify-write？
    → 换单步原子 API（putIfAbsent/merge/compareAndSet）或加锁
□ 构造器里有没有 this 逃逸（start 线程/注册监听/返回内部类）？
    → 私有构造 + 静态工厂
□ 对象是否跨线程发布？
    → 四姿势对号（final/volatile/静态初始化/锁内）
□ 线程池场景 ThreadLocal 有没有 set 无 remove？（day20）
□ 双重检查锁单例有没有 volatile？（day04）
```

## 4. 面试连接

**Q：你在项目里遇到/排查过什么并发 bug？**
> STAR 模板（用今天的复现撑腰）：某接口压测时库存/计数偶发不准 → 定位到 check-then-act（先查后改）→ 复现出"200 抢 100 剩 0 以下" → 修复用条件更新/原子 API → 上线后加对拍回归。关键是"复现-定位-修复-回归"四步都有自己的代码证据。

**Q：什么是安全发布？有哪些方式？**
> 定义：对象引用对其他线程可见时，其字段的写入也保证可见。四方式：final 字段语义、volatile 引用、静态初始化器（类加载机制）、锁内发布。反例：构造器 this 逸出——引用跑了字段还没写完，读到默认值。能主动把 DCL（day04）串进来一起讲更佳。

**Q：code review 你怎么发现并发问题？**
> 拿排查清单逐条过（§3.4 那张）：先看共享可变状态清单 → 再查两步操作 → 再查发布方式 → 再查 ThreadLocal 配对 → 最后看线程池参数与拒绝策略。展示这张清单本身就是高水平信号。

## 5. 今日验收清单

- [ ] CheckThenAct 复现超卖 + 三种修复各跑通
- [ ] ThisEscape 复现读到 0 + ThisSafe 修复验证
- [ ] UnsafePublish 四种安全发布姿势各改造一次
- [ ] 《并发 bug 排查清单 v1》成文
- [ ] `git add . && git commit -m "day25: race-escape-publish zoo"`

---
[← Day 24](day24-虚拟线程工程化.md) | [本月目录](README.md) | [Day 26 · 并发性能排查 →](day26-并发性能排查.md)
