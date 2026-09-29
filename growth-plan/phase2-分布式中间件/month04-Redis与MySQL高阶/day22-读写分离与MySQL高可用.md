# Day 22 · 读写分离与 MySQL 高可用（路由代码 + 方案对比）

> **今日目标**：决策树 Q2 的标准答案展开：读写分离的两层实现（应用路由/中间件）、复制延迟的三大治理手段、MySQL 高可用四方案对比（MHA/MGR/Orchestrator/云 RDS）。手写一个读写路由 Demo 把 day15 决策树的"读瓶颈"解法落地。
> **时长**：理论 1.5h / 实操 2h / 输出 0.5h
> **今日产出**：一主一从环境 + 读写路由 Demo + 复制延迟实验 + 高可用选型卡

## 1. 知识地图

```
读写分离的本质：写走主（唯一事实源），读走从（N 个副本分摊）

路由的两层实现：
  应用层路由（手写/框架内置）：
    AbstractRoutingDataSource（Spring）按注解/AOP 切换数据源
    + 灵活可控、无额外组件     - 每个应用都要接一遍
  中间件路由（ProxySQL / ShardingSphere-Proxy / MyCat）：
    应用连中间件 → 中间件解析 SQL 判读写 → 路由到主/从
    + 语言无关集中管控         - 多一跳网络、中间件本身要高可用
  ★路由规则细节：事务内强制走主！否则"写了立刻在自己事务里读不到"

复制延迟（month03 day26 的伏笔今天收线）：
  成因：从库单线程 SQL 回放（MySQL 5.7 前并行度低）/ 大事务 / 从库机器差
  现象：写完立刻读从库 → 读不到刚写的（"下单成功但订单列表没有"事故）
  治理三板斧：
  ① 关键路径强制走主：下单后跳转订单详情 → 走主库（业务侧路由规则）
  ② GTID 等待：SELECT WAIT_FOR_EXECUTED_GTID_SET(gtid, timeout)
     = 告诉从库"等我这笔事务到了再读"（精准且高效，推荐）
  ③ 半同步复制：主库等至少 1 从 ACK 才返回成功（写入变慢换安全，
     month03 day26 的 min-replicas 同款思想）

高可用四方案对比（选型卡，面试必考）：
  ┌──────────────┬────────────┬──────────────┬─────────────────┐
  │ 方案          │ 原理        │ 优/劣          │ 适用             │
  ├──────────────┼────────────┼──────────────┼─────────────────┤
  │ MHA          │ 外部监控+脚本│ 成熟简单/      │ 传统一主多从     │
  │              │ 提升+补齐    │ 切换丢数据风险 │ 老系统          │
  │ Orchestrator │ 拓扑发现+自动│ 可视化好/      │ 多套主从统一管理 │
  │              │ failover    │ 社区活跃       │ 中小规模        │
  │ MGR 组复制   │ Paxos 多数派 │ 强一致/       │ 核心库、金融级   │
  │              │ 自动选主     │ 运维复杂、版本≥8 │ 新建核心系统    │
  │ 云 RDS 高可用 │ 云厂商托管   │ 省心/          │ 云上业务（多数   │
  │              │              │ 被锁定         │ 公司的最优解）   │
  └──────────────┴────────────┴──────────────┴─────────────────┘
  MGR 亮点（深入 1 层）：多数派提交（写要过半节点同意）→ 不丢数据；
    单主模式自动选主；是 Paxos 思想在 MySQL 的落地（month05 理论铺垫）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Read/Write Splitting | 读写分离 | 写主读从 |
| Replication Lag | 复制延迟 | 主从数据时间差 |
| GTID | 全局事务标识 | 精准等待事务到位 |
| Semi-Sync | 半同步复制 | ≥1 从 ACK 才提交 |
| MGR | 组复制 | 多数派+自动选主 |
| Failover | 故障切换 | 主挂自动提升从 |

## 3. 动手实操

### 3.1 主菜①：读写路由 Demo（事务内强制走主）

```java
// ReadWriteRoute.java —— 读写分离路由器（应用层路由的教学版）
import java.sql.*;
import java.util.*;

