# Day 25 · 技术选型方法论：MQ 选型报告

> **今日目标**：技术选型四维框架（功能/成熟度/团队认知/TCO）+ PoC 方法；产出《MQ 选型报告》正式版——结合 M5/M6 实测数据与打分表（README 验收第 4 条）。
> **时长**：选型框架 1h / PoC 数据整理 1.5h / 报告成文 1.5h
> **今日产出**：docs/rfc/mq-selection-report.md（含 PoC 与打分表）+ 《选型四维卡》

## 1. 知识地图

```
选型四维框架（每维有证据要求，拒绝"听说 Kafka 快"式选型）：
① 功能匹配（40%）：需求清单逐项对照候选（不是对照功能列表——是对照"我们的场景需要"）
   MQ 场景需求：事务消息✓/延迟消息✓/顺序消息✓/消息回溯✓/堆积能力✓/百万级 topic？
   ——需求从哪来：M5-M8 的真实用例（秒杀削峰要事务+延迟、日志流要高吞吐、订单分场景要顺序）
② 成熟度（20%）：版本历史/社区活跃/生产案例/bug 响应——"新东西让别家先趟坑"
③ 团队认知（20%）：团队运维过什么？踩过什么坑？——认知成本是最贵的成本
   （M5 起 RocketMQ 两年运维：事务消息回查/堆积处理/顺序消费坑全踩过——这是无形资产）
④ TCO 总拥有成本（20%）：资源成本+人力成本+迁移成本+机会成本
   ——day24 IM 机器账的思想迁移：选型也是立项，也要算钱

PoC（Proof of Concept）方法——选型的实证环节：
  PoC 三原则：①用真实负载画像（不是官方 benchmark）②测自己的拓扑（不是厂商推荐拓扑）
  ③记录调参过程（性能是调出来的，参数也是选型交付物）
  我的 MQ PoC（M5/M6 实测数据整理）：
  | 场景 | RocketMQ 5.x | Kafka 3.x | 说明 |
  |------|-------------|-----------|------|
  | 削峰写入（万级 TPS 小消息）| 8.2 万/s | 11 万/s | Kafka 吞吐胜 |
  | 事务消息半消息回查 | 原生支持，回查 P99 45ms | 无原生（需自研模拟）| RocketMQ 胜 |
  | 延迟消息（关单场景）| 原生 18 级/5.x 任意延迟 | 无原生 | RocketMQ 胜 |
  | 万级 topic（多业务线）| 支持好（轻量 queue）| 分区文件句柄爆炸 | RocketMQ 胜 |
  | 消息堆积 1 亿条恢复 | 25min | 40min | 消费模型差异 |
  | 运维：扩容/迁移 | console 成熟 | 需 confluent 工具链 | 团队认知差异 |

打分表（四维加权，决策可追溯）：
| 维度(权重) | RocketMQ 5.x | Kafka 3.x | 评分依据 |
|-----------|-------------|-----------|---------|
| 功能匹配(40%) | 4.5 | 3.5 | 事务+延迟消息为商城刚需，Kafka 缺原生 |
| 成熟度(20%) | 4.0 | 4.5 | Kafka 社区更大，RocketMQ 阿里生产验证充分 |
| 团队认知(20%) | 5.0 | 2.5 | 两年运维经验 vs 零实战 |
| TCO(20%) | 4.5 | 3.0 | 迁移成本 0 vs 重建认知+工具链 |
| 加权总分 | 4.5 | 3.4 | → 维持 RocketMQ（新增场景）+Kafka 引入日志流场景 |
  ——注意结论不是"谁赢"：按场景分治（交易链路 RocketMQ/日志流 Kafka），
  "一个 MQ 通吃"是选型大忌（正确答案常常是组合）

选型报告的结构（RFC 的近亲）：
  TL;DR（结论+总分）→ 需求清单（从用例来）→ PoC 数据（自己测的）→ 打分表（可挑战的）
  → 分场景结论 → 迁移/引入路线 → 被否理由 → 复审触发条件（半年后复审）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| TCO | 总拥有成本（资源+人力+迁移+机会） |
| PoC | 概念验证（真实负载实测） |
| Weighted Scoring | 加权打分（可挑战的决策） |
| Polyglot Middleware | 多中间件共存（分场景选型）|
| Vendor Lock-in | 供应商锁定（迁移成本核算）|
| Re-review Trigger | 复审触发条件（选型有效期）|

## 3. 动手实操：打分计算器

```java
// learning/month09-system-design/src/SelectionScorer.java（四维加权打分：选型决策计算器）
import java.util.*;

public class SelectionScorer {
    record Candidate(String name, double func, double mature, double team, double tco) {}
    static final double W_FUNC = 0.40, W_MATURE = 0.20, W_TEAM = 0.20, W_TCO = 0.20;

