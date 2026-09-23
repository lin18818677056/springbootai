# Day 21 · 第三周复盘（排查工具链输出）

> **今日目标**：本周是"武器库周"。今天默写工具链四张图、完成工具主题 10 题自测、发布《线上排查工具箱》博客——形成你自己的排查方法论。
> **时长**：默写 1h / 自测 1h / 输出 1h
> **今日产出**：四张默写图 + 自测成绩 + 工具箱博客

## 1. 默写任务（四张图，拍照存 notes/week03/）

| 图 | 内容要求 | 对照 |
|----|---------|------|
| 图 1 | JFR 数据流：业务线程→环形缓冲→.jfr→view/JMC；对比 dump/jstack 定位 | day16 |
| 图 2 | Arthas 原理：Attach API→Instrumentation→字节码增强；6 命令定位 | day17 |
| 图 3 | CPU 四步法：top→top -Hp→printf %x→jstack nid；双元凶决策树 | day20 |
| 图 4 | dump 三板斧：Leak Suspects→Dominator Tree→Path to GC Roots | day19 |

## 2. 工具主题十连问（闭卷）

1. JFR 为什么开销小？三种开录方式分别什么场景用？
2. `jfr view hot-methods` 和火焰图的数据来源一样吗？
3. Arthas attach 的完整原理链？（Attach API → Instrumentation → ？）
4. watch/trace/tt 各解决什么问题？一句话区分。
5. 火焰图"平顶"代表什么？宽但不是平顶说明什么？
6. async-profiler 为什么在 Windows 跑不了？替代方案两条。
7. jmap -dump:live 的 live 是什么意思？有什么副作用？
8. Shallow Heap 和 Retained Heap 的区别？找泄漏按哪个排序？
9. Path to GC Roots 为什么要排除弱/软引用？
10. CPU 100% 双元凶是什么？GC 线程忙时下一步查什么？

**通过线**：8/10。错题回对应 day 的知识地图段落重读。

## 3. 综合思考题（写 300 字）

> **"同事说服务 'CPU 高'，你到场后第一件事做什么？列出你的判断顺序。"**
>
> 要点自查（写完再对照）：
> ① 先定性：top 看 us/sy 比例；top -Hp 看是业务线程还是 GC 线程在烧
> ② 业务线程 → 四步法 + 火焰图（占比分布）→ 代码行
> ③ GC 线程 → jstat -gcutil + GC 日志触发原因（Humongous/Metadata？）
> ④ 都不高但 load 高 → sy 占比高 → 查线程数/上下文切换/IO
> ⑤ 全程留证：截图、dump、日志，事后出报告（day19 骨架）

## 4. 输出任务：发布《我的线上排查工具箱》博客

结构建议（素材全来自本周）：
1. 工具地图：命令行（day15）→ JFR（day16）→ Arthas（day17-18）→ MAT（day19）
2. 三个真实小案例：死锁（thread -b）、泄漏（三板斧）、CPU（四步法）
3. 一张《症状→工具》速查表（day15 的 3.4 表扩充）
4. 彩蛋：你踩的坑（如 async-profiler 不支持 Windows 的容器方案）

## 5. git 归档

```powershell
cd D:\mywork\springbootai\learning\month01-jvm
git add .
git commit -m "week03: jfr, arthas, mat, cpu troubleshooting"
```

## 6. 今日验收清单

- [ ] 四张图默写完成
- [ ] 10 题自测 ≥8 分
- [ ] 综合思考题 300 字成文
- [ ] 《排查工具箱》博客发布（或草稿定稿）
- [ ] git 归档 + 预习 day22 JIT

## 7. 下周预告

Week 4-5 进入"性能本质周"：JIT 即时编译（代码为什么越跑越快）→ JMH 基准测试（科学地比快）→ JVM 参数与生产模板 → 内存泄漏六大场景 → OOM 全型复现 → G1 调优实战 → 虚拟线程 → 综合演练。从"会查问题"升级到"能防问题、能调优"。

---
[← Day 20](day20-CPU飙高排查实战.md) | [本月目录](README.md) | [Day 22 · JIT 即时编译 →](day22-JIT即时编译.md)
