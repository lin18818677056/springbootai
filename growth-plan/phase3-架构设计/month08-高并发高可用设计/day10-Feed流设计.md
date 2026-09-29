# Day 10 · Feed流设计：推拉结合与Timeline

> **今日目标**：掌握 Feed 流三种模型（推/拉/推拉结合）的量级推演与选型；Redis zset Timeline 实现；游标分页——商城"店铺动态"落地推拉结合。
> **时长**：模型推演 1.5h / Timeline 实现 2h / 分页与收口 1.5h
> **今日产出**：Feed 流选型推演文档 + store-feed Timeline 实现（推模式+大V拉模式）

## 1. 知识地图

```
Feed 流 = "我关注的人发的内容，聚合成一条时间线"——量级推演是选型的唯一依据：

推模式（写扩散/Fan-out，发件箱→收件箱）：
  用户发动态 → 写进每个粉丝的收件箱（zset）
  读：只读自己收件箱——读快 O(logN)
  写成本：大 V 100 万粉丝 = 一次发布写 100 万 key——写爆炸
  适用：粉丝量中等（<1 万）、读多写少（典型社交比例 读:写 ≈ 100:1）

拉模式（读扩散，只写发件箱）：
  用户发动态 → 只写自己发件箱
  读：拉所有关注人的发件箱合并排序——读慢 O(关注数×logN)
  写成本：恒定 1 次——大 V 友好
  适用：关注数少或对读 RT 要求不高

推拉结合（主流答案）：
  普通用户发 → 推（粉丝少，写得起）
  大 V 发 → 不推，标记"大V动态"（拉模式，读的时候合并）
  读：自己收件箱(推来的) + 关注的大V发件箱(拉取) → 归并排序取前 N
  ——推的量被大 V 边界截断：100 万粉的大 V 有 10 个，推总量可控

Timeline 存储（Redis zset 天然适配）：
  inbox:{uid} → zset，member=postId，score=时间戳（毫秒）
  读时间线：ZREVRANGE inbox:95533 cursor 0 9（游标分页，score 做游标）
  游标分页 vs OFFSET：深翻页 OFFSET 扫描浪费，游标 O(logN+10) 恒定
  收件箱窗口：只保留最近 3000 条（ZREMRANGEBYRANK 裁剪）——更早的走"翻页兜底读发件箱"

对照秒杀（day08）：秒杀是"写热点"治理（分桶），Feed 是"写放大"治理（推拉选择）
  ——都是"先算账再动手"：day01 的量级推演方法论在场景题里反复复用
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Fan-out on Write | 推模式（写扩散） |
| Fan-out on Read | 拉模式（读扩散） |
| Hybrid Fan-out | 推拉结合 |
| Inbox / Outbox | 收件箱 / 发件箱 |
| Timeline | 时间线（zset 按时间排序） |
| Cursor-based Pagination | 游标分页（score 游标） |
| Inbox Window | 收件箱窗口（只存最近 N 条） |

## 3. 动手实操：商城店铺动态 Timeline

```java
// mall-store 模块：店铺动态 Feed（推拉结合的简化落地——商城场景"大V"=头部店铺）
@Service
public class StoreFeedService {
    private static final int INBOX_WINDOW = 3000;     // 收件箱窗口：只保 3000 条
    private static final Set<Long> BIG_STORES = Set.of(1001L, 1002L);  // 头部店铺(粉丝>1万)

    /** 店铺发动态：普通店铺推，头部店铺只写发件箱（拉模式） */
    public void publish(long storeId, String postId) {
        redis.opsForZSet().add(outbox(storeId), postId, now());
        if (!BIG_STORES.contains(storeId)) {
            Set<Long> fans = followDao.fans(storeId, 5000);      // 推模式上限保护
            fans.forEach(fan -> {
                redis.opsForZSet().add(inbox(fan), postId, now());
                // 窗口裁剪：保最新的 3000 条，更早的读时兜底走发件箱
                redis.opsForZSet().removeRange(inbox(fan), 0, -INBOX_WINDOW - 1);
            });
        }
    }

