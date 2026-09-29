# Day 14 · 分布式锁三代演进 + 第二周复盘（含 RedLock 之争博客）

> **今日目标**：Week2 收官双任务：① 手写分布式锁三代演进（SETNX→原子加锁→Lua 安全释放→看门狗续期），每一代都补上一代的死法；② 发布本月指定博客《Redis 分布式锁的三代演进与 RedLock 之争》并完成第二周十题自测。
> **时长**：实操 2h / 博客 1.5h / 复盘 1h
> **今日产出**：EvolvedLock 三代代码 + 看门狗续期演示 + 博客发布 + 周测成绩

## 1. 知识地图

```
为什么单机锁不够：synchronized/ReentrantLock 只锁本 JVM
  多实例部署（生产标配）→ 必须把锁放到公共第三方 → Redis/ZK

三代演进（每代死于什么，★面试主线）：
一代 SETNX + EXPIRE（两条命令）
  SETNX lock 1   拿锁
  EXPIRE lock 30 设过期（防死锁）
  ✗ 死法①：两条命令之间进程崩溃 → 锁永远不过期（死锁）
二代 SET k v NX EX 30（一条命令，原子）
  SET lock uuid NX EX 30
  ✗ 死法②：业务执行 40s > 锁 30s → 锁过期 → 线程B 拿到新锁
    → 线程A 干完随手 DEL → 删掉了【B 的锁】→ C 又进来 → 锁形同虚设
三代 唯一 value + Lua 比对释放（value=唯一标识，删前验明正身）
  加锁：SET lock <my-uuid> NX EX 30
  释放（Lua 保证"比对+删除"原子）：
    if redis.call("get", KEYS[1]) == ARGV[1] then
        return redis.call("del", KEYS[1])
    else
        return 0
    end
  ✗ 剩最后一个问题：业务 40s，锁 30s，中间 B 已抢入 → 互斥被破
看门狗续期（Redisson 的核心思想，手写教学版）：
  拿锁成功 → 起后台线程每 10s 检查：业务还活着 → EXPIRE 续到 30s
  业务结束 → 主动释放 + 停看门狗
  ★逻辑：锁的寿命跟着业务走，而不是跟着预估时间走

RedLock 争议（博客素材，两方观点都讲）：
  RedLock 提案：向 N 个独立 Redis 主节点同时加锁，过半成功且耗时 < TTL 才算拿到
  反方（Martin Kleppmann《DDIA》作者）：
    ① Redis 主从异步复制：主拿到锁未同步就宕机 → 从上位 → 锁丢失（互斥被破）
    ② 依赖分布式系统内时钟同步（GC 停顿/时钟漂移会让"锁未过期"的判断失真）
    ③ 结论：RedLock 不够安全，要安全用 fencing token（单调递增令牌）
  正方（antirez 作者）：RedLock 在"现实假设"下够用；fencing token 需要
    存储端配合改造，很多系统做不到
  工程结论（你的观点，面试这样说）：
    ① 锁只是"效率优化"（防重复干活），不是"正确性保证"（不靠它防资损）
    ② 正确性靠下游幂等/乐观锁/DB 唯一约束兜底
    ③ 单 Redis+看门狗 覆盖 95% 场景；金融级正确性要求 → ZK/etcd 或 DB 方案
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Distributed Lock | 分布式锁 | 跨进程互斥（Redis/ZK/etcd） |
| NX / EX | 不存在才设/过期 | SET 的原子选项 |
| Watchdog | 看门狗 | 后台续期线程 |
| Unique Value | 唯一标识 | 防"删别人的锁" |
| Fencing Token | 栅栏令牌 | 单调递增，拒绝旧令牌 |
| Mutual Exclusion | 互斥性 | 锁的第一性原理 |

## 3. 动手实操

### 3.1 主菜：三代演进手写 + 看门狗

```java
// EvolvedLock.java —— 三代锁 + 看门狗（教学版，对照 Redisson）
import java.util.*;
import java.util.concurrent.*;

