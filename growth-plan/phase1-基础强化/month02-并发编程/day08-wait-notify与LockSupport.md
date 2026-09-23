# Day 08 · wait/notify 与 LockSupport（线程通信两套）

> **今日目标**：线程间"你等我、我叫你"有两套机制：Object 的 wait/notify（配 synchronized）和 LockSupport 的 park/unpark（配任意场景）。今天掌握两套的区别与坑，手写一个标准的生产者-消费者。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：手写 wait 版有界缓冲区 + 两套通信对比表

## 1. 知识地图

```
两套线程通信机制对比：

  wait/notify（Object 方法，配合 synchronized）
    wait()      持锁线程主动进 WaitSet 休息，并【释放锁】
    notify()    随机唤醒 WaitSet 里【一个】线程（醒后要重新抢锁）
    notifyAll() 唤醒全部（惊群，但安全）
    铁律：必须在 synchronized 块内调用，否则 IllegalMonitorStateException
    铁律：wait 要用 while 包住（防虚假唤醒 Spurious Wakeup）

  park/unpark（LockSupport，JUC 的地基，day10 AQS 就靠它）
    park()       拿不到"许可"就睡觉（不占 CPU，不释放任何锁！）
    unpark(t)    给指定线程发"许可"（permit，最多存 1 个）
    特点①：以【线程】为单位，先 unpark 再 park 也不丢（许可可预存）
    特点②：不需要持有任何锁，任意位置调用
    特点③：不抛 InterruptedException，但响应中断（park 返回，需自己查标志）

  对照记忆：
              wait/notify        park/unpark
    调用前提    持有锁              无
    唤醒顺序    随机/全部            精确指定线程
    先唤醒后等待 丢失通知             ★许可预存，不丢
    抛中断异常   是                  否（返回后自查）

生产者-消费者骨架（wait 版，while + notifyAll 是标准姿势）：
  synchronized (queue) {
      while (queue.isFull())  queue.wait();   // while 不是 if！
      queue.put(item);
      queue.notifyAll();                       // 唤醒所有等待者（消费者+生产者都醒来自查）
  }
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| wait / notify / notifyAll | 等待/唤醒 | Object 的等待集机制，必须持锁调用 |
| LockSupport | 锁支持工具 | park/unpark 许可模型，AQS 的基石 |
| Permit | 许可 | park/unpark 的信号量（0/1，不累加） |
| Spurious Wakeup | 虚假唤醒 | wait 可能无因返回 → 必须用 while 重查条件 |
| Producer-Consumer | 生产者-消费者 | 经典并发协作模式（今天 wait 版实现） |
| Bounded Buffer | 有界缓冲区 | 容量有限的生产消费队列（day11 Condition 版） |

## 3. 动手实操

### 3.1 实验①：wait 的三条铁律逐条验证

```java
// WaitRules.java
public class WaitRules {
    static final Object LOCK = new Object();

    public static void main(String[] args) throws Exception {
        // 铁律1 验证：不持锁调 wait → IllegalMonitorStateException
        try { LOCK.wait(); } catch (Exception e) {
            System.out.println("无锁 wait → " + e.getClass().getSimpleName());
        }

        Thread consumer = new Thread(() -> {
            synchronized (LOCK) {
                while (!ready()) {                    // 铁律3：while 防虚假唤醒
                    try {
                        System.out.println("消费者: 没货，我去 WaitSet 休息（释放锁）");
                        LOCK.wait();
                    } catch (InterruptedException e) { return; }
                }
                System.out.println("消费者: 醒了且有货，开吃");
            }
        });
        consumer.start();
        Thread.sleep(500);

        synchronized (LOCK) {                          // 生产者持同一把锁
            System.out.println("生产者: 上货 + notifyAll");
            ready = true;
            LOCK.notifyAll();
        }
        consumer.join();
    }
    static boolean ready = false;
}
```

### 3.2 实验②：park/unpark 的"许可预存"特性

```java
// ParkDemo.java —— 先 unpark 再 park，不阻塞！
import java.util.concurrent.locks.LockSupport;

