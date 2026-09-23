# Day 29 · 综合演练②：陌生库巡检 + 慢 SQL 门诊 + 模拟验收

> **今日目标**：M3 实战大考日。上午扮演"接手陌生生产库的工程师"完成一次标准巡检；下午独立诊断 3 条全新组合病例（没人带你做）；最后阶段一模拟验收——白板手写三件套限时演练。
> **时长**：巡检 1h / 门诊 2h / 模拟验收 1h
> **今日产出**：巡检清单执行记录 + 3 病例独立诊断报告 + 模拟验收成绩单

## 1. 知识地图

```
接手陌生库的标准巡检六查（★面试"你怎么熟悉一个新系统"的 DB 版）：
  ① 版本与架构：SELECT VERSION(); 单机/主从/集群？（SHOW REPLICA STATUS）
  ② 资源水位：内存(BP大小/命中率) 磁盘(剩余/IO) 连接数(Threads_connected/max)
  ③ 参数红线：双1？BP 大小合理？long_query_time？
  ④ 慢日志：开着吗？Top 慢 SQL 是谁？
  ⑤ 大表与长事务：TOP 5 大表；innodb_trx 超 60s 的；
  ⑥ 锁与错误：最近死锁（INNODB STATUS）；error log 尾部

慢 SQL 门诊流程卡（day20 五步的肌肉记忆版）：
  抓到 → EXPLAIN → 失效场景对照 → 处方 → 前后耗时
  今天的 3 条病例全是【组合症状】——真实世界没有单一病因：
    病例1 大表 COUNT 慢      → 统计的代价观 + 方案选型
    病例2 join+排序双重坏点   → 拆开逐个击破
    病例3 隐式转换+深分页叠加 → 复合失效的排查顺序
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Inspection | 巡检 | 陌生系统的标准体检 |
| Baseline | 基线 | 优化前的性能快照 |
| Diagnosis | 诊断 | 症状→化验→根因 |
| Dry Run | 演练 | 面试/事故前的彩排 |
| Whiteboard Coding | 白板手写 | 无 IDE 编码能力验收 |
| Composite Failure | 复合故障 | 多病因叠加的场景 |

## 3. 动手实操

### 3.1 陌生库巡检六查（对着容器真做一遍，记录进笔记）

```sql
-- ① 版本与架构
SELECT VERSION();  SHOW REPLICA STATUS\G        -- 是否从库；SHOW MASTER STATUS 主库证据
-- ② 资源水位
SHOW GLOBAL STATUS LIKE 'Threads_connected';
SHOW GLOBAL STATUS LIKE 'Innodb_buffer_pool_pages_%';   -- dirty/total
-- ③ 参数红线
SHOW VARIABLES WHERE Variable_name IN
 ('innodb_buffer_pool_size','innodb_flush_log_at_trx_commit','sync_binlog','long_query_time');
-- ④ 慢日志
SHOW VARIABLES LIKE 'slow_query_log';
-- ⑤ 大表排行（TOP 5）
SELECT table_schema, table_name,
       ROUND(data_length/1024/1024) AS data_mb,
       ROUND(index_length/1024/1024) AS idx_mb, table_rows
FROM information_schema.tables
WHERE table_schema NOT IN ('mysql','sys','performance_schema','information_schema')
ORDER BY data_length+index_length DESC LIMIT 5;
-- ⑥ 错误与死锁
SHOW ENGINE INNODB STATUS\G                     -- 死锁段 + 错误统计
-- 输出：《巡检报告》一页纸（每查一行结论）
```

### 3.2 病例门诊：三条组合症状（独立诊断，先自己做完对答案）

```sql
-- 病例1：运营说"统计总数太慢"
SELECT COUNT(*) FROM learn.t_index;             -- 100 万行，观察耗时
-- 你的诊断：__________（提示：COUNT(*) 走最小索引，但仍是全索引扫；
--   方案选型：① EXPLAIN 确认是否走小索引 ② 展示型计数改计数表/缓存
--   ③ 精确值换 SHOW TABLE STATUS 的近似值——业务接受误差吗？）

-- 病例2：列表页慢（join+排序）
EXPLAIN
SELECT t.id, t.name, e.tag FROM learn.t_index t
JOIN learn.t_user_ext e ON e.id = t.id
WHERE t.status = 1 ORDER BY t.created DESC LIMIT 20;
-- 诊断线索：看两表 type；Extra 有无 filesort；
--   处方：驱动表 t 先过滤(status 索引)，e 主键 eq_ref；
--   若 filesort 在 → (status, created) 索引吃掉排序（idx_status_created 已有！）