public class EvolvedLock {
    // 模拟 Redis（生产： jedis.set("lock", uuid, SetParams.setnx().ex(30))）
    static final Map<String, String> REDIS = new ConcurrentHashMap<>();
    static final ScheduledExecutorService DOG = Executors.newScheduledThreadPool(2);
    final String key, myId;                    // 锁名 + 唯一标识（三代关键）
    volatile ScheduledFuture<?> watchdog;      // 看门狗句柄

    public EvolvedLock(String key) {
        this.key = key;
        this.myId = UUID.randomUUID().toString();   // 每次加锁的身份牌
    }

    /** 一代（错误示范）：SETNX 与 EXPIRE 非原子 */
    public boolean lockGen1() {
        if (REDIS.putIfAbsent(key, "1") == null) {  // SETNX
            // 若此刻崩溃 → 无过期 → 永久死锁（一代死法）
            return true;
        }
        return false;
    }

    /** 二代（半安全）：原子加锁，但释放会误删别人的锁 */
    public boolean lockGen2() {
        return REDIS.put(key, myId) == null;        // SET NX EX（原子，value=唯一id）
        // 二代死法：业务超时锁过期 → B 进来 → A 释放时无脑 del → 删掉 B 的锁
    }

    /** 三代（安全）：原子加锁 + Lua 语义释放 + 看门狗续期 */
    public boolean lockGen3() {
        boolean ok = REDIS.put(key, myId) == null;  // SET k uuid NX EX
        if (ok) startWatchdog();                    // 拿锁成功 → 看门狗上岗
        return ok;
    }

    /** 看门狗：每 1s 续期（Redisson 默认锁 30s、每 10s 续），教学版压缩节奏 */
    private void startWatchdog() {
        watchdog = DOG.scheduleAtFixedRate(() -> {
            // Lua 语义（比对+续期原子）：只有还是我的锁才续
            if (myId.equals(REDIS.get(key)))
                System.out.println("  [看门狗] 续期 " + key + " → 30s");
            else
                watchdog.cancel(true);              // 锁已易主 → 下岗
        }, 1, 1, TimeUnit.SECONDS);
    }

    /** 三代释放：比对唯一值再删（Lua 保证原子，教学版用两步模拟） */
    public boolean unlockGen3() {
        if (watchdog != null) watchdog.cancel(true);      // 停看门狗
        // if redis.call("get",KEYS[1])==ARGV[1] then del end ——原子比对删除
        return myId.equals(REDIS.get(key)) && REDIS.remove(key, myId);
    }

    public static void main(String[] args) throws Exception {
        // 场景：业务要跑 3.5 秒（锁只给 3 秒 → 演示看门狗救命）
        EvolvedLock lock = new EvolvedLock("lock:order:1001");
        System.out.println("三代加锁: " + lock.lockGen3());
        Thread.sleep(3500);                              // 期间看门狗续期 3 次
        System.out.println("业务完成，三代释放: " + lock.unlockGen3());

        // 反例：A 未释放时 B 来抢 → 失败（互斥成立）
        EvolvedLock b = new EvolvedLock("lock:order:1001");
        System.out.println("B 抢锁（应 false）: " + b.lockGen3());
        // 反例：模拟 B 误删场景——若用二代无脑 del，B 的 del 会成功（危险）；
        // 三代 unlockGen3 的 uuid 比对会拒绝 → 这里演示比对拒绝
        REDIS.put("lock:order:1001", "B-uuid");          // 锁已被 B 持有
        System.out.println("A 想释放 B 的锁（应 false）: " + lock.unlockGen3());
        DOG.shutdown();
    }
}
// 生产版 Redisson：RLock lock = redisson.getLock("lock:order:1001");
//   lock.lock();  ...  lock.unlock();  看门狗/续期/Lua 全部内置
```

### 3.2 redis-cli 层面验证三代语义

```powershell
# 一代复现：只 SETNX 不 EXPIRE → 查 TTL（-1 = 永不过期 = 死锁隐患）
docker exec -it redis-learning redis-cli setnx lock:demo 1
docker exec -it redis-learning redis-cli ttl lock:demo      # -1
# 三代标准姿势：一条命令原子加锁（NX+EX+唯一 value）
docker exec -it redis-learning redis-cli set lock:demo "uuid-A" nx ex 30
docker exec -it redis-learning redis-cli ttl lock:demo      # 30
# 释放的 Lua（比对+删除原子）：
docker exec -it redis-learning redis-cli eval "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end" 1 lock:demo uuid-B
#   期望：0（value 不是 uuid-B → 拒删——防误删成立）
docker exec -it redis-learning redis-cli eval "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end" 1 lock:demo uuid-A
#   期望：1（本人释放成功）
docker exec -it redis-learning redis-cli del lock:demo > $null   # 清场
```

### 3.3 博客发布：《Redis 分布式锁的三代演进与 RedLock 之争》

```text
大纲（1500-2500 字，用你这三天的实验截图）：
  ① 引子：为什么 synchronized 出不了单机（多实例部署的现实）
  ② 三代演进：每代配"死法复现"小节（一代 TTL=-1 截图 / 三代 uuid 拒删截图）
  ③ 看门狗原理：手写教学版代码 + Redisson 用法对照
  ④ RedLock 之争：正反双方论点表格 + fencing token 是什么
  ⑤ 你的工程结论：锁是效率优化不是正确性保证，正确性靠幂等兜底
  投稿：掘金/CSDN/知乎任选，发布链接记到本文件末尾
  ★月度产出清单指定博客②，day30 验收要检查发布链接
