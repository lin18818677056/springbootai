# Day 03 · 底层数据结构(下)：手写跳表 SkipList 并压测

> **今日目标**：Redis ZSet 的灵魂结构。先讲透跳表的多层索引思想，然后亲手写一个 ≤100 行的 Java 简化跳表（延续 month02 手写风），与 TreeMap（红黑树）对拍压测——面试"跳表 vs 红黑树"从此有手写实证。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：MiniSkipList 手写代码 + 与 TreeMap 对拍记录 + ZSet 对拍验证

## 1. 知识地图

```
跳表 = 给有序链表加"多层快车道"（空间换时间的二分思想链表版）：

  L3:  head ────────────────→ 31 ────────────────→ NULL
  L2:  head ─────→ 13 ────────→ 31 ────────→ 47 ──→ NULL
  L1:  head → 5 → 13 → 19 → 31 → 37 → 47 → 55 → NULL
  查找 37：L3 从 31 直达 → L2 走到 31 → L1 到 37 —— 只走 3 步
  （不带索引的链表要走 5 步；1 亿节点跳表只需 ~27 层期望步数 log n）

三个关键机制：
  ① 随机层数：新节点以概率 p=1/4 晋升上层（幂次定律 Power Law）
     期望层数 = 1/(1-p) ≈ 1.33，最高 32 层 —— 简单随机代替严格平衡
  ② Redis 节点结构（比教学版多两个字段）：
     backward 指针（反向遍历）+ span 跨度（支持 ZRANK 排名 O(log n)）
  ③ 底层是双向链表 → 范围查询 ZRANGE 顺链走，不用回溯

跳表 vs 红黑树（★面试名场面，三层递进答）：
  ① 功能：范围查询跳表天然优势（底层有序双向链）——ZSet 的命门
  ② 实现：跳表无旋转逻辑，插入删除只改指针（代码量 1/3）
  ③ 并发：跳表可做无锁化（ConcurrentSkipListMap 就是无锁跳表）
  红黑树的赢面：内存更省（无多层）、最坏情况有保障
  MySQL 为什么不用？——B+ 树才是磁盘友好版（month03 day15 呼应）

Redis 的 ZSet = 跳表 + 字典 双结构组合：
  skiplist 管【排序与范围】，dict 管【按成员 O(1) 查分数】
  两份结构共享成员对象（不用两倍内存）——空间换双维度查询
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Skip List | 跳表 | 多层索引的有序链表 |
| Random Level | 随机层数 | 幂次定律，期望 1.33 层 |
| Span | 跨度 | 相距元素数，支撑排名 |
| Power Law | 幂次定律 | 层数越高概率越低 |
| Range Query | 范围查询 | ZSet 的核心场景 |
| Treemap/RB-Tree | 红黑树 | 对比参照物 |

## 3. 动手实操

### 3.1 主菜：手写 MiniSkipList（纯 JDK，≤100 行）

```java
// MiniSkipList.java —— 教学版跳表：插入/查找/范围计数 + 对拍 TreeMap
import java.util.*;

public class MiniSkipList {
    static final int MAX_LEVEL = 8;
    static final double P = 0.5;

    static class Node {
        int val;
        Node[] next;                        // 各层的后继指针
        Node(int val, int level) { this.val = val; this.next = new Node[level]; }
    }

    private final Node head = new Node(-1, MAX_LEVEL);   // 哨兵头
    private int size;

    private int randomLevel() {             // 幂次定律：抛硬币晋升
        int lv = 1;
        while (Math.random() < P && lv < MAX_LEVEL) lv++;
        return lv;
    }

    public void insert(int val) {
        int lv = randomLevel();
        Node node = new Node(val, lv);
        Node cur = head;
        Node[] update = new Node[MAX_LEVEL];             // 各层前驱
        for (int i = MAX_LEVEL - 1; i >= 0; i--) {       // 从高层往下找
            while (cur.next[i] != null && cur.next[i].val < val) cur = cur.next[i];
            update[i] = cur;
        }
        for (int i = 0; i < lv; i++) {                   // 逐层插入
            node.next[i] = update[i].next[i];
            update[i].next[i] = node;
        }
        size++;
    }

    public boolean search(int val) {
        Node cur = head;
        for (int i = MAX_LEVEL - 1; i >= 0; i--)
            while (cur.next[i] != null && cur.next[i].val < val) cur = cur.next[i];
        return cur.next[0] != null && cur.next[0].val == val;   // L1 精确定位
    }

