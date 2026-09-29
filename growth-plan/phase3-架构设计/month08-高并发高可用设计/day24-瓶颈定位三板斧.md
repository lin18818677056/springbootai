# Day 24 · 瓶颈定位三板斧：Metrics、Trace、Profile

> **今日目标**：掌握性能定位三层次（Metrics 圈范围→Trace 定链路→Profile 挖代码）；火焰图与 CPU/内存/锁三类剖析；用三板斧复现并解决两个压测瓶颈——M6 day12 可观测的深度版。
> **时长**：方法 1h / 实操定位 3h / 案例沉淀 1.5h
> **今日产出**：两个瓶颈案例（慢 SQL+热点锁）全流程定位报告 + 火焰图实操记录

## 1. 知识地图

```
性能定位的三层漏斗（从"系统慢"到"这行代码慢"）——由粗到细，别一上来就 jstack：

第一板斧 Metrics（圈范围——哪个服务/哪个接口/哪个资源）：
  黄金四信号大盘（day15）：RT 飙的服务、错误率高的接口、饱和度高的资源
  输出：把"系统慢"收窄到"订单服务 /api/order/create P99 640ms"
  耗时：30 秒——大盘看一眼的事，但必须先做（跳过=大海捞针）

第二板斧 Trace（定链路——慢在哪一跳）：
  SkyWalking 拉慢调用链（M6 day12 资产）：640ms = 网关5 + 订单40 + 营销20 + 库存530 + DB45
  输出：瓶颈收窄到"库存服务的扣减方法 530ms"
  判读技巧：自耗时间 vs 含下游时间——每一跳拆出"自己花了多久"（饼图思维）
  耗时：3 分钟——trace 一查，链路图自动铺开

第三板斧 Profile（挖代码——那 530ms 里 CPU/IO/锁各占多少）：
  CPU 剖析：async-profiler 生成火焰图——找"最宽的塔"（自上而下都是热点）
  锁剖析：jstack 抓 BLOCKED 线程 / -agentlib 或 JFR 锁事件——热点行锁/wait
  内存剖析：jmap histod / MAT 支配树——大对象/泄漏
  IO 剖析：iostat（磁盘 await）/ MySQL slowlog——IO 等待
  输出：定位到"UPDATE stock 单行热点，行锁队列 420ms + 索引缺失扫描 90ms"
  ——三板斧各自输出下一个板斧的输入，漏斗收窄不回头

火焰图读法（3 分钟入门）：
  横轴=采样占比（越宽越热），纵轴=调用栈深度（上层是叶子）
  找"宽且平顶"的塔=热点函数；"平顶"说明叶子就是瓶颈（不用再看下面）
  工具：async-profiler -d 30 -f flame.html <pid>（30 秒采样，0 侵入）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Metrics / Trace / Profile | 三板斧（圈范围/定链路/挖代码） |
| Self Time | 自耗时间（不含下游） |
| Flame Graph | 火焰图（宽度=热度） |
| Lock Contention | 锁竞争（BLOCKED/热点行锁） |
| async-profiler / JFR | 采样剖析器（低开销） |
| Dominator Tree | 支配树（内存 MAT） |

## 3. 动手实操：两个瓶颈的全流程定位

```text
案例一：下单链路 P99 640ms（day23 拐点处的真实场景）
① Metrics：订单服务 P99 飙、CPU 66% 不高 → 不是算的瓶颈（30s）
② Trace：慢链路拆解 640ms=网关5+订单40+库存530（自耗 45）+DB45 → 锁定库存扣减（3min）
③ Profile：
   jstack 3 次间隔 1s：12 个线程 BLOCKED on 库存扣减 synchronized → 锁竞争实锤
   MySQL slowlog：UPDATE t_stock WHERE sku=? 平均 90ms（无索引走全表？EXPLAIN 验证：缺联合索引）
   ——530ms = 锁排队 420ms + 慢 SQL 90ms + 业务 20ms（两个问题叠加）
④ 治理与验证：
   加联合索引（90ms→3ms）；热点锁分桶（day08 库存分桶，锁竞争除以 8）
   复压：P99 640→140ms，拐点从 800 并发推到 2000+（回填 day23 报告）

案例二：秒杀预扣 QPS 上不去（预期 10 万实测 6 万）
① Metrics：Redis CPU 55%、应用 CPU 40%、网络带宽 90% ⚠ → 网络是瓶颈（非常规！）
② Trace：预扣单次 2 次 Redis RT（校验+扣减没合并）→ 网络往返翻倍
③ Profile：抓包确认每请求 2 个 RTT；Lua 合并后 1 次（day08 的 Lua 本来就该一次——
   历史版本遗留了先 GET 判断）→ 复压 9.8 万/s 达标
   ——教训：链条上每一跳都要"合并 RTT"（day06 批量思想的 Redis 版）
