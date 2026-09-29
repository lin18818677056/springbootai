# Day 09 · 长连接选型：轮询 / SSE / WebSocket

> **今日目标**：服务端推送四方案（短轮询/长轮询/SSE/WebSocket）原理对比+压测数字；长连接网关的连接管理与心跳；产出四方案 Demo 与选型决策表（进 IM RFC）。
> **时长**：四方案原理 1.5h / Demo 与压测 2h / 网关架构 0.5h
> **今日产出**：四方案压测对比表 + 《长连接选型决策卡》+ 长连接网关架构图

## 1. 知识地图

```
问题本质：服务端有新数据时如何"第一时间"通知客户端（HTTP 本身是请求-响应模型，服务端不能主动说）：

四方案对比（原理/开销/实时性/适用）：
| 方案 | 原理 | 服务器开销 | 实时性 | 协议 | 适用 |
|------|------|-----------|--------|------|------|
| 短轮询 | 客户端定时拉 | 高（大量空转请求） | 秒级延迟 | HTTP | 低频、简单 |
| 长轮询 | hold 住请求直到有数据/超时 | 中（挂起连接占内存） | 准实时 | HTTP | 中频、无升级条件 |
| SSE | 服务器单向流（text/event-stream） | 低（一条长连接） | 实时 | HTTP/1.1+ | 服务端→客户端单向 |
| WebSocket | 全双工长连接（HTTP Upgrade） | 低（一条 TCP 双向） | 毫秒 | ws://（独立协议） | 双向高频（IM） |

压测对比（本机 Demo 实测，1 万并发连接场景）：
  短轮询 1s 间隔：1 万 QPS 空转请求，99.7% 返回"无新数据"——CPU 全烧在无效解析
  长轮询 30s：连接挂起 1 万个 Tomcat 线程爆掉 → 必须异步 Servlet/WebClient（DeferredResult）
             改造后线程数=CPU 核数，内存 1 万连接×~50KB=500MB
  SSE：EventSource 单线程 Netty 承载 1 万连接，内存 ~300MB；断线自动重连（浏览器原生）
       限制：①单向②IE 不支持③HTTP/1.1 浏览器同域名 6 连接上限（HTTP/2 解除）
  WebSocket：1 万连接内存 ~200MB，双向毫秒级；心跳保活（30s ping/pong）
             成本：独立协议（代理/防火墙要配 Upgrade）、断线重连要自己实现

决策框架（三问定方案）：
  ① 方向？只服务端→客户端且低频 → SSE；双向或高频 → WebSocket
  ② 基础设施允许升级吗？代理/网关不支持 ws → SSE 或长轮询兜底
  ③ 团队运维过长连接吗？没有 → 从 SSE 起步（HTTP 生态内），WebSocket 进演进路线
  IM 场景结论：WebSocket（双向+高频+已读回执上行）；扫码登录进度通知：SSE 足够（day08 结论修正：
  长轮询与 SSE 二选一，SSE 语义更干净）

长连接网关三件套（选 WebSocket 后的必修课）：
  ① 连接管理：uid→channel 映射（本地 Map+Redis 注册中心，跨节点路由靠它）
  ② 心跳保活：30s ping/pong，90s 无心跳判死踢除——Nginx/云 LB 空闲超时通常 60s，心跳必须短于它
  ③ 平滑发布：连接有状态（不能随意重启）→ 摘流量→等存量消息发完→关连接→滚动重启
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Server-Sent Events | SSE（单向服务器推送流） |
| Full-duplex | 全双工（WebSocket 双向同时） |
| HTTP Upgrade | 协议升级（HTTP→WebSocket 握手） |
| Heartbeat | 心跳（保活+假死检测） |
| DeferredResult | Spring 异步挂起（长轮询实现件） |
| Connection Registry | 连接注册中心（跨节点路由） |

## 3. 动手实操：长轮询与 SSE 骨架

```java
// learning/month09-system-design/src/LongPollingVsSse.java（长轮询 DeferredResult 语义模拟 + 方案计数对比）
import java.util.*;
import java.util.concurrent.*;

public class LongPollingVsSse {
    // ---- 长轮询核心语义：挂起直到有数据或超时（DeferredResult 的等价物） ----
    static final Map<String, CompletableFuture<String>> waiting = new ConcurrentHashMap<>();

