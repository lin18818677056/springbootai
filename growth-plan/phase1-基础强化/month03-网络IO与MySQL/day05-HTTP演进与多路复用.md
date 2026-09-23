# Day 05 · HTTP 演进与多路复用（1.1 → 2 → 3 的三连跳）

> **今日目标**：HTTP 每一代解决什么问题？今天用 curl 实测 HTTP/1.1 与 HTTP/2 的资源加载差异，看穿"队头阻塞"在 1.1 的应用层和 2 的 TCP 层各是什么，最后理解 HTTP/3 为什么敢换掉 TCP。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：HTTP 三代对比表 + curl 实测数据 + 队头阻塞两层解读笔记

## 1. 知识地图

```
HTTP 演进一张图（每代核心痛点 → 核心解法）：

  HTTP/1.0          HTTP/1.1                HTTP/2                HTTP/3
  ─────────        ─────────               ─────────             ─────────
  每请求一条         Keep-Alive 长连接        二进制分帧              QUIC(UDP)
  连接              （复用但仍排队）          + 多路复用              + 0-RTT 握手
  频繁握手           队头阻塞①：              队头阻塞②：             彻底解决
  5 并发上限         响应必须按序返回          TCP 层丢包全队等待       （流独立交付）
                   管道化默认关闭            头部压缩 HPACK
                                            服务器推送
                                            仅 HTTPS

  队头阻塞（Head-of-Line Blocking）的两层理解（高频考点）：
  ① HTTP/1.1 应用层阻塞：一条连接一次只处理一个请求-响应
     → 前一个响应没回来，后面的只能排队（浏览器开 6 条并发缓解）
  ② HTTP/2 的残留：应用层流(stream)独立了，但都跑在一条 TCP 上
     → TCP 只看到一个字节流，任何一个包丢失，所有流都要等重传！
     ★"HTTP/2 在应用层解决了队头阻塞，却把它推给了 TCP"
  ③ HTTP/3/QUIC：UDP 上自己实现可靠传输，流之间独立交付 → 终结

  其他关键机制：
    HPACK：静态表(61 个常见头) + 动态表 + Huffman，头体积降 85%+
    服务器推送：提前推静态资源（实践中收益存疑，Chrome 已移除支持）
    0-RTT：QUIC 首包即带数据（复用会话时），弱网收益巨大
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Head-of-Line Blocking | 队头阻塞 | 1.1 应用层 / 2 TCP 层 两处 |
| Multiplexing | 多路复用 | 2 的核心：一条连接并发多个流 |
| Stream / Frame | 流/帧 | 2 的抽象单元：流=逻辑通道，帧=传输单位 |
| HPACK | 头部压缩 | 静态表+动态表+Huffman |
| QUIC | 快速 UDP 网络连接 | HTTP/3 底层：UDP+TLS1.3 内嵌 |
| 0-RTT | 零往返 | QUIC 会话恢复时首包带数据 |

## 3. 动手实操

### 3.1 实验①：curl 实测 HTTP/1.1 vs HTTP/2

```powershell
# Windows 10/11 自带 curl（Schannel 版支持 HTTP/2）
# ① 看 curl 版本与协议支持
curl --version

# ② HTTP/1.1 请求（强制）
curl -s -o NUL --http1.1 -w "http1.1 总耗时=%{time_total}s 建连=%{time_connect}s\n" `
  https://httpbin.org/get

# ③ HTTP/2 请求（协商）
curl -s -o NUL --http2 -w "http2   总耗时=%{time_total}s 建连=%{time_connect}s\n" `
  https://httpbin.org/get

# ④ 查看响应头里的协议版本
curl -s -I --http2 https://httpbin.org/get | Select-String "HTTP"
# 多跑几组取平均，把数据记下来（本地网络下差距可能不明显，重在流程）

