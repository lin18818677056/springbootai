# Day 19 · MAT 内存分析与泄漏定位（Memory Analyzer Tool）

> **今日目标**：MAT 是 heap dump 分析的事实标准。今天复现一个真实泄漏 → 抓 dump → 用"三板斧"（嫌疑报告→支配树→引用链）把它揪到代码行，产出第三份分析报告。
> **时长**：理论 0.5h / 实操 2h / 输出 0.5h
> **今日产出**：《泄漏定位报告》（day27 调优报告的姊妹篇）

## 1. 知识地图

```
dump 分析三板斧（顺序固定，别乱）：

① Leak Suspects 嫌疑报告   → MAT 自动猜"谁最可疑"（先看一眼，别全信）
        ↓
② Dominator Tree 支配树    → 按 Retained Heap 降序，找"最占内存的大户"
        ↓
③ Path to GC Roots 引用链  → 大户被谁拽着不放？→ 泄漏点定位到代码行

两个关键概念（面试必考）：
Shallow Heap   浅堆   = 对象自己占的字节（对象头+字段，很小）
Retained Heap  保留堆 = 这个对象被回收后，能连带释放多少（它的"势力范围"）

  ArrayList(浅堆 24B)
  ├── byte[1MB]     ← 被 ArrayList "支配"：它死我也死
  ├── byte[1MB]        ArrayList 的 Retained = 24B + 100 × 1MB ≈ 100MB
  └── ...×100
支配树把堆切成"一户一个"，保证 Retained 不重复计数

泄漏 vs 合理缓存（day14 压轴题的实操版）：
  泄漏      无上界引用链，Retained 持续涨 → 最终 OOM
  合理缓存  有淘汰结构（LRU/过期），dump 里能看出"边界"
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| MAT (Memory Analyzer Tool) | 内存分析工具 | Eclipse 出品，dump 分析事实标准 |
| Dominator Tree | 支配树 | 按 Retained Heap 排序的"内存大户榜" |
| Leak Suspects | 泄漏嫌疑报告 | MAT 自动生成的怀疑点报告 |
| Path to GC Roots | 到 GC 根的引用链 | 谁引用着它（排除弱/软引用） |
| Shallow / Retained Heap | 浅堆/保留堆 | 自身占用 / 连带可释放 |
| OQL | 对象查询语言 | 类 SQL 语法查询堆内对象 |

## 3. 动手实操

### 3.1 安装 MAT（10 分钟）

```powershell
# 浏览器打开 https://eclipse.dev/mat/download.php → Windows x86_64
# 解压到 D:\tools\mat，双击 MemoryAnalyzer.exe
# 大 dump 建议调大 MAT 自身内存：编辑 MemoryAnalyzer.ini，-Xmx1g → -Xmx4g
```

### 3.2 复现泄漏：LeakDemo.java（经典"会话缓存永不清理"）

```java
import java.util.ArrayList;
import java.util.List;

public class LeakDemo {
    static List<byte[]> SESSION_CACHE = new ArrayList<>();   // 元凶：静态无界集合
    static int sessionSeq = 0;

    public static void main(String[] args) throws Exception {
        while (true) {                        // 模拟"每个新会话"塞 1MB
            SESSION_CACHE.add(new byte[1024 * 1024]);
            sessionSeq++;
            Thread.sleep(200);                // -Xmx256m 时约 50s 后 OOM
        }
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month01-jvm\day19
javac LeakDemo.java

# 方式 A（推荐）：泄漏中期手动抓，问题现场更"鲜活"
java -Xms256m -Xmx256m LeakDemo     # 跑约 1 分钟（内存涨到一半以上）
jps -l                               # 找 PID
jmap -dump:live,format=b,file=leak.hprof <PID>
# :live = 先 FullGC 只留活对象（回收不掉的才是泄漏）

# 方式 B：等 OOM 自动落 dump（也是生产标准配置）
java -Xms256m -Xmx256m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=oom.hprof LeakDemo
```

### 3.3 MAT 三板斧 + OQL（每步截图）

```text
① Leak Suspects
   打开 leak.hprof → 欢迎页点 "Leak Suspects Report"
   → 报告点名：一个 java.util.ArrayList 占了 9x% 的堆
   → 点 "See stacktrace" 能看到创建它的线程栈

② Dominator Tree
   工具栏 Open Dominator Tree → 按 Retained Heap 降序
   → 第一名就是那个 ArrayList（Retained ≈ 200MB）
   → 展开子节点：上千个 byte[1048576]
   → 每行看两列：Shallow=16B  Retained=1048592B
     （体会浅堆/保留堆的巨大反差——这就是"数组壳子小、内容占大头"）

③ Path to GC Roots
   右键 ArrayList → Path to GC Roots → exclude weak/soft references
   （排除弱/软引用：它们随时可回收，不构成泄漏）
   → 链路显示：ArrayList ← LeakDemo 类（静态字段 SESSION_CACHE）
   → 泄漏点定位完毕：静态无界集合 + 无淘汰机制

④ OQL 查询（加分技能）
   SELECT * FROM byte[] WHERE length > 1048576        ← 找大数组
   SELECT s FROM java.lang.Thread s WHERE daemon = false  ← 非守护线程
```

### 3.4 撰写《泄漏定位报告》（day30 综合演练也用这个骨架）

```text
一、现象：-Xmx256m 运行约 1 分钟 OOM；GC 日志回收后基线持续抬升（day13 判型法）
二、采集：jmap -dump:live → leak.hprof（约 250MB）；无 FullGC 后回落现象
三、分析：① Leak Suspects：ArrayList 占 9x%
        ② Dominator Tree：该列表 Retained 200MB+，子元素 byte[1MB]×200+
        ③ Path to GC Roots：LeakDemo.SESSION_CACHE 静态字段强引用
四、结论：静态无界集合持有大对象且永不清理 → 泄漏实锤
五、修复：改有界缓存（Guava Cache maximumSize/expireAfterWrite 或 Caffeine）；
        或会话结束时显式 remove。修复后同参数复跑，OOM 消失即闭环
```

## 4. 面试连接

**Q：线上内存泄漏的完整排查流程？**
> 四步：① jstat -gcutil 确认"回收后基线抬升"（区分泄漏还是波动）→ ② jmap -histo:live 看哪类对象在涨 → ③ dump + MAT：支配树找大户、引用链找持有点 → ④ 修复后复跑验证。加分项："live dump 触发 FullGC，大堆生产建议 OOM 自动 dump 或低峰抓。"

**Q：Shallow Heap 和 Retained Heap 的区别？**
> 浅堆是对象自身的字节数；保留堆是它被回收后可释放的总量（支配关系保证不重复计数）。找泄漏按 Retained 排序看大户，看对象头/引用开销看 Shallow。

**Q：为什么 Path to GC Roots 要排除弱/软引用？**
> 弱引用下次 GC 必回收、软引用内存吃紧才回收——被它们引用的对象随时会消失，不构成泄漏；只有强引用链（静态字段/线程栈/监听器注册）才能把对象"拽住不放"。

## 5. 今日验收清单

- [ ] MAT 安装完成，打开过自己抓的 dump
- [ ] LeakDemo 两种抓 dump 方式都试过
- [ ] 三板斧全流程走通，每步有截图
- [ ] 能解释支配树里 Shallow=16B / Retained=1MB 的反差
- [ ] 《泄漏定位报告》提交 git：`git commit -m "day19: mat leak analysis"`

---
[← Day 18](day18-Arthas进阶与火焰图.md) | [本月目录](README.md) | [Day 20 · CPU 飙高排查实战 →](day20-CPU飙高排查实战.md)
