# Day 01 · Redis 总体架构：为什么快（Reactor 的最佳代言人）

> **今日目标**：阶段二第一天。用 month03 day10 的 Reactor 眼光看 Redis：画出"一条 SET 命令的完整旅程"，回答住面试第一问"Redis 为什么快、到底是不是单线程"——今天之后这个题你有图有实验有实证。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：Redis 命令执行全流程图 + redis-benchmark 压测记录 + "为什么快"四要素卡

## 1. 知识地图

```
Redis 命令执行全流程（对号 month03 day10 的单 Reactor 单线程）：
  客户端 → TCP → [内核 epoll] → Redis 事件循环（单线程）
                    │
                    ├ 文件事件：accept/read/write（网络 IO）
                    └ 时间事件：serverCron（过期清理/统计/RDB 触发…）
        事件循环伪代码：
          while(true) {
            events = epoll_wait();        // 一次等所有连接的就绪事件
            for (e : events) { 读字节流 → 解析命令 → 执行(内存操作) → 写回 }
            处理到期的时间事件;
          }
  ★命令执行永远单线程（6.0 也是）→ 无锁、无上下文切换、天然原子

"为什么快"四要素（★面试标准答案，每个都能展开）：
  ① 纯内存操作：内存随机访问 ~100ns vs 磁盘 ~10ms，差 5 个数量级
  ② IO 多路复用 + epoll：单线程管理数万连接（month03 day09 的知识直接复用）
  ③ 单线程模型：无锁无竞争无切换；慢命令会卡全场（所以禁 KEYS *）
  ④ 高效数据结构：SDS/跳表/ListPack——本周逐个拆

6.0 多线程 IO（易错点，别答成"6.0 多线程执行命令"）：
  多线程只干【读写网络 + 协议解析】，命令执行仍单线程
  配置：io-threads 4（读）+ io-threads-do-reads yes
  收益：大流量下吞吐 ↑，原子性/简单性不破坏

对号入座表（month03 知识变现时刻）：
  | 中间件      | Reactor 形态         |
  | Redis(<6.0) | 单 Reactor 单线程    |
  | Redis(6.0+) | 单 Reactor + 多线程 IO |
  | Netty       | 主从 Reactor 多线程  |
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| File Event | 文件事件 | 网络 IO 事件（epoll 就绪） |
| Time Event | 时间事件 | 周期任务 serverCron |
| Single Threaded | 单线程 | 命令执行永远单线程 |
| IO Thread | IO 多线程 | 6.0 只管读写/解析 |
| Latency Monitor | 延迟监控 | 记录慢事件（超过阈值） |
| Throughput | 吞吐量 | QPS/带宽 |

## 3. 动手实操

### 3.1 环境确认 + 命令初体验

```powershell
docker ps --filter "name=redis-learning"      # 在跑吗（README 已起）
docker exec -it redis-learning redis-cli      # 进入交互

# 在 redis-cli 里依次执行（观察返回格式——RESP 协议 day06 拆解）
SET hello world
GET hello
TYPE hello
DBSIZE
INFO stats          # total_commands_processed / instantaneous_ops_per_sec
INFO server         # redis_version / process_id
LATENCY HISTORY command
```

### 3.2 压测：亲眼看单线程的吞吐上限

```powershell
# 容器内置 redis-benchmark：10 万次 SET/GET
docker exec redis-learning redis-benchmark `
  -t set,get -n 100000 -c 50 -q
# 典型输出（本机 Docker）：
#   SET: 120000+ requests per second
#   GET: 130000+ requests per second
# 记录你的数字。对照组：-c 1（单连接）vs -c 200（高并发连接）
docker exec redis-learning redis-benchmark -t set -n 100000 -c 1 -q
docker exec redis-learning redis-benchmark -t set -n 100000 -c 200 -q
# 观察：连接数↑吞吐先升后平——单线程 CPU 打满是天花板（8C 机器 ~15 万）
```

### 3.3 慢命令卡顿实验（理解"单线程 = 全场被卡"）

