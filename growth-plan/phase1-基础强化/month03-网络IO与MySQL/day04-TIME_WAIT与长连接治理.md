# Day 04 · TIME_WAIT 与长连接治理（短连接的账单）

> **今日目标**：day02 看到了 TIME_WAIT 现象，今天算清"短连接的账"：高频短连接会把端口和内存都吃光。做一个"短连接 vs 长连接"的定量实验，输出一份《短连接治理方案》。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：短/长连接压测对比数据 + 《短连接治理方案》文档 + TCP keepalive 实验记录

## 1. 知识地图

```
短连接的隐藏成本（每次请求 = 握手 + 数据 + 挥手）：

  短连接一次生命周期：
    SYN/SYN-ACK/ACK(1RTT) → 请求/响应 → FIN 交换 + TIME_WAIT 2MSL(60s)
    ★一次 10ms 的业务，连接开销可能 5ms，还要留下 60 秒的 TIME_WAIT 尸体

  三大账单：
    ① 端口账单：客户端每个 TIME_WAIT 占一个本地端口（默认动态端口约 1.6 万~6.4 万）
       端口耗尽 = connect 报 "Address already in use"
    ② CPU 账单：握手挥手协议处理 × 每次请求
    ③ 内存账单：每个 TIME_WAIT 的 socket 结构体常驻内核

治理四招（按优先级）：
  ① 长连接 + 连接池（根治）：复用连接，握手挥手成本摊薄到 0
  ② tcp_tw_reuse=1 + tcp_timestamps=1（Linux）：允许复用"安全"的 TIME_WAIT 端口
     （仅出方向有效，且靠时间戳保证旧报文不串包）
  ③ 扩大动态端口范围 ip_local_port_range（缓解）
  ④ 角色反转：让固定角色的服务端承受 TIME_WAIT（服务端端口固定无耗尽问题）

TCP keepalive ≠ HTTP Keep-Alive（高频混淆点）：
  TCP keepalive：内核探测层——空闲 N 秒后发探活包，判断对端死活（默认 7200s 才开始）
  HTTP Keep-Alive：应用层复用——一条 TCP 连接连续发多个 HTTP 请求
  ★两者完全独立！连接池的"保活"通常自己用心跳实现（day12 Netty IdleStateHandler）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Short-lived Connection | 短连接 | 每请求建连/断连 |
| Keep-Alive | 保活 | TCP 探活 / HTTP 复用（两个东西！） |
| Port Exhaustion | 端口耗尽 | TIME_WAIT 堆积的恶果 |
| tcp_tw_reuse | 时间戳复用 | 出方向安全复用 TIME_WAIT |
| Connection Pool | 连接池 | 长连接的工程化管理 |
| Ephemeral Port | 动态端口 | 客户端出方向随机端口 |

## 3. 动手实操

### 3.1 主菜：短连接 vs 长连接定量对比

```java
// ConnBench.java —— 同样 2000 次请求：每次新建 vs 复用一条连接
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class ConnBench {
    public static void main(String[] args) throws Exception {
        benchShort(2000);                            // ① 短连接：2000 次全建断
        benchLong(2000);                             // ② 长连接：1 条连发送 2000 次
    }
    static void benchShort(int n) throws Exception {
        long begin = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            try (Socket s = new Socket("localhost", 9995)) {   // ★每次新建
                OutputStream out = s.getOutputStream();
                out.write("ping".getBytes(StandardCharsets.UTF_8));
                byte[] buf = new byte[64];
                new DataInputStream(s.getInputStream()).readFully(buf, 0, 4);
            }
        }
        System.out.println("短连接 " + n + " 次: " + (System.currentTimeMillis() - begin) + "ms");
    }
    static void benchLong(int n) throws Exception {
        long begin = System.currentTimeMillis();
        try (Socket s = new Socket("localhost", 9995)) {       // ★只建一次
            OutputStream out = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());
            for (int i = 0; i < n; i++) {
                out.write("ping".getBytes(StandardCharsets.UTF_8));
                out.flush();
                byte[] buf = new byte[64];
                in.readFully(buf, 0, 4);
            }
        }
        System.out.println("长连接 " + n + " 次: " + (System.currentTimeMillis() - begin) + "ms");
    }
}

// EchoServer.java —— 配套回显服务（收 4 字节回 4 字节）
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

