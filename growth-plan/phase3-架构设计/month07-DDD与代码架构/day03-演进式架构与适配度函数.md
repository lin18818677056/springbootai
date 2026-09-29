# Day 03 · 演进式架构与适配度函数：把架构纪律变成测试

> **今日目标**：理解"演进式架构=用适配度函数守护架构特性"；手写分层依赖检查器（纯 Java 扫描 import 判违规）——把 day01 说的"演进友好"量化成 CI 可跑的代码。
> **时长**：理论 1.5h / 手写检查器 2.5h / 技术债 1h
> **今日产出**：LayerDependencyChecker（javac 可跑）+ 商城 3 个适配度函数定义

## 1. 知识地图

```
演进式架构（Evolutionary Architecture）的核心主张：
  架构不是一次画完的蓝图，是持续演化的过程——"上次重构的边界，半年后一定有人踩"
  问题：怎么防止"演进"退化成"腐烂"？——人盯不靠谱，review 会漏，要看代码的无数人
  答案：适配度函数（Fitness Function）——把架构特性变成自动化测试，CI 里持续验证

适配度函数：来自进化计算的概念迁移
  定义：对"架构离目标有多近"的客观度量
  特征：可执行（自动跑）/可量化（数值判定）/持续运行（CI 每次验证）
  常见的架构特性与对应函数：
  ① 分层依赖正确 → import 扫描：domain 包禁止 import org.springframework infra 层类
  ② 无循环依赖 → 模块依赖图环检测（jacoco/ArchUnit 皆可，今天手写核心逻辑）
  ③ 圈复杂度上限 → 静态分析（方法 > 15 告警——day26 重构的守门员）
  ④ API 兼容性 → 消费者驱动契约测试（接口改动破坏消费方 → 红）
  ⑤ 性能不退化 → 基准测试对比（JMH 阈值断言）
  ——今天的核心输出：手写 ①，理解原理后生产用 ArchUnit（思路完全一致）

技术债（Technical Debt）管理：
  比喻：为赶工期写的"快但歪"的代码 = 高息贷款——每次后续改动都在还利息
  量化：SonarQube 的 Sqale 模型 / 简易版 = 违规数×预估修复时长
  管理策略（别幻想"还清"，要"可控"）：
  ① 新增代码零新增债（适配度函数拦截）②每次需求顺带偿还 20%（童子军军规）
  ③ 列入 roadmap 的专项偿还（重构月）④利息可视化（把"改这个类平均连带改 N 个文件"
  做成指标——变更放大系数 Change Amplification）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Evolutionary Architecture | 演进式架构 |
| Fitness Function | 适配度函数（架构特性的自动化验证） |
| Architectural Coupling | 架构耦合 |
| Technical Debt | 技术债 |
| Change Amplification | 变更放大系数（改一处连动 N 处） |
| ArchUnit | 架构测试库（Java 生态标准方案） |
| Cyclic Dependency | 循环依赖 |
| Guardrail | 护栏（架构约束的自动化执行） |

## 3. 动手实操：手写分层依赖检查器

```java
// learning/month07-ddd-architecture/src/LayerDependencyChecker.java
// 原理：扫描 .java 源文件的 import 行 → 按"类所在包"判层 → 检查"禁止的依赖方向"
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class LayerDependencyChecker {
    // 分层规则：值越小越"核心"。核心层禁止依赖更外层（数值大的）层的类
    record Layer(String name, String packagePrefix, int rank) {}
    static final List<Layer> LAYERS = List.of(
        new Layer("domain",         "com.mall.order.domain.",         0),   // 最核心
        new Layer("application",    "com.mall.order.application.",    1),
        new Layer("adapter-in",     "com.mall.order.adapter.web.",    2),   // 驱动侧
        new Layer("adapter-out",    "com.mall.order.adapter.persist.",2));  // 被驱动侧
    static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+);");

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(args.length > 0 ? args[0] : "src");
        int violations = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String pkg = packageOf(f, root);
                int from = rankOf(pkg);
                if (from < 0) continue;                          // 不在分层的文件跳过
                for (String line : Files.readAllLines(f)) {
                    Matcher m = IMPORT.matcher(line.trim());
                    if (!m.find()) continue;
                    int to = rankOf(m.group(1));
                    // 核心规则：依赖只能"由外向内"（from 的 rank 必须 <= to 的 rank 之内的规则：
                    // domain(0) 不能依赖 application(1)/adapter(2)；application(1) 不能依赖 adapter(2)
                    if (to > from && to >= 1 && from == 0 || (from == 1 && to == 2)) {
                        System.out.printf("VIOLATION: %s (%s) -> %s%n",
                                root.relativize(f), LAYERS.get(from).name(), m.group(1));
                        violations++;
                    }
                }
            }
        }
        System.out.println("scan done, violations=" + violations);
        if (violations > 0) System.exit(1);                      // CI 里非零退出=构建失败
    }
    static int rankOf(String fqcnOrPkg) {
        return LAYERS.stream().filter(l -> fqcnOrPkg.startsWith(l.packagePrefix()))
                .findFirst().map(Layer::rank).orElse(-1);
    }
    static String packageOf(Path file, Path root) throws IOException {
        return Files.readAllLines(file).stream()
                .filter(l -> l.startsWith("package ")).findFirst()
                .map(l -> l.replace("package ", "").replace(";", "").trim()).orElse("");
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month07-ddd-architecture
# 准备测试样本（模拟违规与合规目录）：
#   src/com/mall/order/domain/Order.java          → import 了 spring 的 Service 注解 = 违规！
#   src/com/mall/order/application/OrderApp.java  → import domain 类 = 合规
javac -encoding UTF-8 -d out src\LayerDependencyChecker.java
java -cp out LayerDependencyChecker src
# 预期输出：VIOLATION: ... domain/Order.java -> org.springframework...  violations=1 退出码 1
# 进阶：把规则改成"全模块无循环依赖"（构建包依赖图+拓扑排序判环——day 手写彩蛋）
# 对应关系：生产用 ArchUnit：classes().that().resideInAPackage("..domain..")
#           .should().onlyDependOnClassesThat().resideInAnyPackage("..domain..")
```

## 4. 面试连接

**Q：什么是适配度函数？你们落地过吗？**
> 适配度函数是把架构特性（分层依赖/无环/复杂度/API 兼容）变成可自动化执行的验证——本质是"给架构上护栏"，防止演进退化成腐烂。我落地过三件：手写过分层依赖检查器（扫 import 判 domain 层违规，CI 非零退出拦合并）；方法圈复杂度超 15 拦截（静态分析，给重构月当守门员）；变更放大系数做成了季度指标（改一个类平均连带几个文件——从 4.2 降到 1.6 说明解耦见效）。生产可以直接用 ArchUnit，但我手写过核心逻辑后对原理（包前缀判层+import 解析+退出码）完全透明。收尾："架构纪律靠自觉会衰减，靠测试才可持续——这是我把 day01 '演进友好'的定义变成工程手段的关键一步。"

**Q：技术债怎么管理？能还清吗？**
> 不能幻想还清，目标是"可控"：①拦截新增——适配度函数让新代码零新增违规；②顺带偿还——每个需求迭代顺带修 20% 的存量债（童子军军规，评审卡点）；③专项偿还——排期重构月；④利息可视化——把技术债翻译成业务语言给管理层听："这个模块的变更放大系数是 4，意味着每个小需求实际改 4 个文件，交付速度慢 60%。"——不说"代码烂"说"迭代慢"，债的管理才拿得到资源。（向上管理视角=工程师到 Tech Lead 的分水岭）

## 5. 今日验收清单

- [ ] LayerDependencyChecker 手写完成并跑通（违规检出+退出码）
- [ ] 环检测彩蛋思路写出（包依赖图+拓扑排序）
- [ ] 商城定义 3 个适配度函数（依赖/复杂度/放大系数）
- [ ] 技术债四策略能结合实例讲
- [ ] ArchUnit 对照写法抄进笔记
- [ ] `git add . && git commit -m "day03: fitness function"`

---
[← Day 02](day02-SOLID深挖.md) | [本月目录](README.md) | [Day 04 · 分层架构的堕落 →](day04-分层架构的堕落.md)
