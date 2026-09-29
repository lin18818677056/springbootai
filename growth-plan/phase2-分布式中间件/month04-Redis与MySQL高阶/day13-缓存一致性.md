# Day 13 · 缓存一致性（Cache Aside / 延迟双删 / Canal 时序）

> **今日目标**："更新了 DB，缓存怎么办？"——今天把 4 种方案的时序画出来逐个淘汰，推导出工业标准 Cache Aside Pattern，再用延迟双删和 Canal 补齐最后的不一致窗口。学完你能现场推演任何竞态时序图。
> **时长**：理论 1.5h / 实操 1.5h / 输出 0.5h
> **今日产出**：4 方案时序推演图 + 竞态复现实验 + 一致性方案选型卡

## 1. 知识地图

```
不一致的根源：DB 和缓存是两个独立系统，两条写路径无法原子化

四方案淘汰赛（面试就按这个顺序讲）：
方案① 先更新缓存，再更新 DB
  ✗ DB 写失败 → 缓存是新值 DB 是旧值 → 缓存永久脏（缓存是易失层，不能当主数据）
方案② 先更新 DB，再更新缓存
  ✗ 竞态：写A(耗时) → 写B(快) 交叉时缓存最终=B 的值？错！
    线程1: 更新DB(a=1) ────────→ 更新缓存(a=1)   ★后到覆盖先到
    线程2:      更新DB(a=2) → 更新缓存(a=2)     结果缓存=1 而 DB=2 脏了
  ✗ 还有：写缓存的值要先算好（复杂查询结果）→ 浪费（可能根本没人读）
方案③ ★Cache Aside Pattern（工业标准）：
  读：先读缓存 → miss → 读 DB → 回填缓存 → 返回
  写：先更新 DB → 再【删除】缓存（不是更新！）
  为什么"删"而不是"更"？（两连问，必背）
    Q1 并发写乱序：删除是幂等的，两个写都只留"删" → 下次读时回填最新值
    Q2 懒加载：删了也不亏，反正 miss 后自然回填最新——不为没人读的数据算缓存
方案④ Canal 订阅 binlog 异步删（强一致补充，day25 思想的旁路版）
  业务只管写 DB → Canal 伪装从库订阅 binlog → 解析出变更 → 删对应缓存
  优点：业务代码零侵入、删除不丢（Canal + MQ 重试保证）
  适合：对一致性要求高的核心数据（库存/价格）

Cache Aside 还剩的竞态窗口（诚实面对，概率极低）：
  线程1(读): 缓存 miss → 查 DB 得 v1 ─────────────→ 回填缓存 v1  ★旧值回填！
  线程2(写):            更新 DB=v2 → 删缓存 ─┘
  时序：线程1 读 DB(旧) → 线程2 写 DB+删缓存 → 线程1 慢吞吞把旧值回填
  发生条件苛刻：读请求在写删除【之后】才回填（读 DB 慢于整场写）→ 概率极低
  解药A 延迟双删：写后 sleep(500ms) 再删一次 → 干掉回填的旧值
  解药B 删缓存进 MQ 重试：删除失败不放弃（Canal 方案天然覆盖）

选型决策卡（抄进笔记）：
  一般业务          → Cache Aside（先 DB 后删缓存）
  核心强一致数据     → Cache Aside + 延迟双删
  高写入+团队够大   → Canal 旁路（业务零侵入）
  绝对不能不一致     → 别用缓存，直接读 DB（一致性换性能的天平）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Cache Aside | 旁路缓存模式 | 读回填 / 写删缓存 |
| Lazy Loading | 懒加载 | miss 才回填，不算没人读的 |
| Race Condition | 竞态条件 | 两线程时序交叉产生脏数据 |
| Delayed Double Delete | 延迟双删 | 写后延时再删一次 |
| Canal | 运河 | 阿里 binlog 增量订阅组件 |
| Eventual Consistency | 最终一致 | 窗口收敛，不强求实时 |

## 3. 动手实操

### 3.1 主菜：Cache Aside 标准模板（秒杀周直接用）

```java
// CacheAsideTemplate.java —— 工业标准读写模板
import java.util.concurrent.*;
import java.util.*;

public class CacheAsideTemplate {
    static final Map<String, String> REDIS = new ConcurrentHashMap<>();  // 模拟缓存
    static final Map<String, String> DB   = new ConcurrentHashMap<>();   // 模拟数据库

    // 读路径：缓存 → miss → DB → 回填
    static String read(String key) {
        String v = REDIS.get(key);                              // ① 先缓存
        if (v != null) { System.out.println("缓存命中: " + key); return v; }
        v = DB.get(key);                                        // ② miss 查 DB
        if (v == null) return null;
        REDIS.put(key, v);                                      // ③ 回填（生产加 TTL）
        System.out.println("回填缓存: " + key + " = " + v);
        return v;
    }

