# Day 02 · 类加载机制与双亲委派

> **今日目标**：搞懂一个类从 .class 文件到可用对象的完整旅程（加载→验证→准备→解析→初始化），亲手实现一个打破双亲委派的类加载器。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：自定义 ClassLoader 代码 + 一次"主动引用触发初始化"实验记录

## 1. 知识地图

```
类的生命周期：
加载 ──→ 验证 ──→ 准备 ──→ 解析 ──→ 初始化 ──→ 使用 ──→ 卸载
│          │        │         │          │
读字节流    文件格式/  静态变量    符号引用     执行 <clinit>
到方法区    元数据/    设默认值    →直接引用    (静态块+静态赋值)
          字节码验证  (零值!)    (可延后)

【准备 ≠ 初始化】经典考点：
static int a = 10;   在"准备"阶段 a = 0（零值），在"初始化"阶段才 = 10
static final int B = 10;  常量在准备阶段直接 = 10（ConstantValue 属性）

类加载器层级（JDK9+，注意 Extension 已改名 Platform）：
┌────────────────────────────┐
│  Bootstrap ClassLoader      │ ← C++ 实现，加载 java.lang 等（java 里拿不到引用）
└─────────────┬──────────────┘
┌─────────────▼──────────────┐
│  Platform ClassLoader       │ ← 加载平台模块（原 Extension）
└─────────────┬──────────────┘
┌─────────────▼──────────────┐
│  Application ClassLoader    │ ← 加载 classpath（我们写的类）
└─────────────┬──────────────┘
┌─────────────▼──────────────┐
│  自定义 ClassLoader          │ ← 你今天要写的
└────────────────────────────┘

双亲委派：收到加载请求 → 先问父亲 → 父亲搞不定才自己干
效果：java.lang.String 永远由 Bootstrap 加载 → 防止核心类被篡改 + 避免重复加载
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Class Loading | 类加载 | 生命周期前五步统称 |
| Parent Delegation Model | 双亲委派模型 | 先委派父加载器的机制 |
| `<clinit>` | 类初始化方法 | 编译器合成的静态块+静态赋值合集 |
| Active Reference | 主动引用 | 触发初始化的 6 种场景（见下） |
| Lazy Resolution | 惰性解析 | 符号引用推迟到首次使用才解析 |

**触发初始化的 6 种主动引用**（面试必背）：`new`/getstatic/putstatic/invokestatic 四条指令、反射调用、子类初始化触发父类、main 所在类、`MethodHandle` 句柄对应类、接口含 default 方法时实现类初始化。**访问 static final 常量、通过数组定义引用都不触发**。
主动引用（6 种，面试必背）
1. new          →  new HelloJVM()
2. getstatic    →  读静态变量 HelloJVM.a
3. putstatic    →  写静态变量 HelloJVM.a = 1
4. invokestatic →  调静态方法 HelloJVM.main()
5. 反射调用      →  Class.forName("HelloJVM")
6. 子类初始化    →  初始化子类时，先初始化父类
7. main 所在类   →  JVM 启动时，先初始化 main 方法所在的类
8. MethodHandle →  句柄对应的类
9. 接口 default  →  接口有 default 方法时，实现类初始化

被动引用（不触发初始化）

// 1. 访问 static final 常量
System.out.println(HelloJVM.MAX);  // 常量在编译期就放进常量池了，不触发初始化

// 2. 通过数组定义引用
HelloJVM[] arr = new HelloJVM[10];  // 只创建数组对象，不初始化 HelloJVM 类

// 3. 访问父类的静态变量
System.out.println(Sub.PARENT_STATIC);  // 只初始化父类，不初始化子类

## 3. 动手实操

### 3.1 观察类加载的真实顺序

```powershell
cd D:\mywork\springbootai\learning\month01-jvm
mkdir day02; cd day02
# 用 JDK9+ 统一日志看每个类被谁加载
java -Xlog:class+load HelloJVM | Select-String "HelloJVM"
# 输出形如：
# [class,load] source: ... HelloJVM source: jrt:/...   ← 前几行是被 Bootstrap 加载的核心类
# 注意找 HelloJVM 自己那一行：由 "app"（Application）加载器加载
```

### 3.2 实验一：验证"准备阶段是零值"

```java
public class InitOrder {
    static int a = 10;
    static final int B = 10;

