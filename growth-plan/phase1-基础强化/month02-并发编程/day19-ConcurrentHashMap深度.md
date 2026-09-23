# Day 19 · ConcurrentHashMap 深度（putVal 流程 + 扩容协助）

> **今日目标**：CHM 是并发容器面试之王。今天画出 JDK8 putVal 分支流程图（M2 验收项），读源码四段关键代码，压测对比三代表，并踩一遍"复合操作不原子"的经典坑。
> **时长**：理论 1.5h / 实操 1.5h / 输出 0.5h
> **今日产出**：putVal 分支流程图 + 三代表压测数据 + CounterBug 修复记录

## 1. 知识地图

```
演进总览：
  JDK7  Segment 分段锁（16 段，锁粒度=段）  → 已成历史，面试只需一句对比
  JDK8  Node 数组 + CAS + synchronized(桶头节点)
        锁粒度从"段"细化到"单个桶"，并发度大幅提升

putVal(key, value) 六分支流程图（★M2 验收：必须能默写）：
  计算 hash（spread 高低位异或扰动）
      │
      ▼
  ① 桶为空？ ──是──→ CAS 放入新节点（无锁！），结束
      │否
      ▼
  ② hash == MOVED(-1)？ ──是──→ 正在扩容 → helpTransfer 帮搬数据
      │否
      ▼
  ③ synchronized(头节点 f) {      ← 只锁这一个桶
        ④ f 是链表头？→ 沿链遍历找 key，有则覆盖，无则尾插
        ⑤ f 是树根？  → 红黑树插入
     }
  ⑥ binCount ≥ 8 且数组长度 ≥64 → 链表转红黑树（treeifyBin）

get(k) 为什么不加锁：
  Node.val 和 next 都是 volatile → 读到的一定是最新写入（happens-before）
  扩容中的桶：ForwardingNode 会把读引导到新表

sizeCtl 控制字段（状态机）：
  -1 = 正在初始化   -(1+迁移线程数) = 扩容中   正数 = 下次扩容阈值
size() 为什么弱一致：先试 baseCount CAS，竞争大转 CounterCell[] 分段计数
  → 类似 LongAdder（day09 伏笔），所以 size 只是估计值，精确用 mappingCount
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Bin / Bucket | 桶 | 数组槽位，链表或树的起点 |
| spread | 扰动函数 | (h ^ (h>>>16)) & HASH_BITS |
| ForwardingNode | 转发节点 | hash=MOVED，指向新表 |
| helpTransfer | 协助扩容 | 写线程顺手帮搬一段桶 |
| CounterCell | 计数单元 | size 分段计数（LongAdder 思想） |
| Weakly Consistent | 弱一致 | 迭代器不抛 ConcurrentModificationException |

## 3. 动手实操

### 3.1 实验①：三代表压测（Hashtable / synchronizedMap / CHM）

```java
// MapBench.java —— 8 线程 × 10 万次 put+get
import java.util.*;
import java.util.concurrent.*;

public class MapBench {
    public static void main(String[] args) throws Exception {
        bench("Hashtable             ", new Hashtable<>());
        bench("SynchronizedMap        ", Collections.synchronizedMap(new HashMap<>()));
        bench("ConcurrentHashMap      ", new ConcurrentHashMap<>());
    }
    static void bench(String name, Map<Integer, Integer> map) throws Exception {
        int threads = 8, per = 100_000;
        CountDownLatch latch = new CountDownLatch(threads);
        long begin = System.currentTimeMillis();
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                for (int i = 0; i < per; i++) { map.put(i, i); map.get(i); }
                latch.countDown();
            }).start();
        }
        latch.await();
        System.out.println(name + " 耗时=" + (System.currentTimeMillis() - begin) + "ms");
    }
}
// 预期：CHM 一骑绝尘（细粒度锁 + 无锁读），Hashtable 与 SynchronizedMap 慢一个量级
```

### 3.2 实验②：经典坑——"复合操作不原子"（必须亲手踩）

```java
// CounterBug.java —— map 里计数，错的和对的各来一遍
import java.util.*;
import java.util.concurrent.*;

