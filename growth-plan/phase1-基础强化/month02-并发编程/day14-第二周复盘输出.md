# Day 14 · 第二周复盘输出（《一张图讲透 AQS》成文日）

> **今日目标**：周日复盘日。默写第二周三张核心图（两套通信/CAS/AQS 三大件）→ 10 题自测 → 盘点本周代码资产 → 把 day13 的素材正式成文《一张图讲透 AQS》。
> **时长**：默写 1h / 自测 0.5h / 博客成文 2h
> **今日产出**：三张默写图 + 10 题自测记录 + 博客《一张图讲透 AQS》初稿

## 1. 知识地图

```
本周（day08-13）的主线：从"线程间怎么说话"到"同步器怎么造出来"

  day08 两套通信          day09 CAS 原子化        day10-11 AQS 与显式锁
  ┌────────────┐        ┌────────────┐        ┌──────────────────┐
  │ wait/notify │        │  CAS 循环   │        │ state + CLH 队列  │
  │ park/unpark │  ───→  │ AtomicLong  │  ───→  │ + park/unpark     │
  │ 许可可预存   │        │ LongAdder   │        │ = ReentrantLock   │
  └────────────┘        └────────────┘        └──────────────────┘
                                                     │
  day12 三剑客（共享模式应用）                          ▼
  ┌──────────────────────────────────────────┐   day13 手写迷你 AQS
  │ CountDownLatch(剩余计数/事件)              │   ┌──────────────────┐
  │ Semaphore(剩余许可/流量)        都是 AQS   │   │ 50 行三大件锁     │
  │ CyclicBarrier(未到人数/可复用)  state 语义 │   │ 100 线程对拍 100000│
  └──────────────────────────────────────────┘   └──────────────────┘

复盘方法论（与 month01 复盘日一致）：
  默写（不看资料画图）→ 自测（写答案再对）→ 盘点（git log 过一遍代码）
  → 输出（博客初稿）→ 预告（下周主线是什么、承接哪天）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Review / Recall | 复盘/回忆 | 默写优先于重读，暴露记忆空洞 |
| Spaced Repetition | 间隔重复 | 隔 3/7/15 天再自测一次 |
| Code Asset | 代码资产 | 本周可复用的 Demo 与对拍程序 |
| Blog Draft | 博客初稿 | 先成文再迭代，别追求一次完美 |
| Mental Model | 心智模型 | 图 + 类比 + 亲手实验的三位一体 |

## 3. 动手实操

### 3.1 默写任务：三张图（不看资料，画在本子上或白板）

```text
图①：wait/notify vs park/unpark 对比图（day08）
  要点自查：
  □ wait 必须在 synchronized 内 / park 随时可用
  □ notify 随机唤醒一个 / unpark 精确唤醒指定线程
  □ 许可可预存（先 unpark 后 park 不阻塞）vs wait 无此待遇
  □ notifyAll 惊群 vs 多 Condition 精准唤醒（衔接 day11）

图②：CAS 三步骤 + ABA 复现图（day09）
  要点自查：
  □ CAS(V, E, N) 三元组含义
  □ 两条硬件指令对照：x86 cmpxchg + lock 前缀（缓存行锁定）
  □ ABA 复现路径：1→2→1，AtomicStampedReference 版本号解决
  □ LongAdder 分段累计图：base + Cell[] 数组

图③：AQS 三大件 + acquire/release 流程图（day10-13）
  要点自查：
  □ state（volatile int + CAS）
  □ CLH 变体双向队列（prev/next/waitStatus=-1 SIGNAL）
  □ park/unpark 挂起唤醒协议
  □ 独占 vs 共享：state==0 才进 vs state>0 就进
  □ 你的 MiniAqsLockV2 三大件各自对应哪一行代码