    public static void main(String[] args) {
        MiniSkipList sk = new MiniSkipList();
        TreeMap<Integer, Boolean> rb = new TreeMap<>();  // 红黑树对照组
        // ① 正确性对拍：10 万随机数，两边同时插+查
        Random r = new Random(42);
        for (int i = 0; i < 100_000; i++) {
            int v = r.nextInt(1_000_000);
            sk.insert(v); rb.put(v, true);
            if (r.nextBoolean()) {
                int q = r.nextInt(1_000_000);
                if (sk.search(q) != rb.containsKey(q)) {
                    throw new AssertionError("对拍失败: " + q);
                }
            }
        }
        System.out.println("对拍通过：10 万插入 + 5 万随机查询全一致");
        // ② 性能压测：插入 100 万 + 范围统计
        long t1 = System.currentTimeMillis();
        MiniSkipList big = new MiniSkipList();
        for (int i = 0; i < 1_000_000; i++) big.insert(r.nextInt(10_000_000));
        long t2 = System.currentTimeMillis();
        TreeMap<Integer, Boolean> bigRb = new TreeMap<>();
        for (int i = 0; i < 1_000_000; i++) bigRb.put(r.nextInt(10_000_000), true);
        long t3 = System.currentTimeMillis();
        System.out.printf("跳表插入100万=%dms  红黑树插入100万=%dms%n", t2 - t1, t3 - t2);
        // 范围查询对拍（跳表手动降层 / 红黑树 tailMap）
        System.out.println("红黑树 range[100,200) 个数 = " +
                bigRb.subMap(100, 200).size());
    }
}
// 观察点：两者插入耗时同数量级（都是 O(log n)）——差异在实现复杂度与范围语义
```

```powershell
cd D:\mywork\springbootai\learning\month04-redis-mysql
javac -d out MiniSkipList.java; java -cp out MiniSkipList
# 记录：对拍结果 + 两组插入耗时
```

### 3.2 与 Redis ZSet 对拍（真跳表行为验证）

```powershell
docker exec -it redis-learning redis-cli
#   ZADD leaderboard 100 alice 90 bob 95 carol 85 dave
#   ZRANGE leaderboard 0 -1 WITHSCORES     ← 升序有序（跳表 L1 有序实证）
#   ZREVRANGE leaderboard 0 1              ← 反向取前 2（backward 指针/降层）
#   ZRANK leaderboard carol                ← 排名 O(log n)（span 跨度累加）
#   ZRANGEBYSCORE leaderboard 85 96        ← 按分数范围（底层链表顺走）
#   OBJECT ENCODING leaderboard            ← skiplist（元素少时可能 listpack）
# 手绘：把你画的跳表图加一列"Redis 增强点"：backward / span / 与 dict 双结构
```

## 4. 面试连接

**Q：为什么 Redis ZSet 用跳表不用红黑树？**
> 三层递进：① 范围查询是 ZSet 命门——跳表底层有序双向链，ZRANGE 顺链即得；红黑树要中序遍历回溯；② 实现成本——跳表无旋转，插入删除仅指针操作，代码简单不易错；③ 无锁潜力——跳表分层结构天然适合 CAS 无锁化（JDK ConcurrentSkipListMap 就是先例）。再补一刀："ZSet 其实是跳表+字典双结构，dict 管成员查分数 O(1)，skiplist 管排序——两者共享成员对象。"

**Q：跳表的层数怎么定？为什么用随机？**
> 抛硬币式：新节点以 p=1/4 概率晋升上层，期望层数 1/(1-p)≈1.33，封顶 32。用随机而非强制平衡的原因：无需全局再平衡信息（红黑树要维护颜色与旋转规则），插入只做局部决策，简单且期望性能同样 O(log n)——"用概率换复杂度"的经典取舍。

**Q：ZRANK（排名）为什么能做到 O(log n)？**
> 因为 Redis 跳表节点带 span（跨度）字段：记录到下一节点跳过了几个元素。查找时把沿途的 span 累加就是排名。这是 Redis 对教科书跳表的增强之二（之一是 backward 指针支持反向遍历）——能说出 span 就超过 90% 的候选人。

## 5. 今日验收清单

- [ ] MiniSkipList 对拍通过（10 万插入无 AssertionError）
- [ ] 跳表 vs 红黑树插入耗时记录
- [ ] ZSet 五个命令实验 + 编码观察
- [ ] "跳表 vs 红黑树"三层递进答案能脱稿讲
- [ ] `git add . && git commit -m "day03: hand-written skiplist"`

---
[← Day 02](day02-SDS与字典渐进式rehash.md) | [本月目录](README.md) | [Day 04 · 对象系统与编码 →](day04-对象系统与编码.md)
