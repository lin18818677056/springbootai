# Day 17 · Arthas 入门（阿尔萨斯 · 在线诊断神器）

> **今日目标**：Arthas 是阿里开源的线上诊断工具——不改代码、不重启应用，attach 上去就能看线程/反编译/读对象。今天装好它，用 6 个命令把 day15 的 DiagTarget"查个底朝天"。
> **时长**：理论 0.5h / 实操 2h / 输出 0.5h
> **今日产出**：DiagTarget 全套 Arthas 诊断记录

## 1. 知识地图

```
Arthas 原理一句话：Java 自己提供的"官方后门"

Arthas 客户端 ──attach──► 目标 JVM（DiagTarget / 线上服务）
     │                       │
     │            ① Attach API（com.sun.tools.attach）
     │               ↓
     │            ② Instrumentation 接口 → retransformClasses
     │               ↓
     │            ③ 字节码增强：给目标类"装探头"
     │                       │
     └──命令行交互（输入命令看结果）◄──┘

高频命令速查（今天 6 个 + 2 个加分）：
  dashboard   总览仪表盘（线程/内存/GC 实时刷）
  thread      线程全家桶：-n 3 最忙三线程 / --state BLOCKED / -b 一键抓死锁
  sc          查类：Search Class（类从哪来、被谁加载）
  jad         反编译：线上正在运行的 class 还原成 java
  ognl        执行表达式：读静态字段 / 调静态方法（读缓存大小神器）
  vmtool      直接从堆里捞对象实例（JDK9+ 推荐方式）
```

## 2. 核心概念（中英对照）

| 英文/术语 | 中文 | 要点 |
|-----------|------|------|
| Arthas | 阿尔萨斯 | 阿里开源 Java 诊断工具（名字来自魔兽角色） |
| Attach API | 附加机制 | JVM 官方提供的跨进程注入接口 |
| Instrumentation | 插桩机制 | java.lang.instrument 字节码增强规范 |
| OGNL | 对象图导航语言 | Arthas 里执行表达式的 DSL |
| Decompile | 反编译 | jad 把 .class 还原成 .java 源码 |
| dashboard | 仪表盘 | 实时刷新的线程/内存/GC 总览 |

## 3. 动手实操

### 3.1 安装并连接（5 分钟）

```powershell
mkdir D:\mywork\springbootai\learning\month01-jvm\day17 -Force
cd D:\mywork\springbootai\learning\month01-jvm\day17

# ① 下载 arthas-boot.jar（约 1.5MB）
Invoke-WebRequest -Uri https://arthas.aliyun.com/arthas-boot.jar -OutFile arthas-boot.jar

# ② 启动"受害者"（day15 的 DiagTarget：死锁 + 泄漏双症状）
Copy-Item ..\day15\DiagTarget.java .
javac DiagTarget.java
java DiagTarget          # 记下它打印的 PID

# ③ 新开一个 PowerShell 窗口，启动 Arthas 并选择 DiagTarget 进程
java -jar arthas-boot.jar
# 列表里输入 DiagTarget 对应的序号回车 → 提示符变成 [arthas@PID]$
```

### 3.2 六连击实操（每条命令的输出截图存档）

```text
[arthas@PID]$ dashboard
      ① 总览仪表盘，每 5 秒刷新：看线程 ID/CPU%、内存分区、GC 次数
         按 q 或 Ctrl+C 退出

[arthas@PID]$ thread -n 3
      ② CPU 最忙的 3 个线程 + 完整调用栈（含 CPU 时间占比）

[arthas@PID]$ thread --state BLOCKED
      ③ 只看阻塞态线程 → 应看到 worker-1/worker-2 都卡着

[arthas@PID]$ thread -b
      ④ ★一键抓死锁：直接打印互相持锁的两个线程和它们在等谁

[arthas@PID]$ sc -d DiagTarget
      ⑤ 查类详情：类加载器、class 文件路径、是否被增强过

[arthas@PID]$ jad DiagTarget
      ⑥ 反编译线上类：确认"跑的代码"和你以为的版本一致

[arthas@PID]$ ognl '@DiagTarget@CACHE.size()'
      ⑦ ★读静态字段：泄漏中的 Map 现在多少条？（隔 30 秒再敲一次，看增长）

[arthas@PID]$ vmtool --action getInstances --className DiagTarget --limit 1
      ⑧ 从堆里直接捞 DiagTarget 实例（进阶可配 --express 读实例字段）
```

### 3.3 一次"线上问题"最小闭环（把命令串成破案故事）

1. `dashboard` → 老年代占用持续攀升、不回落（泄漏嫌疑）
2. `ognl '@DiagTarget@CACHE.size()'` → 每分钟 +300 条，持续增长 → 实锤增长点
3. `thread -b` → 先排除死锁干扰（本例确实有死锁，处理思路：先恢复再根因）
4. 结论：主线程持续往静态 `CACHE` put 100KB 的 byte[]，从不清理。
   —— 这是"快速定位"版；要精确到代码行和修复建议，交给 day19 的 dump 分析。

### 3.4 （预告）容器里的 Arthas

线上应用十有八九跑在容器里。day18 做火焰图必须 Linux 环境，届时我们在 Docker 容器里把今天这套流程原样走一遍——命令一模一样，体会"容器化诊断"。

## 4. 面试连接

**Q：Arthas 的原理？为什么不重启就能改字节码？**
> Attach API 让外部进程连上目标 JVM 拿到 Instrumentation 对象，retransformClasses 用增强后的字节码替换目标类（ASM 生成探针代码）。这是 JVM 规范内的官方机制；用完 stop 会还原字节码。

**Q：jstack 和 Arthas thread 有什么区别？**
> jstack 是一次性快照、全量输出，还要自己肉眼找；Arthas thread 能按 CPU 排序（-n）、按状态过滤（--state）、一键死锁（-b），还支持持续交互。排查效率不是一个量级。

**Q：生产敢用 Arthas 吗？**
> 敢，但守纪律：① 只读命令优先（dashboard/thread/sc/jad）② watch/trace 短时低频，别忘加条件（如 '#cost>100ms'）③ 用完 exit 并确认增强已还原。顺带说"我们在灰度环境先演练过"最稳。

## 5. 今日验收清单

- [ ] arthas-boot.jar 下载成功并 attach 到 DiagTarget
- [ ] 8 条命令全部实操并截图
- [ ] thread -b 输出的死锁对能讲清互等关系
- [ ] ognl 读到 CACHE.size() 且观察到持续增长
- [ ] `git add . && git commit -m "day17: arthas basics"`

---
[← Day 16](day16-JFR飞行记录仪.md) | [本月目录](README.md) | [Day 18 · Arthas 进阶与火焰图 →](day18-Arthas进阶与火焰图.md)
