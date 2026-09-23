# Day 07 · 第一周复盘（并发地基输出）

> **今日目标**：默写并发地基四张图、完成 10 题自测、产出《同步机制选型决策表 v1》——这周结束，"为什么并发会出问题"你有完整答案了。
> **时长**：默写 1h / 自测 1h / 输出 1h
> **今日产出**：四张默写图 + 自测成绩 + 选型决策表 v1

## 1. 默写任务（四张图，拍照存 notes/week01/）

| 图 | 内容要求 | 对照 |
|----|---------|------|
| 图 1 | 线程六状态图（每条迁移边标触发条件） | day02 |
| 图 2 | JMM 主内存/工作内存交互 + happens-before 八规则 | day03 |
| 图 3 | DCL 单例 new 三步重排图（半初始化链路） | day04 |
| 图 4 | 锁升级双版本图（教科书四级 / JDK25 三级）+ ObjectMonitor 四字段 | day05/06 |

## 2. 并发地基十连问（闭卷）

1. 可见性问题为什么在 JIT 下更明显？-Xint 对照说明什么？
2. count++ 丢更新的完整过程？三种修复武器分别是什么？
3. 六状态里 BLOCKED 和 WAITING 的本质区别？谁响应中断？
4. interrupt() 之后线程一定停吗？三种响应方式是什么？
5. happens-before 是时间顺序吗？八大规则分几类？
6. volatile 两层语义？为什么说"写贵读廉"？
7. DCL 两次检查各自存在的理由？不加 volatile 会怎样？
8. 同步块为什么有两条 monitorexit？同步方法靠什么？
9. synchronized 可重入的机制？锁对象选择的三个原则？
10. JDK15 为什么废弃偏向锁？你的 JDK25 上锁有几级？

**通过线**：8/10。错题回对应 day 知识地图重读。

## 3. 输出任务：《同步机制选型决策表 v1》（本月持续修订）

| 场景 | 选型 | 理由 | 后续更新 |
|------|------|------|---------|
| 一写多读的状态标志 | volatile | 可见性够用，无锁开销 | day04 |
| 计数器/累加器 | Atomic 类（day09）/ 锁 | volatile 不保原子性 | 待 day09 |
| 复合操作的互斥 | synchronized / ReentrantLock | 保证原子性+可见性 | 待 day11 对比 |
| 高竞争计数 | LongAdder（day09） | 分段降低热点 | 待 day09 |
| 线程间等待唤醒 | wait/notify / Condition | day08 对比 | 待 day08 |

## 4. 综合思考题（写 300 字）

> **"代码评审看到 `if(flag) doSomething()`，flag 是普通 static 字段，由另一个线程修改。你提出哪些问题？"**
>
> 要点自查：
> ① flag 需要 volatile 吗？读写有没有 happens-before 边（锁/线程关系）？
> ② doSomething 内部有没有共享状态？check-then-act 是不是原子需求？
> ③ flag 会不会是"先置 false 再做事"的语义？有序性有没有影响？
> ④ 给出修改建议并说明选择依据（volatile / AtomicBoolean / 加锁）

## 5. git 归档

```powershell
cd D:\mywork\springbootai\learning\month02-concurrency
git add .
git commit -m "week01: jmm, volatile, synchronized foundations"
```

## 6. 今日验收清单

- [ ] 四张图默写完成
- [ ] 10 题自测 ≥8 分
- [ ] 选型决策表 v1 成文
- [ ] 思考题 300 字
- [ ] git 归档 + 预习 day08 线程通信

## 7. 下周预告

Week 2 进入"锁的内在世界"：wait/notify 与 LockSupport 两套通信 → CAS 与原子类（LongAdder/ABA）→ AQS 总设计 → ReentrantLock 与 Condition → 三剑客工具类 → **手写迷你 AQS（100 行）**。这周结束你就能对任何锁的实现原理说"我看懂了源码"。

---
[← Day 06](day06-synchronized下-锁升级与优化.md) | [本月目录](README.md) | [Day 08 · 线程通信两套 →](day08-wait-notify与LockSupport.md)
