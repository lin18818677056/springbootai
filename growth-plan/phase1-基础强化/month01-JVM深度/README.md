# Month01 · JVM 深度（30 天每日计划）

> **本月一句话目标**：从"知道 JVM 是什么"到"能独立完成一次线上 JVM 问题排查与 GC 调优"。
> **对高职级要求**：P6 要求会用工具，P7 要求懂原理能调优，P8 要求能定 JVM 故障应急预案——本月按 P7 标准训练。
> **结业标志（里程碑 M1）**：完成一次"CPU 飙高 + 内存泄漏 + 频繁 GC"复合案例的全流程排查（day29 综合演练）。

## 每日文件使用方法（每个 day 文件固定五段）

| 段落 | 内容 |
|------|------|
| 知识地图 | ASCII 图解，先看图建立整体认知 |
| 核心概念 | 中英对照 + 面试级讲解 |
| 动手实操 | **完整可复现代码 + 命令**（PowerShell 兼容，含 Docker 方案） |
| 面试连接 | 这个知识点面试怎么问、怎么答 |
| 验收清单 | 打勾完成才算今天结束 |

**每日节奏建议**（约 2.5~3.5 小时）：理论 1h → 实操 1.5h → 输出笔记 0.5h。**所有实操代码放入你的学习仓库**（day01 建立 git 仓库，每天 commit，30 天后这就是你的第一份"作品集"）。

## 环境准备总表（第一天前装好）

| 组件 | 用途 | 安装/验证命令（PowerShell） |
|------|------|---------------------------|
| JDK 25（已装） | 全月实验主环境 | `java -version`（应显示 openjdk-25） |
| IntelliJ IDEA（已装） | 写代码与调试 | 已就绪 |
| Git（已装） | 学习仓库版本管理 | `git --version` |
| Arthas（阿里开源诊断工具） | day17-20 排查神器 | `Invoke-WebRequest https://arthas.aliyun.com/arthas-boot.jar -OutFile arthas-boot.jar` |
| JOL（Java Object Layout） | day04 看对象内存布局 | 见 day04（下载 jar 或用 gradle 依赖） |
| MAT（Eclipse Memory Analyzer） | day19 内存分析 | 官网 https://eclipse.dev/mat/downloads.php 下载 Windows 版 |
| JMH（基准测试） | day23 | 通过 gradle 插件引入，见 day23 |
| **Docker Desktop（已装）** | ① 跑 JDK17 容器做 JOL 兼容实验 ② Linux 容器内做火焰图（async-profiler 不支持 Windows）③ 体验 JDK8 的 CMS | `docker --version` |

> Docker Desktop 在本月的三个用途都写在对应 day 里（day04 / day10 / day18），不用提前装别的东西。

## 30 天课程地图

| 周 | 天 | 主题 | 关键产出 |
|----|----|------|---------|
| 一：体系与内存 | [day01](day01-JVM全景与环境搭建.md) | JVM 全景与环境搭建 | 学习仓库 + 字节码初体验 |
| | [day02](day02-类加载与双亲委派.md) | 类加载机制与双亲委派 | 自定义类加载器 |
| | [day03](day03-运行时数据区.md) | 运行时数据区与栈帧 | SOF 复现 + 内存大图 |
| | [day04](day04-对象内存布局JOL.md) | 对象内存布局（JOL） | 对象头/指针压缩实验 |
| | [day05](day05-对象创建与内存分配.md) | 对象创建与分配（TLAB） | jstat 观察分配 |
| | [day06](day06-字符串常量池与直接内存.md) | 字符串常量池与直接内存 | intern 实验 + 堆外 OOM |
| | [day07](day07-第一周复盘输出.md) | 第一周复盘 | 三张默写图 + 10 道自测 |
| 二：垃圾回收 | [day08](day08-GC基础与四种引用.md) | 存活判定与四种引用 | 引用实验代码 |
| | [day09](day09-GC算法与分代模型.md) | GC 算法与分代模型 | 晋升规则实验 |
| | [day10](day10-收集器演进与CMS.md) | 收集器演进与 CMS | JDK8 容器体验 CMS |
| | [day11](day11-G1垃圾收集器.md) | G1 全解 | G1 日志首次解读 |
| | [day12](day12-ZGC与低延迟收集器.md) | ZGC 与低延迟收集器 | G1 vs ZGC 对比 |
| | [day13](day13-GC日志深度解读.md) | GC 日志深度解读 | GCEasy 分析报告 |
| | [day14](day14-第二周复盘输出.md) | 第二周复盘 | 博客《一次 GC 的旅程》 |
| 三：工具实战 | [day15](day15-命令行工具全家桶.md) | jps/jstat/jmap/jstack 全家桶 | 命令速查表 |
| | [day16](day16-JFR飞行记录仪.md) | JFR 飞行记录仪 | 60 秒录制找热点 |
| | [day17](day17-Arthas入门.md) | Arthas 入门 | dashboard/jad/ognl 实操 |
| | [day18](day18-Arthas进阶与火焰图.md) | Arthas 进阶与火焰图 | Docker 容器内火焰图 |
| | [day19](day19-MAT内存分析与泄漏定位.md) | MAT 内存分析 | 泄漏案例定位报告 |
| | [day20](day20-CPU飙高排查实战.md) | CPU 飙高排查实战 | Linux/Windows 双流程 |
| | [day21](day21-第三周复盘输出.md) | 第三周复盘 | 《线上排查手册 v1》 |
| 四：调优跃迁 | [day22](day22-JIT即时编译.md) | JIT 与分层编译 | -Xint/-Xcomp 对比 |
| | [day23](day23-JMH基准测试.md) | JMH 基准测试 | String 拼接基准报告 |
| | [day24](day24-JVM参数与生产模板.md) | JVM 参数与生产模板 | G1/ZGC 两套参数模板 |
| | [day25](day25-内存泄漏六大场景.md) | 内存泄漏六大场景 | 六个最小复现 |
| | [day26](day26-OOM类型全复现.md) | OOM 类型全复现 | OOM 处置 SOP |
| | [day27](day27-G1调优实战.md) | G1 调优实战 | 调优对比报告 |
| | [day28](day28-虚拟线程与JDK新特性.md) | 虚拟线程与新特性 | 10k 任务性能对比 |
| | [day29](day29-综合实战排查演练.md) | 综合实战排查演练 | 全流程排查报告 |
| | [day30](day30-月度复盘与M1验收.md) | 月度复盘与 M1 验收 | M1 达标 + month02 预习 |

## 本月里程碑 M1 验收标准（day30 逐条打勾）

- [ ] 能白板默画：运行时数据区、类加载流程、分代流转、G1 Region、三色标记（5 张图）
- [ ] 能读懂 G1/ZGC 的 GC 日志并说出 3 个健康指标
- [ ] 能用 Arthas 独立定位：CPU 飙高、慢接口、对象膨胀
- [ ] 能用 MAT 从 heap dump 定位一次内存泄漏的根因
- [ ] 完成一次有前后数据对比的 GC 调优实验（day27 报告）
- [ ] 产出《线上 JVM 问题排查手册 v1》（day21）

> 打开 [day01-JVM全景与环境搭建.md](day01-JVM全景与环境搭建.md) 开始第一天。
