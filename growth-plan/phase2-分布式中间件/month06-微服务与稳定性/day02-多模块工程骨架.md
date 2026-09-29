# Day 02 · 多模块工程骨架：Gradle 六模块与版本对齐

> **今日目标**：搭建商城 Gradle 多模块工程（mall 六模块），搞定 Spring Boot/Cloud/Alibaba 三件套版本对齐，实现 mall-common 规范（Result/异常），mall-order 独立启动。
> **时长**：工程搭建 2.5h / 版本对齐 1.5h / common 规范 1h
> **今日产出**：可编译可启动的六模块工程（step01 骨架落地）

## 1. 知识地图

```
多模块工程的价值（对比单模块）：
  强制物理边界：模块间只能通过依赖声明访问——比包结构约束硬得多
  独立构建/发布：mall-order 改动只构建 mall-order
  未来拆服务的预演：模块边界≈服务边界（day01 的拆分方案在工程上先落地）

版本对齐三件套（Spring Cloud 学的第一个坑）：
  Spring Boot 3.2.x（骨架）
    ↕ 由 BOM 对齐
  Spring Cloud 2023.0.x（Gateway/OpenFeign/LoadBalancer）
    ↕ 由 BOM 对齐
  Spring Cloud Alibaba 2023.0.1.0（Nacos/Sentinel/Seata）
  ⚠ 三者版本有严格兼容矩阵（github alibaba/spring-cloud-alibaba Wiki 查表）
  错配症状：启动报 NoSuchMethodError/ClassNotFoundError——90% 是版本错配不是代码问题
  Gradle 解法：根 build.gradle 用 platform() 统一 import 两个 BOM，子模块不写版本号

模块依赖方向（对应 day01 拆分方案的依赖无环）：
  mall-common ←（被所有人依赖，自身零依赖）
  mall-gateway（独立，依赖 common）
  mall-user / mall-product / mall-marketing（依赖 common）
  mall-order（依赖 common + 各服务的 API 模块/Feign 接口——本阶段直接依赖，M7 引 API 模块解耦）

mall-common 准入标准（step01 的坑 3）：
  只放：统一返回 Result、业务异常体系、通用工具（被 ≥2 模块用才准入）
  不放：任何业务实体/DTO——否则 common 变垃圾场，模块边界名存实亡
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Multi-Module Project | 多模块工程 |
| BOM (Bill of Materials) | 物料清单（版本对齐的依赖管理机制） |
| platform() | Gradle 的 BOM 导入方式（enforcedPlatform 强制对齐） |
| API Module | API 模块（只放接口与 DTO，服务间解耦用，M7 细化） |
| Result Wrapper | 统一返回包装（code/message/data） |
| Bootstrap Context | 引导上下文（config import 机制，替代旧 bootstrap.yml） |

## 3. 动手实操：六模块工程落地

```groovy
// 根 build.gradle（关键骨架）：
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.2.5' apply false
    id 'io.spring.dependency-management' version '1.1.4' apply false
}
subprojects {
    apply plugin: 'java'
    dependencies {
        implementation platform('org.springframework.boot:spring-boot-dependencies:3.2.5')
        implementation platform('org.springframework.cloud:spring-cloud-dependencies:2023.0.1')
        implementation platform('com.alibaba.cloud:spring-cloud-alibaba-dependencies:2023.0.1.0')
        implementation 'com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-discovery'
    }
}
// settings.gradle：include 'mall-common','mall-gateway','mall-user','mall-product','mall-order','mall-marketing'

// mall-common 统一返回与异常：
public record Result<T>(int code, String message, T data) {
    public static <T> Result<T> ok(T data) { return new Result<>(0, "ok", data); }
    public static <T> Result<T> fail(int code, String msg) { return new Result<>(code, msg, null); }
}
public class BizException extends RuntimeException {
    private final int code;
    public BizException(int code, String msg) { super(msg); this.code = code; }
}
// 全局异常处理器（每个服务同构）：@RestControllerAdvice → 兜住 BizException → Result.fail

// mall-order 启动类与探针接口：
@SpringBootApplication
public class MallOrderApplication {
    public static void main(String[] args) { SpringApplication.run(MallOrderApplication.class, args); }
}
@RestController class ProbeController {
    @GetMapping("/probe/ping") public Result<String> ping() { return Result.ok("order-pong"); }
}
```

```powershell
# 编译与启动验证：
cd D:\mywork\springbootai\practice-projects\02-microservice-mall\mall
.\gradlew.bat build -x test         # 六模块全编译
.\gradlew.bat :mall-order:bootRun   # 单模块启动（先不起 Nacos，day03 再接入）
# 验证：Start-Process http://localhost:8080/probe/ping → {"code":0,"message":"ok","data":"order-pong"}
# 版本自检：故意把 spring-cloud 换成 2022.0.0 启动 → 观察报错形态（认脸！以后一眼识别版本错配）
```

## 4. 面试连接

**Q：微服务工程的模块化你们怎么组织的？common 模块怎么防止腐化？**
> 组织：按服务一模块（与拆分方案一一对应），common 只放被 ≥2 模块复用的基础设施（Result/异常/工具）；版本用三个 BOM platform 统一对齐，子模块零版本号——升级改一处。防腐化三规则：①准入需"复用 ≥2 模块"评审；②禁止放业务实体（业务 DTO 按服务归属）；③common 改动必须全模块回归——成本让放东西的人自己掂量。进阶提一句：服务间接口后续抽 API 模块（接口+DTO），order 只依赖 product-api 不依赖 product 实现——依赖倒置在工程层的落地（M7 细化）。

**Q：Spring Cloud 版本错配一般什么症状？怎么排查？**
> 症状脸谱：启动期 NoSuchMethodError/NoClassDefFoundError（如 LoadBalancer 与 Ribbon 类冲突）、运行期某些自动配置静默失效（如 Sentinel 规则不生效）。排查三板斧：①查兼容矩阵（alibaba wiki 对照表）；②gradle dependencies 看实际解析版本（声明的≠生效的，传递依赖会覆盖）；③二分法删 starter 定位冲突源。经验句："我把版本矩阵贴在工程 README 里，新服务 copy 模板就不会踩——踩过一次 NoSuchMethodError 两小时的人都会这么干。"

## 5. 今日验收清单

- [ ] 六模块工程编译通过（gradle build 绿）
- [ ] mall-order 独立启动，ping 接口通
- [ ] 三个 BOM 版本对齐机制能讲清
- [ ] 故意错配实验完成（认脸报错）
- [ ] Result/异常/全局处理器三件套就位
- [ ] common 准入标准写进工程 README
- [ ] `git add . && git commit -m "day02: multi-module skeleton"`

---
[← Day 01](day01-微服务演进与拆分.md) | [本月目录](README.md) | [Day 03 · Nacos注册中心 →](day03-Nacos注册中心.md)
