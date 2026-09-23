# Day 02 · TCP 连接管理与状态机（握手挥手 + 亲眼数状态）

> **今日目标**：三次握手/四次挥手不是背图，是数出来的。今天手起服务抓 SYN/SYN-ACK/FIN 包，用 netstat 亲眼盯着 SYN_SENT→ESTABLISHED→TIME_WAIT 流转，把状态机焊进脑子里。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：握手挥手抓包标注 + 状态机默写图 + 半/全连接队列实验记录

## 1. 知识地图

```
三次握手（Three-way Handshake）—— 双方各确认一次"我能发你能收"：

  客户端                                服务端(LISTEN)
     │ ① SYN, seq=x        ┌─────────┐  │ 半连接队列(SYN Queue)
     │ ────────────────────→│SYN_RCVD │  │ 收到 SYN 未完成握手
     │                      └─────────┘  │
     │ ② SYN+ACK, seq=y, ack=x+1         │
     │ ←────────────────────             │
     │ ③ ACK, ack=y+1       ┌─────────┐  │ 全连接队列(Accept Queue)
     │ ────────────────────→│ESTABLISHED │ 完成握手等 accept()
     └ ESTABLISHED          └─────────┘  │
  为什么三次：防止"历史重复 SYN"建立废连接（客户端旧 SYN 迟到，
  两次握手服务端就会单方面开门）；本质是双方 seq 都要被确认。

四次挥手（Four-way Handshake）—— 数据可能没发完，要两个方向各自关：

  主动方                                被动方
     │ ① FIN, seq=u        ┌──────────┐
     │ ────────────────────→│CLOSE_WAIT │ 被动方：还有数据先发完
     │ ② ACK, ack=u+1       └──────────┘
     │ ←────────────────────              │
     │ (主动方 FIN_WAIT_2)                 │
     │ ③ FIN, seq=w        ┌──────────┐
     │ ←────────────────────│ LAST_ACK  │ 被动方数据发完才发 FIN
     │ ④ ACK, ack=w+1       └──────────┘
     │ ────────────────────→ CLOSED
     │ TIME_WAIT（2MSL≈60s~4min）
  为什么四次：TCP 全双工，两个方向独立关闭；
  为什么 TIME_WAIT：① 保证最后的 ACK 丢了可重传（对方重发 FIN 我还在）
                   ② 让旧连接的迷路报文自然消亡，避免污染新连接

状态机总览（★day07 默写图①）：
  主动打开：CLOSED→SYN_SENT→ESTABLISHED→FIN_WAIT_1→FIN_WAIT_2→TIME_WAIT→CLOSED
  被动打开：CLOSED→LISTEN→SYN_RCVD→ESTABLISHED→CLOSE_WAIT→LAST_ACK→CLOSED
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Three-way Handshake | 三次握手 | SYN → SYN+ACK → ACK |
| Four-way Handshake | 四次挥手 | FIN/ACK ×2，全双工各关各的 |
| Half-open Connection | 半开连接 | 对端崩溃后本端还以为连着 |
| SYN Queue / Accept Queue | 半/全连接队列 | 溢出 = SYN Flood 或 accept 太慢 |
| MSL | 报文最大生存时间 | Linux 30s，TIME_WAIT=2MSL |
| RST | 重置 | 立刻丢弃连接（不握手不体面） |

## 3. 动手实操

### 3.1 实验①：亲眼抓到三次握手

```powershell
# ① Wireshark 开始捕获，过滤：tcp.flags.syn == 1 or tcp.flags.fin == 1
#    （或先不过滤，抓完再筛 tcp.stream）

# ② 造一次 TCP 连接：访问任意网站（或 telnet）
Test-NetConnection -ComputerName httpbin.org -Port 80

# ③ 在抓包里找同一个流（右键 → Follow → TCP Stream），记录：
#    □ SYN: seq=x, 无 ACK       □ SYN+ACK: seq=y, ack=x+1
#    □ ACK: ack=y+1             □ 数据包的 seq 从 x+1 开始数
#    □ 结束时谁先 FIN？（客户端浏览器通常先关）
```

### 3.2 实验②：Java 小服务 + netstat 盯状态流转

```java
// StateServer.java —— 一个只 accept 不读写的 TCP 服务
import java.net.ServerSocket;
import java.net.Socket;

