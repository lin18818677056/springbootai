# Day 24 · JVM 参数与生产模板（背下就能用的两套配置）

> **今日目标**：参数不是背出来的，是"查默认值 → 按目标覆盖 → 验证生效"三步出来的。今天掌握参数体系、两套可直接用的新生代生产模板（G1/ZGC），并学会用 jcmd 验证"参数真的生效了"。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：《生产 JVM 参数模板》+ 生效验证记录

## 1. 知识地图

```
参数三大类：
  -X 系列   常用非标准：-Xms/-Xmx/-Xss/-Xmn
  -XX 系列  高级：-XX:+UseG1GC（布尔）  -XX:MaxGCPauseMillis=200（定量）
  -D 系统   应用属性：-Dserver.port=8080

查询三件套（不懂就查，别背）：
  java -XX:+PrintFlagsFinal -version | findstr <名字>   ← 全量默认值（Windows 用 findstr）
  java -XX:+PrintFlagsFinal -version -XX:+UseZGC | findstr UseZGC  ← 参数影响后的值
  jcmd <pid> VM.flags                                   ← 运行中进程"实际生效"的参数 ★

调参心法（顺序重要）：
  ① 先测量（day13 GC 报告）→ ② 定目标（停顿？吞吐？）→ ③ 动最小参数集
  → ④ 同口径复测 → ⑤ 固化进启动脚本并写文档
  反例大赏：抄 20 个"玄学参数"进生产 = 给自己埋雷

生产模板一：4C8G 常规微服务（G1，JDK17/21/25 通用）
  -Xms4g -Xmx4g                          堆固定一半，避免动态伸缩抖动
  -XX:+UseG1GC                           显式声明（虽是默认，写出来防歧义）
  -XX:MaxGCPauseMillis=100               停顿目标（G1 会自我调节适配）
  -Xlog:gc*:file=logs/gc-%t.log:time,uptime,level,tags:filecount=5,filesize=50m
                                         滚动 GC 日志（day13 生产配置）
  -XX:+HeapDumpOnOutOfMemoryError        OOM 自动取证（day19）
  -XX:HeapDumpPath=/data/dump/           dump 存放路径
  -XX:+ExitOnOutOfMemoryError            OOM 即退出，交给 K8s 拉起新实例
  -XX:MaxMetaspaceSize=512m              元空间上限（防类加载泄漏吃爆机器）

生产模板二：大堆低延迟服务（ZGC 分代，JDK21+）
  -Xms8g -Xmx8g
  -XX:+UseZGC                            JDK25 即分代 ZGC（JEP 474 默认）
  其余同上（GC 日志/OOM 取证/退出策略照抄）
```

## 2. 核心概念（中英对照）

| 英文/参数 | 中文 | 要点 |
|-----------|------|------|
| -Xms / -Xmx | 初始/最大堆 | 生产两者设同值，避免伸缩开销 |
| -Xss | 线程栈大小 | 默认 1M；高并发小栈场景调 512k |
| MaxGCPauseMillis | 最大 GC 停顿目标 | G1 的"油门"，别设太小（<20ms 会自伤） |
| HeapDumpOnOutOfMemoryError | OOM 时自动转储 | 生产必开，事故唯一现场 |
| ExitOnOutOfMemoryError | OOM 即退出 | 配合 K8s 重启，避免"僵尸服务" |
| Reserved / Committed | 预留/已提交内存 | PrintFlagsFinal 里的两种堆容量口径 |

## 3. 动手实操

### 3.1 实验①：查默认值，建立"参数审计"习惯

```powershell
cd D:\mywork\springbootai\learning\month01-jvm; mkdir day24 -Force; cd day24

# 看堆相关默认值（容器敏感参数会显示 Ergonomics 决策）
java -XX:+PrintFlagsFinal -version | findstr "MaxHeapSize"
java -XX:+PrintFlagsFinal -version | findstr "UseG1GC UseZGC"
java -XX:+PrintFlagsFinal -version | findstr "MaxGCPauseMillis"

# 参数影响对比：给 2g 堆后再看 MaxHeapSize
java -XX:+PrintFlagsFinal -version -Xmx2g | findstr "MaxHeapSize"
# 记录：= 2147483648  {product} {command line} ← 最后一列是来源！
# （command line=你传的 / ergonomics=JVM 自适应 / default=出厂值）
```

### 3.2 实验②：生产模板真的生效了吗？（jcmd 验证）

```powershell
# 用模板参数起一个演示进程
java -Xms512m -Xmx512m -XX:MaxGCPauseMillis=100 -XX:MaxMetaspaceSize=256m `
  -Xlog:gc*:file=gc-%t.log:filecount=3,filesize=10m day15.DiagTarget 2>$null
# （没有包结构就直接跑 DiagTarget：javac 后 java -Xms512m ... DiagTarget）

# 另一个窗口：
jps -l                        # 找 PID
jcmd <PID> VM.flags | Select-String "MaxHeapSize|MaxGCPauseMillis|MaxMetaspaceSize|UseG1GC"
# 逐项核对：MaxHeapSize=536870912 ✓ MaxGCPauseMillis=100 ✓ UseG1GC=true ✓
```

### 3.3 实验③：把模板改造成你的《生产参数模板》

```text
存 notes/production-jvm-args.md，包含两部分：
一、8G 机器微服务模板（抄上面的 G1 模板，逐参数写一句"为什么"）
二、16G+ 低延迟服务模板（ZGC 版）
三、三问自查表：
   Q1 堆为什么 = 物理内存一半？（留 OS 缓存+堆外+其他进程）
   Q2 为什么 Xms=Xmx？（避免扩缩容抖动、容器内存超卖风险）
   Q3 为什么必开 HeapDumpOnOOM + ExitOnOOM？（取证 + 快速失败）
```

## 4. 面试连接

**Q：说说你们生产的 JVM 参数，为什么这么配？**
> 结构化答：堆（物理一半、Xms=Xmx）→ 收集器（G1 + 停顿目标 100ms）→ 可观测（滚动 GC 日志 + OOM 自动 dump + ExitOnOOM）→ 防护（Metaspace 上限）。每个参数跟一句"为什么"，完胜背参数清单。

**Q：-Xms 和 -Xmx 为什么要设成一样？**
> 运行期堆伸缩（扩容/收缩）伴随 FullGC 风险和内存抖动；固定堆让容量规划确定，容器环境还避免"超卖被 OOM Killer 干掉"。代价是启动即占满——微服务可接受。

**Q：线上怎么确认一个参数生效了？**
> 启动前 PrintFlagsFinal 看来源列；运行中 jcmd VM.flags 看实际生效值。能说出"参数有 default/ergonomics/command line 三种来源"就是懂行。

## 5. 今日验收清单

- [ ] PrintFlagsFinal 查过 3 个参数并理解"来源"列
- [ ] jcmd VM.flags 验证模板参数全部生效
- [ ] 《生产 JVM 参数模板》成文（含三问自查）
- [ ] 能脱稿说出 G1 微服务模板的 7 个参数和理由
- [ ] `git add . && git commit -m "day24: jvm args & production templates"`

---
[← Day 23](day23-JMH基准测试.md) | [本月目录](README.md) | [Day 25 · 内存泄漏六大场景 →](day25-内存泄漏六大场景.md)
