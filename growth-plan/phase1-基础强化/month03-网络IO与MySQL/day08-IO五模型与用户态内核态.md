# Day 08 · IO 五模型与用户态内核态（打地基日）

> **今日目标**：把最容易混淆的两组概念钉死：同步/异步（消息怎么通知）、阻塞/非阻塞（等待时干不干什么）。今天用 BIO 亲手体验"一线程一连接"的线程爆炸，引出为什么需要 NIO。
> **时长**：理论 1.5h / 实操 1h / 输出 0.5h
> **今日产出**：五模型对比表（默写版）+ BIO 线程数实验记录 + 概念纠错笔记

## 1. 知识地图

```
先钉死两个维度（面试纠错专用）：
  阻塞 vs 非阻塞：调用后线程"停没停"（停=阻塞，不停轮询/继续=非阻塞）
  同步 vs 异步：  "数据从内核到用户空间的拷贝"谁来干
                 （应用程序自己读=同步；内核拷好再通知你=异步）

五种 IO 模型（Unix 定义的五把椅子）：
  ① 阻塞 IO（BIO）
     read() → 没数据 → 线程睡在那 → 数据到了拷贝 → 返回
     ★"等+拷"全程阻塞；Java 的 ServerSocket.accept()/InputStream.read
  ② 非阻塞 IO（NIO 的"非阻塞"面）
     read() → 没数据立刻返回 -1（EWOULDBLOCK）→ 应用轮询
     ★等的过程不睡但要反复问；拷贝阶段仍同步
  ③ IO 多路复用（Multiplexing）★主流
     select/poll/epoll 帮你盯 N 个 fd → 哪个就绪通知你 → 你再 read（同步拷贝）
     ★一个线程管几千连接的钥匙；Java NIO 的 Selector、Redis/Netty/Nginx
  ④ 信号驱动 IO（SIGIO）—— Linux 网络上少用，了解即可
  ⑤ 异步 IO（AIO）
     发起 aio_read → 内核把数据拷到你的缓冲区 → 再通知你"全好了"
     ★连拷贝都不用你干；Windows IOCP 成熟，Linux io_uring 新秀，Java AIO 生态弱

常见纠错（面试金句）：
  ✗ "NIO 就是非阻塞就是异步" —— 三个词三个维度！
    Java NIO = 同步非阻塞（Selector 监听后仍需自己 read 拷贝）
  ✗ "epoll 是异步" —— epoll 只解决"等"的问题，拷贝还是你自己 read
  ✔ Java 真异步 AIO 存在（AsynchronousSocketChannel）但生态弱，生产主流是多路复用

用户态/内核态背景知识：
  read() 系统调用：用户态 → 切内核态 → 网卡 DMA 到内核缓冲 → 拷到用户缓冲 → 切回
  两次拷贝 + 两次态切换 = 每次读的固定税（day12 零拷贝就是省这笔）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Blocking IO | 阻塞 IO | 等待+拷贝全程停 |
| Non-blocking IO | 非阻塞 IO | 没数据立刻返回，轮询 |
| Multiplexing | 多路复用 | 一个监工管所有 fd |
| Asynchronous IO | 异步 IO | 内核拷贝完成后才通知 |
| User/Kernel Space | 用户态/内核态 | 系统调用切换的成本 |
| fd (File Descriptor) | 文件描述符 | socket/文件在进程里的句柄 |

## 3. 动手实操

### 3.1 实验①：BIO 的线程账单（一连接一线程）

```java
// BioServer.java —— 经典 BIO：每来一个连接开一个线程
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

