# Day 06 · 事务、Lua 与手写 RESP 客户端（原子扣减实战）

> **今日目标**：三个任务一锅端：① 搞清 MULTI/EXEC 事务与 Lua 脚本的能力边界（Redis 事务不支持回滚！）；② 用纯 JDK 手写一个 RESP 协议客户端（month03 手写 RPC 的续篇，协议设计知识变现）；③ 用 Lua 写"库存扣减+限购校验"原子操作——day25 秒杀防超卖的地基。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：MiniRedisClient（手写 RESP）+ Lua 原子扣减脚本 + 三方案对比卡

## 1. 知识地图

```
RESP 协议（REdis Serialization Protocol，day01 埋的伏笔）：
  请求 = 命令数组；5 种类型前缀：
    +简单串  -错误  :整数  $bulk串  *数组
  SET key val 的实际字节流（★看一眼就懂）：
    *3\r
$3\r
SET\r
$3\r
key\r
$3\r
val\r

  ★对比 month03 的二进制长度头协议：RESP 是"文本行协议"，
    可读性换紧凑性——两种协议设计哲学（面试可对比聊）

MULTI/EXEC 事务（名不副实的"事务"）：
  MULTI → 后续命令只入队（QUEUED）→ EXEC 一次性串行执行
  两个坑：
    ① 命令语法错：EXEC 整体放弃（入队时发现）
    ② 运行时错（对 String 做 LPUSH）：【跳过该条继续执行】——不支持回滚！
  WATCH key = 乐观锁：EXEC 前若 key 被别人改过 → EXEC 返回 nil（重试）
  定位：Redis 事务 = 排队串行执行器，不是 ACID 事务

Lua 脚本（真正的原子武器）：
  EVAL "脚本" 1 key arg…
  原子性来源：脚本执行期间【事件循环不插队】（单线程独占）
  与 MULTI 区别：Lua 中间结果可判断（if stock<=0 then return 0）
  与 Pipeline 区别：Pipeline 只省 RTT 不保证原子，Lua 原子且可编程

三方案对比卡（★秒杀 day25 的地基，必背）：
  | 方案         | 原子性 | 可编程 | 网络往返 | 适用           |
  |--------------|--------|--------|----------|----------------|
  | MULTI/EXEC   | 串行   | 无逻辑 | 1 次     | 简单批量       |
  | Pipeline     | 无     | 无     | 1 次     | 批量读写       |
  | Lua(EVAL)    | 原子   | 有逻辑 | 1 次     | 校验+扣减组合  |
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| RESP | Redis 序列化协议 | 5 种前缀的文本协议 |
| Queueing | 命令入队 | MULTI 后 QUEUED |
| Optimistic Lock | 乐观锁 | WATCH+EXEC 版本校验 |
| Atomicity | 原子性 | Lua 独占执行期 |
| Round-Trip Time | 往返时延 | Pipeline 省的正是它 |
| Script Cache | 脚本缓存 | EVALSHA 复用脚本 |

## 3. 动手实操

### 3.1 MULTI/EXEC 与 WATCH 实验

```powershell
docker exec -it redis-learning redis-cli
#   ── 基本事务 ──
MULTI
SET tx:k1 v1          # QUEUED（只入队没执行）
SET tx:k2 v2          # QUEUED
EXEC                  # 一次执行，返回两行 OK
#   ── 不支持回滚实证 ──
MULTI
SET tx:a hello
LPUSH tx:a x          # 运行时错误（对 String 做 LPUSH）——入队不报错
SET tx:b world
EXEC                  # tx:a 失败，但 tx:b 依然成功 ★不支持回滚
#   ── WATCH 乐观锁（两终端演示或脑内推演）──
#   A: WATCH cnt   GET cnt → "10"
#   B: SET cnt 99
#   A: MULTI  SET cnt 11  EXEC → (nil) ★版本变了，EXEC 放弃
#   A: UNWATCH; 重试
```

### 3.2 主菜：手写 RESP 客户端（纯 JDK，≤80 行）

```java
// MiniRedisClient.java —— RESP 文本协议客户端（延续 month03 手写协议风）
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class MiniRedisClient implements Closeable {
    private final Socket socket;
    private final BufferedReader reader;
    private final OutputStream out;

    public MiniRedisClient(String host, int port) throws IOException {
        socket = new Socket(host, port);                    // TCP 长连接
        reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8));
        out = socket.getOutputStream();
    }

    /** 发送 RESP 数组：*N CRLF → ($len CRLF arg CRLF) × N */
    public void send(String... args) throws IOException {
        StringBuilder sb = new StringBuilder("*").append(args.length).append("\r\n");
        for (String a : args) {
            byte[] b = a.getBytes(StandardCharsets.UTF_8);
            sb.append("$").append(b.length).append("\r\n");
            sb.append(new String(b, StandardCharsets.UTF_8)).append("\r\n");
        }
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** 读响应：+状态 -错误 :整数 $bulk *数组（教学版支持前四种） */
    public String reply() throws IOException {
        String line = reader.readLine();
        char type = line.charAt(0);
        String body = line.substring(1);
        return switch (type) {
            case '+' -> "OK:" + body;
            case '-' -> "ERR:" + body;
            case ':' -> "INT:" + body;
            case '$' -> "-1".equals(body) ? "NULL"
                       : "BULK:" + reader.readLine();     // $len 的下一行是数据
            default -> body;
        };
    }

    public String set(String k, String v) throws IOException { send("SET", k, v); return reply(); }
    public String get(String k)        throws IOException { send("GET", k);    return reply(); }
    public String del(String k)        throws IOException { send("DEL", k);    return reply(); }

    public static void main(String[] args) throws Exception {
        try (MiniRedisClient r = new MiniRedisClient("localhost", 6379)) {
            System.out.println(r.set("java:key", "hand-made-resp"));   // OK:OK
            System.out.println(r.get("java:key"));                     // BULK:hand-made-resp
            System.out.println(r.get("not:exist"));                    // NULL
            System.out.println(r.del("java:key"));                     // INT:1
        }
    }
}
// ★day01 §3.4 的 RESP 字节流在这里真实发出去——协议知识闭环
```

```powershell
cd D:\mywork\springbootai\learning\month04-redis-mysql
javac -d out MiniRedisClient.java; java -cp out MiniRedisClient
# 观察输出与 redis-cli MONITOR（另开终端）里你客户端产生的原始命令
```

### 3.3 Lua 原子扣减（秒杀核心逻辑预演）

```lua
-- deduct.lua —— 原子"校验+扣减+限购"（day25 秒杀直接复用）
-- KEYS[1]=库存key KEYS[2]=用户集合key  ARGV[1]=用户id
local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
if stock <= 0 then return -1 end                    -- ① 售罄
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -2                                       -- ② 重复购买
end
redis.call('DECR', KEYS[1])                         -- ③ 扣库存
redis.call('SADD', KEYS[2], ARGV[1])                -- ④ 记录用户
return stock - 1
```

```powershell
docker exec redis-learning redis-cli SET stock:1 3
#   用 redis-cli 执行脚本（-4 输出模式看得更清）：
docker exec redis-learning redis-cli -x EVAL "` `
  ""local stock = tonumber(redis.call('GET', KEYS[1]) or '0') ``
  ""if stock <= 0 then return -1 end``
  ""if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -2 end``
  ""redis.call('DECR', KEYS[1])``
  ""redis.call('SADD', KEYS[2], ARGV[1])``
  ""return stock - 1"" 2 stock:1 users:1 1001"
