# Day 12 · ZGC 与低延迟收集器

> **今日目标**：理解 ZGC 为什么能"几乎全程并发"（着色指针 + 读屏障），实测 G1 vs ZGC 的停顿差异，知道什么场景该选 ZGC。
> **时长**：理论 1.5h / 实操 1h / 输出 0.5h
> **今日产出**：G1 vs ZGC 停顿对比表

## 1. 知识地图

```
ZGC 的核心命题：把 G1 里"STW 的活"全都搬进并发阶段

G1 的停顿来源              ZGC 的对策
─────────────────────     ─────────────────────────────
标记需 STW（初始标记）  →   并发标记（读屏障+着色指针保证正确性）
转移对象需 STW       →   并发转移！（对象搬家的同时业务在跑）
引用全要改（搬家后）  →   读屏障懒更新 + Self-Healing 自愈

着色指针 Colored Pointer（64位中借几个位存 GC 元数据）：
 63...46 │ 45 │ 44   43   42 │ 41.................0
 未用     │Final│Marked1 Marked0│        地址
          │able│              │
 Marked0/Marked1 = 标记位（两轮标记交替用，标记视图切换）
 Remapped(45位)  = 转移完成视图
 → 指针本身携带 GC 状态，不用额外内存记标记！

并发转移的"魔法"——自愈 Self-Healing：
 业务线程读对象时，读屏障检查指针颜色：
   颜色 = 旧视图？ → 说明对象可能已被搬走
     → 查转发表找到新地址 → 返回新地址并把指针改写为新地址 ← 这就是自愈
     → 下次再读 = 新地址，零开销
 效果：对象搬家瞬间，业务代码甚至"感觉不到"

分代 ZGC（重要！版本演进）：
 JDK21 引入实验(-XX:+ZGenerational) → JDK23 默认分代
 → JDK24 移除非分代模式 → 你的 JDK25：-XX:+UseZGC 即分代 ZGC
 为什么分代：不分代时每次 GC 全堆标记，CPU 消耗大；分代后短命对象
 走高频年轻代回收，恢复 G1 的"分代红利"且保持亚毫秒停顿

G1 vs ZGC 选型表：
           G1                ZGC（分代）
 停顿     10~200ms          <1ms（几乎不受堆大小影响）
 吞吐     高                略低（读屏障/染色指针开销~5-15%）
 内存开销 RSet 5-20%        转发表/视图（相对小）
 适用     大多数服务        大堆(16G+)+低延迟敏感（网关/实时/大促核心）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Colored Pointer | 着色指针 | 64 位指针的高位存 GC 元数据（标记/重映射视图） |
| Load Barrier | 读屏障 | 读引用时校验/自愈指针颜色的钩子 |
| Self-Healing | 自愈 | 读到旧视图指针时自动改写为新地址 |
| Remap / Relocation | 重映射/转移 | 并发搬移对象并更新视图 |
| Generational ZGC | 分代 ZGC | JDK21+，年轻代高频回收，JDK23 起默认 |
| Shenandoah | 谢南多厄 | Red Hat 的低延迟收集器（Brooks 转发指针方案） |

## 3. 动手实操

### 3.1 G1 vs ZGC 实测对比（今天的重头戏）

```powershell
cd D:\mywork\springbootai\learning\month01-jvm\day11
# 同一个 HumongousDemo + AllocDemo 混合压力，分别用两种收集器跑：

# 轮次 1：G1
java -Xms1g -Xmx1g -Xlog:gc::utctime,uptime AllocDemo 2> g1.log

# 轮次 2：ZGC（你的 JDK25 = 分代 ZGC）
java -Xms1g -Xmx1g -XX:+UseZGC -Xlog:gc::utctime,uptime AllocDemo 2> zgc.log

# 对比统计（PowerShell 统计暂停时长）
Select-String -Path g1.log  -Pattern "(\d+\.\d+)ms" | Measure-Object | Select-Object Count
Select-String -Path zgc.log -Pattern "Pause Mark Start|Pause Mark End|Pause Relocate Start"
```

### 3.2 填写对比表（notes/week02/g1-vs-zgc.md）

| 指标 | G1 | ZGC（分代） |
|------|----|-----------|
| 10 分钟 GC 次数 | （你填） | （你填） |
| 最大单次暂停 | （你填，如 12.3ms） | （你填，如 0.4ms） |
| 平均暂停 | | |
| 总吞吐感受（完成轮数） | | |
| 内存占用（任务管理器看） | | |

### 3.3 观察 ZGC 日志特色

```
ZGC 日志关键字段（对照你的 zgc.log 找）：
GC(4) Pause Mark Start 0.008ms      ← 三段式小停顿（都是亚毫秒）
GC(4) Pause Mark End   0.006ms
GC(4) Pause Relocate Start 0.007ms  ← 真正搬对象在这之后并发进行
GC(4) Concurrent Mark Cycle 12.3ms  ← 并发阶段（不暂停业务）
```

### 3.4 思考题

ZGC 停顿几乎不随堆变大而变长（G1 会），为什么？（答案要点：转移是并发的、分 Region 增量进行，停顿只与"根扫描"相关而与堆总量无关——这是它敢上 TB 级堆的底气）

## 4. 面试连接

**Q：ZGC 为什么能亚毫秒停顿？**
> 三件套：着色指针（指针携带 GC 状态，标记不用额外内存）+ 读屏障自愈（业务读到旧指针自动改新）+ 并发转移（搬家不停业务）。分代化后补上了吞吐短板。

**Q：为什么不是所有系统都上 ZGC？**
> 吞吐税（读屏障常驻开销）+ 小堆收益小 + 团队运维经验。选型公式："停顿敏感且堆 ≥8~16G → ZGC；否则 G1"。能说出"我们压测过两者各指标"最硬。

**Q：ZGC 的读屏障对代码有侵入吗？**
> 无侵入——屏障是 JIT 在访问堆引用时自动插入的指令，Java 代码无感知；代价是所有引用读取多几条指令。

## 5. 今日验收清单

- [ ] 能画着色指针位段图并解释 Marked0/1 交替
- [ ] 讲清 Self-Healing 流程（读→发现旧视图→查表→改指针→返回）
- [ ] G1 vs ZGC 对比表填写完成（真实数据）
- [ ] 能说出分代 ZGC 的版本演进（21 实验/23 默认/24 移除非分代）
- [ ] `git add . && git commit -m "day12: zgc & colored pointer"`

---
[← Day 11](day11-G1垃圾收集器.md) | [本月目录](README.md) | [Day 13 · GC 日志深度解读 →](day13-GC日志深度解读.md)