```

### 3.4 第二周复盘：十题自测（答不出的回对应 day）

```text
① 全量复制五步 vs 部分重同步条件（day09）
② replid/offset/backlog 各自的作用（day09）
③ SDOWN 与 ODOWN 的判定区别，quorum 影响什么不影响什么（day10）
④ 哨兵选新主的三轮打分顺序（day10）
⑤ 脑裂是什么，min-replicas-to-write 怎么防（day10）
⑥ 槽路由公式与"为什么 16384"（day11）
⑦ MOVED 与 ASK 的区别（day11）
⑧ hash tag 解决什么问题，举例 key 设计（day11）
⑨ 穿透/击穿/雪崩一句话区分 + 各自首选方案（day12）
⑩ 布隆过滤器"绝不漏判"的含义与代价（day12）
```

## 4. 面试连接

**Q：Redis 分布式锁怎么实现？有什么坑？**
> 讲演进史最有深度：一代 SETNX+EXPIRE 非原子（崩溃=死锁）→ 二代 SET NX EX 一条命令（业务超时后误删别人的锁）→ 三代唯一 value+Lua 比对删除 + 看门狗续期（锁寿跟随业务）。收尾给观点："锁是效率优化不是正确性保证，资损场景必须靠幂等/乐观锁兜底；RedLock 争议本质是 Redis 异步复制模型下互斥性的极限，要强正确性换 ZK 或 DB 唯一约束。"加分句："三代锁我手写过并复现了每一代的死法，博客里有完整复现截图。"

**Q：Redisson 看门狗的工作机制？**
> lock() 不传超时时间时启用：默认锁 30 秒，后台线程每 10 秒（1/3 周期）检查锁是否还被当前线程持有，是则重置为 30 秒——业务跑多久锁活多久，锁过期打断业务的问题消失。释放时看门狗随锁注销。注意：传了 leaseTime 反而不启用看门狗（很多人不知道的坑）。

**Q：ZK 分布式锁和 Redis 锁怎么选？**
> Redis：性能高（内存+单线程），AP 倾向——主从切换瞬间可能丢锁（异步复制）；ZK：CP 倾向——临时顺序节点+watch 机制，会话断开锁必然释放，正确性更强但吞吐低、运维重。选型：防重复执行的效率锁 → Redis 足够；资金/库存等强正确性 → ZK/etcd 或干脆 DB 乐观锁+唯一索引。

## 5. 今日验收清单

- [ ] EvolvedLock 三代跑通（B 抢锁 false + 误删拒绝 false）
- [ ] redis-cli 验证：TTL=-1 死法 + Lua 拒删实验记录
- [ ] 博客发布 + 链接记录
- [ ] 十题自测 ≥8 题全对（错题回对应 day 重学）
- [ ] `git add . && git commit -m "day14: distributed lock + week2"`

---
[← Day 13](day13-缓存一致性.md) | [本月目录](README.md) | [Day 15 · 何时分库分表 →](day15-何时分库分表.md)
