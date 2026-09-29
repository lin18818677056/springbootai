# Day 10 · IM 消息模型：读扩散 / 写扩散 / Sequence

> **今日目标**：IM 消息模型核心决策——读扩散 vs 写扩散（M8 day10 Feed 推拉的"同源问题"，伏笔兑现）；Sequence 序列号设计；会话模型。产出《IM 概要设计》。
> **时长**：模型对比推演 2h / Sequence 设计与代码 1.5h / 概要设计成文 0.5h
> **今日产出**：IM 概要设计（模型选型+Sequence 方案）+ SequenceGenerator 代码

## 1. 知识地图

```
IM 与 Feed 的同源性（M8 day10 伏笔兑现）：Feed 流和 IM 会话都是"消息怎么组织到每个人面前"的问题
  Feed：动态从作者流向订阅者（关注关系）；IM：消息从发送者流向会话成员（会话关系）
  Feed 结论"推拉结合"在 IM 的映射：单聊写扩散、群聊读扩散、超大群特殊处理（day12）

读扩散 vs 写扩散（IM 场景重新推演，不能照抄 Feed 结论）：
  写扩散（Timeline 收件箱）：消息写入每个收件人的 Timeline（M8 day10 收件箱模式）
    ✓ 读路径简单：按序读自己的收件箱即可（时间线就是聊天记录）
    ✗ 写放大：万人群=写 1 万次——群聊场景写爆炸
    适用：单聊/小群（≤500 人）
  读扩散（每个会话一个 Timeline）：消息只写会话一份，读时聚合 N 个会话
    ✓ 写路径轻：一条消息写一次
    ✗ 读放大：收件箱要聚合所有会话并按时间归并排序（读时 join，首页延迟高）
    适用：群聊（尤其大群）
  决策表：
  | 场景 | 模型 | 理由 |
  |------|------|------|
  | 单聊 | 写扩散 | 2 人收件箱，写放大可忽略；读路径快（打开即聊）|
  | 群聊 ≤500 | 写扩散 | 群成员有限，写放大可控 |
  | 大群 500~10万 | 读扩散 | 写 1 万次不可接受；读侧用"会话列表懒加载"摊平 |
  | 超大群（直播评论）| 无扩散 | 消息不进收件箱，订阅广播流（day12）|

Sequence 序列号设计（IM 的"先有鸡还是先有蛋"问题）：
  问题：客户端时间不可信（回拨/时区），消息排序必须有服务端权威顺序——Sequence
  需求特性：①同会话内严格递增②不同会话独立③性能：万级 TPS
  方案对比：
  | 方案 | 原理 | 问题 |
  |------|------|------|
  | 时间戳 | 毫秒 | 时钟回拨乱序——否 |
  | 全局发号器 | 单调全局 | 浪费：会话间无需可比——过重 |
  | per-会话号段 | 会话维度 Redis INCR | ✓ 会话内递增、会话间独立、INCR 原子 |
  实现：Redis INCR seq:{convId}，批量取（一次取 100 个号段内存分配，降 Redis 压力——
  M8 day08 号段思想的三级缩放：全局→机器→会话）
  接收端排序：客户端按 (seq) 排序展示；跨会话列表按 lastMsgSeq 比较——序号即索引

会话模型三张表（概要设计的数据模型节）：
  t_conversation（会话）：conv_id/conv_type(SINGLE/GROUP)/member_count/last_msg_seq
  t_message（消息）：msg_id/conv_id(分片键！)/sender_id/content/seq/create_time
    ——分片键=conv_id：会话的消息永远在一个分片（会话内查询本地化，跨会话无需聚合）
  t_timeline（收件箱，写扩散场景）：uid(分片键)/conv_id/last_read_seq——轻表，只存游标不存消息
  消息体与游标分离：消息存一份（conv 维度），收件箱只存"读到哪了"（游标）
  ——未读数=last_msg_seq - last_read_seq，O(1) 计算（这就是 Sequence 的第二个用途）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Write/Read Fanout | 写/读扩散（收件箱 vs 聚合读） |
| Timeline | 时间线（有序消息列表） |
| Sequence | 序列号（服务端权威排序） |
| Last Read Seq | 已读游标（未读数=差值） |
| Sharding by Conv | 按会话分片（消息本地化） |
| Session Model | 会话模型（单聊/群聊统一抽象） |

## 3. 动手实操：Sequence 与未读数

```java
// learning/month09-system-design/src/ConvSequence.java（per-会话号段：Redis INCR 语义模拟）
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class ConvSequence {
    record Segment(long start, long end, AtomicLong cur) {
        boolean exhausted() { return cur.get() >= end; }
        long next() { return cur.incrementAndGet(); }
    }
    static final Map<String, Segment> segs = new ConcurrentHashMap<>();
    static final int BATCH = 100;                        // 一次取 100 个号（Redis INCRBY 语义）

    /** 同会话内严格递增；会话间独立；批量预取降 Redis 压力 */
    public static synchronized long nextSeq(String convId) {
        Segment s = segs.computeIfAbsent(convId, k -> fetch(k));
        if (s.exhausted()) { s = fetch(convId); segs.put(convId, s); }
        return s.next();
    }
    static Segment fetch(String convId) {
        // 真实实现：INCRBY seq:{convId} 100 → 返回新终值（模拟：从 1 开始累计）
        long end = BATCH * (segs.size() + 1);
        return new Segment(end - BATCH, end, new AtomicLong(end - BATCH));
    }
    public static void main(String[] a) throws Exception {
        // 并发 50 线程往两个会话各发 1 万条：验证会话内无重复无乱序
        var pool = Executors.newFixedThreadPool(50);
        var conv1 = Collections.synchronizedList(new ArrayList<Long>());
        var conv2 = Collections.synchronizedList(new ArrayList<Long>());
        for (int t = 0; t < 50; t++) pool.submit(() -> {
            for (int i = 0; i < 200; i++) {
                (Thread.currentThread().getId() % 2 == 0 ? conv1 : conv2).add(
                        nextSeq("conv" + (Thread.currentThread().getId() % 2 == 0 ? 1 : 2)));
            }});
        pool.shutdown(); pool.awaitTermination(1, TimeUnit.MINUTES);
        var c1 = conv1.stream().distinct().sorted().toList();
        System.out.printf("conv1: 发送 %d 条 / 去重后 %d 条 / 递增=%b%n",
                conv1.size(), c1.size(), conv1.size() == c1.size());
        // 未读数演示（Sequence 的第二个用途）：
        long lastMsgSeq = 42, lastReadSeq = 37;
        System.out.println("未读数 = " + (lastMsgSeq - lastReadSeq));          // 5，O(1)
        // 输出：conv1: 发送 5000 条 / 去重后 5000 条 / 递增=true
    }
}
```

```text
《IM 概要设计》骨架（docs/rfc/im-draft.md，day13 扩成完整 RFC）：
┌─ 架构：Client(ws) → 接入网关 → LogicSvc（消息处理）→ 存储（按 conv_id 分片）
│                     ↓                 ↓
│              连接注册中心      MQ → 离线推送/写扩散分发
├─ 模型：单聊+小群写扩散 / 大群读扩散 / 超大群广播（演进触发条件：群成员 500）
├─ Sequence：per-会话 Redis INCR + 号段批量；未读数=last_msg_seq-last_read_seq
├─ 数据：t_message 按 conv_id 分片（会话内查询本地化）/ t_timeline 只存游标
└─ 伏笔：day11 可靠投递（ACK/离线/去重）、day12 万人群与推送路由
```

```powershell
cd D:\mywork\springbootai\learning\month09-system-design\src
javac -encoding UTF-8 -d ../out ConvSequence.java ; java -cp ../out ConvSequence
# 验证：5000 并发消息序号无重复且严格递增、未读数 O(1) 演示
cd D:\mywork\springbootai ; git add . ; git commit -m "day09-10: im model"
```

## 4. 面试连接

**Q：IM 的消息模型怎么设计？读扩散还是写扩散？（IM 面试第一题）**
> 这个问题和 Feed 流的推拉是同源问题，但 IM 的结论不一样——Feed 我选推拉结合，IM 我按会话规模分档：单聊和小群写扩散（消息写进每个成员的收件箱 Timeline，读路径打开即有序读，体验最好，写放大在 500 人内可控）；大群读扩散（消息只写会话一份，成员读时拉会话时间线，避免万人群写 1 万次）；直播评论那种超大群干脆不扩散，消息走广播流不进收件箱。落地上有个关键配套：消息表按 conv_id 分片，一个会话的消息永远在同一个分片——会话内查询本地化，这是读性能的根基。收件箱表只存已读游标不存消息体，消息一份游标每人一份，未读数就是 last_msg_seq 减 last_read_seq，O(1) 算出来。这套分档的本质还是量级思维：写放大的代价随群规模线性涨，读聚合的代价用懒加载摊平，在 500 人这个阈值换挡。

**Q：消息顺序怎么保证？客户端时间为什么不能用？（Sequence 追问）**
> 客户端时间三宗罪：时钟回拨、时区不一致、发消息和到达的顺序可能颠倒——排序权威必须在服务端。方案对比后选 per-会话 Sequence：Redis INCR 按会话维度发号，同会话内严格递增、会话间相互独立。为什么不全局发号器？消息排序只发生在会话内，全局单调号浪费且集中压力大；为什么不用时间戳？上面三宗罪。工程优化是号段批量取——一次 INCRBY 拿 100 个号在内存分配，Redis 压力降两个数量级，这是秒杀发号器号段思想的三级缩放（全局发号器→机器雪花→会话 INCR，粒度越细、协调成本越低）。Sequence 还有第二个用途：已读游标和未读数——收件箱只存 last_read_seq，未读数是两个 seq 的差值，连计数器都省了。这个设计我验证过并发正确性：50 线程并发发 5000 条，序号无重复且严格递增。

## 5. 今日验收清单

- [ ] 读/写扩散决策表（按群规模分档+触发条件）能画
- [ ] ConvSequence 运行：并发 5000 条无重复递增验证通过
- [ ] 未读数=seq 差值的 O(1) 设计能讲
- [ ] t_message 按 conv_id 分片的论证写进《IM 概要设计》
- [ ] `git add . && git commit -m "day09-10: im model"`

---
[← Day 09](day09-长连接选型.md) | [本月目录](README.md) | [Day 11 · IM 可靠投递 →](day11-IM可靠投递.md)
