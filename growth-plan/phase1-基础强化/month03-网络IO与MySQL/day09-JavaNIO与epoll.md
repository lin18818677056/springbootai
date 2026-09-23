# Day 09 · Java NIO 与 epoll（手写多路复用 Echo 服务）

> **今日目标**：NIO 三剑客（Channel/Buffer/Selector）逐个上手，手写一个单线程管多连接的 NIO Echo 服务端；讲清 select→poll→epoll 的演进与 epoll 的红黑树+就绪链表。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：NioEchoServer（单线程多连接实证）+ epoll 演进对比表 + 三剑客概念卡

## 1. 知识地图

```
NIO 三剑客（谁负责什么）：

  Selector（监工）── 注册并监听 N 个 Channel 的 4 种事件
  Channel（管道）── 双向数据通道（读/写/连接/接受）
  Buffer（缓冲）── 数据容器，读写的临时站台

  Buffer 四指针（★必考）：
  ┌───────────────────────────────────┐
  │ 0      position      limit    capacity │
  │ ▼         ▼            ▼        ▼      │
  │ [■■■■■□□□□□□□□□□□□□□□□□□□]        │
  │ 已写   下一个读写位置    不可越界   容量     │
  └───────────────────────────────────┘
  写模式 → 读模式：flip()（position 归 0，limit=旧 position）
  清空重写：clear()；保留未读：compact()

select → poll → epoll 演进（为什么 epoll 快）：
  select：fd_set 位图，最多 1024 个；每次调用全量拷贝进内核 + 内核线性扫 O(n)
  poll：  链表去掉 1024 限制；仍是"全量拷贝 + 线性扫"
  epoll： 三件套 ——
     epoll_create 建实例
     epoll_ctl 增删改监听的 fd（红黑树 O(logn) 存储，只注册一次！）
     epoll_wait 只返回"就绪链表"里的 fd（事件回调填充，O(1) 取）
  ★一句话："select/poll 是每次问一遍全班谁完成了作业；
           epoll 是完成后自己举手进就绪名单，老师只念名单。"
  两种触发模式：LT 水平触发（没处理完会一直通知，默认）/
               ET 边缘触发（状态变化只通知一次，必须一次读空——Nginx 用 ET）
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Channel | 通道 | 全双工，可读可写 |
| Buffer | 缓冲区 | position/limit/capacity 四指针（mark 略） |
| Selector | 选择器 | Java 对 epoll/kqueue 的封装 |
| SelectionKey | 选择键 | Channel 与事件的绑定句柄 |
| Level/Edge Triggered | 水平/边缘触发 | LT 反复通知 / ET 一次通知 |
| Ready List | 就绪链表 | epoll_wait 直接拿到的活跃 fd 名单 |

## 3. 动手实操

### 3.1 主菜：单线程 NIO Echo 服务端（多连接实证）

```java
// NioEchoServer.java —— 单线程 + Selector 同时服务多个连接
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;

public class NioEchoServer {
    public static void main(String[] args) throws IOException {
        Selector selector = Selector.open();                       // ① 建监工（epoll 实例）
        ServerSocketChannel server = ServerSocketChannel.open();
        server.configureBlocking(false);                           // ★必须非阻塞
        server.bind(new InetSocketAddress(9993));
        server.register(selector, SelectionKey.OP_ACCEPT);         // ② 注册接受事件
        System.out.println("NIO Echo on 9993 —— 单线程伺候所有连接");

        ByteBuffer buf = ByteBuffer.allocate(256);
        while (true) {
            selector.select();                                     // ③ 阻塞等事件（epoll_wait）
            Set<SelectionKey> keys = selector.selectedKeys();
            Iterator<SelectionKey> it = keys.iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();                                       // ★手动移除（防重复处理）
                if (key.isAcceptable()) {                          // ④ 新连接
                    SocketChannel client = server.accept();
                    client.configureBlocking(false);
                    client.register(selector, SelectionKey.OP_READ);
                    System.out.println("接入: " + client.getRemoteAddress()
                            + " | 线程: " + Thread.currentThread().getName()
                            + " | 活跃线程≈" + Thread.activeCount());
                } else if (key.isReadable()) {                     // ⑤ 可读事件
                    SocketChannel client = (SocketChannel) key.channel();
                    buf.clear();
                    int r = client.read(buf);
                    if (r == -1) { client.close(); continue; }     // 对端关闭
                    buf.flip();                                    // 写→读切换
                    client.write(buf);                             // 原样回显
                }
            }
        }
    }
}