    // 写路径：先 DB 后删缓存（★顺序不能反）
    static void write(String key, String value) {
        DB.put(key, value);                                     // ① 先更新 DB
        REDIS.remove(key);                                      // ② 再删缓存（幂等+懒加载）
        System.out.println("写 DB=" + value + "，删缓存: " + key);
    }

    public static void main(String[] args) {
        write("sku:1", "价格100");                              // 首写（缓存无）
        read("sku:1");                                          // miss → 回填
        read("sku:1");                                          // 命中
        write("sku:1", "价格80");                               // 更新：DB 先行，删缓存
        System.out.println("缓存里还有 sku:1 吗? " + REDIS.containsKey("sku:1")); // false
        read("sku:1");                                          // 再次 miss → 回填新值 80
        // ★结论：删缓存方案下，缓存里的值永远来自"删之后"的 DB 读取 → 不残留旧值
    }
}
```

```powershell
javac -d out CacheAsideTemplate.java; java -cp out CacheAsideTemplate
```

### 3.2 实验：复现"先更新缓存"方案的脏数据

```powershell
# 手工模拟方案②（先更新 DB 再更新缓存）的并发竞态：
# 窗口1：线程1 改 DB(100→200)，还没改缓存
docker exec -it redis-learning redis-cli set sku:1 "价格100"
# 窗口2：此刻线程2 抢先完成了全程（DB 和缓存都改成 300）
docker exec -it redis-learning redis-cli set db-sku1 "价格300"
docker exec -it redis-learning redis-cli set sku:1 "价格300"
# 窗口3：线程1 慢一步，把【旧值 200】覆盖进缓存
docker exec -it redis-learning redis-cli set sku:1 "价格200"
docker exec -it redis-learning redis-cli get sku:1      # 缓存=200，DB=300 → 脏！
# ★这就是"更新缓存"死于并发写乱序的全过程；换成"删缓存"则第 3 步是 del → 安全
```

### 3.3 延迟双删 + Canal 方案卡（抄进笔记）

```text
延迟双删（治"旧值回填"窗口）：
  写请求：① 删缓存 → ② 更新 DB → ③ sleep(读请求耗时上限，如 500ms) → ④ 再删一次
  第二删兜底：清掉并发读回填的旧值
  实现建议：第④步丢进延迟队列/定时任务，别 sleep 阻塞业务线程

Canal 旁路（业务零侵入版）：
  ┌业务服务┐ 只写 DB ─→ ┌MySQL┐ binlog ─→ ┌Canal Server┐
                                          └→ 解析变更 → 删 Redis（失败进 MQ 重试）
  学习预告：month05 学完 MQ 后可以真装 Canal 做一遍（本周先记时序与原理）
```

## 4. 面试连接

**Q：如何保证缓存与数据库的一致性？**
> 递进答：① 标准答案 Cache Aside——写路径"先更新 DB，再删除缓存"，读路径 miss 回填；② 解释为什么"删"不"更"：并发写乱序会让更新产生旧值覆盖，删除幂等且懒加载；③ 诚实指出窗口：读请求查 DB 在写删除前、回填在删除后，会残留旧值——概率极低，用延迟双删兜底；④ 强一致场景上 Canal 订阅 binlog 异步删除+MQ 重试；⑤ 收尾："绝对一致就别用缓存——这是性能与一致性的交易，先想清楚业务容忍度。"（生产经验感立刻出来）

**Q：先更新数据库再更新缓存不行吗？为什么非要删除？**
> 两个死因：① 并发写乱序：两个写线程交叉时，"先写 DB 的后写缓存"会把旧值盖到新值上（现场画 3.2 的时序）；② 无效功：更新缓存要提前计算值（可能是复杂聚合查询），而这条数据可能根本没人读。删除则把计算推迟到真正有人读的时刻（懒加载），且幂等——并发删一万次结果一样。

**Q：延迟双删的第二删为什么有效？延迟多久？**
> 目标是清掉"竞态读"回填的旧值：该读请求从查 DB 到回填之间有时间差，写方睡过这个差再删一次，旧值必被清。延迟取"业务读链路耗时上限"（一般 200ms~1s）。注意用异步延迟任务实现，不能阻塞写请求本身。

## 5. 今日验收清单

- [ ] CacheAsideTemplate 跑通（删缓存后回填新值证据）
- [ ] 方案② 竞态复现记录（缓存 200 vs DB 300）
- [ ] 4 方案淘汰赛时序图手绘
- [ ] 延迟双删/Canal 时序卡默写
- [ ] `git add . && git commit -m "day13: cache consistency"`

---
[← Day 12](day12-缓存三大问题.md) | [本月目录](README.md) | [Day 14 · 分布式锁与第二周复盘 →](day14-分布式锁与第二周复盘.md)