```

```powershell
# 三板斧工具实操清单（逐个跑一遍，截图进 docs/perf/toolbox.md）：
# ① 火焰图（应用容器内）：
docker exec -it mall-order bash -c "wget -qO- https://.../async-profiler.tar.gz | tar xz"
docker exec -it mall-order ./profiler.sh -d 30 -f /tmp/flame.html 1
docker cp mall-order:/tmp/flame.html docs\perf\flame-order.html   # 浏览器打开读塔
# ② 锁分析：jstack 三连抓（间隔 1s）→ grep BLOCKED 统计锁对象
docker exec -it mall-order jstack 1 > jstack-1.txt ; Start-Sleep -Seconds 1 ; docker exec -it mall-order jstack 1 > jstack-2.txt
# ③ 内存：jmap -histo:live 1 | Select-Object -First 20；MAT 深查用 jmap dump
# ④ MySQL：EXPLAIN 慢 SQL + sys.innodb_lock_waits（day03 三查的复用）
git add . ; git commit -m "day08-24: metrics trace profile"
```

## 4. 面试连接

**Q：线上接口突然变慢，你的排查思路？**
> 三层漏斗由粗到细，不跳步。第一层 Metrics 圈范围（30 秒）：黄金四信号大盘看哪个服务 RT 异常、哪个资源饱和——把"系统慢"收窄到"订单服务下单接口 P99 640ms"；同时看 CPU，不高说明是"等的瓶颈"不是"算的瓶颈"，排查方向直接分叉。第二层 Trace 定链路（3 分钟）：SkyWalking 拉慢链路拆每一跳的自耗时间——640ms 里库存扣减占 530ms，一跳锁定。第三层 Profile 挖代码：jstack 三连抓发现 12 线程 BLOCKED 在库存锁（锁竞争），MySQL 慢日志+EXPLAIN 发现 UPDATE 缺索引（90ms 慢 SQL）——两个问题叠加。治理：加索引 90→3ms、热点锁分桶，复压 P99 降到 140ms。这个回答的重点是"漏斗不回头"：每一层输出下一层的输入，跳过 Metrics 直接 jstack 的排查是碰运气。

**Q：火焰图怎么看？什么时候用？**
> 火焰图是 CPU 剖析的可视化：横轴是采样占比（越宽的函数越耗 CPU），纵轴是调用栈（上面是叶子）。读法三句：找最宽的塔（热点调用链）、找平顶（叶子函数本身就是瓶颈，不用往下看）、对比优化前后宽度变化（验证效果）。使用时机：Metrics 显示 CPU 高、Trace 定位到某服务自耗时间高之后——用 async-profiler 采 30 秒生成，看那 530ms 的自耗里到底烧在哪段代码。它和 jstack 的区别：jstack 是瞬时快照（碰运气抓现场），火焰图是持续采样（统计意义上的热点），CPU 问题用火焰图，锁问题用 jstack/JFR，内存问题用 jmap+MAT——工具跟着问题类型走。我们把"火焰图实操"放进压测周例行项，每次压测顺手采一张，热点变化趋势本身就是优化路线图。

**Q：怎么定位是锁问题？解决了几个经典锁瓶颈？**
> 锁问题的指纹：CPU 不高但 RT 高 + jstack 大量 BLOCKED 等同一把锁 + 业务上有热点共享资源。三个经典案例：①库存热点行锁——12 线程 BLOCKED 在 synchronized 库存方法，根治用 day08 的 Redis 预扣+分桶，DB 侧再按条件更新兜底；②热点行 DB 锁——UPDATE 单行排队（innodb_lock_waits 可见），方案是分桶写或队列串行化（day03 攒批）；③应用内 synchronized 粗粒度锁——改为 LongAdder（计数场景 day11）或分段锁（ConcurrentHashMap 思想）。定位口诀：先从 Trace 看自耗时间异常的跳，再 jstack 确认 BLOCKED 分布，最后 EXPLAIN/sys 库确认 DB 侧等待——应用锁和 DB 锁的症状相似但治理完全不同，别混。

## 5. 今日验收清单

- [ ] 三板斧案例一完整走通（640→140ms，含工具截图）
- [ ] 火焰图实操（生成+读塔+优化前后对比）
- [ ] 案例二网络瓶颈识别（RTT 合并 6→9.8 万/s）
- [ ] 《性能定位 toolbox》入库（工具清单+适用问题类型）
- [ ] `git add . && git commit -m "day08-24: mtp funnel"`

---
[← Day 23](day23-压测执行.md) | [本月目录](README.md) | [Day 25 · 秒杀模块总实战 →](day25-秒杀模块总实战.md)