public class ParkDemo {
    public static void main(String[] args) throws Exception {
        Thread worker = new Thread(() -> {
            System.out.println("worker: 我先睡（park）");
            LockSupport.park();                        // 许可已预存 → 立即返回
            System.out.println("worker: 醒了（拿的是预存许可）");
        });
        worker.start();
        Thread.sleep(500);
        LockSupport.unpark(worker);                    // 先发许可（worker 还没 park）
        Thread.sleep(100);                              // 给 worker 时间 park
        // 对照组：把 unpark 挪到 worker.start() 之前效果相同——这就是 vs notify 的核心差异
        System.out.println("main: 结束");
    }
}
```

### 3.3 实验③：手写生产者-消费者（wait 版有界缓冲区）

```java
// BoundedBuffer.java —— 容量 3，1 生产者 2 消费者
import java.util.LinkedList;
import java.util.Queue;

public class BoundedBuffer {
    private final Queue<Integer> buf = new LinkedList<>();
    private final int cap = 3;

    public void put(int v) throws InterruptedException {
        synchronized (this) {
            while (buf.size() == cap) this.wait();     // 满了等（while！）
            buf.offer(v);
            System.out.println("P -> " + v + "  [" + buf.size() + "]");
            this.notifyAll();
        }
    }
    public int take() throws InterruptedException {
        synchronized (this) {
            while (buf.isEmpty()) this.wait();          // 空了等（while！）
            int v = buf.poll();
            System.out.println("      C <- " + v + "  [" + buf.size() + "]");
            this.notifyAll();
            return v;
        }
    }
    public static void main(String[] args) throws Exception {
        BoundedBuffer b = new BoundedBuffer();
        new Thread(() -> { for (int i = 1; i <= 10; i++) try { b.put(i); Thread.sleep(100); } catch (Exception e) {} }).start();
        for (int c = 0; c < 2; c++)
            new Thread(() -> { while (true) try { Thread.sleep(150); b.take(); } catch (Exception e) { return; } }).start();
    }
}
```

### 3.4 观察点

1. 把 put/take 里的 while 改成 if，多跑几次 → 观察索引越界/空取（虚假唤醒现形）
2. notifyAll 改成 notify → 偶发全部卡死（唤醒了同类：生产者唤醒生产者）
3. 两个"坑"都踩过一遍，这两个铁律就是你的了

## 4. 面试连接

**Q：sleep 和 wait 的区别？（高频题）**
> 四维度：wait 是 Object 方法、sleep 是 Thread 静态方法；wait 释放锁、sleep 不释放；wait 必须 synchronized 内、sleep 任意；wait 靠 notify/notifyAll/中断唤醒、sleep 到点自然醒。答全四点 + 补"共同点：都响应中断、都进入 TIMED_WAITING/WAITING"。

**Q：notify 和 notifyAll 怎么选？**
> 保守用 notifyAll：条件谓词复杂（多生产多消费）时 notify 可能唤醒"同类"导致系统卡死（今天实验③复现过）；notify 只在"所有等待者条件相同且每次唤醒必然满足"时才安全。折中方案：Lock + 多个 Condition 精准唤醒（day11）。

**Q：为什么 wait 必须在循环里？**
> ① 虚假唤醒：OS 层面 wait 可能无因返回；② 唤醒到真正抢到锁之间条件可能又被改掉。while 重查条件是唯一正确姿势——这是"防御性并发编程"的代表案例。

## 5. 今日验收清单

- [ ] WaitRules / ParkDemo 跑通，三铁律亲自验证
- [ ] BoundedBuffer 跑通，两个"坑"（if/notify）各复现一次
- [ ] 两套通信对比表成文（含许可预存）
- [ ] sleep vs wait 四维度背熟
- [ ] `git add . && git commit -m "day08: wait-notify & park-unpark"`

---
[← Day 07](day07-第一周复盘输出.md) | [本月目录](README.md) | [Day 09 · CAS 与原子类 →](day09-CAS与原子类.md)