public class EchoServer {
    public static void main(String[] args) throws Exception {
        try (ServerSocket ss = new ServerSocket(9995)) {
            System.out.println("9995 回显服务就绪");
            while (true) {
                Socket s = ss.accept();
                new Thread(() -> {                       // 每连接一线程（简单演示）
                    try {
                        InputStream in = s.getInputStream();
                        OutputStream out = s.getOutputStream();
                        byte[] buf = new byte[64];
                        int r;
                        while ((r = in.read(buf)) != -1) {
                            out.write(buf, 0, r);
                            out.flush();
                        }
                        s.close();
                    } catch (Exception e) { }
                }).start();
            }
        }
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month03-net-mysql
javac EchoServer.java; javac ConnBench.java
java EchoServer                      # 终端1
java ConnBench                       # 终端2
# 另开终端3 在压测期间统计：
netstat -an | Select-String ":9995" | Select-String "TIME_WAIT" | Measure-Object
# 预期：短连接版耗时约为长连接的数倍，且压测后 TIME_WAIT 瞬间出现一大片
# ★把两组耗时 + TIME_WAIT 峰值数字记进《短连接治理方案》
```

### 3.2 实验②：TCP keepalive 探活观察

```java
// KeepAliveProbe.java —— 设置 keepalive 后阻塞读，对端 kill -9 后看多久发现
import java.net.*;
import java.io.InputStream;

public class KeepAliveProbe {
    public static void main(String[] args) throws Exception {
        Socket s = new Socket("localhost", 9995);
        s.setKeepAlive(true);                        // 开启内核探活
        // Java 不能直接调参 keepIdle/keepIntvl（需 JVM 参数或 native），这里演示开关
        System.out.println("已连接并开启 keepalive，现在去杀掉 EchoServer 进程...");
        InputStream in = s.getInputStream();
        long begin = System.currentTimeMillis();
        int r = in.read();                           // 阻塞等数据
        System.out.println("read 返回 " + r + "，耗时 " + (System.currentTimeMillis() - begin) + "ms");
        // 对端进程被杀：内核会立刻发 RST → read 返回 -1（秒级发现）
        // 若对端是"拔网线/断电"（无 RST）：默认 keepalive 要 7200s+ 才发现！
        // ★结论：生产应用层心跳才是主流（Netty IdleStateHandler，day12 落地）
    }
}
```

### 3.3 输出：《短连接治理方案》模板（填上你的数据）

```markdown
# 短连接治理方案（实验版）
## 现状
  客户端调用某 HTTP 服务 QPS=__，每次新建连接
  netstat 观测：TIME_WAIT 峰值=____，压测 2000 次短连接耗时 ____ms
## 账单
  端口：TIME_WAIT × 60s 存活 → 潜在端口压力 = 峰值 QPS × 60
  延迟：每次握手 1RTT=__ms + 挥手处理
## 治理（按优先级）
  ① 改长连接 + 连接池：实测同负载 2000 次耗时 ____ms（对比短连接）
  ② Linux 侧兜底：tcp_tw_reuse=1 + timestamps=1（仅出方向）
  ③ 端口范围扩大：netsh（Windows）/ ip_local_port_range（Linux）
  ④ 角色反转：让服务端承担主动关闭（服务端口固定不怕 TIME_WAIT）
## 心跳策略
  应用层心跳间隔 30s × 2 次超时判死（内核 keepalive 默认 7200s 不可用）
```

## 4. 面试连接

**Q：线上出现大量 TIME_WAIT 怎么办？**
> 结构化回答：① 先分清角色（谁主动关谁 TIME_WAIT）与量级（netstat 统计）；② 根治靠长连接/连接池——用我的 ConnBench 数据说明收益；③ 系统参数兜底（tw_reuse+timestamps，讲清只对出方向安全的原因是时间戳防旧报文）；④ 高危红线：端口耗尽后 connect 直接报错，业务雪崩。能报出实测数字的候选人极少。

**Q：TCP keepalive 和 HTTP Keep-Alive 的区别？**
> 一句话拆混淆："前者是内核探活机制（默认 2 小时才动，感知对端死活），后者是应用层连接复用（一条连接发多个请求）。"生产实践：连接池自己用心跳管理死活（间隔远小于内核默认），HTTP 的复用靠 Keep-Alive 头 + 服务端 timeout 配置。

**Q：为什么不直接调大动态端口范围解决端口耗尽？**
> 缓解不根治：① 6.4 万端口 × 60s TIME_WAIT 上限约 1000 QPS 短连接（算给学生听）；② 每次握手 CPU/延迟成本仍在；③ 端口多了 SNAT/防火墙规则压力。参数是"争取时间"，长连接改造才是"还债"。

## 5. 今日验收清单

- [ ] ConnBench 两版数据记录（短 vs 长耗时 + TIME_WAIT 峰值）
- [ ] KeepAliveProbe 完成 RST 感知实验
- [ ] 《短连接治理方案》文档成文
- [ ] TCP keepalive vs HTTP Keep-Alive 区分能脱口而出
- [ ] `git add . && git commit -m "day04: timewait governance"`

---
[← Day 03](day03-TCP可靠传输与粘包.md) | [本月目录](README.md) | [Day 05 · HTTP 演进与多路复用 →](day05-HTTP演进与多路复用.md)
