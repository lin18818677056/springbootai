# Step 1：架构设计与环境搭建

> **本步目标**：完成需求澄清、容量估算、接口设计，搭好可运行的工程骨架与中间件环境。

## 前置术语

| 英文 | 中文 |
|------|------|
| Capacity Planning | 容量规划 |
| Traffic Funnel | 流量漏斗 |
| API (Application Programming Interface) | 应用程序编程接口 |
| Idempotent | 幂等的 |
| Rate Limiting | 限流 |

## 1. 需求澄清（先问清再动手，架构师第一习惯）

| 问题 | 答案（本项目设定） | 对设计的影响 |
|------|------------------|-------------|
| 商品数与库存量？ | 1 场活动 / 1000 件 | 库存可全放 Redis（内存友好） |
| 预估瞬时请求？ | 50 万 QPS 打到入口 | 必须限流，只放行 ~5000 |
| 每人限购？ | 1 件 | Redis 需记录"一人一单" |
| 下单到支付？ | 15 分钟未支付自动关单 | 需要延迟消息 |
| 是否允许排队？ | 允许（异步） | MQ 削峰成立的前提 |

## 2. 容量估算（写出计算过程，面试直接用）

```
入口流量：      500,000 QPS（营销页面打开 + 刷新）
  ↓ 前端按钮置灰+验证码    放行 20%：100,000 QPS
  ↓ 网关 Sentinel 限流     放行 5,000 QPS（单机 1000 × 5 台）
  ↓ Redis Lua 扣减         库存仅 1000 → 放行 ≈ 1,050 QPS（含重试）
  ↓ MQ 异步落库            消费端按 MySQL 能力消费 ≈ 1,000 TPS
MySQL 最终写入： ~1,000 行订单（其他全部被前置层拒绝）
```

> 结论：**数据库只承受 1/500 的入口流量**，这就是"流量漏斗"的价值。

## 3. 接口设计（RESTful）

| 接口 | 方法 | 说明 | 幂等 |
|------|------|------|------|
| `/seckill/goods/list` | GET | 秒杀商品列表（缓存） | 天然幂等 |
| `/seckill/goods/{id}` | GET | 商品详情（多级缓存） | 天然幂等 |
| `/seckill/token` | GET | 获取秒杀令牌（防脚本直刷下单接口） | 一次性 |
| `/seckill/order` | POST | 下单（body: goodsId + token），返回排队中/成功/失败 | token 一次性保证 |
| `/seckill/result?orderNo=` | GET | 轮询下单结果 | 天然幂等 |

统一响应体：

```java
public record Result<T>(int code, String message, T data) {
    public static <T> Result<T> ok(T data)  { return new Result<>(0, "success", data); }
    public static <T> Result<T> fail(int code, String msg) { return new Result<>(code, msg, null); }
}
```

## 4. 环境搭建（infra/docker-compose.yml）

```yaml
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root123
      MYSQL_DATABASE: seckill
    ports: ["3306:3306"]
    volumes:
      - ./mysql/init.sql:/docker-entrypoint-initdb.d/init.sql   # 建表脚本
  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
    command: redis-server --requirepass redis123 --maxmemory 512mb --maxmemory-policy allkeys-lru
  rmqnamesrv:
    image: apache/rocketmq:5.3.1
    command: sh mqnamesrv
    ports: ["9876:9876"]
  rmqbroker:
    image: apache/rocketmq:5.3.1
    depends_on: [rmqnamesrv]
    command: sh mqbroker -n rmqnamesrv:9876 -c /home/rocketmq/conf/broker.conf
    ports: ["10911:10911", "10909:10909"]
```

启动与验证：

```powershell
cd infra
docker compose up -d
docker exec -it <redis容器> redis-cli -a redis123 ping   # 期望 PONG
```

## 5. 工程骨架（Gradle）

build.gradle 关键依赖：

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.apache.rocketmq:rocketmq-spring-boot-starter:2.3.1'
    implementation 'com.baomidou:mybatis-plus-spring-boot3-starter:3.5.7'
    implementation 'com.alibaba.csp:sentinel-core:1.8.8'
    runtimeOnly 'com.mysql:mysql-connector-j'
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
}
```

包结构（先立规矩）：

```
com.trained.project.seckill
├── controller      # 接口层：参数校验 + 编排，不写业务
├── service         # 业务层
├── domain          # 领域对象：entity / dto / enums
├── infra           # 基础设施：redis / mq / cache
└── config          # 配置类
```

## 6. 建表脚本（init.sql 核心）

```sql
CREATE TABLE seckill_goods (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  goods_id      BIGINT NOT NULL,
  stock_count   INT NOT NULL,             -- 数据库库存（兜底层）
  start_time    DATETIME NOT NULL,
  end_time      DATETIME NOT NULL,
  version       INT NOT NULL DEFAULT 0,   -- 乐观锁
  KEY idx_goods (goods_id)
) COMMENT '秒杀商品库存表';

CREATE TABLE seckill_order (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no   VARCHAR(32) NOT NULL UNIQUE, -- 唯一索引 = 幂等底线
  user_id    BIGINT NOT NULL,
  goods_id   BIGINT NOT NULL,
  status     TINYINT NOT NULL DEFAULT 0,  -- 0待支付 1已支付 2已关闭
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_goods (user_id, goods_id)  -- 一人一单的数据库底线
) COMMENT '秒杀订单表';
```

## 验收清单

- [ ] `docker compose up -d` 一键拉起 MySQL/Redis/RocketMQ
- [ ] `gradlew bootRun` 应用启动成功，Redis 连接测试通过
- [ ] 两张核心表建好（注意两个唯一索引的用途你能说清）
- [ ] 5 个接口定义写进 README（含幂等说明）

## 常见坑

1. **Windows Docker 端口占用**：先 `netstat -ano | findstr 3306` 查占用再改映射。
2. **RocketMQ broker 注册宿主机 IP**：容器内 broker 会注册内网地址，需在 broker.conf 配 `brokerIP1=127.0.0.1`。
3. **时区问题**：JDBC URL 加 `serverTimezone=Asia/Shanghai`。

> 完成后进入 `step02-商品详情与缓存体系.md`。
