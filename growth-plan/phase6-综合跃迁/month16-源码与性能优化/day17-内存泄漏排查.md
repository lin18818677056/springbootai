# Day 17 · 内存泄漏排查：占着储藏室不还钥匙

> **今日目标**：理解"有 GC 也会泄漏"；亲手埋一个泄漏再用 jmap+MAT 抓出来；掌握排查四步法。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：一次完整的泄漏排查记录（从 O 区异常到定位代码行）

## 1. 知识地图（先讲人话）

```
Java 也有内存泄漏？（颠覆认知，但真实）
  GC 的规矩：没人引用的对象才回收。
  泄漏的定义：对象【还被引用着】但【永远不会再用了】。
  类比：住户搬走了（不再用），但物业登记册上还有他的名字
  （还被引用）→ 他的储藏室永远没人敢清 → 储藏室越占越满。
  最终：老年代塞满 → Full GC 清不出东西 → OOM。

四大惯犯（背下来，排查时对号入座）：
  ① 静态集合只进不出：static Map 当缓存用却没淘汰策略
     （今天要埋的雷就是它）
  ② 资源未关闭：流/连接没 close（连接池对象滞留）
  ③ ThreadLocal 不 remove：线程池线程不死，ThreadLocal 值
     跟着线程永生（线程池场景高危！）
  ④ 监听器/回调注册后不注销：发布者长命，订阅者该死不死

排查四步法（肌肉记忆级）：
  ① jstat 发现异常：老年代只涨不跌（GC 后回收率极低）
  ② jmap 抓现场：jmap -dump:live,format=b,file=heap.hprof <pid>
    （live = 先触发一次 Full GC 只留活物）
  ③ MAT 看大头：打开 hprof → Dominator Tree（支配树）
     → 最大的对象是谁、谁引用着它 → 顺藤摸瓜到 GC Root
  ④ 回代码修：找到"只进不出"的那行代码，加淘汰/关闭/移除
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 大白话 |
|------|------|--------|
| Memory Leak | 内存泄漏 | 该死的对象死不了（还被引用） |
| GC Root | GC 根 | GC 的"点名起点"（静态变量/活跃线程栈/本地引用） |
| Heap Dump | 堆转储 | 给整个堆拍"全家福"（hprof 文件） |
| Dominator Tree | 支配树 | "谁扣着谁的命脉"：A 支配 B = B 活着必须 A 活着 |
| Shallow vs Retained Heap | 浅堆 vs 保留堆 | 对象自己的大小 vs 它活着连带着不能回收的总大小 |

## 3. 动手实操

### 3.1 埋雷：一个"好心缓存"引发的血案

```java
public class LeakCache {
    // 立意是好的：trace 查询结果缓存，加速回放（day23 的回放功能）
    static final Map<String, byte[]> TRACE_CACHE = new HashMap<>();

    public static void put(String sessionId, byte[] traceJson) {
        TRACE_CACHE.put(sessionId, traceJson);   // 只进不出！没有淘汰！
    }
    public static void main(String[] args) throws Exception {
        var rnd = new java.util.Random();
        for (int i = 0; ; i++) {
            byte[] fake = new byte[1024 * 512];  // 每个 512KB
            rnd.nextBytes(fake);
            put("session-" + i, fake);
            Thread.sleep(50);
        }
    }
}
// 每 50ms 泄漏 512KB ≈ 每分钟 600MB——几分钟内必炸
```

### 3.2 抓捕全流程（四步法实战）

```powershell
# ① 启动 LeakCache，同时开第二个终端盯：
jstat -gcutil <pid> 2000
# 观察记录：O 列从 20% → 60% → 90%，FGC 后几乎不降（回收率<5%）
# —— "只涨不跌"实锤

# ② 趁 FGC 频繁时抓现场（jcmd 更现代，等价 jmap）：
jcmd <pid> GC.heap_dump D:\tmp\leak.hprof

# ③ MAT 打开 leak.hprof：
#    Dominator Tree → 排第一的是 byte[512KB] × N 个
#    → 右键 Path to GC Roots (exclude weak/soft refs)
#    → 路径显示：LeakCache.TRACE_CACHE (static) ← 钉死凶手！
#    浅堆/保留堆：HashMap 本身浅堆小，但保留堆 = 全部 byte 数组总和

# ④ 修复：换带淘汰的缓存（Caffeine maximumSize + expireAfterWrite）
#    或这里业务上根本不该缓存大 JSON（存库就行，查库不慢）
```

### 3.3 ThreadLocal 排查加练（线程池场景）

```java
static final ThreadLocal<byte[]> CTX = new ThreadLocal<>();
// 模拟：每次请求 CTX.set(new byte[1024*1024])，从不清除
// 池里 50 个平台线程 → 最多挂 50MB？错！如果 set 过 1000 次不同请求
// 且线程复用，最后一次 set 的会覆盖前值（同线程只留一份）——
// 但如果 value 被别处引用（如静态集合）就全留。
// 规范：try { ... } finally { CTX.remove(); } ——写进团队红线
```

### 3.4 思考题

为什么 jmap dump 要加 `live` 参数？不加会怎样？（提示：不加=把垃圾也拍进去，支配树全是干扰项，而且快照更大更慢；加 live 会先触发一次 Full GC——注意：大堆生产服务上触发 Full GC 有卡顿风险，理想是摘流量后再抓）

## 4. 面试连接

**Q：线上怀疑内存泄漏，怎么排查？**
> 四步：jstat 确认"老年代只涨不跌"；jmap/jcmd 抓 live 堆转储；MAT 支配树找 Retained Heap 最大的对象，Path to GC Roots 定位引用链；回代码修（我实战过一次"静态 Map 缓存只进不出"，换成 Caffeine 带淘汰解决）。如果生产不能 dump，就靠 JFR 的 Old Object Sample 事件轻量定位。

**Q：ThreadLocal 为什么会泄漏？**
> ThreadLocalMap 的 key 是弱引用（GC 后 key 变 null），但 value 是强引用——线程池的线程永生，value 就一直挂在 Thread 上。规范是 finally 里 remove；我们团队红线里明确写了。追问"为什么 key 设计成弱引用"——给"忘 remove"的场景留一条 self-cleanup 的路（get 时顺手清 stale entry）。

## 5. 今日验收清单

- [ ] 埋雷实验完整跑通：jstat 发现 → dump → MAT 定位 → 修复
- [ ] 能讲浅堆 vs 保留堆的区别
- [ ] ThreadLocal 红线（finally remove）能举例讲清
- [ ] `git add . && git commit -m "day16-17: leak-hunting"`
- [ ] 笔记：排查四步法流程图（手画）

---
[← Day 16](day16-G1调优实战.md) | [本月目录](README.md) | [Day 18 · trace 采样与冷热分层 →](day18-trace采样与冷热分层.md)
