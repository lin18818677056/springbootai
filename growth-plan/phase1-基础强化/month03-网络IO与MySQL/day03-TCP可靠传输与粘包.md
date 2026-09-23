# Day 03 · TCP 可靠传输与粘包（seq/ack、滑动窗口、拥塞控制）

> **今日目标**：回答两个灵魂拷问：TCP 靠什么可靠？（seq/ack 确认 + 重传 + 滑窗 + 拥塞控制）；TCP 为什么会"粘包"？（它是字节流协议，没有消息边界）。今天用 Java 亲手复现粘包，并写出三种解决方案。
> **时长**：理论 1.5h / 实操 1.5h / 输出 0.5h
> **今日产出**：粘包复现记录 + 三种拆包方案代码 + 拥塞控制四阶段图

## 1. 知识地图

```
① 确认与重传（可靠性的根基）：
   每个字节都有序号 seq；接收方 ACK = "下一个期望收到的字节号"
   发送方超时未收到 ACK → 重传；收到三个重复 ACK → 快速重传（不等超时）

② 滑动窗口（Sliding Window）—— 流量控制：
   接收方在 ACK 里通告窗口 rwnd = "我还能收多少"
   发送方未确认在途字节 ≤ min(rwnd, cwnd)
   ┌────────────────────────────────┐
   │ 已确认│ 可发送(窗口) │ 不可发送   │ → 窗口随 ACK 右滑
   └────────────────────────────────┘

③ 拥塞控制（Congestion Control）四阶段：
   慢启动：cwnd 从 1MSS 指数增长（每 RTT 翻倍）
   拥塞避免：到阈值 ssthresh 后线性 +1
   快重传：3 个重复 ACK → 立即重传丢失段
   快恢复：cwnd 减半（而非回到 1）继续线性增长
   ★Wireshark 里看 seq 曲线：起步陡峭（指数）→ 拐弯（线性）→ 掉头（丢包）

④ 粘包/拆包（Sticky/Half Packet）—— 不是 TCP 的 bug，是设计：
   TCP 只保证"字节流按序完整"，不认识"消息"概念
   发送端：Nagle 合并小包；接收端：read() 一次可能捞到 N 条消息或半条
   ┌─────────────────────────────────────────┐
   │ 应用想发: [MSG1][MSG2][MSG3]             │
   │ TCP 交付: [MSG1的后半][MSG2][MSG3][前半]  │ ← 边界全乱了
   └─────────────────────────────────────────┘
   三种拆包方案（day12 在 Netty 里全部落地）：
     定长法：每条固定 100B（浪费，简单场景）
     分隔符：\n 结尾（文本协议，如 Redis）
     长度头：前 4 字节=长度 ★工业标准（HTTP Content-Length 同思路）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| ACK | 确认号 | 期望收到的下一字节序号 |
| Retransmission | 重传 | 超时重传 RTO / 快速重传 |
| Sliding Window | 滑动窗口 | 接收能力通告 rwnd |
| Congestion Window | 拥塞窗口 cwnd | 发送方对网络的估计 |
| Nagle's Algorithm | Nagle 算法 | 合并小包提效率（代价：延迟） |
| Delayed ACK | 延迟确认 | 攒一攒再 ACK（与 Nagle 相互作用引延迟坑） |
| MSS | 最大段大小 | TCP 分段依据 = MTU - 40 |

## 3. 动手实操

### 3.1 主菜：粘包复现（一次 read 收到两条消息）

```java
// StickyServer.java —— 客户端连发 3 条消息，服务端一次 read 全捞到
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class StickyServer {
    public static void main(String[] args) throws Exception {
        try (ServerSocket ss = new ServerSocket(9997)) {
            System.out.println("9997 就绪");
            Socket s = ss.accept();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[1024];
            int n = in.read(buf);                       // ★一次 read
            String raw = new String(buf, 0, n, StandardCharsets.UTF_8);
            System.out.println("一次 read 收到: [" + raw + "]");
            System.out.println("看到粘包了吗？三条消息挤在一起，边界丢失！");
            s.close();
        }
    }
}

