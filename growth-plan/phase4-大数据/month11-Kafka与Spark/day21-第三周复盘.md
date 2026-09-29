# Day 21 · 第三周复盘：Spark Core 与 SQL 的"六大件"

> **今日目标**：把 W3 六天（RDD 算子/宽窄依赖/持久化/Shuffle/内存/SQL 优化）串成一条完整故事线；12 题自测摸底；产出《Spark 作业调优 Checklist v1》——下周项目周直接照着用。
> **时长**：串讲 1h / 自测 1.5h / 清单成文 0.5h
> **今日产出**：《Spark 作业调优 Checklist v1》+ 六大件关系图

## 1. 六线串讲（先讲人话版故事，再看术语）

```
一个 Spark 作业从出生到跑完的完整故事：

第 1 站【day15 RDD 与算子】
  你写的是"菜谱"（转换算子），不是"炒菜动作"（行动算子才下锅）
  ——惰性求值：不遇到 count/collect，Spark 一根线都不跑
  ——血缘 lineage：每步怎么来的都有记账，分区丢了能沿账本重算（弹性真义）

第 2 站【day16 宽窄依赖与 Stage】
  窄依赖=独家供货（一个分区喂一个分区，流水线不散伙）
  宽依赖=全厂调货（所有分区寄快递重分配，快递站=Shuffle）
  ——Stage 切分规则：遇到宽依赖就断开；Task 数=最后一个 Stage 的分区数

第 3 站【day17 持久化与广播】
  同一条链被多个 Action 用 → cache（灶台上放一下，别回锅重炒）
  血缘太长怕重算失控 → checkpoint（存档室永久保存，账本从头记）
  1 万个 Task 都要同一份维表 → broadcast（全车间贴一张，别一人发一本）

第 4 站【day18 Spark Shuffle】
  快递站两阶段：Write（寄件方按收件地址分拣落盘）+ Read（收件方拉取）
  reduceByKey vs groupByKey：寄之前先打包（Map 端预聚合）能省一半运费
  ——三代演进：M×R→Sort→Bypass（<200 分区免排序），思想=按场景降级

第 5 站【day19 内存与资源】
  统一内存=共享冰箱：Execution（工作台）和 Storage（货架）互借，
  回收不对称（货架随时能清，工作台干活中不能停）
  OOM 三步排查：看现场→对号入座（大记录/大分区/大广播）→动参数
  OOMKilled=堆外无声死亡，补 memoryOverhead 不是无脑加堆

第 6 站【day20 Spark SQL 与 Catalyst】
  DataFrame=带表头的 RDD，引擎看得懂你的意图才会优化
  五站流水线：解析→绑定→Catalyst 改写→选物理算子→执行
  三大优化：谓词下推/列裁剪/常量折叠（EXPLAIN 全有证据）

串联理解：六站就是"菜谱怎么写→在哪断开→怎么备菜→快递怎么寄→
厨房多大→总厨师长怎么改菜谱"。写坏任何一站，作业就慢/就炸。
```

## 2. 12 题自测（先自己答，再翻回对应 day 校对）

```
1. 转换算子和行动算子的区别？惰性求值带来什么好处？（day15）
2. 血缘（lineage）是什么？"弹性"到底弹在哪？（day15）
3. 窄依赖和宽依赖的判据？Stage 怎么切？（day16）
4. Task 数由什么决定？并行度怎么设？（day16）
5. cache 和 checkpoint 的五个维度对比？（day17）
6. 广播变量为什么省网络？什么场景会失效？（day17）
7. 累加器为什么不能当业务账本？（day17）
8. reduceByKey 比 groupByKey 快在哪？（day18）
9. 统一内存两格互借+回收不对称讲一遍？（day19）
10. Executor OOM 三步排查+三大根因？（day19）
11. 谓词下推在 Hive 和 Spark 里各发生在哪一层？（day20，跨月题）
12. 一条 SQL 的五站旅程+Catalyst 三大优化？（day20）

评分规则：能脱稿讲+带实验证据=通过；只能背概念=回炉对应 day
```

## 3. 《Spark 作业调优 Checklist v1》（项目周直接用）

```
□ 提交前：shuffle.partitions 设了吗？（默认 200 是摆设，按数据量/核数算）
□ 提交前：小表会被广播吗？（autoBroadcastJoinThreshold 检查，day23 细讲）
□ 提交前：executor-memory + memoryOverhead 在 YARN 队列配额内吗？（day19 公式）
□ 运行中：UI Stragglers 页有没有"一枝独秀"的超长 Task？（倾斜信号，day24）
□ 运行中：Shuffle Spill (disk) 大不大？（内存不够在落盘，加内存或加分区）
□ 运行中：GC Time 占比 >15% 了吗？（内存结构有问题，先查缓存策略）
□ 结果后：cache 的中间结果 unpersist 了吗？（别让货架白占冰箱）
□ 结果后：Storage 页缓存命中了吗？（缓存失效=白缓存，还占资源）
```

## 4. 面试连接

**Q：你做 Spark 调优的思路是什么？（开放题，检验体系化）**
> 我按"先诊断、后开药"三步走：第一步看 Spark UI 定位瓶颈在哪一层——Stage 耗时集中在 Shuffle 就是 day18 的问题（加分区/预聚合/换算子）；个别 Task 超长就是倾斜（day24 治理）；GC 时间占比高就是内存结构问题（day19 查缓存策略和 executor 配置）。第二步对照清单逐项排：分区数、广播、内存、缓存四类参数该设的都设了吗？第三步才算动刀改代码：换算子（reduceByKey 替 groupByKey）、加 cache、补 checkpoint。我在项目里验证过：同一个聚合作业，只调 shuffle.partitions 和广播阈值，不改一行业务代码，耗时能降一半——调优 80% 的工作在"看 UI 诊断"，不在"背参数"。

## 5. 今日验收清单

- [ ] 六大件关系图能脱稿讲一遍（菜谱→断开→备菜→快递→厨房→改菜谱）
- [ ] 12 题自测完成，错题回炉对应 day
- [ ] 《Checklist v1》已存档（下周项目周开工第一件事就是过一遍）
- [ ] 能讲出"调优先诊断后开药"的三步思路
- [ ] `git add . && git commit -m "day11-21: week3-review"`

---
[← Day 20](day20-SparkSQL与Catalyst.md) | [本月目录](README.md) | [Day 22 · AQE 自适应执行 →](day22-AQE自适应执行.md)
