# Day 13 · 抢红包与资金安全：不可超领与总额守恒

> **今日目标**：掌握抢红包设计（二倍均值法预分配+Redis 原子领取）；资金安全三铁律（不超领/不重领/总额守恒）与对账兜底（兑现 M6 day19 对账伏笔）——商城"拼团红包"落地。
> **时长**：红包算法 1.5h / 领取链路 2h / 对账与退回 1.5h
> **今日产出**：红包拆分算法 + 领取链路（Lua）+ 对账 job + 24h 退回链路

## 1. 知识地图

```
抢红包 = 秒杀（day08）的变体 + 资金约束升级：秒杀少卖可接受，红包一分钱都不能错

红包的两个阶段，两套瓶颈：
  拆红包（发）：二倍均值法预分配——发的时候就把总额切成 N 份
  抢红包（领）：N 人抢 M 份（M<N），Redis 原子弹出——秒杀的"限额发放"同构

二倍均值法（微信公开算法思想）：
  剩余金额 M，剩余人数 N → 本次上限 = M/N × 2（期望均值的 2 倍）
  amount = random(0.01, M/N×2)——先抢多后抢少但波动大，手速不重要
  对比"线段切割法"：N-1 个切点切总额——均匀但先抢占优
  落地注意：全程分为单位（long 分/long 厘）——BigDecimal 在高并发下是性能税（M7 day15 Money 呼应）

领取链路（资金安全三铁律逐条落点）：
  铁律①不可超领：预分配后红包变成"队列弹珠"——RPUSH N 个金额进 list，
    LPOP 原子弹出：弹到即领到，list 空即抢完——物理上不可能超发
    （list 长度=剩余份额，并发 LPOP 由 Redis 单线程保证不重复弹出）
  铁律②不可重复领：同一红包同一人只能领一次——uid 唯一索引（DB 兜底）+
    Lua 里 SISMEMBER 判断（Redis 前置拦截），两层防线
  铁律③总额守恒：Σ(已领金额) + Σ(list 弹珠) = 总金额——对账 job 每分钟核对
    （兑现 M6 day19：对账不是"出了事才查"，是资金链路的常备基础设施）

领取后落库（两阶段）：抢到先记 Redis（领取成功+记录 message）→ MQ 异步落 DB
  ——MQ 丢/消费失败：对账发现 list 弹珠数 vs DB 领取记录数不一致 → 补偿

24 小时退回（延迟消息——M5 day15 连线）：
  发红包时发延迟消息（delay 24h）→ 到点检查：红包未领完 → 剩余弹珠金额原路退回
  幂等：退回前 CAS 红包状态（ACTIVE → REFUNDED），防与最后一抢竞态双退
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Double Mean Method | 二倍均值法（红包拆分） |
| Pre-allocation | 预分配（发时切份，领时弹珠） |
| Amount Conservation | 总额守恒（对账铁律） |
| Idempotent Claim | 幂等领取（唯一索引+SISMEMBER） |
| Delayed Refund | 延迟退回（24h 未领完） |
| Fund Safety | 资金安全（不超/不重/守恒） |

## 3. 动手实操：拼团红包服务

```java
// ① 二倍均值法拆分（全程 long 分，无浮点）
public static List<Long> split(long totalFen, int n) {
    var list = new ArrayList<Long>(n);
    long rest = totalFen;
    var rnd = ThreadLocalRandom.current();
    for (int i = n; i > 1; i--) {
        long mean = rest / i;
        long amt = rnd.nextLong(1, mean * 2);        // 上限=剩余均值×2
        list.add(Math.max(1, amt));
        rest -= list.getLast();
    }
    list.add(rest);                                   // 最后一份=剩余（≥1 分保证）
    return list;                                      // 抽查：Σ = totalFen
}
// ② 发红包：切份 RPUSH + 延迟退回消息
public String send(long uid, long totalFen, int n) {
    String rpid = "rp:" + IdGenerator.next();
    List<Long> parts = split(totalFen, n);
    redis.executePipelined(c -> { parts.forEach(p -> c.rPush(rpid, String.valueOf(p))); });
    rocketMQTemplate.syncSendDelayTimeSeconds("rp-refund", new RefundMsg(rpid, uid), 86400);
    redPacketDao.insert(rpid, uid, totalFen, n);      // 元数据落 DB
    return rpid;
}
// ③ 抢红包：Lua 原子（弹珠+SISMEMBER+登记）——三铁律在 20 行内全部落地
private static final DefaultRedisScript<Long> GRAB = new DefaultRedisScript<>("""
    if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -1 end   -- 重复领
    local amt = redis.call('LPOP', KEYS[1])
    if not amt then return 0 end                                            -- 抢完
    redis.call('SADD', KEYS[2], ARGV[1])                                    -- 登记领取人
    return tonumber(amt)                                                    -- 返回金额(分)
    """, Long.class);
