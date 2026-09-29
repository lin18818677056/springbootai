# Day 04 · 对象系统与编码（OBJECT ENCODING 阈值全验证）

> **今日目标**：Redis 对外是五种类型，对内是一套"按数据形态自动换编码"的对象系统——同一个 Hash 既能是小巧的 ListPack 也能是标准哈希表。今天把五大类型的编码映射与转换阈值全部实验验证，产出《类型-编码-场景速查表》v1。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：五大类型编码验证记录 + 阈值表 + 特殊类型三兄弟（BitMap/HLL/Stream）初探

## 1. 知识地图

```
redisObject = 类型 + 编码 + 指针（对外类型 ↔ 对内编码解耦）：
  TYPE     → 客户端看到的（string/list/hash/set/zset）
  ENCODING → 底层真正的结构（int/listpack/skiplist…）

五大类型编码映射（★7.0 后的准确版本，必背）：
  ┌────────┬────────────────────┬──────────────────────────┐
  │ 类型    │ 编码                │ 转换条件                  │
  ├────────┼────────────────────┼──────────────────────────┤
  │ String │ int / embstr / raw │ 整数 / ≤44B / >44B 或修改 │
  │ List   │ quicklist          │ 7.0：listpack 节点的双向链│
  │ Hash   │ listpack → hashtable│ 字段数≤128 且 值≤64B     │
  │ Set    │ intset → listpack → hashtable │ 全整数/小/大   │
  │ ZSet   │ listpack → skiplist│ 元素数≤128 且 值≤64B     │
  └────────┴────────────────────┴──────────────────────────┘
  配置项可查：hash-max-listpack-entries / -value 等阈值参数

设计哲学（面试升华句）：
  小数据用紧凑连续内存（CPU 缓存友好 + 省指针开销）
  大数据切标准结构（哈希表/跳表保复杂度）
  → 自动挡：写代码的人不用管，Redis 帮你选

特殊类型三兄弟（各自的数据结构彩蛋）：
  BitMap    位图：SETBIT 0 1 —— 1 亿签到只占 12MB（本质 String）
  HyperLogLog 基数统计：PFADD/PFCOUNT —— 12KB 估亿级 UV（误差 0.81%）
  Stream    消息流：XADD/XREADGROUP —— 7.0 的 MQ 雏形（month05 呼应）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Encoding | 编码 | 类型之下的底层结构 |
| ListPack | 紧凑列表 | 7.0 全面替代 ziplist |
| QuickList | 快速列表 | listpack 节点的双向链 |
| IntSet | 整数集合 | 全整数 Set 的紧凑态 |
| BitMap | 位图 | 位级操作，String 本质 |
| HyperLogLog | 基数估算 | 12KB 估海量基数 |

## 3. 动手实操

### 3.1 五大类型编码逐一验证

```powershell
docker exec -it redis-learning redis-cli
#   ── String ──
SET a 12345;            OBJECT ENCODING a     # int
SET b "short";          OBJECT ENCODING b     # embstr
SET c "012345678901234567890123456789012345678901234567890123456789"   # >44B
OBJECT ENCODING c       # raw
#   ── Hash：小 → 大阈值实验 ──
HSET h1 f1 v1;          OBJECT ENCODING h1    # listpack
#   灌到 129 个字段触发转换（阈值 128）：
#   在 PowerShell 循环灌（走管道批量）：
```

```powershell
$cmds = (1..129 | ForEach-Object { "HSET h1 field$_ value$_`r`n" }) -join ""
$cmds | docker exec -i redis-learning redis-cli
docker exec redis-learning redis-cli OBJECT ENCODING h1     # hashtable ★
docker exec redis-learning redis-cli HLEN h1
# 缩不回去！编码转换是单向的（删除字段到 5 个再看）
$del = ((1..129 | ForEach-Object { "HDEL h1 field$_`r`n" }) -join "")
$del | docker exec -i redis-learning redis-cli
docker exec redis-learning redis-cli OBJECT ENCODING h1     # 仍是 hashtable
# ★单向性是面试细节：转换条件只在"变大"方向生效
```

```powershell
docker exec -it redis-learning redis-cli
#   ── Set：intset → hashtable ──
SADD s1 1 2 3;          OBJECT ENCODING s1    # intset
SADD s1 "str";          OBJECT ENCODING s1    # listpack（7.0+）
SADD s1 v1 v2 v3 v4 v5 v6 v7 v8 v9 v10 v11 v12 v13 v14 v15 v16 v17 v18 v19 v20 v21 v22 v23 v24 v25 v26 v27 v28 v29 v30 v31 v32
OBJECT ENCODING s1      # 元素多了后 hashtable
#   ── ZSet ──
ZADD z1 1 a 2 b;        OBJECT ENCODING z1    # listpack
#   灌 129 个成员触发 skiplist（阈值 128）：
```

### 3.2 特殊三兄弟初探

```powershell
docker exec -it redis-learning redis-cli
#   ── BitMap：1 亿用户签到只需 12MB 的原理 ──
SETBIT sign:u1 0 1        # 第 0 天签了
SETBIT sign:u1 6 1
BITCOUNT sign:u1          # 2 天
OBJECT ENCODING sign:u1   # raw（本质就是 String！）
#   ── HyperLogLog：12KB 估基数 ──
PFADD uv:2026-09-24 u1 u2 u3 u1     # 重复元素不影响
PFCOUNT uv:2026-09-24     # 3（近似计数）
OBJECT ENCODING uv:2026-09-24
#   ── Stream：MQ 雏形（month05 对照）──
XADD mystream * sensor-1 36.5
XADD mystream * sensor-1 37.2
XLEN mystream
XRANGE mystream - + COUNT 2
```

### 3.3 《类型-编码-场景速查表》v1（day07 完善定稿）

```text
| 类型  | 小编码        | 大编码     | 阈值              | 典型场景           |
|-------|---------------|------------|-------------------|--------------------|
| String| int/embstr    | raw        | 44B               | 缓存/计数/分布式锁 |
| List  | quicklist     | quicklist  | 单节点 listpack   | 消息队列/时间线    |
| Hash  | listpack      | hashtable  | 128 项/64B        | 对象缓存/购物车    |
| Set   | intset/listpack| hashtable | 128 项            | 标签/去重/抽奖     |
| ZSet  | listpack      | skiplist   | 128 项/64B        | 排行榜/延迟队列    |
| 特殊  | BitMap        | —          | —                 | 签到/活跃          |
| 特殊  | HLL           | —          | 固定 12KB         | UV 统计            |
| 特殊  | Stream        | —          | —                 | 轻量消息           |
填空自测：遮住"小编码/大编码"两列默写
```

## 4. 面试连接

**Q：Redis 对象系统的设计思想？**
> 解耦答：TYPE 与 ENCODING 分离——客户端 API 稳定（都是 HGET/HSET），底层编码按数据形态自动切换（小数据 listpack 省内存且缓存友好，大数据 hashtable/skiplist 保复杂度）。再升华："这是'多态'思想在存储层的落地，等价于 Java 里 List 接口背后自动换 ArrayList/LinkedList——Redis 帮你在运行时选最优实现。"

**Q：ListPack 相比 ZipList 解决了什么问题？（高频深题）**
> ZipList 的致命伤：连锁更新（cascade update）——每个节点的 prevlen 记录前节点长度，前面节点变长导致后面的 prevlen 字段从 1B 扩到 5B，引发连环扩容最坏 O(n²)。ListPack 改记自身长度（不含前节点信息），改谁影响谁，彻底消除连锁更新。7.0 起全部场景用 ListPack 替代 ZipList。这题是"源码级面试"的分水岭。

**Q：为什么 Hash 小的时候用 ListPack？**
> 三个理由：① 连续内存对 CPU 缓存极友好（一次加载整块）；② 省指针与哈希表开销（O(1) 空间 vs O(n) 头）；③ 小数据线性查找也快（128 项内遍历 < 1μs）。当字段数/值大小超阈值再升级 hashtable 换 O(1) 查找——"空间与复杂度的动态平衡"。附实证话术："我在自己容器里把 h1 灌到 129 个字段，OBJECT ENCODING 从 listpack 变 hashtable，且删除后不回落——单向转换。"

## 5. 今日验收清单

- [ ] 五大类型编码全部实验验证（截图）
- [ ] Hash 129 字段阈值实验 + 单向性观察
- [ ] BitMap/HLL/Stream 三兄弟初探记录
- [ ] 《类型-编码-场景速查表》v1 默写填空通过
- [ ] `git add . && git commit -m "day04: object system & encodings"`

---
[← Day 03](day03-跳表SkipList.md) | [本月目录](README.md) | [Day 05 · 内存与淘汰策略 →](day05-内存与淘汰策略.md)
