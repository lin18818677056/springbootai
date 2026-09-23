# Day 01 · JVM 全景与环境搭建

> **今日目标**：建立 JVM 全景认知（它是谁、在哪、怎么工作），搭好本月学习环境，并亲手看到"字节码"。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：git 学习仓库 + 第一份字节码阅读笔记

## 1. 知识地图

```
我们写代码的位置                字节码（平台无关）          执行（平台相关）
┌──────────────┐   javac   ┌──────────┐   类加载   ┌────────────────────┐
│  Hello.java  │ ────────→ │Hello.class│ ────────→ │      JVM 进程       │
└──────────────┘           └──────────┘            │ ┌────────────────┐ │
                                                   │ │  类加载子系统    │ │
 "一次编写，到处运行"的真义：                          │ ├────────────────┤ │
 平台差异被 JVM 吃掉了——                              │ │ 运行时数据区     │ │
 Windows/Linux/Mac 各有 JVM 实现，                     │ │ (堆/栈/方法区..) │ │
 但 .class 字节码完全一样                              │ ├────────────────┤ │
                                                   │ │ 执行引擎         │ │
 JDK = JVM + 核心类库 + 开发工具(javac/javadoc)       │ │ 解释器 + JIT    │ │
 JRE = JVM + 核心类库（JDK9 后被模块化取代概念）        │ └────────────────┘ │
                                                   └────────────────────┘
```

**三个必背事实**：
1. **JVM 是规范（Specification）**：Java Virtual Machine Specification 定义"字节码该怎样执行"；HotSpot（你机器上的）、OpenJ9、GraalVM 是不同实现。
2. **字节码是中间语言**：`.class` 文件里是 JVM 指令集（如 `iload`、`invokevirtual`），任何语言（Java/Kotlin/Groovy）编译成它都能跑。
3. **JVM 本身是个 C++ 写的进程**：`java` 命令就是启动一个进程，你的代码跑在它的线程里。

## 2. 核心概念（中英对照）

| 英文 | 中文 | 一句话解释 |
|------|------|-----------|
| Bytecode | 字节码 | .class 文件中的 JVM 指令集 |
| HotSpot | 热点代码/主流 JVM 实现 | 也指 JIT 只优化"热点代码"的设计 |
| Interpretation | 解释执行 | 逐条翻译字节码执行（慢但立即启动） |
| JIT (Just-In-Time) Compilation | 即时编译 | 把热点字节码编译成机器码（快） |
| Class File Format | 类文件格式 | .class 的二进制规范（魔数 0xCAFEBABE 开头） |

## 3. 动手实操

### 3.1 建立本月学习仓库（今天的头等大事）

```powershell
# 1. 创建本月工作目录
mkdir D:\mywork\springbootai\learning\month01-jvm
cd D:\mywork\springbootai\learning\month01-jvm

# 2. 初始化 git 仓库（本月所有代码/笔记都提交到这里）
git init
New-Item -ItemType File -Path notes\day01.md -Force | Out-Null
"# JVM 深度学习笔记" | Out-File notes\day01.md -Encoding utf8
git add .
git commit -m "day01: init jvm learning repo"
```

### 3.2 验证环境：确认你的 JVM

```powershell
# 版本（你的是 OpenJDK 25）
java -version
javac -version

# 看一看 JVM 的"出厂设置"：900+ 个 JVM 参数的当前值
# Select-String 相当于 Linux 的 grep（PowerShell 用法）
java -XX:+PrintFlagsFinal -version | Select-String "UseG1GC"
# 预期输出： bool UseG1GC = true  —— 说明 JDK25 默认收集器是 G1
```

### 3.3 第一次阅读字节码（今天的"哇哦时刻"）

新建 `HelloJVM.java`：

```java
public class HelloJVM {
    static int counter = 0;

    public int add(int a, int b) {
        return a + b;
    }

    public static void main(String[] args) {
        HelloJVM app = new HelloJVM();
        for (int i = 0; i < 3; i++) {
            counter = app.add(counter, i);
        }
        System.out.println(counter);
    }
}
```

编译并查看字节码：

```powershell
javac HelloJVM.java
javap -c HelloJVM          # 反汇编：看字节码指令
javap -v HelloJVM          # 更详细：常量池/版本号/编译器信息
javap -l -c HelloJVM       # -l 带局部变量表（day03 会用到）
```

**对照观察**（在笔记里回答这三个问题）：
1. `main` 方法里的 `for` 循环，字节码用了哪条指令跳转？（找 `if_icmpge`）
2. `app.add(...)` 调用是哪条指令？（`invokevirtual`——虚方法调用，day22 会再遇到它）
3. `static int counter` 的 `counter = 0` 赋值出现在字节码里吗？（不出现！静态变量初始化在 `<clinit>` 方法里——这就是 day02 要讲的"类初始化阶段"）

### 3.4 看一眼 JVM 进程

```powershell
# 先保持一个 Java 程序运行：另开一个终端跑 java HelloJVM 改成死循环版
# 或者直接看当前有哪些 java 进程
jps -l
# 输出示例： 12345 HelloJVM   （12345 就是 JVM 进程的 PID）
```

## 4. 面试连接

**Q：JVM、JRE、JDK 的关系？**
> JDK 包含 JRE 的内容 + 开发工具（javac/jar/javadoc）；JRE 在 JDK9 后被模块化系统取代为一个运行时镜像；核心是 JVM——规范 + 实现（HotSpot）+ 运行时数据区 + 执行引擎。答完补一句"我日常用 jlink 裁剪过最小运行时"是加分项（month03 回来补）。

**Q：Java 是编译型还是解释型语言？**
> 混合模型：javac 编译成字节码（不是机器码）→ JVM 解释执行 + JIT 即时编译热点代码为机器码。这才是准确答案，只答"解释型"会暴露基础问题。

## 5. 今日验收清单

- [ ] git 仓库建好并完成首次 commit
- [ ] `java -XX:+PrintFlagsFinal -version` 能跑通，确认默认收集器是 G1
- [ ] `javap -c/-v/-l` 三个输出都看过，笔记里回答了 3 个观察问题
- [ ] `jps` 能看到 Java 进程 PID
- [ ] 笔记完成：用自己的话写"一次编写到处运行到底指什么"（150 字）

---
[← 本月目录](README.md) | [Day 02 · 类加载与双亲委派 →](day02-类加载与双亲委派.md)
