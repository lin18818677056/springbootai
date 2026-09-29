# Day 20 · 数据迁移与双写（平滑切换六步 + 对账）

> **今日目标**：分库分表最惊险的一步不是拆，是"把线上几亿行从一个地方搬到另一个地方还不许出错"。今天学双写迁移六步法，亲手写 mini 版双写+对账程序，并输出本月文档产出《分库分表迁移方案》。
> **时长**：理论 1h / 实操 2h / 方案文档 1h
> **今日产出**：双写 Demo + 对账程序 + 《分库分表迁移方案》文档 v1

## 1. 知识地图

```
两种迁移路线（先选路线再谈细节）：
  停机迁移：公告停服 → 导出 → 导入 → 校验 → 恢复
    + 简单粗暴零一致性问题    - 业务中断（小系统/夜间窗口可接受）
  双写迁移（★在线平滑，工业标准）：不停车，数据"追着搬"

双写迁移六步法（★必背流程图，每步可回滚）：
  ① 备态：新库建好分片结构（路由/ID 方案全部就位）
  ② 全量：存量数据批量搬（按 id 分批 SELECT → INSERT，
     大表按天/按主键段切片，别一口气锁死）
  ③ 双写：业务代码同时写【老库+新库】（老库为主、新库失败只记日志不阻塞）
     ★顺序：先写老库成功 → 再写新库（老库是"事实源"）
     增量从这一刻起两条腿走路，全量阶段漏搬的由双写"补上"
  ④ 对账：定时任务比对老库 vs 新库（数量+checksum 抽样+全字段比对重点行）
     不一致 → 以老库为准修复新库（reconcile 修复程序）
  ⑤ 切读：读流量灰度切到新库（按用户尾号 1% → 10% → 50% → 100%）
     观察错误率/耗时/数据投诉，每档观察至少一个完整业务周期
     写仍在双写（新库已成为读写事实源的前夜）
  ⑥ 收尾：停写老库（只写新库）→ 老库降级为只读备份观察 1-2 周 → 下线
  ★每一步都有"回滚开关"：切读出问题立即回老库读（双写保证两边都在更新）

对账三件套（day19 ES 对账思想的通用化）：
  ① 总量对账：COUNT 快筛（分钟级，发现大面积极不一致）
  ② checksum 抽样：按 id 段 CRC32/MD5 比对（发现内容级不一致）
  ③ 流水对账：从 binlog/操作日志重放比对（终极证据，月05 消息表复用此思想）

热点坑位（面试展示踩坑经验）：
  ✗ 双写不同事务：老库成功新库失败 → 靠对账补偿（别用分布式事务硬保证，过重）
  ✗ 迁移期间 DDL：新库结构变更要在"全量"之前冻结（否则校验全乱）
  ✗ 自增 ID 混用：新库必须用全局 ID（day17），禁止依赖 AUTO_INCREMENT
  ✗ 唯一索引冲突重跑：全量任务必须幂等（INSERT ... ON DUPLICATE KEY UPDATE）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Dual Write | 双写 | 迁移期同时写新旧库 |
| Reconciliation | 对账 | 数据一致性核验 |
| Gray Release | 灰度发布 | 读流量按比例切换 |
| Idempotent | 幂等 | 迁移任务可安全重跑 |
| Rollback | 回滚 | 每步都有退路 |
| Cutover | 切换 | 流量从老到新的关键时刻 |

## 3. 动手实操

### 3.1 mini 双写迁移演练（老库 learn.t_old ←→ 新分片库）

```powershell
# 准备"老库"单表（模拟迁移源）与目标分片表（day18 已建）
docker exec -it mysql-learning mysql -uroot -proot123 learn -e "
CREATE TABLE IF NOT EXISTS t_old (
  id BIGINT PRIMARY KEY, user_id INT, amount DECIMAL(10,2), status TINYINT);"
```

```java
// DualWriteDemo.java —— 双写迁移 mini 演练：写入老库+新库，对账校验
import java.sql.*;
import java.util.*;

