# Day 29 · 综合实战排查演练（三病齐发，15 步通关）

> **今日目标**：把本月全部武器投入一场"战役"：一个同时患有 CPU 热点 + 内存泄漏 + 大对象 GC 压力的程序。按生产节奏走完 15 步，产出《综合排查报告》——这就是你面试的"我处理过"素材。
> **时长**：实操 2.5h / 输出 1h
> **今日产出**：《综合排查报告》（含时间线、证据链、修复清单）

## 1. 知识地图

```
今天没有新知识，只有"调度能力"：

      ┌── 病灶① CPU 热点（hot-burner 线程死循环）
ChaosApp
      ├── 病灶② 内存泄漏（静态 Map 只进不出，64KB/次）
      └── 病灶③ 大对象风暴（512KB 高频分配 → Humongous 压力）

生产节奏 = 按症状收敛，不按病灶顺序：
  症状 A：CPU 高     → day20 四步法 → hot-burner
  症状 B：老年代爬升  → day15/19 组合 → LEAK Map
  症状 C：GC 频繁    → day13 日志判型 → Humongous 触发原因

15 步清单（打勾推进，全程截图/日志留证）：
  第 1~3 步  定进程定症状（jps / jstat -gcutil / GC 日志）
  第 4~6 步  CPU 线（Arthas thread -n 3 / thread -b / 火焰图可选）
  第 7~9 步  内存线（ognl 读 LEAK.size / jmap -histo:live / dump+MAT）
  第 10~12 步 GC 线（Humongous 统计 / 触发原因 / 与内存线交叉验证）
  第 13 步   汇总三病灶清单 + 修复方案
  第 14 步   改代码复跑验证
  第 15 步   《综合排查报告》成文 + git 归档
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Incident Timeline | 事故时间线 | 报告第一要素：几点几分发生什么 |
| Evidence Chain | 证据链 | 每个结论都要有截图/日志支撑 |
| Root Cause | 根因 | 区分"现象"（CPU 高）和"根因"（死循环） |
| MTTR | 平均恢复时长 | 排查能力的业务语言 |

## 3. 动手实操

### 3.1 病人登场：ChaosApp.java

```java
import java.util.*;

public class ChaosApp {
    static Map<String, byte[]> LEAK = new HashMap<>();      // 病灶②：静态泄漏

    public static void main(String[] args) throws Exception {
        new Thread(() -> {                                   // 病灶①：CPU 热点
            long x = 1;
            while (true) x = x * 31 + System.nanoTime();
        }, "hot-burner").start();

        int i = 0;
        while (true) {                                       // 主线程：②+③ 双病灶
            LEAK.put("leak-" + i++, new byte[64 * 1024]);    // 泄漏 ≈ 320KB/s
            byte[] snapshot = new byte[512 * 1024];          // 大对象 ≈ 2.5MB/s
            Thread.sleep(200);
        }
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month01-jvm; mkdir day29 -Force; cd day29
javac ChaosApp.java
java -Xms256m -Xmx256m `
  -Xlog:gc*:file=gc-%t.log:time,uptime,level,tags:filecount=3,filesize=20m `
  -XX:+HeapDumpOnOutOfMemoryError ChaosApp
```

### 3.2 十五步实战（对照打勾）

```text
□ 1  jps -l 定 PID，开始记录时间线（模拟收到告警）
□ 2  jstat -gcutil <PID> 1000 30 → 观察：O 段持续爬升不回落（症状B 实锤）
□ 3  打开 gc 日志 → Select-String "Humongous Allocation" 有命中（症状C 线索）
□ 4  Arthas attach → thread -n 3 → hot-burner 99% CPU（症状A 定位）
□ 5  thread -b → 无死锁（排除干扰项，这步体现严谨）
□ 6  trace/watch 无关线程确认：burner 的栈顶就是 ChaosApp.main lambda
□ 7  ognl '@ChaosApp@LEAK.size()' → 隔 60 秒再敲一次，对比增速（病灶②锁定）
□ 8  jmap -histo:live <PID> | Select-Object -First 12 → byte[] 大头（类指纹）
□ 9  jmap -dump:live → MAT 三板斧 → 引用链指向 ChaosApp.LEAK（证据闭环）
□ 10 GC 线收尾：Select-String gc 日志 "Pause Young" 统计次数 + 触发原因分布
□ 11 交叉验证：泄漏导致老年代涨 + Humongous 独立存在（两个内存问题别混为一谈）
□ 12 三病灶汇总表（下表）
□ 13 修复：① 热点算法改造/限流 ② LEAK 换 Caffeine 有界缓存 ③ 拆分快照对象
□ 14 改后复跑同负载：O 段平稳 / CPU 恢复 / Humongous 归零 → 三绿
□ 15 《综合排查报告》成文 + git 归档
```

### 3.3 病灶汇总表（报告核心页）

| # | 症状 | 病灶 | 证据 | 根因 | 修复 |
|---|------|------|------|------|------|
| 1 | CPU 单核 100% | hot-burner 死循环 | thread -n 3 栈截图 | 算法无限循环 | 业务化改造+限流 |
| 2 | 老年代爬升 | LEAK 静态 Map | dump 引用链 | 只进不出 | Caffeine maximumSize |
| 3 | YGC 频繁 | 512KB 大对象 | gc 日志触发原因 | Humongous 边缘 | 拆对象/调 Region |

### 3.4 《综合排查报告》骨架（复用 day19/27 模板）

```text
一、时间线：HH:MM 告警 → HH:MM 定位热点 → HH:MM 锁定泄漏 → HH:MM 修复验证
二、现象：CPU / 内存 / GC 三维度量化
三、证据链：每条结论附截图编号（evidence/ 目录）
四、根因与修复：三病灶表
五、预防：告警规则补强（O 段基线抬升告警/Humongous 频次告警）
六、复盘：哪步最耗时？工具断点在哪？SOP 如何更新？（必填！）
```

## 4. 面试连接

**Q：讲一个你排查过的最复杂的问题。（月度面试压轴）**
> STAR 结构：S 场景（服务三症状齐发）→ T 任务（保住大促稳定性）→ A 行动（15 步时间线，三线并进：CPU 四步法/GC 判型/dump 三板斧）→ R 结果（MTTR 2 小时，三病灶清单+预防告警落地）。把今天的报告讲成这个故事，就是 20 分钟的高光时刻。

**Q：多个问题同时出现，怎么排优先级？**
> 先恢复后根因（哪个先打爆服务先处理——泄漏 > CPU > GC 压力，因为不可逆）；症状会互相干扰（CPU 高也可能是 GC 风暴），所以"先分型再动手"；每条线独立收敛、交叉验证。

**Q：这次演练你哪一步卡最久？**
> 诚实答（如：MAT 索引大 dump 慢→先 histo 缩小范围再 dump；或 ognl 类名带包名找不到）+ 你的改进动作。体现"排查方法论在迭代"。

## 5. 今日验收清单

- [ ] ChaosApp 三病灶全部复现并定位
- [ ] 15 步全部走完，证据存 evidence/ 目录
- [ ] 《综合排查报告》成文（时间线+证据链+复盘）
- [ ] 能脱稿把这次演练讲成 3 分钟面试故事
- [ ] `git add . && git commit -m "day29: chaos drill"`

---
[← Day 28](day28-虚拟线程与JDK新特性.md) | [本月目录](README.md) | [Day 30 · 月度复盘与 M1 验收 →](day30-月度复盘与M1验收.md)
