# 实战项目总览（Practice Projects）

> **定位**：知识只有变成系统才算掌握。这 4 个项目按难度递进，覆盖 Java 后端 → 分布式 → 大数据 → AI 全链路，每个项目都按 Step（步骤）拆解，跟着写即可。
> **技术基线**：与你的 `springbootai` 项目一致 —— Java 25 + Spring Boot + Gradle + Docker。

## 项目矩阵

| # | 项目 | 目录 | 核心技术 | 配合学习阶段 | 难度 |
|---|------|------|---------|------------|------|
| 一 | 高并发秒杀系统 | `01-seckill-system/` | Redis + RocketMQ + Sentinel + JMeter | 第 4-6 月 | ★★★★ |
| 二 | DDD 微服务商城 | `02-microservice-mall/` | Spring Cloud Alibaba + Seata + SkyWalking | 第 6-8 月 | ★★★★☆ |
| 三 | 实时数据分析平台 | `03-realtime-analytics/` | Kafka + Flink + ClickHouse + Grafana | 第 10-12 月 | ★★★★☆ |
| 四 | 企业级 RAG 知识库 | `04-rag-knowledge-base/` | Spring AI + 向量库 + Ollama + MCP | 第 13-15 月 | ★★★★★ |

## 环境准备总表（一次装好，四项目通用）

| 组件 | 用途 | 安装方式（Windows） | 验证命令 |
|------|------|--------------------|---------|
| JDK 25 | 运行环境 | 已有（openjdk-25.0.2） | `java -version` |
| Gradle | 构建工具 | 项目自带 wrapper（gradlew） | `gradlew -v` |
| Docker Desktop | 全部中间件 | 官网下载 Windows 版 | `docker -v` |
| MySQL 8 | 关系库 | docker-compose | `docker exec mysql mysql -V` |
| Redis 7 | 缓存/锁 | docker-compose | `docker exec redis redis-cli ping` |
| RocketMQ 5 | 消息队列 | docker-compose | Dashboard 可访问 |
| Nacos 2.x | 注册配置中心 | docker-compose | http://localhost:8848 |
| Kafka 3.x | 大数据消息管道 | docker-compose | `kafka-topics.sh --list` |
| Flink 1.18+ | 流计算 | docker-compose | http://localhost:8081 |
| ClickHouse | OLAP 分析库 | docker-compose | `clickhouse-client` |
| Grafana | 可视化 | docker-compose | http://localhost:3000 |
| Ollama | 本地大模型 | 官网 Windows 安装包 | `ollama list` |
| JMeter 5.x | 压测 | 官网 zip 解压 | `jmeter -v` |
| Arthas | Java 诊断 | `curl -O arthas-boot.jar` | `java -jar arthas-boot.jar` |

**建议**：项目根目录建一个 `infra/docker-compose.yml`，把 MySQL/Redis/Nacos/RocketMQ 一次定义好，`docker compose up -d` 一键拉起。项目三、四再叠加各自的 compose 文件。

## 通用开发规范（四个项目统一遵守）

1. **包结构**：`com.trained.project.<项目名>` 下按 `controller / service / domain / infra` 分层（项目二升级为 DDD 四层）。
2. **接口规范**：统一响应体 `Result<T>`（code/message/data）、统一异常处理（@RestControllerAdvice）、参数校验（Jakarta Validation）。
3. **日志规范**：SLF4J + Logback，JSON 格式，必须带 traceId；禁止 `System.out.println`。
4. **配置规范**：环境变量优先，敏感信息不进 Git（.gitignore 检查）。
5. **每个 Step 的验收**：必须真实跑通并截图/记录数据，不跑通不进入下一步。

## 项目与学习阶段的对应关系

```
第 4-6 月  │███░░░░░░░░│ 项目一：秒杀系统（边学 Redis/MQ 边做）
第 6-8 月  │░░░████░░░░│ 项目二：微服务商城（边学微服务/DDD 边做）
第 10-12月 │░░░░░░░████│ 项目三：实时分析平台（边学大数据边做）
第 13-15月 │░░░░░░░░░██│ 项目四：RAG 知识库（边学 AI 边做）
```

> 从 `01-seckill-system/README.md` 开始你的第一个项目。