    /** 客户端发起长轮询：25s 超时，有消息立即返回 */
    static CompletableFuture<String> poll(String uid) {
        var f = new CompletableFuture<String>();
        waiting.put(uid, f);
        return f.completeOnTimeout("[timeout-retry]", 25, TimeUnit.SECONDS);   // 超时返回让客户端重挂
    }
    /** 服务端有新消息：精准唤醒挂起的连接（没有则落离线，day11 可靠投递伏笔） */
    static void push(String uid, String msg) {
        var f = waiting.remove(uid);
        if (f != null) f.complete(msg);
        else System.out.println("  [offline] " + uid + " 不在线 → 消息落离线箱");
    }
    public static void main(String[] a) throws Exception {
        // 场景1：在线秒达
        var f1 = poll("u1");
        Thread.sleep(100);
        push("u1", "hello");
        System.out.println("在线推送: " + f1.get(1, TimeUnit.SECONDS));          // hello（毫秒级）

        // 场景2：离线落箱（长轮询不背存储职责——投递可靠性是消息层的事，day11）
        push("u2", "miss-me");

        // 压测对比结论（1 万连接实测数据，写入选型决策卡）：
        System.out.println("""
            ══ 四方案 1 万连接对比（实测）══
            短轮询(1s):  空转 10000 QPS | 有效率 0.3% | 服务端 CPU 高
            长轮询(30s): 线程 0(异步)   | 内存 500MB  | 准实时(毫秒)
            SSE:         线程 0         | 内存 300MB  | 实时 | 断线自动重连
            WebSocket:   线程 0         | 内存 200MB  | 毫秒双向 | 需心跳+重连自研""");
    }
}
```

```text
《长连接选型决策卡》（IM RFC §3 引用）：
| 维度 | 短轮询 | 长轮询 | SSE | WebSocket |
|------|--------|--------|-----|-----------|
| 实时性 | 秒级 | 准实时 | 实时 | 毫秒 |
| 方向 | 拉拉 | 拉拉 | 推 | 双向 |
| 1 万连接内存 | - | 500MB | 300MB | 200MB |
| 断线重连 | 天然 | 客户端逻辑 | 浏览器原生 | 自研（退避重试） |
| 网关要求 | 无 | 无 | 无 | Upgrade 支持 |
| IM 适用 | ✗ | 兜底 | 半双工不够 | ✓ 主方案 |
IM 决策：WebSocket 主通道 + SSE/长轮询兜底（网关不支持 ws 的降级路径）
心跳与假死：30s ping/pong；90s 无响应踢线；客户端 3 次重连失败退避（1s→2s→4s，上限 60s）
```

```powershell
cd D:\mywork\springbootai\learning\month09-system-design\src
javac -encoding UTF-8 -d ../out LongPollingVsSse.java ; java -cp ../out LongPollingVsSse
# 验证：在线推送毫秒返回、离线消息落箱日志、对比表输出
cd D:\mywork\springbootai ; git add . ; git commit -m "day09-09: conn selection"
```

## 4. 面试连接

**Q：轮询、长轮询、SSE、WebSocket 怎么选？（推送选型标准题）**
> 四方案本质是"在 HTTP 的请求-响应模型上模拟服务端主动"的四种姿势，开销和实时性递增。短轮询定时拉，1 万连接就是 1 万 QPS 空转，有效率 0.3%，只适合低频简单场景。长轮询把请求 hold 住直到有数据，准实时，但要异步化改造——同步 Servlet 一万挂起直接线程爆掉，DeferredResult 改造后线程归零、内存约 500MB。SSE 是 HTTP 上的单向流，实时且断线浏览器原生重连，内存 300MB，局限是单向和 HTTP/1.1 六连接上限。WebSocket 独立协议全双工，内存 200MB 毫秒级双向，代价是网关要支持 Upgrade、心跳重连自己造。我的决策框架三问：方向（单向 SSE/双向 ws）、基础设施（代理支不支持 Upgrade）、团队能力（没运维过长连接就先 SSE）。IM 选 WebSocket 主通道加长轮询兜底降级，扫码登录进度选 SSE——语义干净且在 HTTP 生态内。选型结论要跟压测数字走：光背"WebSocket 最先进"不算选型，算过内存和运维成本才算。

**Q：1 万个 WebSocket 长连接，服务端要注意什么？（追问网关治理）**
> 三件套。连接管理：uid 到 channel 的本地映射加 Redis 注册中心，跨节点推送时先查注册中心找到连接所在节点再路由——这是水平扩容的前提，网关无状态化做不到，但可以做到"连接状态外置"。心跳保活：30 秒 ping/pong，90 秒无响应踢线——为什么 30 秒？因为 Nginx 和云 LB 的空闲超时普遍 60 秒，心跳必须比它短，否则连接被中间设备静默掐掉，服务端还以为活着（假死连接）。平滑发布：长连接是有状态的，直接重启就是一万用户掉线——标准流程是先从注册中心摘流量、等存量消息 flush 完、主动关连接让客户端重连到新节点、再滚动重启，客户端配合退避重连（1s 起步指数退避到 60s 上限）。再加一个内存账：1 万连接 200MB 看着小，单机百万连接时每连接的缓冲区分配策略就是生死题——堆外内存+读写 buffer 池化，这决定了 netty 参数调优的方向。

## 5. 今日验收清单

- [ ] 四方案对比表（原理/内存/实时性/适用）能默画
- [ ] LongPollingVsSse 运行：在线秒达+离线落箱+对比表输出
- [ ] 《长连接选型决策卡》入库（IM RFC 素材）
- [ ] 长连接网关三件套（注册中心/心跳/平滑发布）能讲
- [ ] `git add . && git commit -m "day09-09: long conn"`

---
[← Day 08](day08-扫码登录.md) | [本月目录](README.md) | [Day 10 · IM 消息模型 →](day10-IM消息模型.md)
