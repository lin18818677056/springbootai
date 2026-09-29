# Day 12 · 签到与位图：Bitmap 的存储魔法

> **今日目标**：掌握 Bitmap 签到（SETBIT/BITCOUNT/BITFIELD）与连续签到推导；亿级用户签到的存储账；RoaringBitmap 进阶——商城"会员签到送积分"落地。
> **时长**：位图原理 1.5h / 签到实现 2h / 存储账与进阶 1.5h
> **今日产出**：签到服务（打卡/连续天数/月度统计）+ 亿级存储账 + 补签方案

## 1. 知识地图

```
签到 = "每天一个 bit"——用存储账理解为什么非位图不可：

存储账（day01 估算方法论的存储版）：
  1 亿用户 × 每天 1 bit = 1 亿 bit ≈ 12.5MB/天 ≈ 4.5GB/年（Redis 随便放）
  如果用 DB 行存（uid, date, status）：1 亿行/天，索引几十 GB，写入 10 万/s
  如果用 JSON 存 uid→[1,1,0,...]：一个活跃用户 12KB，亿用户 1.2TB——完全不可行
  位图赢在信息密度：1 bit 表达 1 个布尔事实，这是物理极限

三个核心命令（key 按月组织：sign:{uid}:{yyyyMM}）：
  SETBIT sign:95533:202609 22 1     —— 23 号那天（day-1=22）打卡
  BITFIELD sign:95533:202609 GET u23 0 —— 取 1~23 号的 23 个 bit（连续签到用）
  BITCOUNT sign:95533:202609        —— 本月共打卡几天

连续签到推导（面试高频手写题）：
  BITFIELD GET u23 0 → 得到整数 N（bit23 是最低位=今天，bit1 是月初）
  连续天数 = N 的二进制从最低位起连续 1 的个数：
    N & 1 为 0 则断（今天没签）；否则 n>>=1 循环计数
  今天没签但想看"截至昨天"：GET u22 0（偏移 22 位）

补签（业务绕不开的缝）：
  SETBIT 直接补历史 bit 即可——但要防刷：补签卡数量限制（每月 2 张）落 DB
  注意 SETBIT 天然可"重复设置同一天"（幂等，无副作用）——业务只查补签卡余额

进阶两兄弟（与 day09 布隆连线，都是 bit 魔法家族）：
  Redis Bitmap：key 内偏移量=天/序号，适合"一个主体的时间轴"
  RoaringBitmap（Java 库）：稀疏位图压缩，亿级 uid 去重/交并集（人群圈选）
  布隆过滤器（day09）：k 个 hash 位判"可能存在"，有误判——三者解决三个不同问题
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Bitmap | 位图（1 bit = 1 布尔事实） |
| SETBIT / GETBIT | 置位 / 读位 |
| BITCOUNT | 统计 1 的个数 |
| BITFIELD | 按位域批量读取 |
| Offset | 偏移量（day-1 约定） |
| RoaringBitmap | 压缩位图（稀疏优化） |
| Check-in Streak | 连续签到 |

## 3. 动手实操：会员签到服务

```java
// mall-member：签到送积分（连续 7 天额外 +50）
@Service
public class CheckinService {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMM");

    /** 打卡：幂等（重复 SET 同一位无副作用），返回今天是否首签 */
    public boolean checkin(long uid, LocalDate day) {
        String key = "sign:" + uid + ":" + day.format(FMT);
        int offset = day.getDayOfMonth() - 1;                 // 1 号 → bit 0
        return Boolean.TRUE.equals(redis.opsForValue().setBit(key, offset, true));
        // setBit 返回旧值：false=首签（发积分），true=重复签（不发）
    }

    /** 连续签到天数（含今天；今天没签则按昨天） */
    public int streak(long uid, LocalDate today) {
        LocalDate base = Boolean.TRUE.equals(
            redis.opsForValue().getBit(key(uid, today), today.getDayOfMonth() - 1))
            ? today : today.minusDays(1);                     // 今天没签，看昨天
        int days = 0;
        // 逐月回溯：本月 bit 域 → 上月 bit 域（跨月连续不断签）
        for (LocalDate d = base; !d.isBefore(base.minusDays(31)); d = d.minusDays(1)) {
            if (!Boolean.TRUE.equals(redis.opsForValue().getBit(key(uid, d), d.getDayOfMonth() - 1)))
                break;                                        // 断签即停
            days++;
        }
        return days;                                          // 31 天循环上限防死循环
    }