public class DualWriteDemo {
    static final String OLD_URL = "jdbc:mysql://localhost:3306/learn?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    static final String[] NEW_URLS = {
        "jdbc:mysql://localhost:3306/learn_db0?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
        "jdbc:mysql://localhost:3306/learn_db1?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    };
    static Connection OLD, NEW0, NEW1;
    static int db(int u) { return u % 2; }
    static int tb(int u) { return (u / 2) % 4; }

    /** ③ 双写：老库为主（同步），新库尽力（失败仅记录，交给对账补偿） */
    static void dualWrite(long id, int uid, double amt) throws Exception {
        // 先写老库 —— 事实源
        try (PreparedStatement ps = OLD.prepareStatement(
                "INSERT INTO t_old (id,user_id,amount,status) VALUES (?,?,?,1)")) {
            ps.setLong(1, id); ps.setInt(2, uid); ps.setDouble(3, amt); ps.executeUpdate();
        }
        // 再写新库 —— 尽力而为（生产：失败进"迁移补偿队列"等对账修复）
        try (PreparedStatement ps = (db(uid) == 0 ? NEW0 : NEW1).prepareStatement(
                "INSERT INTO t_order_" + tb(uid) + " (id,user_id,amount,status) VALUES (?,?,?,1)")) {
            ps.setLong(1, id); ps.setInt(2, uid); ps.setDouble(3, amt); ps.executeUpdate();
        } catch (Exception e) {
            System.out.println("[补偿队列] 新库写入失败 id=" + id + " → 待对账修复");
        }
    }