// NioClient.java —— 3 条并发连接（验证单线程伺候）
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class NioClient {
    public static void main(String[] args) throws Exception {
        for (int i = 1; i <= 3; i++) {
            new Thread(() -> {
                try {
                    SocketChannel ch = SocketChannel.open(new InetSocketAddress("localhost", 9993));
                    for (int j = 0; j < 5; j++) {
                        ch.write(ByteBuffer.wrap(("hello-" + Thread.currentThread().getName()).getBytes()));
                        ByteBuffer buf = ByteBuffer.allocate(128);
                        ch.read(buf);
                        buf.flip();
                        System.out.println(Thread.currentThread().getName() + " echo: "
                                + new String(buf.array(), 0, buf.limit()));
                        Thread.sleep(500);
                    }
                    ch.close();
                } catch (Exception e) { e.printStackTrace(); }
            }, "C" + i).start();
        }
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month03-net-mysql
javac NioEchoServer.java; javac NioClient.java
java NioEchoServer             # 终端1
java NioClient                 # 终端2
# 观察点①：3 条连接 echo 正常，服务端活跃线程数始终 ≈2（main+JVM 内务）
#          ——对比 day08 BioServer 的 20 线程，这就是多路复用的账单
# 观察点②：jstack 看服务端唯一的工作线程（可选）
```

### 3.2 Buffer 指针实验（flip/clear 肉眼看）

```java
// BufferWalk.java —— 打印三指针变化
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class BufferWalk {
    public static void main(String[] args) {
        ByteBuffer buf = ByteBuffer.allocate(10);
        show("allocate", buf);
        buf.put("hi".getBytes(StandardCharsets.UTF_8));    show("put hi", buf);
        buf.flip();                                        show("flip(写→读)", buf);
        System.out.println("读出一个: " + (char) buf.get()); show("get 1 字节", buf);
        buf.compact();                                     show("compact(保留未读)", buf);
        buf.clear();                                       show("clear(全部清空)", buf);
    }
    static void show(String op, ByteBuffer b) {
        System.out.printf("%-18s position=%d limit=%d capacity=%d%n",
                op, b.position(), b.limit(), b.capacity());
    }
}
// 预期输出对照：
//   put 后 position=2；flip 后 position=0 limit=2；
//   get 1 字节后 position=1；compact 后未读数据前移 position=1→limit=10
```

### 3.3 输出：select/poll/epoll 对比表

```text
| 维度 | select | poll | epoll |
|------|--------|------|-------|
| fd 上限 | 1024(位图) | 无(链表) | 无(红黑树) |
| 内核拷贝 | 每次全量 | 每次全量 | ctl 时一次 |
| 就绪查找 | O(n) 线性扫 | O(n) 线性扫 | O(1) 就绪链表 |
| 触发模式 | LT | LT | LT/ET 都支持 |
| 适用 | 连接少 | 连接多但活跃少 | 大规模高并发 ★ |
```

## 4. 面试连接

**Q：epoll 为什么比 select/poll 快？**
> 三个点：① 注册一次（红黑树增删改 O(logn)）vs 每次调用全量拷贝 fd 集合；② 就绪 fd 由内核回调挂入就绪链表，epoll_wait 直接取 O(1)，vs 每次 O(n) 全量扫描；③ 支持百万级连接。再补触发模式："LT 没处理完反复提醒（Java NIO 默认），ET 只提醒一次必须读空（Nginx），ET 少一次系统调用但编程要求高。"+"我在单线程 NIO 服务上接过多连接，线程数不随连接数涨。"

**Q：Java NIO 的 Buffer 使用有什么坑？**
> flip 忘调（读了写模式的数据，全 0 或越界）；compact 与 clear 选错（compact 保留未读处理半包，clear 全清）；多线程共享 Buffer 不是线程安全的（Netty 每个 Channel 绑定一个 EventLoop 线程独占 Buffer——衔接 day10/11）。场景化："我写过 BufferWalk 实验把三指针变化全部打印过。"

**Q：Selector 空轮询 bug 听说过吗？**
> JVM/OS 组合下 epoll_wait 可能无事件也返回，导致 select() 疯狂循环烧 CPU。Netty 的解法：计数器检测（512 次空轮询阈值）→ 重建 Selector。能讲这个 bug 说明读过 Netty 源码（衔接 day11 的 NioEventLoop）。

## 5. 今日验收清单

- [ ] NioEchoServer + 3 客户端跑通（单线程实证记录）
- [ ] BufferWalk 三指针变化记录
- [ ] select/poll/epoll 对比表默写
- [ ] LT/ET 区别 + 空轮询 bug 能讲
- [ ] `git add . && git commit -m "day09: nio echo & epoll"`

---
[← Day 08](day08-IO五模型与用户态内核态.md) | [本月目录](README.md) | [Day 10 · Reactor 模式三形态 →](day10-Reactor模式三形态.md)