public class StateServer {
    public static void main(String[] args) throws Exception {
        ServerSocket ss = new ServerSocket(9999);
        System.out.println("LISTEN on 9999，等你连接...（别急着 accept 完）");
        while (true) {
            Socket s = ss.accept();                    // 每接受一个打印一次
            System.out.println("accepted: " + s.getRemoteSocketAddress());
            // 注意：accept 后不 close，连接保持 ESTABLISHED
        }
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month03-net-mysql
javac StateServer.java; java StateServer

# ---- 另开终端观察（客户端一连接就断开，制造 TIME_WAIT）----
Test-NetConnection -ComputerName localhost -Port 9999   # 客户端连一下即断
netstat -ano | Select-String ":9999"
# 记录你看到的状态与角色：
#   客户端侧：TIME_WAIT（主动关闭方，等 2MSL）
#   服务端侧：ESTABLISHED（被动方还没关）
# 多连几次：netstat -ano | Select-String ":9999" | Measure-Object
# 统计全局：netstat -an | Group-Object {($_ -split '\s+')[3]} 
# （简化版：netstat -an | Select-String "TIME_WAIT" | Measure-Object）
```

### 3.3 实验③：CLOSE_WAIT 堆积复现（生产最经典的连接泄漏）

```java
// CloseWaitServer.java —— accept 后永不 close，客户端主动断开
import java.net.ServerSocket;

public class CloseWaitServer {
    public static void main(String[] args) throws Exception {
        ServerSocket ss = new ServerSocket(9998);
        System.out.println("9998: accept 后永远不 close —— 客户端断开试试");
        while (true) ss.accept();                  // 拿到连接后什么都不做
    }
}
```

```powershell
javac CloseWaitServer.java; java CloseWaitServer
# 另开终端：连 3 次然后立刻断开
3..1 | % { Test-NetConnection -ComputerName localhost -Port 9998 | Out-Null }
netstat -ano | Select-String ":9998"
# 现象：服务端侧出现 3 条 CLOSE_WAIT，且永不消失！
# 结论：CLOSE_WAIT 堆积 = "对端已关，你自己的代码没调 close()"
#      生产排查第一步永远是 review 代码的 finally close / try-with-resources
```

### 3.4 观察点（对照理论三问）

```text
1. 你抓到的握手 seq/ack 数字，和"对方确认我的下一字节"怎么对应？
2. 为什么主动关闭方是 TIME_WAIT 而被动方直接 CLOSED？（最后的 ACK 发出去
   无法确认送达，必须留守等对方重发 FIN）
3. 半连接队列溢出会怎样？（SYN 丢包 → 客户端重试 → 服务无响应；
   Linux 下 syncookies 应急，Windows 用 netsh 看队列参数）
```

## 4. 面试连接

**Q：为什么握手三次，挥手四次？**
> 握手三次：两次不能防历史 SYN（旧的重复连接请求迟到会开出废连接），双方初始序号都需要被确认；挥手四次：全双工两个方向独立关闭，被动方收到 FIN 时可能还有数据要发，所以 ACK 和自己的 FIN 分开发（②③不能合并；而握手时 SYN+ACK 无此约束可以合并）。

**Q：TIME_WAIT 为什么是 2MSL？过多怎么治理？**
> ① 最后 ACK 丢失后对方会重传 FIN，我必须留在原地应答（一个 MSL 传去 + 一个 MSL 回）；② 让本连接旧报文在网络中自然消亡，避免串进相同四元组的新连接。治理四招：① 用长连接/连接池从根上减少短连接；② 客户端侧 tcp_tw_reuse+时间戳（Linux）；③ 扩大本地端口范围；④ 让"客户端主动关闭"变成"服务端主动关闭"分担（如 Nginx upstream keepalive）。加分句："我在本机 netstat 里亲眼数过 TIME_WAIT 的 2MSL 存活时间。"

**Q：CLOSE_WAIT 堆积说明什么？**
> 唯一解释：应用代码拿到断开的连接后没有调用 close()（连接泄漏）。排查：先 netstat 确认堆积在服务端 → jstack 看连接处理线程卡在哪 → review IO 代码的异常路径是否漏 close。这是和生产事故直接挂钩的高频题。

## 5. 今日验收清单

- [ ] Wireshark 抓到完整握手挥手，seq/ack 标注记录
- [ ] StateServer 实验：亲眼看到 TIME_WAIT 与 2MSL
- [ ] CloseWaitServer 复现堆积并解释根因
- [ ] 状态机图默写（主动/被动两条路径）
- [ ] `git add . && git commit -m "day02: tcp handshake & states"`

---
[← Day 01](day01-网络分层与抓包入门.md) | [本月目录](README.md) | [Day 03 · TCP 可靠传输与粘包 →](day03-TCP可靠传输与粘包.md)
