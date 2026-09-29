# Day 06 · MapReduce：全班分工数作业本

> **今日目标**：搞懂 MapReduce 的分而治之思想与完整执行流程——**重点啃下 Shuffle**（Map 输出到 Reduce 输入之间那段"重新洗牌"的过程，性能问题一半出在这）。今天手写一个 WordCount 并亲眼看到每一阶段。思想连接：这就是 M8 day11 计数三层聚合（内存累加→定时 flush→落库）的集群版。
> **时长**：MR 思想与流程 2h / 手写 WordCount 1.5h / Shuffle 深挖 1h
> **今日产出**：WordCount 跑通+Shuffle 数据流手绘图 + 每阶段日志对号入座

## 1. 知识地图

```
问题引入：一个 1TB 的文本，要统计每个单词出现几次。一台机器读 1TB 要 3 小时+
，内存也装不下"所有单词→次数"的表。怎么办？——全班分工数作业本：

Map 阶段（拆活：每人负责一摞本子，数出"我这一摞里每个词出现几次"）：
  输入按 128MB 的块切开（day02 的伏笔闭环：块既是存储单元也是计算单元！）
  每个块分配一个 Map 任务：逐行读 → 把每行拆成单词 → 输出 (单词, 1) 这样的键值对
  （类比：50 个同学，每人发 2% 的页数，各自数自己那部分。）

Shuffle 阶段（洗牌：把"同一个单词"从各个同学手里收集到同一个班长手里）：
  这是 MR 的灵魂，也是最常出性能问题的一段，分四步（按数据流动顺序）：
  ① Partition 分区：决定"这个单词该去几号 Reduce"——默认对 key 取 hash 取模
    （保证同一个词一定去同一个 Reduce，否则"和"就加不起来了）
  ② Sort 排序：同一分区内按键排序（相同单词挨在一起，Reduce 好处理）
  ③ Spill 溢写：Map 的结果先写内存缓冲（默认 100MB），写满就"溢写"到磁盘成小文件
  ④ Merge 合并：把多个溢写小文件合并成一个大文件（边合并边归并排序）
  然后 Reduce 端通过 HTTP 把属于自己分区的数据拉（fetch）过去——
  （类比：全班每人把数好的词卡按"词的首字母"放进对应班长的那摞，
    班长收到时卡已经排好序，直接挨个累加。）

Reduce 阶段（汇总：每个班长数完自己那摞，输出最终结果）：
  对同一个 key 的所有值做聚合：(word, [1,1,1,...]) → (word, 3)
  每个 Reduce 输出一个结果文件（按分区），合起来就是全量答案。

为什么 MapReduce 慢？（理解了流程自然懂，为 M11 Spark 伏笔）：
  ① Shuffle 要落盘：Map 结果写磁盘 → Reduce 拉取再落盘 → 磁盘 IO 好几轮
  ② 每个任务起一个 JVM：秒级启动开销（任务本身可能就 100ms）
  ③ 中间结果存 HDFS：又是磁盘
  Spark 的回答：中间结果放内存、进程复用——同样的思想快 10 倍+（M11 细讲）

思想连接（本月最重要的"原来如此"时刻）：
  M8 day11 计数：接口内存 LongAdder 累加 → 500ms flush → 异步落库——
  这就是单机版 Map+Reduce：Map=内存累加（局部汇总），Reduce=落库（全局汇总）。
  M8 day13 对账、M9 分布式限流器练手题——"先局部聚合再全局汇总"是贯穿的思想。
  大数据没有新魔法，只是把单机的智慧放大到集群。
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Map | 拆活（每块数据一个任务，各自算局部的账） |
| Reduce | 汇总（同一 key 的局部账合并成总账） |
| Shuffle | 洗牌（Map 输出按 key 重新分发到对应 Reduce 的全过程） |
| Partition | 分区（决定哪个 key 去哪个 Reduce，默认 hash 取模） |
| Spill | 溢写（内存缓冲满了就写磁盘） |
| Combiner | 小聚合（Map 端先"预汇总"一次，减少传输量——可选优化） |
| Speculative Execution | 推测执行（某任务慢，另起一份同时跑，谁先完成用谁） |

## 3. 动手实操：手写 WordCount 并观察各阶段

```java
// learning/bigdata-m10/WordCount.java（MR 原生 API，理解结构用；生产都用 Hive/Spark 写）
public class WordCount {
  public static class TokenizerMapper extends Mapper<Object, Text, Text, IntWritable> {
    private final static IntWritable one = new IntWritable(1);
    private Text word = new Text();
    public void map(Object key, Text value, Context ctx) throws IOException, InterruptedException {
      for (String w : value.toString().split("\\s+"))
        if (!w.isEmpty()) { word.set(w); ctx.write(word, one); }  // 输出 (word, 1)
    }
  }
  public static class IntSumReducer extends Reducer<Text, IntWritable, Text, IntWritable> {
    public void reduce(Text key, Iterable<IntWritable> vals, Context ctx)
        throws IOException, InterruptedException {
      int sum = 0;
      for (IntWritable v : vals) sum += v.get();   // (word,[1,1,1]) → (word,3)
      ctx.write(key, new IntWritable(sum));
    }
  }
  public static void main(String[] a) throws Exception {
    Job job = Job.getInstance(new Configuration(), "wc");
    job.setJarByClass(WordCount.class);
    job.setMapperClass(TokenizerMapper.class);
    job.setCombinerClass(IntSumReducer.class);   // 加分项：Map 端预聚合
    job.setReducerClass(IntSumReducer.class);
    job.setOutputKeyClass(Text.class); job.setOutputValueClass(IntWritable.class);
    FileInputFormat.addInputPath(job, new Path(a[0]));
    FileOutputFormat.setOutputPath(job, new Path(a[1]));
    System.exit(job.waitForCompletion(true) ? 0 : 1);
  }
}
```

```powershell
# 编译打包（在 Hadoop 容器内跑，容器自带 hadoop classpath）
docker cp WordCount.java hadoop-single:/opt/
docker exec hadoop-single bash -c "cd /opt && mkdir -p wc_classes && `$(hadoop classpath | head -c 0; javac -classpath `$(hadoop classpath) -d wc_classes WordCount.java) && jar -cf wc.jar -C wc_classes ."
# 造测试数据：把 day04 的 1 万小文件先 getmerge 成大文件（顺手复习治理）
docker exec hadoop-single bash -c "hdfs dfs -getmerge /smallfiles-merged /tmp/wc-input.txt; hdfs dfs -put /tmp/wc-input.txt /wc-in/"
# 跑任务（跑在 YARN 上，day08 讲调度；本地模式看结果先）
docker exec hadoop-single bash -c "hadoop jar /opt/wc.jar WordCount /wc-in /wc-out"
docker exec hadoop-single bash -c "hdfs dfs -cat /wc-out/part-* | head -10"
# 打开 http://localhost:8088 看 YARN 上的作业记录 → 点进去看 Counters：
# Map input records / Spilled Records / Combine input records——每个概念对上日志数字
# 加分实验：注释掉 setCombiner 重跑，对比 Counters 里 "Combine input records" 与运行时长
```

## 4. 面试连接

**Q：讲讲 MapReduce 的工作原理，Shuffle 具体发生了什么？（必考，白板画图）**
> 总思想是分而治之：Map 阶段每个数据块起一个任务，逐行处理输出"键值对"形式的局部结果；Reduce 阶段把相同 key 的结果归并汇总。中间那段就是 Shuffle，按数据流四步：先 Partition——对 key 取 hash 决定去哪个 Reduce，保证同一个 key 只去一个地方，这是"能加起来"的前提；然后 Sort——分区内按键排序，相同 key 挨在一起；Map 侧还有 Spill——结果先写 100MB 内存缓冲，满了就溢写成磁盘小文件；最后 Merge——多个溢写文件归并成一个大文件，Reduce 端再通过 HTTP 拉取属于自己分区的部分。Shuffle 是 MR 慢的根源：中间数据要落盘、Reduce 拉取再落盘，好几轮磁盘 IO，所以才有 Spark 用内存替代的演进。两个优化必须会提：Combiner——Map 端先预聚合一次，比如本地先数好"the:500"再传输，网络量少几个数量级；Speculative Execution——某台机器任务跑得慢（可能是硬盘坏道），就另起一份同样的任务两边同时跑，谁先完成用谁——用资源换尾延迟。

**Q：WordCount 里如果把 Combiner 去掉，结果还对吗？性能差多少？**
> 结果仍然对——Combiner 是可选优化，逻辑上等价于把 (w,[1,1,1]) 改成传输前先加成 (w,3)，加法满足结合律所以不影响最终求和；但如果聚合逻辑不满足结合律（比如求平均数），Combiner 就不能随便当 Reducer 用，要单独设计。性能差距取决于数据倾斜程度：我实验里 1 万个文件合并出的输入，"small"这种词占了绝对多数，加 Combiner 后 Shuffle 写出字节量降了 95%+，整体运行时间从分钟级压到秒级（单机伪分布式）。生产经验是：能加 Combiner 的任务一律加，这是零成本的优化——代价只是要求聚合函数满足结合律。这个细节和 M8 计数设计相通：接口层 LongAdder 内存聚合（本地预聚合）→ 异步批量落库（全局汇总），同一个"先局部后全局"的形状。

## 5. 今日验收清单

- [ ] MR 三阶段+Shuffle 四步白板可画（数据流动方向标注清楚）
- [ ] WordCount 跑通，Counters 里找到 Spill/Combine 对应数字
- [ ] Combiner 加/不加对比实验完成（字节量与时长都有对比数）
- [ ] "MR 为什么慢"三点能讲（为 M11 Spark 伏笔）
- [ ] M8 计数三层 ↔ MR 分而治之的同构关系能说清
- [ ] `git add . && git commit -m "day10-06: mapreduce"`

---
[← Day 05](day05-HDFS实操与运维速查.md) | [本月目录](README.md) | [Day 07 · 第一周复盘 →](day07-第一周复盘.md)
