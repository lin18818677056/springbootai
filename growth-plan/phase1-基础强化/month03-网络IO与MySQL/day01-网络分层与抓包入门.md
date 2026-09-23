# Day 01 · 网络分层与抓包入门（Wireshark 逐层拆解一次请求）

> **今日目标**：把"网络分层"从背书变成眼见为实——用 Wireshark 抓一次 HTTP 请求，逐层拆开看 Ethernet/IP/TCP/HTTP 四层头，建立本月抓包实证的习惯。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：一次抓包的逐层标注记录 + 分层对照表（抄进笔记）

## 1. 知识地图

```
OSI 七层（教学模型） vs TCP/IP 四层（工程现实）：

  OSI 七层          TCP/IP 四层        本月关心            典型设备/协议
  ┌──────────┐    ┌──────────┐
  │ 7 应用层  │    │          │    HTTP/HTTPS/DNS     浏览器/网关
  │ 6 表示层  │───→│ 4 应用层  │    TLS(加密在这一层)   Nginx
  │ 5 会话层  │    │          │    RPC 协议            RPC 框架
  ├──────────┤    ├──────────┤
  │ 4 传输层  │───→│ 3 传输层  │    TCP/UDP/端口        ★本月前两周主场
  ├──────────┤    ├──────────┤
  │ 3 网络层  │───→│ 2 网络层  │    IP/路由/分片         路由器
  ├──────────┤    ├──────────┤
  │ 2 数据链路 │───→│ 1 网络接口│    Ethernet/ARP/MAC    交换机/网卡
  │ 1 物理层  │    │          │    比特流              网线/光纤
  └──────────┘    └──────────┘

发送方逐层加头（封装 Encapsulation）→ 接收方逐层剥头（解封装）：
  HTTP 报文 → +TCP头(端口) → +IP头(地址) → +帧头帧尾(MAC) → 比特流
  ★面试直觉："分层 = 关注点分离，每层只对自己那层头负责"

Wireshark 抓包三步：选网卡 → 过滤表达式 → 逐层看
  过滤器（Display Filter）速记：
    http                    只看 HTTP
    tcp.port == 80          只看 80 端口
    ip.addr == 1.2.3.4      只看这个 IP
    tcp.flags.syn == 1      只看 SYN 包（day02 用）
    http.request            只看请求（响应用 http.response）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Encapsulation | 封装 | 每层加上自己的头 |
| Frame / Packet / Segment | 帧/包/段 | 链路层/网络层/传输层的 PDU |
| MTU | 最大传输单元 | 以太网默认 1500B（IP 分片的根源） |
| Display Filter | 显示过滤器 | Wireshark 的抓包后过滤语法 |
| Port | 端口 | 传输层寻址：0-65535，知名端口 0-1023 |
| Round-Trip | 往返 | RTT 是网络延迟的基本测量单位 |

## 3. 动手实操

### 3.1 准备：Wireshark 安装与首次抓包

```powershell
# ① 官网下载 Wireshark Windows 安装包（一路下一步，勾选安装 Npcap）
# https://www.wireshark.org/download.html

# ② 找一个干净的 HTTP 站点（无 HTTPS 更容易看清）：
#    经典练习靶场：http://httpbin.org/get（若被重定向到 https 也没关系，day06 再看）

# ③ 命令行确认路由与接口：
ipconfig /all                       # 看本机 IP、网关、DNS
ping httpbin.org                    # 解析 IP 并测 RTT（记下这个数字，day03 对比）
```

### 3.2 主菜：抓一次 HTTP 请求并逐层拆解

```text
操作步骤（Wireshark 里）：
  ① 选活动网卡开始捕获（WLAN 或以太网）
  ② 浏览器访问 http://httpbin.org/get
  ③ 停止捕获，过滤框输入：http
  ④ 找到 GET /get 那一行 → 选中后看下方三个面板：

逐层拆解记录（把你的实际值填进去）：
  □ Frame 层：帧长度 ___ 字节（观察是否 = 各层头之和 + 数据）
  □ Ethernet II：源 MAC ___、目的 MAC ___（目的应是网关 MAC，用 arp -a 对照）
  □ IP 层：版本 4、TTL ___、协议 6(TCP)、源/目的 IP
  □ TCP 层：源端口 ___（随机高端口）、目的端口 80、序号/确认号、Flags
  □ HTTP 层：请求方法 GET、Host 头、User-Agent、响应状态码 200

  ⑤ 对照实验：再抓一次 https 版本 → 看 HTTP 层变成 TLS Application Data
     （内容加密不可读——这个"看不懂"就是 day06 的引子）
```

### 3.3 实验②：DNS 解析抓包（请求的第一步）

```powershell
ipconfig /flushdns                 # 清空本机 DNS 缓存，强制重新解析
# Wireshark 过滤：dns
nslookup httpbin.org               # 触发一次查询
```

```text
观察记录：
  □ 先查 hosts/缓存，没有才发 UDP 53 端口查询
  □ 看到一次递归查询 + 响应（A 记录 = IP）
  □ 思考：为什么 DNS 用 UDP 而不是 TCP？（短小、无连接状态、超时重传即可；
     响应超 512B 或区域传送才用 TCP）
```

### 3.4 输出：分层对照表（抄进笔记，day07 复盘要用）

```text
| 层 | 报文单位 | 地址 | 协议举例 | 谁在用它（Java 视角） |
|----|---------|------|---------|---------------------|
| 应用 | 消息 | 域名 | HTTP/DNS | RestTemplate/OkHttp/Netty Handler |
| 传输 | 段 | 端口 | TCP/UDP | Socket/ServerSocket |
| 网络 | 包 | IP | IP/ICMP | （OS 内核） |
| 链路 | 帧 | MAC | Ethernet | （网卡驱动） |
```

## 4. 面试连接

**Q：浏览器输入 URL 到页面显示，发生了什么？**
> 分层版标准答案：① URL 解析 → DNS 递归查询（UDP 53）② TCP 三次握手（day02 细讲）③ TLS 握手（若是 HTTPS，day06）④ 发送 HTTP 请求 → 服务器处理 → 响应 ⑤ 浏览器解析渲染。每个步骤都能落到"哪一层、什么协议、抓包能看见什么"——比背八股高一层的是"我用 Wireshark 逐层看过"。

**Q：OSI 七层和 TCP/IP 四层为什么不一样？面试该怎么答？**
> OSI 是理论教学模型（层多便于分知识点），TCP/IP 是工程事实标准（把 5-7 层合并为应用层）。一句话加分："工程上没有 OSI 的 5/6/7 层实现，TLS 通常被认为横跨表示层——这也是 HTTPS 抓包看不到明文的层级原因。"

**Q：MTU 是什么？跟分片有什么关系？**
> 以太网 MTU 1500B，IP 包超过则分片（或 TCP 按 MSS=MTU-40 分段避免 IP 分片）。加分点："现代实践倾向避免 IP 分片（分片丢失整包重传），所以 TCP 协商 MSS、HTTP/2/QUIC 也围绕这个约束设计。"

## 5. 今日验收清单

- [ ] Wireshark 完成一次 HTTP 抓包，五个层级字段记录齐全
- [ ] DNS 抓包观察过（UDP 53 + A 记录）
- [ ] 分层对照表默写
- [ ] 能脱稿讲"输入 URL 之后"（分层版）
- [ ] `git add . && git commit -m "day01: layers & wireshark"`

---
[← Day 00 · 本月目录](README.md) | [Day 02 · TCP 连接管理与状态机 →](day02-TCP连接管理与状态机.md)