    static { System.out.println("静态块执行, 此时 a=" + a); }

    public static void main(String[] args) {
        System.out.println("main: a=" + a + ", B=" + B);
    }
}
// 运行后思考：如果把 main 里改成只打印 B，静态块还执行吗？→ 常量不触发初始化！
java InitOrder
java -Xlog:class+load InitOrder | Select-String "InitOrder"   # 对比观察
```

### 3.3 实验二：自定义类加载器（今天的核心代码）

```java
import java.nio.file.*;
import java.nio.file.Path;

public class MyClassLoader extends ClassLoader {
    private final Path classDir;

    public MyClassLoader(Path classDir) {
        this.classDir = classDir;          // 不指定 parent → 默认用 AppClassLoader 作父
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            byte[] bytes = Files.readAllBytes(classDir.resolve(name + ".class"));
            return defineClass(name, bytes, 0, bytes.length);   // 关键：字节 → Class 对象
        } catch (Exception e) {
            throw new ClassNotFoundException(name, e);
        }
    }

    public static void main(String[] args) throws Exception {
        // 先把 HelloJVM.class 拷到独立目录，模拟"磁盘上的加密类"
        Path dir = Path.of("encrypted");
        Files.createDirectories(dir);
        Files.copy(Path.of("../day01/HelloJVM.class"), dir.resolve("HelloJVM.class"),
                   java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        MyClassLoader loader = new MyClassLoader(dir);
        Class<?> c = loader.loadClass("HelloJVM");    // loadClass 走双亲委派 → 父亲已加载过 → 直接返回！
        System.out.println("加载器: " + c.getClassLoader());
        System.out.println("与系统类相同吗: " + (c == HelloJVM.class));   // true！委派的结果
        c.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
    }
}
```

```powershell
javac MyClassLoader.java
java MyClassLoader
```

**观察与思考**（写进笔记）：`loadClass` 默认走双亲委派所以返回的是父亲加载的类。要真正"自己加载"，把 `loadClass` 改成直接调 `findClass`（打破委派），再跑一次，`(c == HelloJVM.class)` 会变成 **false**——同一个 .class 文件，两个类加载器 = 两个不同的 Class 对象。这就是 Tomcat 热部署、OOM"类加载器泄漏"（day25）的原理。

## 4. 面试连接

**Q：为什么需要双亲委派？能打破吗？**
> 两个价值：安全（核心类不可被篡改——你写的 java.lang.String 不会被加载）+ 唯一性（同一类只加载一次）。打破的正当场景：SPI（JDBC Driver 用线程上下文类加载器让 Bootstrap 反向委托）、Web 容器隔离（Tomcat 每个 webapp 独立加载器）、热部署。答"能打破但要知道为什么"是 P7 水平。

**Q：两个类加载器加载同一个类，equals 相等吗？**
> 不等。类的相等性 = 同一 ClassLoader + 同一全限定名。这是类隔离的基础，也是 day25 类加载器泄漏的根源。

## 5. 今日验收清单

- [X] 能默画类加载五阶段，说清"准备是零值、初始化才赋值"
- [X] 能背出 6 种主动引用
- [X] MyClassLoader 两次实验都跑通，理解 `true→false` 的原因
- [X] `git add . && git commit -m "day02: classloader"` 提交
- [X] 笔记：写清"哪些框架打破了双亲委派、为什么敢打破"
  双亲委派是“先问上级”，保证安全和唯一；但 JDBC 要让上级反过来问下级，Tomcat 要让每个应用各用各的，热部署要换加载器——这些场景不打破规矩就做不出来，所以必须打破
---
[← Day 01](day01-JVM全景与环境搭建.md) | [本月目录](README.md) | [Day 03 · 运行时数据区 →](day03-运行时数据区.md)
