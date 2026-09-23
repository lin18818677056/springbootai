# Day 25 · 内存泄漏六大场景（每个都有最小复现）

> **今日目标**：泄漏不是玄学，是六类"引用没断"的模式。今天逐个复现（代码都在 50 行内），配检查命令和修复方案，把 day19 的"三板斧"升级成"见招拆招"。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：《泄漏六大场景速查卡》+ 各场景复现记录

## 1. 知识地图

```
六大场景一张图（都是"本该回收的对象被强引用拽着"）：

① 静态集合型   static Map/List 只进不出           → 换有界缓存
② ThreadLocal 线程池复用，value 永不回收           → finally remove
③ 资源未关闭   流/连接持有缓冲，GC 依赖不实时       → try-with-resources
④ 监听器/回调  register 不 unregister              → 生命周期对齐注销
⑤ 无界缓存/队列 本地缓存无上界 / 任务队列堆积       → maximumSize + 容量上限
⑥ 类加载器泄漏 动态类/热部署，Metaspace 只涨不掉     → 规范卸载/复用

通用公式：
  泄漏 = 对象不再使用 + 强引用链可达（静态字段/线程栈/监听器/类加载器）

检查命令组合拳（对应 day15/19）：
  jstat -gcutil <pid> 1000        → O 段只涨不跌 = 泄漏嫌疑
  jmap -histo:live <pid>          → 哪个类在涨（场景指纹）
  dump + MAT Path to GC Roots     → 精确到引用点
各场景"指纹"：
  ① byte[]/业务DTO 占大头   ② ThreadLocal$ThreadLocalMap/Entry
  ③ 各种 Buffer/连接对象    ④ 监听器类实例堆积
  ⑤ 缓存节点（Node/Entry）  ⑥ Metaspace 涨（jcmd VM.metaspace，类计数只增）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Memory Leak | 内存泄漏 | 不再使用的对象无法被回收 |
| Unbounded Collection | 无界集合 | 容量无上限的缓存/队列（①⑤根源） |
| ThreadLocalMap | 线程本地表 | key 弱引用、value 强引用 → 经典泄漏 |
| Listener Registry | 监听器注册 | 注册后忘记注销（④） |
| ClassLoader Leak | 类加载器泄漏 | 动态类引用卡住整个加载器（⑥） |
| try-with-resources | 自动资源管理 | JDK7+，语法级防资源泄漏 |

## 3. 动手实操（六连复现，每个 3 分钟）

### 3.1 场景①：静态集合（day19 的 LeakDemo，一分钟复习）

```java
static Map<String, byte[]> cache = new HashMap<>();
void onEvent(String id, byte[] data) { cache.put(id, data); }   // 只进不出
```

**修复**：Guava/Caffeine `CacheBuilder.newBuilder().maximumSize(10_000).expireAfterWrite(10, MINUTES)`；或业务层定期清理。

### 3.2 场景②：ThreadLocal × 线程池（高频面试！）

```java
static ThreadLocal<byte[]> ctx = new ThreadLocal<>();
public static void main(String[] a) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);   // 线程长寿命！
    for (int i = 0; i < 100_000; i++) {
        pool.execute(() -> {
            ctx.set(new byte[1024 * 100]);    // ① set 进 ThreadLocalMap
            // ... 业务 ...
            // ② 忘记 remove：线程不死，value 一直被强引用
        });
    }
    Thread.sleep(3000);
    // jmap -histo:live → byte[] 数量 ≈ 2 线程 × set 次数中"最新"的一批
    // （value 会被旧值顶替，但每个线程常驻最新一条 + 不释放）
}
```

**原理图**：`Thread → ThreadLocalMap → Entry(key=弱引用 TL 对象, value=强引用你的数据)`。key 可被 GC，但 value 被 Entry 强引用着，只要线程活着就回收不掉。
**修复**：`finally { ctx.remove(); }` 或改用方法传参/上下文对象。

### 3.3 场景③：资源未关闭

```java
FileInputStream in = new FileInputStream(f);   // 异常时跳过 close → 泄漏
in.read(buf);
in.close();
```

**修复**：`try (FileInputStream in = new FileInputStream(f)) { ... }`（自动关闭）；连接池场景配 `leakDetectionThreshold`（HikariCP 检测未归还连接）。

### 3.4 场景④：监听器/回调只注册不注销

```java
public class App {
    static List<Listener> listeners = new ArrayList<>();   // 全局注册表
    public static void main(String[] args) {
        for (int i = 0; i < 100_000; i++) {
            Session s = new Session(i);
            listeners.add(() -> System.out.println(s.id)); // 每个会话注册，从不注销
        }  // Session 想被回收？listeners 全拽着
    }
    interface Listener { void onEvent(); }
    record Session(int id) {}
}
```

**修复**：会话关闭时 `listeners.remove(...)`；或用弱引用监听器列表；框架内用 `@PreDestroy`/`DisposableBean` 对齐生命周期。

### 3.5 场景⑤：无界队列堆积（更隐蔽：泄漏在"等待中"）

```java
static BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();  // 默认上限 Integer.MAX_VALUE
public static void main(String[] a) {
    new Thread(() -> { while (true) queue.put(new byte[1024]); }).start();  // 生产 1KB/次
    new Thread(() -> { while (true) { Thread.sleep(1000); queue.take(); } }).start(); // 消费巨慢
}   // 内存被"待处理任务"吃光 → 不是引用错误，是容量设计错误
```

**修复**：`new LinkedBlockingQueue<>(10_000)` 有界 + 满时拒绝/降级；本地缓存同理由 maximumSize 兜底。

### 3.6 场景⑥：类加载器泄漏（Metaspace 涨）

```java
// 每轮都 new 一个 ClassLoader 加载同一个类 → 类元数据无法卸载
for (int i = 0; i < 10_000; i++) {
    MyClassLoader cl = new MyClassLoader(dir);
    Class<?> c = cl.loadClass("Script");   // 自定义加载器（day02 写过）
    c.getDeclaredMethod("run").invoke(c.getDeclaredConstructor().newInstance());
}   // jcmd <pid> VM.metaspace 反复执行：loaded classes 只涨不跌 = 泄漏
```

**修复**：脚本/热加载复用 ClassLoader；检查 Groovy/动态代理的类生成频率；设 `-XX:MaxMetaspaceSize` 让问题早暴露而不是吃爆机器。

## 4. 面试连接

**Q：生产上你见过哪些内存泄漏？**
> 按六大场景讲 2~3 个真实的（ThreadLocal 线程池 / 无界缓存 / 监听器最常见），每个按"现象（GC 基线抬升）→ 发现（histo 类指纹）→ 定位（dump 引用链）→ 修复（一句话）"讲，就是满分叙事。

**Q：ThreadLocal 为什么会泄漏？key 不是弱引用吗？**
> key 是弱引用没错（TL 对象可回收），但 value 是强引用，被 Thread→ThreadLocalMap→Entry 拽着；线程池线程永不死亡 → value 永不回收。所以规范是 finally remove。追问"为什么 key 设弱引用"：防止 TL 对象本身泄漏（map 自动清 stale entry 兜底）。

**Q：怎么预防泄漏？（比事后排查更值钱）**
> 四条军规：缓存必有上界和过期；线程池上下文 finally 清理；资源一律 try-with-resources；Metaspace 必设上限。再配"GC 基线监控告警"当哨兵。

## 5. 今日验收清单

- [ ] 六场景全部复现（至少 ②③⑤ 亲手跑）
- [ ] ThreadLocal 泄漏链能画图（弱 key/强 value）
- [ ] 《泄漏六大场景速查卡》成文（场景/指纹/修复三列表）
- [ ] 能说出四条预防军规
- [ ] `git add . && git commit -m "day25: six leak patterns"`

---
[← Day 24](day24-JVM参数与生产模板.md) | [本月目录](README.md) | [Day 26 · OOM 类型全复现 →](day26-OOM类型全复现.md)
