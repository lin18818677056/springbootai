# Day 07 · 第一周复盘：架构思想落地为检查器

> **今日目标**：把 day01-06 的架构演进主线串成一张网；12 道自测题检验掌握度；盘点本周代码资产；为下周战略设计埋好衔接。
> **时长**：自测 2h / 串讲笔记 2h / 手绘 1h
> **今日产出**：第一周知识网络图 + 自测错题清单 + `git tag week07-1`

## 1. 知识地图

```
第一周三条线（一条主线：让业务逻辑有家，且不依赖技术细节）：
  思想线：day01 什么是好架构（合适+演进友好/康威定律/ADR）
       → day03 演进式架构（架构要能"改"，适配度函数是它的体温计）
  原则线：day02 SOLID（SRP/LSP/ISP/DIP——DIP 是后面一切的根基）
       → day04 分层堕落（三层不管不住业务逻辑 → 领域四层给规则唯一的家）
  落地线：day05 六边形（端口与适配器，依赖向心）
       → day06 整洁/COLA（同心圆，依赖向内——同一思想不同画法）
  三线汇合点：day05 的 mall-order 改造 + day03 的检查器 = "思想"变成"可验证资产"

自测暴露的三个薄弱点（我的答案要点，比对后补强）：
  ① LSP 讲不清"里氏替换"的行为约束 → 记：子类不能收紧前置条件/放松后置条件
  ② 检查器只会扫单模块 → 补：Gradle 多模块下扫每个 srcDir（M6 day02 的模块化是物理边界）
  ③ COLA 扩展点三级定位（bizId→useCase→scenario）说不全 → 明天 day09 通用语言时顺带复习
```

## 2. 核心概念（本周名词快检）

| 概念 | 一句话自检标准 |
|------|---------------|
| ADR | 五段模板能默写（背景/决策/备选/后果/状态） |
| Fitness Function | 能说出 3 个例子（分层违规数/圈复杂度/P99） |
| DIP | 能讲清 RepositoryImpl implements 领域接口为什么是"倒置" |
| Ports & Adapters | 驱动侧/被驱侧两类端口能各举 2 例 |
| Dependency Rule | 同心圆唯一规则一句话说清 |
| COLA Executor | 能讲"一个用例一个类"与 AppService 多方法的取舍 |

## 3. 动手实操：自测 12 题（限时 40 分钟，只写要点）

```text
1. 架构的定义（day01）？"重要决策的结构化表达"——追问：哪些算重要决策？
2. 康威定律与逆康威：组织结构如何塑造系统？想改系统先改什么？
3. ADR 五段模板，写一条你司"为什么用 Nacos 而不是 ZooKeeper"的 ADR。
4. SRP 的"变化原因"判别法：OrderService 有几个变化原因？（day02 重构案例）
5. OCP 开闭原则：新增支付渠道要改代码吗？怎么做到不改？
6. LSP 反例：正方形继承长方形为什么违例？（不变量被破坏）
7. DIP 与六边形的关系：被驱端口定义在领域层 = 依赖倒置的落地，展开讲。
8. 三层架构的四个堕落证据，你司能各找到一例吗？
9. "产品提的还是架构师提的"口诀：满减规则/重试策略/状态流转各归哪层？
10. 六边形两类端口×两类适配器：HTTP/MQ/DB/支付 SDK 各是哪侧的什么？
11. 整洁架构唯一规则一句话；为什么说"框架是细节"？
12. 三架构取舍表默写：依赖方向/规则住处/框架隔离/适用规模四行。
```

```powershell
# 本周代码资产盘点（learning/month07-ddd-architecture）：
cd D:\mywork\springbootai\learning\month07-ddd-architecture
javac -encoding UTF-8 -d out src\LayerDependencyChecker.java
java -cp out LayerDependencyChecker D:\mywork\springbootai\practice-projects\02-microservice-mall\mall\mall-order\src\main\java
# 验收：violations=0（day05 改造后的常态）
git add . ; git commit -m "week07-1: architecture review" ; git tag week07-1
```

## 4. 第一周知识网络（手绘参考）

```
康威定律 ──→ 团队/服务边界 ──────────────→ 下周：限界上下文（day10）
   │                                              ↑
ADR ──→ 演进式架构 ──→ 适配度函数 ──→ 依赖检查器 ─┤
   │                                ↑            │
SOLID(DIP) ──→ 六边形 ──→ 端口/适配器 ┘       商城=多上下文？(day10 的核心问题)
   │              ↑
分层堕落 ────────┘          整洁/COLA ──→ 用例编排 → day17 领域/应用服务之分
【下周主线】业务逻辑的"家"有了（本周），下周解决"业务语言与边界"——战略设计。
```

## 5. 今日验收清单

- [ ] 12 题自测完成，错题要点补写（≥3 条补强笔记）
- [ ] 三架构取舍表默写通过
- [ ] 检查器复跑 violations=0 截图
- [ ] 知识网络手绘图完成
- [ ] `git add . && git commit -m "day07: week1 review" && git tag week07-1`

---
[← Day 06](day06-整洁架构与COLA.md) | [本月目录](README.md) | [Day 08 · 领域与子域分级 →](day08-领域与子域分级.md)
