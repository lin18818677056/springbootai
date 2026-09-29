# Day 02 · 底层数据结构(上)：SDS 与字典的渐进式 rehash

> **今日目标**：拆 Redis 两大基石：SDS（简单动态字符串——为什么比 C 字符串强）与 Dict（字典——扩容时怎么做到"渐进式 rehash 不卡顿"）。今天源码级理解 + 伪代码手推，是"跳表 vs 红黑树"之外的第二道源码面试题。
> **时长**：理论 1.5h / 实操 1h / 输出 0.5h
> **今日产出**：SDS 结构图 + 渐进式 rehash 手绘图 + 两段伪代码默写

## 1. 知识地图

```
SDS = Simple Dynamic String（所有 key/value 的本体）：
  ┌─────────────────────────────────────┐
  │ header: len(已用长度) + alloc(总分配) + flags(类型) │
  ├─────────────────────────────────────┤
  │ buf[]: 'h''e''l''l''o''\0'           │ ← 仍保留 \0（兼容 C 函数）
  └─────────────────────────────────────┘
  四大优势 vs C 字符串（★必背）：
    ① O(1) 拿长度（len 字段）——C 要 strlen 遍历 O(n)
    ② 二进制安全（按 len 读，不怕内容里有 \0——可存图片/序列化字节）
    ③ 杜绝缓冲区溢出（写前检查 alloc-len，不够就扩）
    ④ 空间预分配 + 惰性释放（减少 realloc 次数）
       预分配：改后 len<1MB → 新空间=2×len；≥1MB → +1MB
  头部多档位（flags 决定）：len/alloc 用 int8/16/32/64 —— 省

Dict = 字典（db0 这坨 key-value 的家）：
  struct dict {
    dictht ht[2];        // ★两张哈希表！ht[1] 平时是空的
    long rehashidx;      // rehash 进度（-1=没在进行）
  }
  dictht = 桶数组 + 链地址法（冲突串链）

渐进式 rehash（★灵魂考点，画两阶段图）：
  触发：负载因子 = used/size ≥ 1（无 BGSAVE）或 ≥ 5（有 BGSAVE）
  过程：
    ① 给 ht[1] 分配新容量（第一个 ≥ used×2 的 2^n）
    ② rehashidx=0，进入渐进模式
    ③ 每次增删改查，【顺手】把 ht[0] 的 rehashidx 桶整桶迁移到 ht[1]
       （另由 serverCron 定时批量迁 1ms，怕冷数据没人动）
    ④ 全迁完 → rehashidx=-1，释放 ht[0]，ht[1] 变 ht[0]
  期间读写规则：
    新增：只进 ht[1]（保证 ht[0] 只减不增，终会搬完）
    查找：先查 ht[0] 再查 ht[1]（可能在任一张）
    删除/更新：两边都找
  为什么不一次搬完？→ 千万级 key 一次搬 = 秒级卡顿（Redis 是单线程！）
    渐进 = 把一次 O(n) 摊到每次操作 O(1) —— "化整为零"
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| SDS | 简单动态字符串 | len/alloc/flags + buf |
| Binary Safe | 二进制安全 | 按 len 读不怕 \0 |
| Load Factor | 负载因子 | used/size，扩容判据 |
| Progressive Rehash | 渐进式 rehash | 分桶迁移不卡顿 |
| Chaining | 链地址法 | 冲突桶串链表 |
| Amortized | 摊还 | 一次 O(n) 摊成多次 O(1) |

## 3. 动手实操

### 3.1 SDS 行为观察（用现象反推结构）

```powershell
docker exec -it redis-learning redis-cli
#   在 redis-cli 里依次：
SET s1 "hello"
STRLEN s1                          # 5 —— O(1) 读 len 字段
APPEND s1 " world"                 # 11 —— 触发扩容（预分配生效）
OBJECT ENCODING s1                 # embstr（≤44B 的短串：SDS 与 redisObject 一块分配）
SET s2 "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"   # 61 字符
OBJECT ENCODING s2                 # raw（>44B：两次分配）
SET num 12345
OBJECT ENCODING num                # int（能转 long 就用整数编码）
APPEND num x                       # 12345x —— int 编码被破坏 → 转回 embstr/raw
MEMORY USAGE s2                    # 这个 key 占多少字节（含 SDS 头）
```

### 3.2 渐进式 rehash 行为观察

```powershell
# 在 redis-cli 里：
INFO keyspace                      # 当前 db0 的 key 数
# 造 5 万个 key 让字典负载过重触发扩容：
1..50 | ForEach-Object {
  $batch = ((1..1000 | ForEach-Object { "SET r$($_)_$($_) v`r`n" }) -join "")
  $batch | docker exec -i redis-learning redis-cli --pipe
}
docker exec redis-learning redis-cli DBSIZE        # ~50000
# 观察 rehash 期间的过程（新版本可用 DEBUG 不便时用逻辑推演）：
#   反复执行 INFO memory → used_memory 阶梯式上涨（ht[1] 分配 + 迁移）
#   期间 SET/GET 全程不卡（对比 day01 的 KEYS 卡顿实验）★这就是渐进的价值
# 清理实验数据（用 SCAN 分批删，练习一下）：
docker exec redis-learning redis-cli --scan --pattern "r*_*" | Out-File del.txt
(Get-Content del.txt | ForEach-Object { "DEL $_" }) -join "`r`n" | docker exec -i redis-learning redis-cli
```

### 3.3 渐进式 rehash 伪代码（手推 + 默写）

```text
dictRehashStep(d):                      // 每次操作顺带调一步
  while (d->ht[0].used != 0) {
    // 跳过空桶（最多连跳 10 次，防止 rehashidx 停在空桶区）
    if (ht[0].table[rehashidx] == NULL) { rehashidx++; continue; }
    // 整桶迁移
    for (e = ht[0].table[rehashidx]; e; e = next)
      hash & ht[1].sizemask 定位新桶 → 头插到 ht[1]
    ht[0].table[rehashidx] = NULL;
    rehashidx++;
    return;                             // ★每次只迁一桶就返回（渐进！）
  }
  rehashidx = -1; ht[0] = ht[1]; 重置;