    /** 月度统计 + 签到日历（前端渲染 31 格） */
    public CheckinMonth month(long uid, YearMonth ym) {
        int count = redis.execute((RedisCallback<Long>) c ->
            c.bitCount(("sign:" + uid + ":" + ym.format(FMT)).getBytes()));
        return new CheckinMonth(count, streak(uid, ym.atEndOfMonth()));
    }
    private String key(long uid, LocalDate d) { return "sign:" + uid + ":" + d.format(FMT); }
}
// 积分发放：首签 +10，streak%7==0 再 +50 —— 积分走 day11 计数链路（LongAdder 聚合）
```

```powershell
# 命令级验证（对照代码逐条理解）：
docker exec -it redis redis-cli SETBIT sign:95533:202609 22 1    # 23 号打卡 → (integer) 0（旧值=未签）
docker exec -it redis redis-cli SETBIT sign:95533:202609 22 1    # 再签 → (integer) 1（旧值=已签，幂等）
docker exec -it redis redis-cli BITFIELD sign:95533:202609 GET u23 0
docker exec -it redis redis-cli BITCOUNT sign:95533:202609
# 存储复核：1 亿用户一年账
#   12.5MB/天 × 365 ≈ 4.5GB（Redis 可放）—— DB 行存对比：365 亿行，不可行
git add . ; git commit -m "day08-12: checkin bitmap"
```

## 4. 面试连接

**Q：设计一个亿级用户的签到系统？**
> 先算存储账——这是 day01 估算方法论的条件反射：1 亿用户每天 1 个布尔事实，位图 12.5MB/天、一年 4.5GB，DB 行存则是一天 1 亿行、索引几十 GB。所以结构选 Redis Bitmap，key 按"用户+月"组织（sign:{uid}:{yyyyMM}，单 key 恒定 31 bit 不会成为大 key），offset=日-1。三个接口：打卡用 SETBIT 且返回旧值判断首签（天然幂等）；连续签到用 BITFIELD 取位域，从最低位循环判 1 计数，跨月逐月回溯；月度统计 BITCOUNT。两个工程细节：补签走 SETBIT 但次数限制（补签卡）落 DB 防刷；积分发放不直连，走 day11 的聚合计数链路。如果面试官追问"用户画像圈人"——那是 RoaringBitmap 的交并集场景，与签到同宗但不同用。

**Q：Bitmap、布隆过滤器、RoaringBitmap 区别？**
> 三个都玩 bit，解决三个不同问题。Redis Bitmap：一个 key 内的位轴，"偏移量"有业务语义（第几天/第几号），适合签到、在线状态这类"一个主体一条时间轴"，精确无误差。布隆过滤器：多个 hash 函数把元素映射到共享位数组，回答"可能存在/一定不存在"，有误判率（day09 防缓存穿透：100 万元素 1% 误判只要 1.2MB），不能删除（计数布隆/ cuckoo 是变体）。RoaringBitmap：稀疏位图的压缩算法库（分桶存储，连续 0 不占空间），核心能力是亿级 id 集合的 AND/OR/NOT 交并差，用于人群圈选（"收藏过 A 且买过 B"的人群包）。一句话收拢：Bitmap 记时间轴、布隆判存在、RBM 算集合——选型先问业务问题属于哪一类。

**Q：连续签到的位运算怎么写？**
> 核心三步：①BITFIELD key GET u{N} 0 取出 1 号到今天的 N 个 bit 组成的整数（今天在最低位，因为 offset=day-1，低偏移是低位）；②若最低位是 0，说明今天没签，基准改看昨天（GET u{N-1} 0）；③循环：n & 1 判断最低位，为 1 则计数+1 且 n >>>= 1 右移，为 0 停止——得到连续天数。跨月处理：本月域读到头（最低位穿到月初）还没断，继续取上月 BITFIELD 接着循环，一般回溯 31 天封顶。这个题面试官考的不是 API 记忆，是"位域→整数→位运算"的推导能力，建议在白板上画一个 23 bit 的方格图讲清楚方向：Redis 的 bit0 是最高有效位还是最低位，取决于你用 GET u{N} 还是逐位 GETBIT，我们实现里统一用 GETBIT 逐位判断更不易错（代价是 N 次往返，生产可用 pipeline 一次取回）。

## 5. 今日验收清单

- [ ] 签到服务三接口（打卡幂等/连续天数跨月/月度统计）
- [ ] SETBIT/BITFIELD/BITCOUNT 命令级验证记录
- [ ] 亿级存储账（位图 4.5GB/年 vs 行存不可行）
- [ ] 三种 bit 结构辨析能讲（Bitmap/布隆/RBM）
- [ ] `git add . && git commit -m "day08-12: bitmap checkin"`

---
[← Day 11](day11-计数与排行榜.md) | [本月目录](README.md) | [Day 13 · 抢红包与资金安全 →](day13-抢红包与资金安全.md)