-- 病例3：PHP 老系统传参 int（复合失效）
SELECT * FROM learn.t_index WHERE name = 42 ORDER BY id LIMIT 900000, 10;
-- 诊断顺序教学：① name=42 隐式转换（全表）② 深分页（ Limit 前全读）
--   先修类型（name='42' 走 idx_name）再修分页（游标/延迟关联）
--   复合故障的排查纪律：一次只修一个变量，修一个测一轮
```

### 3.3 阶段一模拟验收（month01-03 综合水平测试，限时）

```text
【白板手写三件套】（每件 15 分钟，无 IDE，写完口述运行结果）
  ① 手写长度头协议的编解码伪代码（day03/12）：魔数+长度+半包回退
  ② 手写 RPC 调用时序图 + InvocationHandler 核心 10 行（day13）
  ③ 手写一条 UPDATE 的 11 站旅程图（day28）——3 分钟画完
【三大图脱稿】
  ④ TCP 三次握手/四次挥手全状态图（day02）
  ⑤ 主从 Reactor 结构图（day10）
  ⑥ B+ 树页结构与三层 2000 万推导（day15）
【30 分钟排查演练】（模拟故障场景，自问自答）
  场景：接口突然 P99 从 50ms 涨到 2s
  你依次检查什么？（参考答案链：应用日志→线程池满？→慢日志新面孔？
  →EXPLAIN 新 SQL→索引失效/数据量突增→锁等待→Buffer Pool 命中率骤降）
评分：8 项各 1 分，≥6 分=阶段一毕业达标；不足项列出补强 day
```

### 3.4 阶段一知识总图（30 天可视化串珠，贴墙）

```text
month01 语言/JVM：语法→集合→IO→JMM→GC→Arthas/MAT 排查
month02 并发：线程基础→锁→AQS→线程池→CHM/TL→设计模式→实战组件
month03 网络IO+MySQL：
  网络：TCP 状态机→粘包→TIME_WAIT→HTTP→TLS
  IO：五模型→NIO/epoll→Reactor→Netty→手写 RPC
  MySQL：页/B+树→索引四件套→失效十场景→EXPLAIN→优化器/Join
         →慢SQL门诊→事务→MVCC→锁→三大日志→主从→参数容量
  综合：UPDATE 旅程（12 站串联）
★三个月 = 三条主线一张网：写代码(01) + 跑得快(02) + 传得快存得住(03)
```

## 4. 面试连接

**Q：你怎么快速熟悉一个陌生的生产数据库？（巡检六查就是答案）**
> 六查框架：版本架构→资源水位→参数红线→慢日志→大表长事务→锁与错误，半小时内给出"一页纸体检报告"。加分句："我巡检过自己的学习库：发现 BP 才 128MB 默认值、慢日志没开——这是新手的库；生产库我会重点看长事务和锁等待，它们才是突刺的常客。"（真实巡检记录是最好的谈资）

**Q：复合故障（多个问题叠加）你怎么排查？**
> 纪律三则：① 分层拆解——先看最常见的（写法失效）再看低频的（统计信息翻转）；② 单变量原则——一次只修一个点，修一个测一轮（病例 3 演示）；③ 保留基线与现场——改前 EXPLAIN/耗时存档，出问题可回滚可对比。这个回答展示的是排障方法论，比答对具体 SQL 更值钱。

**Q：白板手写能力你怎么练？**
> 三件套方法：① 选经典骨架（协议编解码/动态代理/线程池）；② 脱稿限时写（15 分钟一件）；③ 写完口述运行结果自测（能讲清边界分支=真会）。加分句："我的验收标准是能讲：手写 RPC 我能从代理讲到 2PC 式的 Latch 等待——每一行都能回答为什么。"

## 5. 今日验收清单

- [ ] 巡检六查执行完成，一页纸报告产出
- [ ] 3 病例独立诊断（先做后对），记录你的答案与差距
- [ ] 模拟验收 8 项打分 ≥6（不足项列补强计划）
- [ ] 阶段一知识总图手绘上墙
- [ ] `git add . && git commit -m "day29: inspection + clinic + mock exam"`

---
[← Day 28](day28-一条UPDATE的完整旅程.md) | [本月目录](README.md) | [Day 30 · 月度复盘与 M3 验收 →](day30-月度复盘与M3验收.md)
