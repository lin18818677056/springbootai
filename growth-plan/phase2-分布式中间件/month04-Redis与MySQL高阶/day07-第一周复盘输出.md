# Day 07 · 第一周复盘输出（《Redis 类型-编码-场景速查表》定稿）

> **今日目标**：Week1「数据结构与原理」收官：day01-06 知识树串珠 + 十题自测 + 把 day04 的速查表 v1 升级定稿（旧大纲指定产出①）——今天它是完整的生产参考文档。
> **时长**：复盘 1h / 定稿 1h / 自测 1h
> **今日产出**：速查表定稿 + 十题自测成绩 + 周知识树图

## 1. 知识地图

```
Week1 全景知识树（day01-06，从根讲叶）：
  Redis 为什么快（day01）
   ├ 单 Reactor 单线程（month03 对号）+ 6.0 多线程 IO
   ├ 四要素：内存/epoll/无锁/数据结构
   └ KEYS 卡全场实验（单线程的代价）
  数据结构地基
   ├ day02 SDS（O(1) 长度/二进制安全）+ 字典两张表渐进 rehash
   ├ day03 跳表（手写 + span/backward 增强）vs 红黑树三层递进
   └ day04 对象系统：TYPE/ENCODING 解耦 + ListPack vs ZipList
  内存与原子性
   ├ day05 近似 LRU 采样 + LFU 对数计数衰减 + 八策略矩阵
   └ day06 RESP 协议（手写客户端）+ MULTI/Lua 三方案
跨月连线：
  month03 长度头协议 ←→ day06 RESP 文本协议（协议设计两派）
  month03 Reactor     ←→ day01 Redis 线程模型
  month03 B+树三层    ←→ day03 跳表 log n（内存版数据结构对照）
  month02 手写组件风  ←→ day03 手写跳表 / day06 手写客户端
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Recap | 复盘 | 周级知识串珠 |
| Reference Card | 速查表 | 生产随手翻的文档 |
| Active Recall | 主动回忆 | 自测优于重读 |
| Elaboration | 精细化加工 | 与旧知识连线 |
| Spaced Repetition | 间隔重复 | 卡片轮播 |

## 3. 动手实操

### 3.1 《Redis 类型-编码-场景速查表》定稿（今天的主产出）

```text
# Redis 类型-编码-场景速查表 v2.0（发布到笔记/博客/团队 wiki）
【数据结构选型】
| 业务场景           | 推荐类型 | 理由与要点                    |
|--------------------|----------|-------------------------------|
| 对象缓存(用户/商品)| Hash     | listpack 小巧；可单字段更新   |
| 计数器/限流        | String   | INCR 原子；int 编码最省       |
| 排行榜/延迟队列    | ZSet     | 跳表范围+排名；128 项内 listpack |
| 标签/去重/抽奖     | Set      | SISMEMBER O(1)；交并差集       |
| 签到/活跃打点      | BitMap   | 1 亿人 12MB；BITCOUNT/OR      |
| UV 统计            | HLL      | 固定 12KB；误差 0.81%         |
| 简单队列/时间线    | List     | quicklist；LPUSH+BRPOP        |
| 轻量消息(消费组)   | Stream   | XADD/XREADGROUP；可持久       |
【编码阈值】（day04 已验）
String 44B ｜ Hash/ZSet 128 项 64B ｜ Set intset→listpack→hashtable
【危险命令黑名单】
KEYS(用 SCAN) / HGETALL 大 hash(用 HSCAN) / SMEMBERS 大 set / FLUSHALL 生产
【大 key 判定参考】
String>10KB、集合类>5000 元素 → 排查拆分（UNLINK 异步删）
```

### 3.2 周十题自测（合上笔记作答）

```text
 1. Redis 为什么快？四要素是什么？          → 内存/epoll/单线程无锁/数据结构
 2. 6.0 多线程改了什么？                     → 只改网络读写与解析，执行仍单线程
 3. 渐进式 rehash 期间读/写规则？            → 读两表都查；写只进 ht[1]
 4. SDS 比 C 字符串强在哪四点？              → O(1)长/二进制安全/防溢出/少 realloc
 5. 跳表 vs 红黑树三层递进？                 → 范围链表/实现简单/可无锁
 6. ListPack 解决了 ZipList 什么死穴？       → 连锁更新（prevlen 连环扩）
 7. Hash 什么时候 listpack→hashtable？       → 128 项或 64B 值；单向不回落
 8. 近似 LRU 怎么实现？                      → 24bit 时钟+采样 5 个挑 idle 最大
 9. LFU 计数器为什么要对数+衰减？            → 8bit 装百万次 + 历史热点冷却
10. MULTI/EXEC/Pipeline/Lua 原子性排序？     → Lua>MULTI(串行不回滚)>Pipeline(无)
评分 ≥8 = Week1 达标；错题标注对应 day，下周一回炉
```

### 3.3 复盘三件套（固定流程）

```text
① 串讲：对空气讲 15 分钟"Redis 从一个 SET 命令看穿全局"
   （epoll→RESP 解析→dict→SDS→内存与淘汰——一条命令串 6 天）
② 卡片：本周 20 张概念卡入库（SDS/rehash/跳表 span/ListPack/
   近似 LRU/LFU 衰减/RESP/MULTI 坑/Lua 原子性…）
③ 归档：速查表 v2.0 发博客或团队 wiki；learning 仓库 NOTES.md 记链接
```

## 4. 面试连接

**Q：用一条 SET 命令把 Redis 原理串一遍（杀手锏题，检验体系化）**
> 串珠答法："SET k v 到达 → epoll 事件就绪（day01 单线程事件循环）→ RESP 解析 *3 $3（day06 我手写过协议客户端）→ 命令表定位 SET → dict 定位 key（day02 两张表渐进 rehash）→ 值存进 SDS（len/alloc 结构）→ 若内存超限触发近似 LRU（day05 采样淘汰）→ 返回 +OK。一条命令穿过六天知识——这就是我理解的'体系化'。"这段话背熟，面试官会记住你。

**Q：你怎么保证团队规范使用 Redis？（管理视角预演）**
> 三道闸：① 速查表进开发 wiki + Code Review 检查项（禁 KEYS/大 key 判定）；② 工具层——redis-cli --bigkeys 日常巡检 + 慢查询日志 SLOWLOG GET 监控；③ 演进——大 key 治理案例沉淀回速查表。句式沿用 month03 day21 的"三道闸"模板，管理题答案成体系。

## 5. 今日验收清单

- [ ] 速查表 v2.0 定稿发布（博客/wiki/笔记三选一）
- [ ] 十题自测 ≥8（错题标回炉 day）
- [ ] "一条 SET 串全局"串讲练习（录音回听）
- [ ] 20 张概念卡入库
- [ ] `git add . && git commit -m "day07: week1 review + cheat sheet v2"`

---
[← Day 06](day06-事务与Lua.md) | [本月目录](README.md) | [Day 08 · RDB 与 AOF 持久化 →](day08-RDB与AOF持久化.md)