public long grab(String rpid, long uid) {
    Long fen = redis.execute(GRAB, List.of(rpid, rpid + ":takers"), String.valueOf(uid));
    if (fen == null || fen <= 0) return fen == null ? -2 : 0;   // -2 异常 0 抢完 -1 重复
    mqTemplate.send("rp-record", new ClaimMsg(rpid, uid, fen)); // 异步落库（消费幂等）
    return fen;
}
// ④ 对账 job（M6 day19 的直接复用）：每分钟比对三方数量
//   LLEN rp:{id}（剩弹珠）+ S CARD takers（已领人数）== 总份数 N
//   Σ(领取记录金额) + Σ(剩余弹珠) == 总金额 → 不等即告警+冻结+人工（资金红线）
```

```powershell
# 并发抢红包验证（100 人抢 10 份——关注三个数：领到 10 人/无重复/无超领）：
docker exec -it redis redis-cli RPUSH rp:test 100 200 50 300 80 120 60 90 150 77
for ($i=1; $i -le 100; $i++) { Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/redpacket/grab/test?uid=$i" }
docker exec -it redis redis-cli SCARD rp:test:takers   # 必须 = 10
docker exec -it redis redis-cli LLEN rp:test           # 必须 = 0
git add . ; git commit -m "day08-13: redpacket fund safety"
```

## 4. 面试连接

**Q：设计一个抢红包系统？**
> 两个阶段两套设计。发红包用二倍均值法预分配：剩余金额/剩余人数的均值×2 做上限随机，全程 long 分运算；切好的 N 份 RPUSH 进 Redis list——这一步把"抢 N 人分 M 份"退化成"并发弹珠"，Redis 单线程保证 LPOP 不重不漏，list 空即抢完，物理上杜绝超领。抢红包用一个 Lua 脚本原子完成三件事：SISMEMBER 查重（重复领返回-1）、LPOP 弹金额（空则 0）、SADD 登记领取人，然后 MQ 异步落库（消费幂等靠 uid 唯一索引兜底）。资金安全收尾靠两条常备机制：每分钟对账（弹珠数+已领人数=总份数，Σ金额守恒，不等即告警冻结）；发红包时同时发 24 小时延迟消息，未领完自动原路退回，退回前 CAS 状态防竞态。一句话总结：秒杀容忍少卖，红包一分不能错——所以多了"对账+延迟退回"两条资金基础设施。

**Q：红包金额怎么随机才公平？**
> 二倍均值法：每次在 [1分, 剩余均值×2] 随机。数学性质：期望等于剩余均值（抢先抢后期望一致），方差大（有人手气最佳）——这正是红包的娱乐性来源。实现细节两个坑：①不能用 double——浮点累计误差在"总额守恒"铁律下不可接受，全程 long 分；②最后一份不用随机、直接等于剩余金额（且保证 ≥1 分），否则四舍五入会导致总额不守恒。替代方案线段切割法（总额上切 N-1 刀）更均匀但先抢有优势，业务上二倍均值是主流。如果面试官追问"超小额"（总额 1 分发给 10 人）——前 N-1 人各 1 分会出现负数，要加"剩余金额-剩余人数×1 ≥ 上限"的保护分支，这类边界 case 是资深与普通的分水岭。

**Q：怎么保证红包一分钱不错？**
> 三层防线：事前——预分配把总额切成弹珠，领取只是"弹出"，没有任何加总运算可以出错；事中——Lua 原子操作+DB 唯一索引双防线防重复领，金额在拆分时就守恒；事后——对账兜底：每分钟"剩余弹珠+领取记录=总额"三方核对，不一致立即告警+冻结红包+人工介入，这是 M6 day19 对账体系在资金场景的直接复用。还有一条隐性防线：所有资金操作落流水表（发放/领取/退回三张表），任何修正动作都有据可查——资金系统可以出告警，不可以出"说不清"。这套思路迁移到任何资金场景（优惠券核销/佣金结算）都成立：预算约束前置 + 原子操作 + 对账兜底。

## 5. 今日验收清单

- [ ] 二倍均值法拆分（含小总额边界保护）
- [ ] Lua 领取链路（三铁律 20 行内落地）
- [ ] 100 并发抢 10 份验证（人数/不重/守恒三查通过）
- [ ] 对账 job + 24h 延迟退回（CAS 防双退）
- [ ] `git add . && git commit -m "day08-13: fund safety"`

---
[← Day 12](day12-签到与位图.md) | [本月目录](README.md) | [Day 14 · 第二周复盘 →](day14-第二周复盘.md)
