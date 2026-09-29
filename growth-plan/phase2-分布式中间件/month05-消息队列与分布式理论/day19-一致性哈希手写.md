# Day 19 · 手写一致性哈希：哈希环、虚拟节点与迁移率实测

> **今日目标**：手写 ConsistentHashRouter（含虚拟节点），实测"扩容 1/8 节点迁移率 ≈ 1/8"，对照 M4 手写的分片路由——第三次亲手实现"数据分布"，这次是理论最优雅的版本。
> **时长**：原理 1h / 编码 2.5h / 实测复盘 1.5h
> **今日产出**：ConsistentHashRouter.java（可运行）+ 三种分布策略对比数据

## 1. 知识地图

```
普通取模 hash(key) % N 的痛：
  N 从 8 → 9（扩 1 台）：几乎所有 key 的映射全变 → 缓存全失效/数据全迁移
  一致性哈希 Consistent Hashing 的目标：扩缩容时只迁移 ≈1/N 的数据

哈希环 Consistent Hashing Ring：
  把哈希空间想成一个环（0 ~ 2^32-1）
  节点：hash(节点标识) 放到环上；数据：hash(key) 顺时针找第一个节点
  扩容节点 X：只有"环上 X 与其逆时针前驱之间"的 key 归属改变 → 约 1/N
  节点下线同理，只迁移该节点的数据

虚拟节点 Virtual Node（解决两个问题）：
  问题① 数据倾斜：节点少时，环上分布不均，某节点吃掉大半 key
  问题② 物理节点异构：8C 机器和 32C 机器承担同样多 key 不合理
  解法：每个物理节点生成 M 个虚拟节点（如 150 个，hash("nodeA#vn" + i)）
  → 环上密密麻麻均匀分布，key 落到虚拟节点 → 映射回物理节点
  虚拟节点越多越均匀（实验见），M=150 时单节点份额波动可控制在均值 ±5%

有界负载 Bounded Load（Google 2016 进阶，了解即可）：
  哈希环仍可能因热点 key 打爆单节点 → 给每个节点设上限 (1+ε)×均值
  超限的 key 顺延到下一个节点 → 兼顾均匀与热点（Tencent/Cloudflare 落地过）

跨月呼应（你已经有三个"数据分布"作品）：
  M4 ShardStrategy（取模+基因法）：数据库分片，扩容靠迁移工具
  M5 day05 RocketMQ Rebalance 的 CONSISTENT_HASH 策略：队列分配
  今天 ConsistentHashRouter：通用算法版——三者同源，一个思想的三次落地
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Consistent Hashing | 一致性哈希（Karger 1997） |
| Hash Ring | 哈希环（2^32 空间首尾相接） |
| Virtual Node | 虚拟节点（物理节点的多个环上映射） |
| Clockwise Successor | 顺时针后继（key 归属的第一个节点） |
| Data Skew | 数据倾斜 |
| Bounded Load | 有界负载（(1+ε) 上限顺延） |
| Migration Ratio | 迁移率（扩容时需迁移的数据比例） |
| Ketama | Memcached 的一致性哈希实现规范（业界参考） |

## 3. 动手实操：手写 ConsistentHashRouter

```java
// learning/month05-mq-theory/src/ConsistentHashRouter.java（完整骨架，纯 JDK）
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ConsistentHashRouter {
    // TreeMap 就是环：key=虚拟节点哈希，value=物理节点；tailMap 完成顺时针查找
    private final TreeMap<Long, String> ring = new TreeMap<>();
    private final int vnodeCount;                       // 每物理节点的虚拟节点数

    public ConsistentHashRouter(List<String> nodes, int vnodeCount) {
        this.vnodeCount = vnodeCount;
        for (String n : nodes) addNode(n);
    }
    public void addNode(String node) {
        for (int i = 0; i < vnodeCount; i++)
            ring.put(hash(node + "#VN" + i), node);      // 虚拟节点撒到环上
    }
    public String route(String key) {
        long h = hash(key);
        SortedMap<Long, String> tail = ring.tailMap(h);  // 顺时针第一个 ≥ h 的节点
        return (tail.isEmpty() ? ring.firstEntry() : tail.firstEntry()).getValue(); // 环形回绕
    }
    // FNV-1a 32 位：比 String.hashCode 分布更均匀（hashCode 集中低位，环上会扎堆）
    private long hash(String s) {
        long h = 2166136261L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) { h ^= b & 0xff; h *= 16777619L; }
        return h & 0xffffffffL;
    }
    // main：迁移率实验
    //  ① 100 万 key（"key-"+i）在 8 节点分布 → 记录每个 key 的归属
    //  ② 扩容第 9 节点 → 重新路由 → 统计归属变化的 key 数 / 100 万
    //  ③ 对照组 1：vnodeCount=1（无虚拟节点）重跑 → 观察迁移率波动与分布倾斜
    //  ④ 对照组 2：普通取模重跑 → 迁移率应接近 90%
    // 预期（参考）：vnode=150 迁移率 ≈ 11%（1/9）；vnode=1 迁移率波动大且分布倾斜；
    //              取模 ≈ 89%。三组数据写进对比表——又一个"实测数据"作品
}
```

```powershell
# 编译运行（纯 JDK，无依赖）：
cd D:\mywork\springbootai\learning\month05-mq-theory\src
javac -encoding UTF-8 ConsistentHashRouter.java
java ConsistentHashRouter
# 把输出（三组迁移率+分布直方图）贴进笔记，顺手画哈希环手绘图（8 节点+150 虚拟节点的密度）
```

## 4. 面试连接

**Q：一致性哈希解决什么问题？怎么解决？**
> 问题：普通取模在节点数变化时几乎全部 key 重映射（8→9 迁移 ~89%），缓存全失效/数据大迁移。解决：把节点与 key 都哈希到环上，key 顺时针找最近节点；扩缩容只影响环上相邻区间，迁移率 ≈1/N（实测 11% @ 9 节点）。虚拟节点解决少节点倾斜与异构负载（实测 vnode=150 时份额波动 <5%）。最后给落地场景：Redis 客户端分片、RocketMQ Rebalance 的 CONSISTENT_HASH 策略、负载均衡、我手写的路由器——"实测数据+多场景呼应"收尾。

**Q：一致性哈希就能解决缓存热点吗？**
> 不能全解。一致性哈希解决的是"节点变化时的迁移最小化"，不解决"某几个热 key 打爆单节点"。热点方案：①有界负载（超限顺延）；②热 key 多副本（key 加随机后缀散到多节点，读时聚合）；③应用层本地缓存挡读（M4 day12 的 HotPointCache 思路）。区分"分布均匀问题"和"访问频率问题"——两个不同维度，面试里能把这两者分开的候选人不多了。

## 5. 今日验收清单

- [ ] ConsistentHashRouter 编译运行通过
- [ ] 三组实测数据（vnode=150/1/取模）对比表完成
- [ ] 哈希环手绘图（含虚拟节点密度）
- [ ] FNV-1a vs hashCode 为什么换算法能讲
- [ ] "均匀 vs 热点"两个维度的区分观点能讲
- [ ] 映射表新增：一致性哈希→Rebalance CONSISTENT_HASH/Redis 分片
- [ ] `git add . && git commit -m "day19: consistent hash"`

---
[← Day 18](day18-Raft日志复制.md) | [本月目录](README.md) | [Day 20 · 分布式事务全家桶 →](day20-分布式事务全家桶.md)