```

### 3.2 十题自测（先写答案，再翻回对应 day 核对）

```text
1. notify 和 notifyAll 怎么选？为什么生产者-消费者必须 while 检查？（day08）
2. park 的许可为什么可以"预存"？这对实现锁的 unlock→unpark 顺序意味着什么？（day08）
3. CAS 的 ABA 问题在什么业务下会出事？举一个真实场景（day09）
4. AtomicLong 高并发下慢在哪？LongAdder 快在哪？代价是什么？（day09）
5. AQS 的 state 在三剑客里分别是什么语义？（day10/12）
6. 为什么真 AQS 需要 SIGNAL（waitStatus=-1）这个状态？（对照你的迷你版会出什么 bug）（day13）
7. ReentrantLock 公平锁比非公平锁慢在哪一句话？（day11）
8. lockInterruptibly 解决了 synchronized 的什么死穴？（day11，day02 伏笔）
9. CyclicBarrier 的回调在哪个线程执行？写错会怎样？（day12）
10. 你的 MiniAqsLockV2 删掉 unlock 里的 unpark 会发生什么？为什么？（day13）
```

### 3.3 本周代码资产盘点（git log + 清单）

```powershell
cd D:\mywork\springbootai\learning\month02-concurrency
git log --oneline
# 应看到：day08 wait-notify / day09 cas-aba / day10 aqs-debug /
#         day11 reentrantlock / day12 latch-semaphore-barrier / day13 mini-aqs

# 资产清单（打勾）：
# [ ] WaitRules / ParkDemo / BoundedBuffer(wait 版)
# [ ] SpinLock / ABADemo / AdderCompare
# [ ] FairVsUnfair + AqsDebug 断点记录（5 帧队列图）
# [ ] BoundedBufferC / InterruptibleLock / TryLockDemo
# [ ] InitDemo / PoolDemo / BatchDemo
# [ ] MiniAqsLockV2（100 线程对拍恒 100000）
```

### 3.4 博客成文：《一张图讲透 AQS》（今天必须出初稿）

```markdown
写作模板（day13 素材直接扩写，目标 2000 字 + 2 张图）：
  标题：《一张图讲透 AQS：一个状态、一条队列、一套挂起唤醒协议》
  ① 开头：从"ReentrantLock.lock() 到底发生了什么"切入（痛点共鸣）
  ② 核心图：state + 双向队列 + park/unpark（自己画的，别盗图）
  ③ 三处语义对照表：ReentrantLock(可重入计数)/Semaphore(许可)/CountDownLatch(倒计时)
  ④ 动手环节：贴 MiniAqsLockV2 全码 + 两个错误实验现象（删 unpark 挂死/删校验互斥失效）
  ⑤ 诚实差距清单：真 AQS 的 SIGNAL 契约/双向队列/重入/共享传播
  ⑥ 收尾一句："同步器的本质 = 一个状态 + 一条队列 + 一套挂起唤醒协议"

发布平台：掘金/CSDN/公众号 任选；发布后把链接记进本文件末尾
```

## 4. 面试连接

**Q：你说你读过 AQS 源码，怎么证明？**
> 三层证据链：① 能白板画 acquire/release 全流程（图③）；② 手写过 50 行迷你版并说出四点差距（SIGNAL 契约/双向队列/重入/共享传播）；③ 能讲两个故意破坏实验的现象与原因（删 unpark 挂死 = 丢失唤醒）。发博客链接是最硬的证据。

**Q：第二周哪块最难？怎么克服的？**
> 建议答 SIGNAL 契约：一开始不懂为什么 park 前要先设 waitStatus=-1。后来在自己迷你版上复现了丢失唤醒（删 unpark 挂死）才真正懂——"检查条件"和"挂起"之间有时间窗，窗口内来的唤醒必须被记录。这是"用实验补齐理解"的回答模板。

## 5. 今日验收清单

- [ ] 三张图默写完成，遗漏点已回炉
- [ ] 十题自测 ≥8 题脱稿答出
- [ ] git log 六个提交齐全，代码资产清单打勾
- [ ] 博客《一张图讲透 AQS》初稿成文（发布后补链接）
- [ ] `git add . && git commit -m "day14: week2 review + aqs blog draft"`

---
[← Day 13](day13-手写迷你AQS.md) | [本月目录](README.md) | [Day 15 · 线程池七参数与执行流程 →](day15-线程池七参数与执行流程.md)