public class CounterBug {
    public static void main(String[] args) throws Exception {
        Map<String, Integer> map = new ConcurrentHashMap<>();

        // ★错误版：get + put 两步不是原子的，CHM 只保证单步原子
        for (int i = 0; i < 8; i++) {
            new Thread(() -> {
                for (int j = 0; j < 10_000; j++) {
                    Integer old = map.get("cnt");              // ① 读
                    map.put("cnt", old == null ? 1 : old + 1); // ② 写（①②之间会被插队！）
                }
            }).start();
        }
        Thread.sleep(1000);
        System.out.println("错误版（get+put）: " + map.get("cnt") + " / 期望 80000");

        // 正确版①：putIfAbsent + 二段，或直接用 merge
        map = new ConcurrentHashMap<>();
        for (int i = 0; i < 8; i++) {
            new Thread(() -> {
                for (int j = 0; j < 10_000; j++) map.merge("cnt", 1, Integer::sum); // 原子复合
            }).start();
        }
        Thread.sleep(1000);
        System.out.println("正确版（merge）  : " + map.get("cnt") + " / 期望 80000");
    }
}
// 结论一句话："线程安全的容器 ≠ 线程安全的复合操作"——这是 CHM 第一大坑
```

### 3.3 实验③：computeIfAbsent 的坑与妙用（本地缓存模板）

```java
// CacheDemo.java —— computeIfAbsent 做"只算一次"的本地缓存
import java.util.concurrent.*;

public class CacheDemo {
    static final ConcurrentHashMap<String, FutureTask<String>> CACHE = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        // 妙用：缓存"计算过程"而不是"结果"，防止缓存击穿（100 个线程只算一次）
        for (int i = 0; i < 5; i++) {
            new Thread(() -> {
                try {
                    String v = CACHE.computeIfAbsent("expensive-key", k -> {
                        FutureTask<String> ft = new FutureTask<>(() -> {
                            System.out.println("★ 只应该打印一次：真正计算中...");
                            Thread.sleep(500);
                            return "计算结果@";
                        });
                        ft.run();                       // 在桶锁内触发计算
                        return ft;
                    }).get();
                    System.out.println(Thread.currentThread().getName() + " 拿到 " + v);
                } catch (Exception e) { e.printStackTrace(); }
            }).start();
        }
    }
}
// 直接 computeIfAbsent(k, k->重计算) 的坑：计算里如果再写同一个 map 的其他 key
// （旧版 JDK）可能死锁/死循环——所以用"缓存 FutureTask"这个更稳的模式
```

### 3.4 源码走读清单（IDEA 里按 F4 逐段看）

```text
ConcurrentHashMap.java（JDK 25 源码）：
  □ putVal：六分支（对照今天流程图逐行确认）
  □ transfer：扩容迁移，stride（每线程认领的桶段）怎么分配
  □ addCount：baseCount CAS 失败 → CounterCell 分段（对照 day09 LongAdder）
  □ get：全程无锁，Node.val/next 是 volatile
  □ tabAt/casTabAt：Unsafe 语义读写数组元素（volatile + CAS）
```

## 4. 面试连接

**Q：ConcurrentHashMap JDK7 和 JDK8 的区别？**
> 锁粒度三级跳：JDK7 Segment 分段锁（默认 16 段，并发度=段数）→ JDK8 放弃分段，Node 数组 + 空桶 CAS + 非空桶 synchronized 头节点（并发度=桶数）；另加红黑树、扩容协助、LongAdder 式分段计数三个增强。

**Q：讲一下 put 的流程？**
> 画六分支流程图（今天默写的那张），重点讲三个亮点：空桶 CAS 无锁插入、非空桶只 synchronized 一个头节点（锁粒度最细）、发现 MOVED 转发节点顺手帮扩容（写线程不分家）。

**Q：CHM 的 size 为什么不准？key 能为 null 吗？**
> size 用 baseCount + CounterCell[] 分段计数（LongAdder 思想），高并发下只是估计值，求精确用 mappingCount；key/value 都不允许 null——因为并发语义下 get 返回 null 无法区分"不存在"还是"值就是 null"（单线程 HashMap 可以用 containsKey 二次确认，并发下二次确认已失真）。

## 5. 今日验收清单

- [ ] putVal 六分支流程图默写（M2 验收项）
- [ ] MapBench 三组数据记录
- [ ] CounterBug 两个版本都跑通并解释为什么错
- [ ] CacheDemo 跑通，说清"缓存 FutureTask"防击穿原理
- [ ] `git add . && git commit -m "day19: chm putval & composite-op trap"`

---
[← Day 18](day18-CompletableFuture异步编排.md) | [本月目录](README.md) | [Day 20 · ThreadLocal 与线程封闭 →](day20-ThreadLocal与线程封闭.md)