public class BioServer {
    static final AtomicInteger THREADS = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        try (ServerSocket ss = new ServerSocket(9994)) {
            System.out.println("BIO: 每连接一线程模式");
            while (true) {
                Socket s = ss.accept();                 // 主线程阻塞 accept
                new Thread(() -> handle(s)).start();    // ★一线程一连接
            }
        }
    }
    static void handle(Socket s) {
        int id = THREADS.incrementAndGet();
        System.out.println("连接 " + id + " 分配线程 " + Thread.currentThread().getName()
                + " | 当前活跃线程数≈" + Thread.activeCount());
        try {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            byte[] buf = new byte[64];
            int r;
            while ((r = in.read(buf)) != -1) {          // ★read 阻塞：连接不说话线程就白占
                out.write(buf, 0, r);
                out.flush();
            }
        } catch (Exception e) {
        } finally { try { s.close(); } catch (Exception e) {} }
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month03-net-mysql
javac BioServer.java; java BioServer
# 另开终端批量开连接（每个连接立刻静默，不发送数据）：
1..20 | % { Start-Process -WindowStyle Hidden java -ArgumentList "-cp",".","SilentClient" }
```

```java
// SilentClient.java —— 连上不说话（制造 20 个阻塞的 read）
import java.net.Socket;

public class SilentClient {
    public static void main(String[] args) throws Exception {
        Socket s = new Socket("localhost", 9994);
        Thread.sleep(120_000);                       // 静默 2 分钟
        s.close();
    }
}
// 观察服务端输出：20 个连接 = 20 个工作线程全部卡在 read
// ★结论：1000 连接 = 1000 线程 ≈ 1GB 栈内存（1MB/线程）+ 疯狂上下文切换
// 这就是"为什么需要 IO 多路复用"的现场证据
```

### 3.2 实验②：非阻塞模式轮询（对比感受）

```java
// NonBlockingPeek.java —— setBlocking(false) 后 read 立即返回
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class NonBlockingPeek {
    public static void main(String[] args) throws Exception {
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);                          // ★非阻塞开关
        ch.connect(new InetSocketAddress("localhost", 9994));
        while (!ch.finishConnect()) { /* 自旋等连上 */ }
        ch.setOption(StandardSocketOptions.SO_RCVBUF, 4096);

        long polls = 0;
        long begin = System.currentTimeMillis();
        ByteBuffer buf = ByteBuffer.allocate(64);
        while (System.currentTimeMillis() - begin < 3000) {   // 轮询 3 秒
            int r = ch.read(buf);                             // ★没数据立刻返回 0，不睡
            polls++;
            if (r > 0) { System.out.println("读到 " + r + " 字节"); break; }
            Thread.sleep(0);                                  // 稍微让出 CPU（纯轮询会烧满核）
        }
        System.out.println("3 秒轮询了 " + polls + " 次 —— 轮询的 CPU 代价可见一斑");
        ch.close();
    }
}
// 两次实验的结论对撞：
//   BIO：不烧 CPU 但一个连接一个线程
//   非阻塞轮询：一个线程但 CPU 空转
//   ★答案=多路复用：一个线程 + 事件驱动不空转（day09 epoll 上场）
```

### 3.3 五模型对比表（默写目标）

```text
| 模型 | 等待阶段 | 拷贝阶段 | 线程数(千连接) | 代表 |
|------|---------|---------|--------------|------|
| BIO 阻塞 | 阻塞 | 同步 | 1000+ | 老项目/数据库驱动 |
| 非阻塞 | 轮询 | 同步 | 1(烧CPU) | 少用（过渡形态） |
| 多路复用 | epoll 通知 | 同步 read | 1~几个 | Java NIO/Netty/Redis ★ |
| 信号驱动 | SIGIO 通知 | 同步 | 1 | Linux 网络少用 |
| AIO 异步 | 不等 | 内核拷完通知 | 1 | IOCP/io_uring |
```

## 4. 面试连接

**Q：同步/异步、阻塞/非阻塞的区别？很多人混着说，你怎么界定？**
> 两个正交维度：阻塞/非阻塞看"调用线程停不停"（等数据就绪的阶段）；同步/异步看"数据从内核拷到用户空间"这件事由谁完成——自己 read 是同步，内核拷好再回调你是异步。然后纠错："Java NIO 准确说是同步非阻塞，epoll 只负责通知就绪，拷贝仍由应用完成。"能主动纠错说明真懂。

**Q：为什么生产主流是多路复用而不是 AIO？**
> 三点：① Linux 原生 AIO 支持残缺（O_DIRECT 限制等），io_uring 较新生态在建；② 多路复用+线程池已能把 IO 线程压到个位数（Netty 默认 2×核数），瓶颈不在拷贝；③ 生态：Netty/Kafka/Redis 全是 epoll 路线，工具链成熟。补一刀：Windows IOCP 是成熟 AIO，所以 Java AIO 在 Windows 反而表现更好——跨平台权衡的活案例。

**Q：BIO 一定不好吗？**
> 不是，看场景：连接数少（数据库连接池、内部 RPC 固定几十条连接）时 BIO 简单可靠，JDBC 至今是阻塞 API——因为"连接有限+池化"场景下阻塞模型最省心。多路复用解决的是"海量连接"问题，C10K 之外的 CRUD 服务没必要为了 NIO 而 NIO。场景化回答 > 技术站队。

## 5. 今日验收清单

- [ ] BioServer 20 连接实验完成（线程数截图/记录）
- [ ] NonBlockingPeek 轮询次数记录
- [ ] 五模型对比表默写
- [ ] "NIO≠非阻塞≠异步"纠错能脱口而出
- [ ] `git add . && git commit -m "day08: 5 io models & bio bill"`

---
[← Day 07](day07-第一周复盘输出.md) | [本月目录](README.md) | [Day 09 · Java NIO 与 epoll →](day09-JavaNIO与epoll.md)
