# Day 10 · 收集器演进与 CMS（含 Docker 体验 JDK8）

> **今日目标**：建立收集器演进时间线（面试必问"你们用什么 GC，为什么"），吃透 CMS 四阶段与三色标记——这是理解 G1/ZGC 的必经之路。
> **时长**：理论 1.5h / 实操 1h / 输出 0.5h
> **今日产出**：收集器演进时间线笔记 + JDK8 容器 CMS 体验记录（Docker Desktop 第二用途）

## 1. 知识地图

```
收集器演进时间线（记忆锚点：痛点驱动进化）：
1999 ──── Serial（单线程，客户端时代）
  │        └→ ParNew（Serial 的多线程版，配 CMS 用）
  │        └→ Parallel Scavenge/Old（吞吐优先，JDK8 默认）
2004 ──── CMS  Concurrent Mark Sweep（停顿优先的第一次革命）JDK14 移除 ✝
2012 ──── G1  Garbage First（可预测停顿 + Region 化）JDK9+ 默认 ★你现在的默认
2018 ──── ZGC（亚毫秒级停顿的第二次革命）JDK21 分代化→JDK23 起默认分代 ★未来主流
        └─ Shenandoah（Red Hat，OpenJDK 版本才有，Oracle JDK 没有）

CMS 四阶段（背！）：
  ① Initial Mark 初始标记 ──── STW！只标记 GC Roots 直达对象（快）
  ② Concurrent Mark 并发标记 ── 与业务并发，遍历整个引用链（慢但不卡）
  ③ Remark 重新标记 ────────── STW！修正并发期间变动的引用（比①久，比②短）
  ④ Concurrent Sweep 并发清除 ─ 与业务并发，清死对象

CMS 三大死穴（为什么被 G1 替代）：
  ① CPU 敏感：并发阶段抢业务线程（默认 (核数+3)/4 个 GC 线程）
  ② 浮动垃圾 Floating Garbage：并发清理时新产生的垃圾本轮收不掉
     → 预留空间给浮动垃圾：-XX:CMSInitiatingOccupancyFraction=92% 提前触发
     → 预留不够 → Concurrent Mode Failure → 退化 Serial Old 全停顿（大事故！）
  ③ 碎片：标记-清除不整理 → 大对象无处安放 → 又是 FullGC

三色标记（并发标记的理论根基，G1/ZGC 都在用）：
  白=未访问(最终白的=垃圾)  灰=自己标了但成员没扫完  黑=自己和成员全扫完
  并发时业务线程改引用，可能"漏标活对象"（错杀！不可容忍）：
    条件① 黑→白的引用被赋值（黑新增指向白）
    条件② 灰→白的原引用被删除（灰失去到白的路径）
    ①②同时发生 → 白对象明明活着却被标成垃圾
  两种解法（背！）：
    CMS=增量更新 Incremental Update：条件①发生时记录黑（Remark 时重扫）→ 破坏①
    G1/ZGC=SATB(原始快照)：条件②发生时记录旧引用 → 破坏②（按快照当时状态收）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| STW (Stop-The-World) | 停顿 | 业务线程全部暂停的阶段 |
| Concurrent Mode Failure | 并发模式失败 | CMS 预留空间不足退化为 Serial Old |
| Floating Garbage | 浮动垃圾 | 并发标记/清除期间产生、下轮才回收的垃圾 |
| Tricolor Marking | 三色标记 | 并发标记的正确性理论 |
| SATB (Snapshot-At-The-Beginning) | 原始快照 | 以 GC 开始时对象图为快照，G1/ZGC 采用 |
| Incremental Update | 增量更新 | 新增引用即记录，CMS 采用 |

## 3. 动手实操

### 3.1 本机 JDK25：验证 CMS 已死

```powershell
java -XX:+UseConcMarkSweepGC -version 2>&1
# 输出会警告： Ignoring option UseConcMarkSweepGC, or ... unsupported
# → 结论写进笔记：CMS 在 JDK14 被移除，ParNew 随之消失
```

### 3.2 Docker Desktop：亲身体验一次 CMS（JDK8 容器）

```powershell
mkdir D:\mywork\springbootai\learning\month01-jvm\day10 -Force
cd D:\mywork\springbootai\learning\month01-jvm\day10
# 把 day05 的 AllocDemo.java 拷贝过来，然后在 JDK8 容器里跑 CMS：
docker run -it --rm -v "D:\mywork\springbootai\learning\month01-jvm\day10:/work" -w /work openjdk:8 bash
# 容器内（Linux 命令）：
javac AllocDemo.java
java -Xms256m -Xmx256m -XX:+UseConcMarkSweepGC -XX:+PrintGCDetails AllocDemo 2> cms.log &
# 观察 cms.log 中的 CMS 阶段名：
grep -i "CMS" cms.log | head -20
# 应能看到：CMS initial mark / CMS-concurrent-mark / CMS-final remark / CMS-concurrent-sweep
```

**对照记录**：CMS 日志四阶段顺序 vs 昨天你 G1 日志的 Pause Young 结构——写 3 条差异进笔记。

### 3.3 在 JDK8 容器里制造一次 Concurrent Mode Failure（加分实验）

```powershell
# 容器内：把老年代逼满，观察退化
java -Xms64m -Xmx64m -XX:+UseConcMarkSweepGC -XX:CMSInitiatingOccupancyFraction=95 -XX:+PrintGCDetails AllocDemo 2> cmf.log
grep -i "concurrent mode" cmf.log     # 出现 promotion failed / concurrent mode failure 即成功
```

## 4. 面试连接

**Q：你们线上用什么 GC？为什么？**
> 答题模板（套你的真实数据）："核心服务 JDK17+G1，目标停顿 200ms 内，堆 8G；对延迟敏感的网关服务在灰度 ZGC（分代），目标 10ms 内；老服务还在 JDK8+CMS，计划升级。"——**有版本、有堆大小、有目标、有演进计划**，这就是 P7 答法。

**Q：CMS 为什么被 G1 取代？**
> 三死穴框架：CPU 敏感 + 浮动垃圾导致 CMF 大停顿 + 碎片不可控；G1 用 Region 化 + 整体标记整理 + 停顿预测模型逐一化解。

**Q：三色标记漏标的两个条件是什么？各收集器怎么解决的？**
> 新增黑→白引用 + 删除灰→白引用同时发生；CMS 用增量更新破坏①，G1/ZGC 用 SATB 破坏②。答出"错杀不可容忍，漏标才要解决，多标（浮动垃圾）可以容忍"更佳。

## 5. 今日验收清单

- [ ] 时间线背熟：CMS 2004/移除 JDK14，G1 默认 JDK9，分代 ZGC 默认 JDK23
- [ ] 能白板画 CMS 四阶段 + 标出哪两段 STW
- [ ] 三色标记漏标条件与两种解法能闭卷作答
- [ ] Docker JDK8 容器 CMS 体验完成（日志截图存 notes/）
- [ ] `git add . && git commit -m "day10: cms & tricolor marking"`

---
[← Day 09](day09-GC算法与分代模型.md) | [本月目录](README.md) | [Day 11 · G1 垃圾收集器 →](day11-G1垃圾收集器.md)