```powershell
# 终端1：造 100 万个 key 然后执行 KEYS *（禁令命令，实验室专用）
docker exec -it redis-learning redis-cli
#   1000000 次太多，先用循环脚本造 10 万：
```

```powershell
1..20 | ForEach-Object { docker exec redis-learning sh -c `
  "redis-cli --pipe" } | Out-Null
# 快速造数（单条循环太慢，用 --pipe 批量）：
$keys = (1..100000 | ForEach-Object { "SET k$_ v$_`r`n" }) -join ""
$keys | docker exec -i redis-learning redis-cli --pipe
# 终端2：卡顿计时（在 KEYS 执行期间反复 GET）
Measure-Command { docker exec redis-learning redis-cli GET k99999 } | Select-Object TotalMilliseconds
docker exec redis-learning redis-cli KEYS "k1*"    # ★执行它
Measure-Command { docker exec redis-learning redis-cli GET k99999 } | Select-Object TotalMilliseconds
# 记录前后耗时对比 → KEYS 扫 10 万键期间普通 GET 也被拖慢 = 单线程排队
# 生产对应：KEYS/SMEMBERS 大集合/HGETALL 大哈希都是"卡全场"嫌疑人
docker exec redis-learning redis-cli DEL k1 k2 k3   # 清几个示例（别 DEL k* 全清）
```

### 3.4 全流程图手绘任务

```text
照 §1 画"SET hello world 的完整旅程"，标注：
  ① epoll_wait 返回就绪事件（fd=X 可读）
  ② 读 socket → 输入缓冲区 → 解析 RESP（*3\r\n$3\r\nSET…）
  ③ 查命令表 → 执行（dictAdd 到 db0 的哈希表）→ 返回 +OK
  ④ 写 socket 回客户端
  ⑤ 同一瞬间若有 100 个客户端 → 事件循环逐个处理（排队但无锁）
默写验收：能对空气讲清"单线程怎么扛 10 万 QPS"
```

## 4. 面试连接

**Q：Redis 到底是不是单线程？**
> 分代答：命令执行【永远】单线程（含 6.0+）——这保证了原子性与无锁；变化在于 6.0 把网络读写与协议解析交给 io-threads 多线程，因为大流量下"读包解包"成了瓶颈而命令执行本身是内存微秒级操作。再补后台线程：fsync AOF、lazyfree 异步释放大 key、close fd 都有专门线程。一句话收尾："核心路径单线程，外围辅助多线程。"

**Q：Redis 为什么快？（四要素展开版）**
> ① 纯内存（数量级差距）；② epoll 多路复用单线程管数万连接（能画出事件循环）；③ 单线程无锁无切换（代价：慢命令卡全场，所以我禁 KEYS 用 SCAN）；④ 精心设计的数据结构（SDS/跳表/紧凑编码——本周逐个拆）。加分实证："我压过 docker 里的 Redis：50 连接 SET 12 万 QPS，还故意 KEYS 扫 10 万键观察普通 GET 被拖慢——单线程排队的现场我亲眼看过。"

**Q：Redis 6.0 为什么引入多线程？为什么不彻底多线程？**
> 引入原因：网络 IO（read/解析/write）在大包大流量下占比过高，多线程 IO 让吞吐提升约 1 倍。不彻底的原因：命令执行多线程就要处理竞态——加锁丢性能、去锁失原子，客户端（如 MULTI/Lua/事务）依赖的原子语义全要重构；而瓶颈往往不在计算而在网络与内存——收益配不上复杂度。这题答的是"工程权衡"而非技术高低。

## 5. 今日验收清单

- [ ] 命令初体验记录（INFO stats 截图）
- [ ] 三组 benchmark 数字记录（c=1/50/200）
- [ ] KEYS 卡顿实验前后耗时对比
- [ ] 全流程图手绘 + "四要素"卡默写
- [ ] `git add . && git commit -m "day01: redis arch & why fast"`

---
[← 本月目录](README.md) | [Day 02 · SDS 与渐进式 rehash →](day02-SDS与字典渐进式rehash.md)