dictFind(d, key):
  if (正在 rehash) 先查 ht[0] 没有再查 ht[1]
  else 只查 ht[0]
dictAdd(d, key):
  if (正在 rehash) 只加到 ht[1]        // 保证 ht[0] 只减不增
默写验收：三段伪代码能默写并讲清"为什么每次只迁一桶"
```

## 4. 面试连接

**Q：SDS 相比 C 字符串好在哪里？**
> 四点背完再各配一句原理：O(1) 长度（len 字段）、二进制安全（按 len 截断不怕 \0，所以能存序列化字节流）、防溢出（写前 alloc-len 检查自动扩容）、减少内存重分配（预分配+惰性释放，追加时少 realloc）。再补一句工程细节："header 按 len/alloc 大小分档用 int8~int64，短串头部只占 3 字节，体现 Redis 对内存的极致抠门。"

**Q：讲讲渐进式 rehash，为什么要渐进？**
> 先画两张表（ht[0] 旧 + ht[1] 新 + rehashidx 进度），再讲四个规则：每次操作顺迁一桶、serverCron 批量兜底、新增只进新表、查找两表都找。为什么渐进：单线程下一次搬百万 key 会阻塞所有请求秒级——渐进把 O(n) 摊还到每次 O(1)，代价仅是 rehash 期间内存占用双份与查找多一次。加分句："我造 5 万 key 触发过扩容，INFO memory 阶梯上涨而读写全程不卡——渐进式'不卡顿'是实验观察过的。"

**Q：Redis 扩容缩容的时机？**
> 扩容：负载因子 ≥1（平时）/≥5（bgsave 期间提高阈值，因为子进程 COW 时尽量少迁移改指针）；新容量为第一个 ≥ used×2 的 2^n。缩容：负载因子 <0.1 时收缩到能容纳 used 的最小 2^n——节省内存。这题检验是否理解"负载因子是核心变量"以及"COW 与扩容的联动"（bgsave 细节下一天讲）。

## 5. 今日验收清单

- [ ] SDS 三编码（int/embstr/raw）验证记录 + 44B 阈值记住
- [ ] 5 万 key 扩容实验完成（不卡顿观察记录）
- [ ] 渐进式 rehash 三段伪代码默写
- [ ] SDS 结构图 + 两张哈希表图手绘
- [ ] `git add . && git commit -m "day02: sds & progressive rehash"`

---
[← Day 01](day01-Redis架构与为什么快.md) | [本月目录](README.md) | [Day 03 · 跳表 SkipList →](day03-跳表SkipList.md)