    /** 用户时间线：收件箱(推来的) ∪ 关注大V发件箱(拉的) 归并取 10 条 */
    public List<String> timeline(long uid, String cursor /*上一页最后 score*/) {
        double max = cursor == null ? Double.MAX_VALUE : Double.parseDouble(cursor);
        TreeSet<FeedItem> merged = new TreeSet<>();              // 按 score 降序归并
        Set<String> inbox = redis.opsForZSet().reverseRangeByScore(inbox(uid), 0, max, 0, 10);
        inbox.forEach(id -> merged.add(toItem(id)));
        for (Long big : BIG_STORES)                              // 大 V 走拉
            redis.opsForZSet().reverseRangeByScore(outbox(big), 0, max, 0, 10)
                 .forEach(id -> merged.add(toItem(id)));
        return merged.stream().limit(10).map(FeedItem::postId).toList();
        // 返回最后一项的 score 作为下一页 cursor（游标分页：恒定 O(logN+K)）
    }
}
```

```text
选型推演文档（docs/feed/store-feed-design.md 的核心表格）：
| 方案     | 写成本        | 读成本          | 商城场景判断                    |
|---------|--------------|----------------|-------------------------------|
| 推      | 粉丝数/条     | O(logN) 极快    | 头部店铺 10 万粉 → 写不起 ❌     |
| 拉      | 1 次          | O(关注数×logN)  | 用户关注 20~50 家，读得起 ✓     |
| 推拉结合 | 普通店粉丝数   | 收件箱+大V合并   | 写峰值可控 + 读 RT <50ms ✓ 首选 |
压测结论：3000 并发读 timeline，P99 = 38ms（收件箱命中 90% + 大 V 拉 2 次 zset）
```

```powershell
# 压测与验证：
docker exec -it redis redis-cli ZADD inbox:95533 1727100000000 "post:9001"
docker exec -it redis redis-cli ZREVRANGEBYSCORE inbox:95533 +inf -inf LIMIT 0 10
docker run --rm williamyeh/wrk -t4 -c200 -d30s http://host.docker.internal:8080/api/feed/timeline/95533
git add . ; git commit -m "day08-10: feed push-pull"
```

## 4. 面试连接

**Q：微博/Twitter 的 Feed 流怎么设计？推拉怎么选？**
> 唯一依据是量级推演。推模式写扩散：发一条动态写 N 个粉丝收件箱，读只需查自己收件箱，读快写爆炸——1 亿粉丝的明星发一条就是 1 亿次写，不可行；拉模式读扩散：发只写自己发件箱，读要合并所有关注的人，写恒定但读放大。主流答案是推拉结合：普通用户发推（粉丝少写得快），大 V 发不推（读时在线合并大 V 发件箱），推的总量被"大 V 边界"截断。我们自己商城店铺动态就是这套：普通店铺推+收件箱窗口 3000 条裁剪，头部店铺拉，读时归并排序、游标分页（score 做游标，恒定代价，不用 OFFSET 深翻页）。压测 200 并发 P99 38ms。关键收获：选型不是背"推拉结合"四个字，是把粉丝量、读写比、收件箱窗口三个数算出来才敢下结论。

**Q：Timeline 为什么用 zset？分页怎么做？**
> Timeline 需要两件事：按时间排序 + 按位置取一段。zset 的 score 存毫秒时间戳、member 存 postId，ZREVRANGEBYSCORE 一次拿到"某时间点之前的前 10 条"，天然就是游标分页——上一页最后一条的 score 是下一页的游标，复杂度 O(logN+K) 恒定；对比 MySQL LIMIT OFFSET 翻到 1 万页要扫描跳过 1 万行，代价线性增长。配套两个工程细节：①收件箱窗口裁剪——只保留最近 3000 条（zremrangebyrank），防止活跃用户收件箱无限膨胀，更早的内容读时兜底查发件箱；②发布与推扩散的延迟——异步化（发 MQ 逐粉丝推或批量 pipeline），用户发完立刻可见自己发件箱，粉丝几秒内到达可接受。

**Q：Feed 流的一致性怎么处理？取消关注后旧动态还在吗？**
> 分级对待。取消关注：收件箱里已推的旧动态，主流做法是"不删，下次翻页自然变少"——删 3000 条成本高且用户对"历史里还有他"不敏感，严格场景（拉黑）才走异步清理（MQ 发 cleanup 任务删收件箱中该作者的 member）。删除动态：发件箱删 + 收件箱懒清理（读时 member 查不到详情就跳过，或版本号过滤）。核心思路：Feed 是"最终一致性可接受"的典型场景——用户感知粒度是秒级，不必为 100% 实时一致付出同步删除的代价；但拉黑等合规场景必须强处理且走异步任务保证最终删干净。

## 5. 今日验收清单

- [ ] 推/拉/推拉推演文档（写读成本三行表+商城选型结论）
- [ ] Timeline 落地（zset+窗口裁剪+游标分页）
- [ ] 压测数据（200 并发 P99<50ms）
- [ ] 一致性分级策略能讲（取关/删除/拉黑三例）
- [ ] `git add . && git commit -m "day08-10: feed design"`

---
[← Day 09](day09-热点Key治理.md) | [本月目录](README.md) | [Day 11 · 计数与排行榜 →](day11-计数与排行榜.md)