    /** ④ 对账：总量比对 + 抽样全字段比对（不一致以老库为准修复新库） */
    static void reconcile() throws Exception {
        long oldCount;
        try (ResultSet rs = OLD.createStatement().executeQuery("SELECT COUNT(*) FROM t_old")) {
            rs.next(); oldCount = rs.getLong(1);
        }
        long newCount = 0;
        for (Connection c : new Connection[]{NEW0, NEW1})
            for (int t = 0; t < 4; t++)
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT COUNT(*) FROM t_order_" + t)) { rs.next(); newCount += rs.getLong(1); }
        System.out.println("总量对账: 老库=" + oldCount + " 新库=" + newCount
                + (oldCount == newCount ? "  ✓ 一致" : "  ✗ 有差异 → 触发修复"));
        // 抽样比对（每库抽 3 条全字段）
        try (ResultSet rs = OLD.createStatement().executeQuery(
                "SELECT id,user_id,amount FROM t_old ORDER BY RAND() LIMIT 6")) {
            while (rs.next()) {
                long id = rs.getLong(1); int uid = rs.getInt(2); double amt = rs.getDouble(3);
                try (PreparedStatement ps = (db(uid) == 0 ? NEW0 : NEW1).prepareStatement(
                        "SELECT amount FROM t_order_" + tb(uid) + " WHERE id=?")) {
                    ps.setLong(1, id);
                    try (ResultSet rs2 = ps.executeQuery()) {
                        boolean ok = rs2.next() && Math.abs(rs2.getDouble(1) - amt) < 0.001;
                        System.out.println("  抽样 id=" + id + " → " + (ok ? "一致" : "缺失/不一致 → 修复"));
                    }
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        OLD  = DriverManager.getConnection(OLD_URL, "root", "root123");
        NEW0 = DriverManager.getConnection(NEW_URLS[0], "root", "root123");
        NEW1 = DriverManager.getConnection(NEW_URLS[1], "root", "root123");
        // 模拟迁移期新订单：500 条双写
        for (int i = 1; i <= 500; i++) {
            int uid = (i * 53) % 99_991;
            dualWrite(900_000L + i, uid, Math.random() * 1000);
        }
        System.out.println("500 条双写完成 → 执行对账：");
        reconcile();
        // 演示"部分失败"：手动删掉新库 1 条 → 对账应发现差异
        try (Statement st = NEW0.createStatement()) {
            st.executeUpdate("DELETE FROM t_order_0 WHERE id=900001");
        }
        System.out.println("\n[事故模拟] 新库删除 id=900001 → 再对账：");
        reconcile();
        OLD.close(); NEW0.close(); NEW1.close();
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month04-redis-mysql
javac -cp "lib/mysql-connector-j.jar" -d out DualWriteDemo.java
java -cp "out;lib/mysql-connector-j.jar" DualWriteDemo
# 记录：两次对账输出（第二次应捕捉到"抽样 id=900001 缺失"——对账的价值实证）
```

### 3.2 《分库分表迁移方案》文档模板（本月指定产出，填空即成文）

```text
# XX 订单库分库分表迁移方案（1 页纸版，团队评审用）
## 1. 背景与目标：当前单表 X 亿行/实例 QPS X → 目标 2 库×4 表
## 2. 分片设计：分片键 user_id；db=user_id%2, table=(user_id/2)%4；
    全局 ID 雪花算法；基因位 3 bit（双维路由）
## 3. 迁移步骤（对应六步法，每步写：动作/负责人/回滚开关/观察指标）
    ① 新库就位 ② 全量迁移(分批切片脚本) ③ 双写上线(灰度开关)
    ④ 对账(每日全量+实时增量) ⑤ 读流量灰度 1%→10%→50%→100% ⑥ 停写老库
## 4. 对账策略：总量/抽样 checksum/流水重放 三层
## 5. 风险与回滚：切读回滚开关（1 分钟生效）；双写失败补偿队列；
    迁移期 DDL 冻结公告
## 6. 时间表与责任人
```

### 3.3 切流灰度配置示意（工程细节）

```text
按用户尾号灰度（可解释、可定向回滚）：
  if (userId % 100 < grayPercent) { readFromNewShards(); } else { readFromOldDb(); }
  grayPercent: 1 → 10 → 50 → 100（配置中心动态推，秒级生效/回滚）
  观察指标三件套：错误率 / P99 耗时 / 数据不一致工单
  ★切读期写仍双写 → 回滚读路径零数据损失
```

## 4. 面试连接

**Q：线上几亿行数据怎么平滑迁移到分库分表？**
> 背六步法：备态→全量→双写→对账→切读→收尾。三个关键设计：① 双写以老库为事实源，新库失败进补偿队列不阻塞业务；② 对账三层——总量快筛、checksum 抽样、流水重放，不一致以老库为准修复；③ 灰度切读按用户尾号 1%→10%→100%，配置中心开关秒级回滚，切读期写仍双写所以回滚零损失。加分句："我写过 mini 双写+对账程序：故意删掉新库一条数据，对账任务精确捕捉到差异——这就是切读敢灰度的底气。"

**Q：双写期间数据不一致怎么办？能用分布式事务吗？**
> 原则：迁移场景不追求强一致，追求"最终一致+可发现"。分布式事务（XA/Seata）把迁移复杂度抬高一个数量级，还会拖慢老库主链路，不划算。正确姿势：新库写失败只记补偿队列；对账任务按小时/天级跑；切读开始前必须零差异——用"可观测+可修复"代替"强一致"。

**Q：怎么验证迁移后数据是对的？**
> 三层证据：① 总量 COUNT 分片之和 = 老库总量；② 内容级：按主键段切片做 CRC32/checksum 比对，抽样全字段核对；③ 流水级：重放 binlog 比对结果。再加业务侧验证：切读 1% 时监控"数据不对"类工单/投诉为零，逐步放大。最后留观察期：老库只读保留 1-2 周再下线——迁移的句号不是切完，而是"稳定运行满观察期"。

## 5. 今日验收清单

- [ ] DualWriteDemo 跑通（两次对账输出截图）
- [ ] 六步法流程图手绘（每步标注回滚开关）
- [ ] 《分库分表迁移方案》文档 v1 完成并存 learning 仓库
- [ ] 对账三件套能脱稿讲
- [ ] `git add . && git commit -m "day20: migration + dual write"`

---
[← Day 19](day19-跨分片查询与深分页.md) | [本月目录](README.md) | [Day 21 · 第三周复盘与决策树 →](day21-第三周复盘与决策树.md)
