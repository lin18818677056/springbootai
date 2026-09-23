# Day 04 · 对象内存布局（JOL 实验）

> **今日目标**：亲手"解剖"一个 Java 对象——对象头、实例数据、对齐填充各占多少字节；回答经典面试题 `new Object()` 到底占多少内存。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：JOL 实验输出 + 指针压缩开关对比记录

## 1. 知识地图

```
一个 Java 对象在堆里的样子（64 位 JVM）：
┌────────────────────────────────────────────────────┐
│ 对象头 Object Header                                │
│ ┌────────────────────────┬───────────────────────┐ │
│ │ Mark Word (8字节)       │ 类型指针 Klass Pointer │ │
│ │ hashcode/GC年龄/锁标志   │ (4字节,压缩后)          │ │
│ └────────────────────────┴───────────────────────┘ │  ← 12 字节（指针压缩开启）
├────────────────────────────────────────────────────┤
│ 实例数据 Instance Data                               │
│  int a(4) + long b(8) + 引用 ref(4,压缩后) ...       │  ← 按字段类型
├────────────────────────────────────────────────────┤
│ 对齐填充 Padding                                     │  ← 补到 8 字节整数倍
└────────────────────────────────────────────────────┘

经典计算：
new Object()      = 12(头) + 0(无字段) + 4(填充) = 16 字节
int[] arr = new int[10] = 12(头) + 4(数组长度) + 40(10×int) + 4(填充) = 60？→ 不对！
数组还有长度字段：12 + 4(长度) + 4(填充?) ... 用 JOL 自己验证！
```

**Mark Word 64 位布局**（锁升级的知识铺垫，month02 并发再深化）：

| 锁状态 | 62 位内容 |
|--------|----------|
| 无锁 | hashcode(31) | 分代年龄(4) | 偏向位=0 | 锁标志=01 |
| 轻量级锁 | 指向栈中 Lock Record 的指针(62) | 锁标志=00 |
| 重量级锁 | 指向 ObjectMonitor 的指针(62) | 锁标志=10 |
| GC 标记 | 空 | 锁标志=11 |

> 注意：偏向锁在 JDK15 起已废弃移除，新版本默认无锁起点——面试讲新版 JVM 别再背"默认偏向锁"。

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Object Header | 对象头 | Mark Word + 类型指针（数组另有长度字段） |
| Mark Word | 标记字 | 复用同一块内存：哈希码/锁/GC 年龄轮流使用 |
| Klass Pointer | 类型指针 | 指向方法区的类元数据（元空间里） |
| Pointer Compression | 指针压缩 | 8 字节指针压成 4 字节（-XX:+UseCompressedOops，堆 <32GB 默认开） |
| Padding | 对齐填充 | 凑 8 字节整数倍，CPU 缓存行友好 |
| JOL (Java Object Layout) | 对象布局分析工具 | OpenJDK 官方工具，直接打印内存布局 |

## 3. 动手实操

### 3.1 准备 JOL（两种方式，推荐方式 B）

```powershell
mkdir D:\mywork\springbootai\learning\month01-jvm\day04 -Force
cd D:\mywork\springbootai\learning\month01-jvm\day04

# 方式 A：直接下载 jol-core jar（PowerShell 下载命令）
Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/org/openjdk/jol/jol-core/0.17/jol-core-0.17.jar" -OutFile "jol-core-0.17.jar"

# 方式 B（JOL 与你本机 JDK25 可能有兼容问题）：用 Docker 起 JDK17 容器跑
# 这就是你 Docker Desktop 的第一个用途！
docker run -it --rm -v "D:\mywork\springbootai\learning\month01-jvm\day04:/work" -w /work eclipse-temurin:17-jdk bash
```

### 3.2 解剖代码 ObjectLayout.java

```java
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.vm.VM;

public class ObjectLayout {
    static class Person {
        int age;             // 4 字节
        boolean married;     // 1 字节
        String name;         // 引用：4 字节（压缩）/ 8 字节（不压缩）
    }

    public static void main(String[] args) {
        System.out.println(VM.current().details());   // 当前 VM 内存模型摘要
        System.out.println(ClassLayout.parseInstance(new Object()).toPrintable());
        System.out.println(ClassLayout.parseInstance(new Person()).toPrintable());
        System.out.println(ClassLayout.parseInstance(new int[10]).toPrintable());
    }
}
```

```powershell
# 方式 A（本机 JDK25）运行：
javac -cp jol-core-0.17.jar ObjectLayout.java
java -Djdk.attach.allowAttachSelf=true -cp ".;jol-core-0.17.jar" ObjectLayout
# 若报 attach/InaccessibleObject 错误，追加：
java -Djdk.attach.allowAttachSelf=true --add-opens java.base/java.lang=ALL-UNNAMED -cp ".;jol-core-0.17.jar" ObjectLayout

# 方式 B（Docker JDK17 容器内）：
javac -cp jol-core-0.17.jar ObjectLayout.java
java -Djdk.attach.allowAttachSelf=true -cp ".:jol-core-0.17.jar" ObjectLayout   # 注意 Linux 分隔符是冒号
```

### 3.3 实验一：指针压缩开关对比

```powershell
java -XX:-UseCompressedOops -Djdk.attach.allowAttachSelf=true -cp ".;jol-core-0.17.jar" ObjectLayout
# 对比记录：new Object() 从 16 字节变成 24 字节（类型指针 4→8）
# 这就是"堆别超过 32GB"的由来：超过后压缩失效，所有引用膨胀 4 字节
```

### 3.4 实验二：字段重排（JVM 比你想象的更聪明）

在 Person 里故意加一个 `long big;` 字段，再次运行——JVM 会把 long 放最前、boolean 塞缝隙（字段重排，Fields Allocation 优化），不是按声明顺序排。把这个观察写进笔记。

## 4. 面试连接

**Q：new Object() 占多少字节？**
> 64 位 JVM + 指针压缩：Mark Word 8B + 类型指针 4B + 填充 4B = 16B。能主动说出"指针压缩在堆 >32GB 时失效，引用变 8B"直接上分。

**Q：为什么对象要 8 字节对齐？**
> CPU 缓存行（64B）与内存读取按块访问；对齐让"一个对象不跨缓存行"的概率最大化，代价是少量填充浪费——用空间换访问效率。

**Q：怎么估算线上一个 List<User> 的内存占用？**
> 公式：对象壳(16B) + int(4) + boolean(1)+填充 + 引用(4) + 实际 String 内容；工具化回答：JOL 算单对象 + `jmap -histo` 看全量，或 heap dump 用 MAT 看保留大小（day19）。

## 5. 今日验收清单

- [ ] JOL 输出截图存 notes/，能指出输出中"HEADER/类型指针/填充"三段
- [ ] 指针压缩开关对比数据记录（16B → 24B）
- [ ] 能口算：`new Object()`、`new int[10]`、你的 Person 类各占多少
- [ ] `git add . && git commit -m "day04: object layout"`
- [ ] 笔记：回答"堆超过 32GB 会发生什么"

---
[← Day 03](day03-运行时数据区.md) | [本月目录](README.md) | [Day 05 · 对象创建与分配 →](day05-对象创建与内存分配.md)
