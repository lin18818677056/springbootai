# Day 11 · Kafka Streams 初识：不起集群的流式小引擎

> **今日目标**：认识 Kafka 自带的流计算库 Streams——不用搭任何新集群，一个 jar 包让普通 Java 应用变成流处理程序。掌握 KStream/KTable 两种抽象和三种窗口；同时搞清它和 M12 要学的 Flink 的定位差异（学完就知道什么时候"杀鸡不用牛刀"）。
> **时长**：抽象与模型 1.5h / WordCount 实操 2h / 与 Flink 对比 1.5h
> **今日产出**：可运行的 Streams WordCount + 《Streams vs Flink 选型卡》

## 1. 知识地图

```
Streams 是什么？——"Kafka 自带的便携搅拌机"
  不是集群、不是服务，是一个客户端库（kafka-streams jar）：
  你的普通 Java 应用引入它 → 它内部起"隐藏的消费组"拉数据 → 加工 → 写回 Kafka
  扩容 = 多起几个你的应用实例（Kafka 消费组自动分担分区——day04 的知识直接复用！）
  容错 = 状态存在 Kafka 的 Compact Topic 里（changelog），实例挂了状态可恢复
  —— 三句话全是你学过的机制：消费组/Compact/状态外置，没有新魔法

两种核心抽象（先说人话）：
  KStream：事件流——"一条是一条"：每条消息独立处理（点击流水：每次点击都算）
  KTable：状态表——"同名只留最新"：按 key 取最新值（用户表：同一人只关心最新状态）
    —— KTable 的底层就是 Compact Topic（day10 通讯录），概念完全对上
  两者的 join：点击流(KStream) join 用户表(KTable) = 每条点击补上用户信息
    （流×表 join 是流计算最常用的形状）

三种窗口（给"无界流"切出边界好聚合）：
  滚动窗口 Tumbling：不重叠的固定段（每 5 分钟一算）——报表粒度
  滑动窗口 Sliding：重叠的滑块（每分钟算一次"最近 10 分钟"）——监控均值
  会话窗口 Session：不固定的gap切分（同一用户 30 分钟内的行为算一次会话）——用户行为
  （类比：滚动=课程表切课；滑动=每隔一小时看"过去 24 小时"监控屏；会话=看聊天记录
    间隔超 30 分钟就算新对话）

与 Flink 的定位差异（M12 预告，先给结论）：
  Streams：轻——"已经有 Kafka，想顺手做点加工"（过滤/补全/轻聚合）
  Flink：重——真流式引擎（事件时间/水位线/超大状态/exactly-once 全家桶）
  杀鸡别用牛刀：日均千万级消息的简单加工，Streams 一天接完；
    亿级实时大屏+复杂窗口+大状态，上 Flink
```

## 2. 核心概念（中英对照+大白话）

| 概念 | 大白话解释 |
|------|------|
| Kafka Streams | 流计算库（jar 包形态，普通应用变身流处理） |
| KStream | 事件流（一条是一条，各自独立） |
| KTable | 状态表（同名只留最新，Compact 语义） |
| Topology | 处理拓扑（源→处理→汇的数据流图） |
| State Store | 状态存储（聚合中间结果，落在 RocksDB/内存+changelog 备份） |
| Window | 窗口（给无界流切段聚合：滚动/滑动/会话） |

## 3. 动手实操：Streams WordCount（本地 Java 直连容器 Kafka）