# 连跑 4 次观察返回：2 → 1 → 0 → -1（第 4 次售罄）
docker exec redis-learning redis-cli SET stock:1 3
docker exec redis-learning redis-cli -x EVAL "` `
  ""local stock = tonumber(redis.call('GET', KEYS[1]) or '0') ``
  ""if stock <= 0 then return -1 end``
  ""if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -2 end``
  ""redis.call('DECR', KEYS[1])``
  ""redis.call('SADD', KEYS[2], ARGV[1])``
  ""return stock - 1"" 2 stock:1 users:1 1001"
# 同一用户再买 → -2（限购生效）
docker exec redis-learning redis-cli SMEMBERS users:1
# 记录：原子性来源 = Lua 执行期间事件循环不插队（day01 知识闭环）
```

## 4. 面试连接

**Q：Redis 事务支持回滚吗？MULTI 和 Lua 怎么选？**
> 明确答"不支持回滚"并给出两种错误的行为差异：入队期语法错 → EXEC 整体拒绝；运行期类型错 → 跳过该条继续（作者认为运行时错误是编程 bug，回滚无助于修 bug 还拖性能）。选型：无逻辑批量用 MULTI 或 Pipeline（仅省 RTT）；需要"判断+多步写"的组合原子性用 Lua——Lua 脚本执行独占事件循环，中间可 if 判断。这句收尾："秒杀扣减我一定选 Lua，因为校验与扣减必须同生共死。"

**Q：为什么 WATCH 能实现乐观锁？**
> WATCH 给 key 打上版本监视标记；期间任何客户端对它的修改会置脏标志；EXEC 时检查脏标志——脏则拒绝执行返回 nil。本质是 CAS 的"比较"环节挪到服务端。代价：竞争激烈时失败率高、要客户端重试；所以高竞争场景直接上 Lua（把判断放进原子块，一次成功），WATCH 适合低竞争偶发冲突。

**Q：手写过 Redis 客户端吗？说说 RESP。**
> 直接秀："我手写过：发送 *N\r\n 打头、每个参数 $len\r\n+数据；响应解析 + - : $ 四种前缀。设计上 RESP 是文本行协议——可读易调（telnet 都能玩）但字节开销大；对比 month03 我写的二进制长度头协议（魔数+版本+int 长度+体）——紧凑高效但调试难。选型逻辑：Redis 面向开发者通用性优先，自定义 RPC 面向性能优先。"这段话把两个月的协议知识串成体系。

## 5. 今日验收清单

- [ ] MULTI 不回滚实验记录（tx:b 依然成功）
- [ ] MiniRedisClient 跑通 + MONITOR 观察记录
- [ ] Lua 扣减脚本跑通：售罄返回 -1、限购返回 -2
- [ ] 三方案对比卡默写
- [ ] `git add . && git commit -m "day06: multi/lua + hand-written resp client"`

---
[← Day 05](day05-内存与淘汰策略.md) | [本月目录](README.md) | [Day 07 · 第一周复盘输出 →](day07-第一周复盘输出.md)