// StickyClient.java —— 发送端：循环 write 3 次
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class StickyClient {
    public static void main(String[] args) throws Exception {
        try (Socket s = new Socket("localhost", 9997)) {
            OutputStream out = s.getOutputStream();
            for (int i = 1; i <= 3; i++) {
                out.write(("MSG" + i + "|").getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(1);                        // 飞快连发（sleep 只是给点节奏）
            }
        }
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month03-net-mysql
javac StickyServer.java; javac StickyClient.java
java StickyServer            # 先起服务端
java StickyClient            # 再跑客户端
# 预期服务端输出：一次 read 收到: [MSG1|MSG2|MSG3|]
# ★调大 byte[] 或删掉 sleep 反复跑——无论怎么发，边界都可能丢，这就是粘包
```

### 3.2 修复：长度头协议（工业标准方案）

```java
// LengthProto.java —— 4 字节长度头 + 数据（读写两端配套）
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class LengthProto {
    /** 发送一条消息：前 4 字节 = 消息字节数 */
    public static void send(OutputStream out, String msg) throws IOException {
        byte[] body = msg.getBytes(StandardCharsets.UTF_8);
        out.write(ByteBuffer.allocate(4 + body.length)
                .putInt(body.length).put(body).array());
        out.flush();
    }
    /** 接收一条消息：先读满 4 字节长度，再精确读满 body */
    public static String recv(InputStream in) throws IOException {
        byte[] lenBuf = readFull(in, 4);                 // ★必须读满（半包防线）
        int len = ByteBuffer.wrap(lenBuf).getInt();
        if (len <= 0 || len > 10 * 1024 * 1024) throw new IOException("非法长度: " + len);
        return new String(readFull(in, len), StandardCharsets.UTF_8);
    }
    private static byte[] readFull(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {                                // 循环读满为止
            int r = in.read(buf, off, n - off);
            if (r == -1) throw new EOFException("对端关闭，只读到 " + off + "/" + n);
            off += r;
        }
        return buf;
    }
}

// FixedServer.java —— 用长度头协议重跑（边界完整）
public class FixedServer {
    public static void main(String[] args) throws Exception {
        try (ServerSocket ss = new ServerSocket(9996)) {
            Socket s = ss.accept();
            for (int i = 0; i < 3; i++) {
                System.out.println("第" + (i + 1) + "条: " + LengthProto.recv(s.getInputStream()));
            }
            s.close();
        }
    }
}

// FixedClient.java
public class FixedClient {
    public static void main(String[] args) throws Exception {
        try (Socket s = new Socket("localhost", 9996)) {
            for (int i = 1; i <= 3; i++) LengthProto.send(s.getOutputStream(), "MSG" + i);
        }
    }
}
// 预期：服务端严格收到 MSG1 MSG2 MSG3 三条，顺序正确、边界完整
```

### 3.3 实验③：Wireshark 观察慢启动（选做但强烈推荐）

```powershell
# ① Wireshark 开始抓包（不过滤）
# ② 访问一个大文件直链（如镜像站的 iso/zip），下载 3-5 秒后停止
# ③ Wireshark 菜单：Statistics → TCP Stream Graphs → Time Sequence (Stevens)
# 观察曲线：起步陡峭上升（慢启动指数）→ 斜率变缓（拥塞避免线性）
# → 突然回落（丢包/快恢复）→ 再爬升
# ★截图存档：面试讲拥塞控制时有图为证
```

### 3.4 输出：三种拆包方案对比卡

```text
| 方案 | 原理 | 优点 | 缺点 | 实际用户 |
|------|------|------|------|---------|
| 定长 | 每条固定 N 字节 | 解析最简单 | 填充浪费 | 简单工控协议 |
| 分隔符 | 特殊字符结尾 | 文本可读 | 内容需转义 | Redis(\r\n)、日志 |
| 长度头 | 头部声明长度 | 精准/二进制友好 | 需处理半包 | HTTP、RPC 主流 ★ |
```

## 4. 面试连接

**Q：TCP 为什么会粘包？怎么解决？**
> 先纠正认知："粘包"不是 TCP 的缺陷——它是字节流协议，只保证字节按序完整，本来就不存在"包"的边界；边界是应用层概念。解决三法：定长、分隔符、长度头（主流）。我做过实验：裸 socket 连发三条被一次 read 捞走；改成 4 字节长度头后边界完整。Netty 里对应 FixedLengthFrameDecoder/DelimiterBasedFrameDecoder/LengthFieldBasedFrameDecoder（day12 落地）。

**Q：讲讲 TCP 拥塞控制。**
> 四阶段：慢启动（cwnd 指数增长到 ssthresh）→ 拥塞避免（线性 +1）→ 快重传（3 重复 ACK 立即重传）→ 快恢复（cwnd 减半继续，不回退到 1）。加分实证："我在 Wireshark 的 Time Sequence 图里亲眼看过指数转线性的拐点和丢包回落。"再补一刀：与滑动窗口的关系——实际发送量 = min(rwnd, cwnd)，rwnd 管对端、cwnd 管网络。

**Q：Nagle 算法和延迟确认一起会出什么问题？**
> Nagle 攒小包等 ACK 才发，延迟确认又故意推迟 ACK（最多 40ms）→ 两者相互作用导致写小数据的客户端出现最长约 40ms 的停顿。解法：TCP_NODELAY 关闭 Nagle（RPC/游戏/低延迟场景标配）。这是"两个各自合理的机制组合变坑"的经典案例。

## 5. 今日验收清单

- [ ] 粘包复现成功（截图或记录输出）
- [ ] LengthProto 修复版三条消息边界完整
- [ ] Wireshark 慢启动曲线截图存档
- [ ] 拆包方案对比卡默写
- [ ] `git add . && git commit -m "day03: reliable-tcp & sticky fix"`

---
[← Day 02](day02-TCP连接管理与状态机.md) | [本月目录](README.md) | [Day 04 · TIME_WAIT 与长连接治理 →](day04-TIME_WAIT与长连接治理.md)