```java
// ===== 工程：IDEA 新建 Maven 项目，依赖如下（pom.xml 片段）=====
// <dependency>
//   <groupId>org.apache.kafka</groupId>
//   <artifactId>kafka-streams</artifactId>
//   <version>3.7.0</version>
// </dependency>
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;
import java.util.Properties;
import java.time.Duration;

public class StreamWordCount {
    public static void main(String[] args) {
        Properties p = new Properties();
        p.put(StreamsConfig.APPLICATION_ID_CONFIG, "wc-demo");   // 就是消费组名
        p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        p.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        p.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

        KStream<String, String> lines = new StreamsBuilder()
            .stream("wc-input");                                 // 源：读 Topic
        lines.flatMapValue(v -> List.of(v.toLowerCase().split("\\W+")))
             .groupBy((k, w) -> w)                               // 按"单词"当 key 分组
             .windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofSeconds(10), Duration.ZERO))
             .count()                                            // 10 秒滚动窗口计数
             .toStream()
             .map((wk, c) -> KeyValue.pair(wk.key(), c.toString()))
             .to("wc-output");                                   // 汇：写回 Topic
        KafkaStreams streams = new KafkaStreams(
            new StreamsBuilder().build(), p);   // 实际工程里 topology 与上面 builder 复用
        streams.start();                                         // 起飞（Ctrl-C 停）
    }
}
// ===== 运行与验证 =====
// 1. docker exec kafka kafka-topics.sh --create --topic wc-input --partitions 1 ...
// 2. 本地启动 StreamWordCount（IDEA 直接跑，连 localhost:9092）
// 3. 控制台向 wc-input 发 "hello kafka hello spark"
// 4. 另开终端消费 wc-output：看到 hello:1/2 kafka:1 spark:1（10 秒窗口内累计）
// 观察点：KTable 语义的 count 状态在更新而不是重算；停掉应用再起——计数继续（状态恢复）
```

## 4. 面试连接

**Q：Kafka Streams 和 Flink 怎么选？（M12 预告题，本月先立框架）**
> 我按三个维度给结论。第一，架构形态：Streams 是库（jar 包），嵌在你的应用里，部署就是部署应用本身，扩容靠多开实例走消费组分担——运维成本约等于零；Flink 是独立集群（JobManager/TaskManager），有自己的资源调度、检查点机制、Web UI——能力上限高但运维成本实打实。第二，语义能力：Streams 覆盖基础流处理（过滤/映射/轻聚合/简单窗口），时间语义以处理时间为主，大状态和复杂事件时间是短板；Flink 是真流式引擎——事件时间+水位线治乱序、增量检查点扛 TB 级状态、端到端 exactly-once 开箱即用。第三，适用规模：日均千万级消息的"清洗+补全+告警"场景，Streams 一两天就能上线，杀鸡不用牛刀；日均十亿级、需要复杂窗口/大状态/严格一次的项目，直接 Flink。我的选型卡写着：已有 Kafka 且加工逻辑简单→Streams；独立流计算平台/实时数仓引擎→Flink（M12 会把这个结论用项目验证一遍）。这个"按规模和复杂度分层"的思路，和我在 M8 说的"先问要不要治"是同一种工程克制。

**Q：KStream 和 KTable 有什么区别？各举一个使用场景？（概念题）**
> 差别就一句话：KStream 里每条记录是独立事件，KTable 里同 key 的记录是"更新"，只保留最新值——KTable 的存储底层就是 Compact Topic（day10 通讯录），概念完全对上。场景对比：统计"每 10 秒每个页面的点击次数"用 KStream——每次点击都是独立事件，来一次算一次；维护"每个用户的最新会员等级"用 KTable——同一用户的新等级就是更新，旧值自然被覆盖。两者可以互相转换（stream 转 table 是按 key 取最新，table 转 stream 是把每个变更当事件发出），更强大的是 join：点击流（KStream）join 用户表（KTable），每条点击补上该用户当前的信息——这是流计算里最常用的形状，本质是"事件补维度"。我项目里的埋点加工就是这么做的：page_view 流 join dim_user 表，先过滤再补维度再写回——一个 Streams 应用十几行，替代了原来一个手动消费+查库+写回的三段式代码。

## 5. 今日验收清单

- [ ] Streams"三句机制复用"（消费组/Compact/状态外置）能讲
- [ ] KStream vs KTable + 流表 join 场景能说清
- [ ] 三种窗口（滚动/滑动/会话）类比+选场景
- [ ] WordCount 跑通（窗口计数+重启状态恢复观察）
- [ ] 《Streams vs Flink 选型卡》成文（三维度结论）
- [ ] `git add . && git commit -m "day11-11: streams"`

---
[← Day 10](day10-Kafka运维实战.md) | [本月目录](README.md) | [Day 12 · Spark 全景 →](day12-Spark全景.md)