# ⑤ 多资源场景对比（8 个小文件，1.1 排队 vs 2 并发流）
#    用 httpbin 的 image 接口模拟：
1..8 | % { Measure-Command { curl.exe -s -o NUL --http1.1 https://httpbin.org/bytes/1000 } | Select-Object -ExpandProperty TotalMilliseconds }
1..8 | % { Measure-Command { curl.exe -s -o NUL --http2 https://httpbin.org/bytes/1000 } | Select-Object -ExpandProperty TotalMilliseconds }
```

### 3.2 实验②：Wireshark 看 HTTP/2 的多路复用

```text
操作步骤：
  ① Wireshark 开始捕获，过滤：http2
  ② 访问任意 HTTP/2 站点（如 https://www.taobao.com 或 github.com）
  ③ 观察：
     □ 连接建立后的 SETTINGS 帧（协商参数）
     □ HEADERS 帧的 stream id（1/3/5/7... 客户端流，2/4/6 服务端推）
     □ 同一时刻多条流的数据帧交错出现 = 多路复用实锤
     □ DATA 帧属于不同 stream id → 一条 TCP 连接上的"并行"
  ④ 对照实验：过滤 http（1.1 站点）→ 看到的是完整明文请求-响应成对出现
```

### 3.3 实验③：观察协议协商 ALPN（衔接 day06 TLS）

```powershell
# curl -v 的握手输出里能看到 ALPN 协商：
curl -v --http2 -o NUL https://httpbin.org/get 2>&1 | Select-String "ALPN"
# 输出示例：* ALPN: offers h2,http/1.1 ... ALPN: server accepted h2
# ★HTTP/2 实践上只跑在 TLS 上，协议选择在 TLS 握手里完成（ALPN 扩展）
```

### 3.4 输出：HTTP 三代对比表（抄进笔记，day07 串讲用）

```text
| 维度 | HTTP/1.1 | HTTP/2 | HTTP/3 |
|------|----------|--------|--------|
| 传输层 | TCP | TCP | QUIC(UDP) |
| 报文格式 | 文本 | 二进制帧 | 二进制帧 |
| 并发方式 | 6 连接×串行 | 1 连接×多流 | 多流+流独立 |
| 队头阻塞 | 应用层有 | TCP 层残留 | 已解决 |
| 头部 | 明文重复 | HPACK 压缩 | QPACK 压缩 |
| 握手成本 | TCP+TLS | TCP+TLS | 1-RTT/0-RTT |
| 部署要求 | 明文可用 | 实践必须 TLS | UDP 443 开放 |
```

## 4. 面试连接

**Q：HTTP/2 解决了什么问题？还剩什么问题？**
> 解决四件：① 多路复用（一条连接并发多流，替代 6 连接）；② 队头阻塞的应用层部分（流之间不等）；③ HPACK 头压缩；④ 二进制分帧（解析高效）。残留：TCP 层队头阻塞——丢一个包，所有流都得等重传，因为 TCP 只看到字节流。所以 HTTP/3 干脆换 QUIC：流独立交付。

**Q：HTTP/3 为什么基于 UDP？TCP 不好吗？**
> 三个原因：① TCP 在内核实现，应用无法改造（队头阻塞修不了、拥塞控制升级慢）；② QUIC 在用户态实现可靠传输，流独立+可插拔拥塞控制；③ UDP 无连接状态，QUIC 用 Connection ID 实现连接迁移（WiFi 切 4G 不断线）。补充：QUIC 内嵌 TLS 1.3，安全性不降级，握手还从 2-RTT 降到 1-RTT/0-RTT。

**Q：什么是队头阻塞？举一个实际影响。**
> 先答两层：HTTP/1.1 应用层（响应按序）与 HTTP/2 的 TCP 层（丢包全队等）。实际影响举例：HTTP/2 页面里一张大图和关键 CSS 同流传输，丢包时 CSS 渲染也被卡住——多路复用的并行性被 TCP 的串行性抵消。能讲出"两层"的人明显更懂。

## 5. 今日验收清单

- [ ] curl 实测两组数据记录（1.1 vs 2）
- [ ] Wireshark 观察到 HTTP/2 多流交错（截图）
- [ ] ALPN 协商输出记录
- [ ] 三代对比表默写 + 两层队头阻塞能讲清
- [ ] `git add . && git commit -m "day05: http evolution"`

---
[← Day 04](day04-TIME_WAIT与长连接治理.md) | [本月目录](README.md) | [Day 06 · HTTPS 与 TLS 1.3 →](day06-HTTPS与TLS1.3.md)
