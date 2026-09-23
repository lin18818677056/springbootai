# Day 21 · 第三周复盘输出（《手写迷你线程池》发布日）

> **今日目标**：周日复盘日。默写本周四张图（线程池三步决策/CHM putVal 六分支/CF 聚合链/ThreadLocal 泄漏链）→ 10 题自测 → 盘点代码资产 → 正式发布《手写迷你线程池》博客（M2 验收第 2 篇）。
> **时长**：默写 1h / 自测 0.5h / 博客发布 1.5h
> **今日产出**：四张默写图 + 10 题自测记录 + 博客《手写迷你线程池》发布链接

## 1. 知识地图

```
本周（day15-20）主线：从"造一个池"到"用好容器与异步"

  day15-16 线程池              day17 手写            day18 CF 编排
  ┌──────────────┐          ┌──────────────┐      ┌──────────────┐
  │ 七参数        │          │ 100 行       │      │ allOf 扇入    │
  │ 三步决策      │  ─────→  │ 队列+工人+关闸│ ──→  │ 300ms→100ms   │
  │ 动态调参      │          │ 对拍一致     │      │ orTimeout     │
  └──────────────┘          └──────────────┘      └──────────────┘
                                                        │
  day19 CHM                          day20 ThreadLocal  ▼
  ┌──────────────────────┐          ┌──────────────────────────┐
  │ putVal 六分支         │          │ 弱 key 强 value 泄漏链    │
  │ CAS+synchronized 头节点│  ─────→ │ finally remove 铁律       │
  │ 复合操作不原子（merge）│          │ TTL 快照/回放             │
  └──────────────────────┘          └──────────────────────────┘

本月过半自查：M2 验收 7 条中，day30 前应已完成：
  ✔ 手写迷你 AQS（day13）  ✔ 手写迷你线程池（day17）
  ✔ 300ms→100ms 聚合（day18）  ✔ CHM put 流程图（day19）
  ✔ ThreadLocal 泄漏链（day20）  ◻ 限流器（day27）  ◻ 连接池（day28）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Publish | 发布 | 博客发布是最强的复盘压力测试 |
| Reference Check | 参照核对 | 默写后逐条对照原文补漏 |
| Asset Inventory | 资产盘点 | git log + 代码清单双核对 |
| Half-way Review | 中期自查 | 第 21 天盘点 M2 验收进度 |
| Next Week Preview | 下周预告 | 带着问题进入 Week4 |

## 3. 动手实操

### 3.1 默写任务：四张图（不看资料画出来）

```text
图①：execute 三步决策图（day15）
  自查：核心线程→队列→救急→拒绝 四级顺序 + SynchronousQueue 例外

图②：CHM putVal 六分支流程图（day19，M2 验收项）
  自查：空桶 CAS / MOVED 帮扩容 / synchronized 头节点 / 链表树化条件
  （binCount≥8 且 数组长度≥64）

图③：CF 聚合链路图（day18）
  自查：supplyAsync×3 → allOf → 逐个 join；超时 orTimeout → exceptionally 兜底
  数据：串行 300ms → 并行 100ms（写上你实测的数字）

图④：ThreadLocal 泄漏链（day20）
  自查：线程池线程不死 → Map 不死 → 弱 key 被回收 → 强 value 成僵尸
  修复：finally remove；TTL：提交抓快照+执行回放
```

### 3.2 十题自测（写完答案再翻回核对）

```text
1. 线程池为什么"先入队再扩容"？SynchronousQueue 为什么例外？（day15）
2. CallerRunsPolicy 的背压效果是怎么发生的？有什么副作用？（day15）
3. keepAliveTime 能回收核心线程吗？怎么强制回收？（day16）
4. 你的 MiniPool 的 while 条件为什么是 !shutdown || !taskQueue.isEmpty()？（day17）
5. submit 的任务抛异常去哪了？execute 呢？（day17）
6. thenApply 和 thenCompose 各解决什么问题？（day18）
7. CF 不传 executor 的默认池是什么？为什么 IO 任务必须自带池？（day18）
8. CHM 空桶和非空桶分别怎么加锁？为什么这样设计？（day19）
9. "线程安全容器里的 get+put"为什么不安全？三种正确写法？（day19）
10. TTL 和 InheritableThreadLocal 的本质区别是什么？（day20）
```

### 3.3 本周代码资产盘点

```powershell
cd D:\mywork\springbootai\learning\month02-concurrency
git log --oneline
# 应看到：day15 tpe / day16 sizing / day17 mini pool / day18 cf /
#         day19 chm / day20 threadlocal

# 资产清单（打勾）：
# [ ] SevenParamsDemo（四阶段）/ RejectPolicyDemo（四策略）/ NamedFactory
# [ ] CpuBench / IoBench（压测数据已记）/ DynamicPoolDemo
# [ ] MiniPool（≤100 行）+ MiniPoolPairTest
# [ ] AggCompare（300→100）/ ChainDemo / TimeoutDemo
# [ ] MapBench / CounterBug / CacheDemo
# [ ] TLBasic / LeakRepro / LeakFixed / TtlDemo
```

### 3.4 博客发布：《手写迷你线程池》（今天必须发出去）

```markdown
成文与发布清单：
  标题：《100 行手写线程池：我独立看懂了 ThreadPoolExecutor》
  素材：day17 博客素材扩写（结构已在 day17 §3.4 定好）
  必须包含：
    □ execute 三步决策图（对照"我的简化版只有队列一级"）
    □ MiniPool 全码（≤100 行标注）+ 对拍结果截图
    □ 两个错误实验（不 shutdown 不退出 / 去掉存量判断丢任务）
    □ 四点差距清单（救急线程/keepAlive/拒绝钩子/状态机）
  发布平台：掘金/CSDN/公众号（与上一篇 AQS 同平台最好，攒系列感）
  发布后：把链接补录到本文件末尾 + day17 的博客素材段落
```

## 4. 面试连接

**Q：并发这块你最有心得的是什么？**
> 推荐答法（有实证链）："手写过迷你 AQS 和迷你线程池，并且都做了对拍和破坏实验"——然后自然引出博客链接。比背概念高两个档位，因为每句话背后都有代码和数据。

**Q：第三周你踩过什么坑？**
> 准备两个真实坑：① CounterBug：CHM 里 get+put 计数丢了 30%，后来用 merge 修复——理解"容器安全≠操作安全"；② LeakRepro：复用线程读到上一个任务的 ThreadLocal 值——从此 set/get/remove 三件套是肌肉记忆。

## 5. 今日验收清单

- [ ] 四张图默写完成（图②是 M2 验收项）
- [ ] 十题自测 ≥8 题脱稿
- [ ] git log 六个提交齐全
- [ ] 博客《手写迷你线程池》已发布，链接已补录
- [ ] `git add . && git commit -m "day21: week3 review + mini-pool blog published"`

---
[← Day 20](day20-ThreadLocal与线程封闭.md) | [本月目录](README.md) | [Day 22 · 读写锁与 StampedLock →](day22-读写锁与StampedLock.md)