public class ReadWriteRoute {
    // 单实例模拟主从（生产：两个真实连接串）——重点在路由逻辑
    static final String MASTER = "jdbc:mysql://localhost:3306/learn?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    static final String SLAVE  = "jdbc:mysql://localhost:3306/learn?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    /** 路由规则三条件（★背下来）：事务内→主；写语句→主；其余→从 */
    static Connection route(boolean inTransaction, String sql) {
        boolean isWrite = sql.trim().toUpperCase().startsWith("INSERT")
                || sql.trim().toUpperCase().startsWith("UPDATE")
                || sql.trim().toUpperCase().startsWith("DELETE");
        boolean forceMaster = inTransaction || isWrite;
        System.out.println("路由 → " + (forceMaster ? "MASTER" : "SLAVE") + " : "
                + sql.substring(0, Math.min(40, sql.length())) + "...");
        try {
            return DriverManager.getConnection(forceMaster ? MASTER : SLAVE, "root", "root123");
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** 下单场景：写主 + 事务内读主（保证自己立即可见） */
    static void placeOrder(int uid, double amt) throws SQLException {
        Connection c = route(false, "INSERT INTO t_rw_order(user_id,amount) VALUES(" + uid + "," + amt + ")");
        c.setAutoCommit(false);                     // 开事务
        Statement st = c.createStatement();
        st.executeUpdate("INSERT INTO t_rw_order(user_id,amount) VALUES(" + uid + "," + amt + ")");
        // 事务内读 —— 若路由到从库会读不到刚写的（复制延迟事故），规则强制走主
        ResultSet rs = route(true, "SELECT COUNT(*) FROM t_rw_order WHERE user_id=" + uid)
                .createStatement().executeQuery("SELECT COUNT(*) FROM t_rw_order WHERE user_id=" + uid);
        rs.next();
        System.out.println("事务内立即可见订单数: " + rs.getInt(1));
        c.commit(); c.close();
    }

    /** 列表页场景：无事务读 → 走从库分摊 */
    static void listOrders() throws SQLException {
        Connection c = route(false, "SELECT * FROM t_rw_order ORDER BY id DESC LIMIT 5");
        ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM t_rw_order");
        rs.next();
        System.out.println("列表页（从库）订单总数: " + rs.getInt(1));
        c.close();
    }

    public static void main(String[] args) throws Exception {
        Connection setup = DriverManager.getConnection(MASTER, "root", "root123");
        setup.createStatement().executeUpdate(
            "CREATE TABLE IF NOT EXISTS t_rw_order (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id INT, amount DECIMAL(10,2))");
        setup.close();
        placeOrder(42, 199.00);                     // 看路由日志：写主+事务内读主
        listOrders();                               // 看路由日志：读从
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month04-redis-mysql
javac -cp "lib/mysql-connector-j.jar" -d out ReadWriteRoute.java
java -cp "out;lib/mysql-connector-j.jar" ReadWriteRoute
# 记录路由日志：INSERT→MASTER / 事务内 SELECT→MASTER / 列表 SELECT→SLAVE
```

### 3.2 一主一从 + 延迟观察（快速版，month03 day26 详细版可选回看）

```powershell
# ① 起主从（独立于学习容器，端口 3307/3308）
docker run -d --name mysql-rw-master -p 3307:3306 `
  -e MYSQL_ROOT_PASSWORD=root123 mysql:8.4
docker run -d --name mysql-rw-slave -p 3308:3306 `
  -e MYSQL_ROOT_PASSWORD=root123 mysql:8.4
# ② 从库指向主库（容器互访走 bridge 默认网关，用宿主机 IP 或 docker network）
docker network create rw-net; docker network connect rw-net mysql-rw-master; docker network connect rw-net mysql-rw-slave
docker exec -it mysql-rw-slave mysql -uroot -proot123 -e `
  "CHANGE REPLICATION SOURCE TO SOURCE_HOST='mysql-rw-master', SOURCE_USER='root', SOURCE_PASSWORD='root123', GET_SOURCE_PUBLIC_KEY=1, SOURCE_AUTO_POSITION=1; START REPLICA;"
docker exec -it mysql-rw-slave mysql -uroot -proot123 -e "SHOW REPLICA STATUS\G" | Select-String "Running"
# 期望：Replica_IO_Running: Yes / Replica_SQL_Running: Yes
# ③ 复制延迟观察（从库 Seconds_Behind 指标）
docker exec -it mysql-rw-slave mysql -uroot -proot123 -e "SHOW REPLICA STATUS\G" | Select-String "Seconds_Behind"
# ④ 主库压一批写入，立刻看从库延迟数字变化
1..1000 | ForEach-Object { "INSERT INTO learn.t_rw_order(user_id,amount) VALUES ($_ , 10);" } | `
  docker exec -i mysql-rw-master mysql -uroot -proot123 learn
docker exec -it mysql-rw-slave mysql -uroot -proot123 -e "SHOW REPLICA STATUS\G" | Select-String "Seconds_Behind"
# 记录延迟从 0 → N 秒的窗口——这就是"强制走主"规则存在的原因
```

### 3.3 高可用选型卡（抄进笔记）

```text
决策线索（面试给"选型理由"而不只是背表格）：
  老系统/传统运维     → MHA（团队熟、脚本可控）
  多套主从集中管控    → Orchestrator（拓扑可视化）
  新建核心/金融级     → MGR（多数派不丢数据）或云 RDS 集群版
  云上普通业务        → 直接 RDS 高可用版（把 DBA 活外包给云）
  万能句式："我按 RPO/RTO 预算选：能接受秒级丢数据选 MHA 系；
  零丢失预算 → MGR 多数派；运维预算不足 → 云托管。"
```

## 4. 面试连接

**Q：读写分离怎么实现？怎么解决复制延迟问题？**
> 两层路由：应用层（Spring AbstractRoutingDataSource + AOP 注解）或中间件层（ProxySQL/ShardingSphere-Proxy，语言无关）。路由规则三条件：事务内强制走主、写语句走主、其余走从——特别是"事务内读必须主"，否则读不到自己刚写的数据。延迟治理三板斧：① 关键业务路径强制走主（下单后详情页）；② GTID 精准等待（WAIT_FOR_EXECUTED_GTID_SET，比 sleep 文明）；③ 半同步复制兜底。加分句："我写过路由 Demo 并实测过批量写入后 Seconds_Behind 从 0 涨到 N 的窗口——所以规则不是教条，是事故教训。"

**Q：MySQL 高可用方案怎么选？**
> 四方案对比后给决策维度：MHA（成熟简单但切换可能丢最新事务）、Orchestrator（拓扑管理强）、MGR（Paxos 多数派、零丢失、自动选主，代价是运维复杂+限制多）、云 RDS（托管省心）。选型按 RPO/RTO 预算：金融核心 → MGR 或云集群版；一般业务 → 云 RDS 高可用版；自建老系统 → MHA。收尾："高可用的本质是'自动失败转移+不丢已提交数据'，方案对比就是把这两个指标和运维成本摆上天平。"

**Q：MGR 为什么能保证不丢数据？**
> 多数派协议：事务提交需要过半节点持久化确认（类 Paxos），主库宕机时已提交事务必然在多数派中存在，新主一定能选到最新数据——对比异步复制"主挂了从没收到"的丢失窗口。单主模式下自动选主，多主模式支持多点写入（冲突检测用认证排序）。代价：写入延迟增加（要等多数派）、表必须有主键、集群规模建议 ≤9。

## 5. 今日验收清单

- [ ] ReadWriteRoute 跑通（三种路由日志记录）
- [ ] 一主一从 Running: Yes 双绿
- [ ] 复制延迟实验记录（批量写入后 Seconds_Behind 变化）
- [ ] 高可用四方案选型卡默写
- [ ] `git add . && git commit -m "day22: rw splitting + ha"`

---
[← Day 21](day21-第三周复盘与决策树.md) | [本月目录](README.md) | [Day 23 · 秒杀01需求与架构 →](day23-秒杀01需求与架构.md)