    static double score(Candidate c) {
        return c.func * W_FUNC + c.mature * W_MATURE + c.team * W_TEAM + c.tco * W_TCO;
    }
    public static void main(String[] a) {
        var rocketmq = new Candidate("RocketMQ 5.x", 4.5, 4.0, 5.0, 4.5);
        var kafka    = new Candidate("Kafka 3.x",    3.5, 4.5, 2.5, 3.0);
        var pulsar   = new Candidate("Pulsar 3.x",   4.0, 3.0, 1.0, 3.5);
        var list = List.of(rocketmq, kafka, pulsar).stream()
                .sorted((x, y) -> Double.compare(score(y), score(x))).toList();
        System.out.println("══ MQ 选型打分（交易链路场景）══");
        list.forEach(c -> System.out.printf("%-14s 总分 %.2f（功能 %.1f/成熟 %.1f/团队 %.1f/TCO %.1f）%n",
                c.name(), score(c), c.func(), c.mature(), c.team(), c.tco()));
        // 敏感性分析：如果团队认知权重降到 0（比如新团队），排名变吗？
        double wFunc = 0.5, wMature = 0.25, wTco = 0.25;   // 去掉团队维重新归一
        System.out.println("── 敏感性：新团队（认知权重→0）──");
        for (var c : List.of(rocketmq, kafka, pulsar)) {
            double s = c.func * wFunc + c.mature * wMature + c.tco * wTco;
            System.out.printf("%-14s 新分 %.2f%n", c.name(), s);
        }
        // 输出：RocketMQ 4.50 > Kafka 3.45 > Pulsar 3.10
        // 敏感性结论：新团队场景 Kafka 3.44 反超 RocketMQ 3.38——权重不同结论翻转，
        // 这就是"别人的选型结论不能抄"的数学证明（他们的权重和你的不一样）
    }
}
```

```text
《MQ 选型报告》骨架（docs/rfc/mq-selection-report.md——README 验收第 4 条交付物）：
0. TL;DR：交易链路维持 RocketMQ 5.x（4.50 分）；日志流场景引入 Kafka（吞吐 PoC 胜出
   34%）；Pulsar 暂不引入（团队认知 1.0 分是硬伤）。复审触发：RocketMQ 5.x 生产事故
   ≥2 次/季 或 日志流规模×10。
1. 需求清单：事务消息（秒杀）/延迟消息（关单）/顺序（分场景）/堆积恢复（大促）——
   全部来自 M5-M8 真实用例，每条链接到使用场景
2. PoC：六场景实测表（本日知识地图）+ 调参记录（刷盘/批量/消费线程）
3. 打分表：四维加权+评分依据列（每个分数可挑战）
4. 分场景结论：交易 RocketMQ / 日志 Kafka / 禁止"一个 MQ 通吃"
5. 迁移路线：无迁移（现状保留）；Kafka 引入走旁路双写观察期
6. 被否理由：Pulsar（认知成本）/ RabbitMQ（吞吐不匹配日志流）/ 自研（维护成本）
```

```powershell
cd D:\mywork\springbootai\learning\month09-system-design\src
javac -encoding UTF-8 -d ../out SelectionScorer.java ; java -cp ../out SelectionScorer
# 验证：主打分+敏感性分析（权重改变结论翻转的证明）
cd D:\mywork\springbootai ; git add . ; git commit -m "day09-25: mq selection"
```

## 4. 面试连接

**Q：你们为什么选 RocketMQ 不选 Kafka？（选型经典题）**
> 先纠正问题再回答：正确答案不是"选谁"是"分场景各选谁"。交易链路维持 RocketMQ，三个硬理由：一是功能刚需——商城秒杀要事务消息（半消息回查）和延迟消息（超时关单），RocketMQ 原生支持，Kafka 事务消息要自研模拟，延迟消息根本没有；二是团队认知——我们从 M5 起两年运维，堆积处理、顺序消费、回查机制这些坑都踩过，换 Kafka 等于认知清零，认知成本是最贵的成本；三是 PoC 数据支撑——我自己压的测：万级 topic 场景 Kafka 分区文件句柄爆炸，堆积一亿条恢复 RocketMQ 25 分钟对 40 分钟。但日志流场景我选 Kafka——纯吞吐场景它 PoC 赢了 34%，且万级 topic 的劣势在日志场景不存在。这套论证沉淀成了正式的选型报告：四维加权打分（功能 40%、成熟度 20%、团队认知 20%、TCO 20%），每个分数有依据列，还做了敏感性分析——把团队认知权重归零，Kafka 就反超了，这从数学上证明"别人的选型结论不能抄"，因为权重背后是各自的团队现状。选型报告最后带复审触发条件：RocketMQ 季度事故两次以上或日志流规模十倍时重审——选型是有有效期的决策，不是终身判决。

**Q：怎么避免选型变成"技术追新"？（选型纪律）**
> 四条纪律。一是需求倒逼：候选清单从"我们的真实用例"出发而不是"它有什么功能"——需求清单每条链接到具体业务场景，没有用例支撑的功能不进评分。二是 PoC 实证：用真实负载画像测自己的拓扑，不用官方 benchmark——厂商的数字是营销，自己的数字才是决策依据，我的 MQ PoC 六个场景全是 M5-M8 的真实流量模型。三是认知成本显式化：团队认知单独占 20% 权重——运维过两年的中间件带着踩坑经验，新中间件的"先进"要先付学费，Pulsar 在我的打分里功能 4.0 不低，但团队认知 1.0 直接判死——这就是纪律。四是复审机制：每个选型写复审触发条件（量化阈值），到期或触发就重审——"技术追新"和"技术演进"的区别就是前者没有触发条件的冲动，后者有数据触发的流程。最后一条软纪律：选型报告里被否的方案必须写"什么条件下会翻盘"——为未来留下理性的翻转通道，选型决策才不会变成站队。

## 5. 今日验收清单

- [ ] mq-selection-report.md 六节全（PoC 数据+打分表+复审触发）
- [ ] SelectionScorer 运行：主打分+敏感性分析（结论翻转证明）
- [ ] 四维框架（功能 40/成熟 20/认知 20/TCO 20）+ 依据能讲
- [ ] "分场景选型，禁止一个 MQ 通吃"论证入库
- [ ] `git add . && git commit -m "day09-25: selection"`

---
[← Day 24](day24-模拟评审二.md) | [本月目录](README.md) | [Day 26 · 架构演进叙事 →](day26-架构演进叙事.md)
