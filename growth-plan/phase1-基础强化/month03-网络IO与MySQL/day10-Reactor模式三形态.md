# Day 10 · Reactor 模式三形态（Redis/Netty/Kafka 对号入座）

> **今日目标**：Reactor 是多路复用的"设计模式化身"。今天画三张结构图（单/多线程、主从），把 Redis、Netty、Kafka、Nginx 逐一对号入座，并用 Proactor 对照收尾——这是面试"讲讲线程模型"的标准答案骨架。
> **时长**：理论 1.5h / 实操 1h / 输出 0.5h
> **今日产出**：三张 Reactor 结构图 + 中间件对号表 + Proactor 对比卡

## 1. 知识地图

```
Reactor 的灵魂一句话：事件驱动 + IO 多路复用 + 回调分发
（"别派线程去等，等有了事件再叫人"）

① 单 Reactor 单线程（Redis 6 之前的网络模型）：
  ┌──────────────────────────────────┐
  │  [epoll] ←── Reactor(同一线程)      │
  │    ↓ accept / read / write / 业务  │
  └──────────────────────────────────┘
  优点：无锁无竞争、实现简单
  死穴：业务耗时=全员卡住；一个核的性能上限
  （Redis 快是因为内存操作微秒级，纯 IO 线程也够用）

② 单 Reactor 多线程：
  ┌──────────────────────────────────────┐
  │  [epoll] ←── Reactor(负责 IO 事件)      │
  │    ↓ read 出数据                        │
  │  → Worker 线程池跑业务 → 结果交回 Reactor 写出 │
  └──────────────────────────────────────┘
  优点：业务并行；注意：IO 仍是单点，读写竞争要加锁/队列

③ 主从 Reactor 多线程（★Netty 默认模型）：
  ┌────────────────────────────────────────────┐
  │  MainReactor(boss, 1 个线程)                 │
  │    └ 只干 accept → 把新连接分发给 SubReactor   │
  │  SubReactor(worker, N 个线程, N≈2×核数)       │
  │    └ 每个线程一个 epoll，绑定一批连接的读写事件  │
  │    └ 业务通常再交业务线程池（或干脆在 handler 里跑）│
  └────────────────────────────────────────────┘
  优点：连接接入与 IO 处理分离；一个连接永远固定在一个线程
  → 无锁串行处理（month02 day23 线程封闭思想的网络版！）

对号入座（面试常考）：
  Redis 6 前：①（6.0 起多线程 IO 读写，命令执行仍单线程）
  Netty：     ③（bossGroup + workerGroup）
  Kafka：     ③ 变体（Acceptor + Processor + Handler 线程池）
  Nginx：     ③ 变体（master + worker 进程，每 worker 一个 epoll）

Proactor 对照（异步版）：
  Reactor：通知你"可以读了" → 你自己 read（同步读）
  Proactor：你发起读 → 内核读好拷到你的缓冲区 → 通知你"读完了"
  对应：epoll=Reactor 家；Windows IOCP/Linux io_uring=Proactor 家
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Reactor | 反应器 | 事件驱动 IO 分发模式 |
| Proactor | 前摄器 | 异步 IO 完成通知模式 |
| Boss / Worker | 主/从事件循环 | Netty 的名字：bossGroup/workerGroup |
| Event Loop | 事件循环 | 一个线程 + 一个 Selector 死循环 |
| Connection Binding | 连接绑定 | 一条连接固定一个 worker（无锁） |
| Acceptor | 接受器 | 只管 accept 的角色 |

## 3. 动手实操

### 3.1 主菜：三张结构图手绘（配对号说明）

```text
图① 单 Reactor 单线程（Redis 经典）：
  客户端×N → [epoll_wait] → Reactor 线程 → accept/read/write/执行命令
  瓶颈标注：⏰ 业务 10ms = 所有连接卡 10ms

图② 单 Reactor 多线程：
  [epoll_wait] → Reactor 线程(read/write) ⇄ Worker 池(业务)
  瓶颈标注：⏰ IO 单点 + 读写结果交接的同步开销

图③ 主从 Reactor 多线程（Netty）：
  boss(1 线程) --accept--> 分发
      ↓
  worker-1(epoll+连接组A)  worker-2(epoll+连接组B) ...
  亮点标注：🔒 连接固定线程 → handler 内无锁

每张图配一行"谁在用"：①Redis(6 前) ②少量框架 ③Netty/Nginx/Kafka
```

### 3.2 实验：用 day09 的 NioEchoServer 改造成"主从雏形"

```java
// MasterSlaveEcho.java —— 主 Selector 管接入，子 Selector 池管读写（教学版主从）
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

public class MasterSlaveEcho {
    static final Selector[] WORKERS = new Selector[4];        // 4 个子 Reactor
    static final AtomicInteger NEXT = new AtomicInteger();    // 轮询分配器

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < WORKERS.length; i++) {
            final int id = i;
            WORKERS[i] = Selector.open();
            new Thread(() -> workerLoop(WORKERS[id], id), "worker-" + i).start();
        }
        Selector boss = Selector.open();                       // 主 Reactor
        ServerSocketChannel server = ServerSocketChannel.open();
        server.configureBlocking(false);
        server.bind(new InetSocketAddress(9992));
        server.register(boss, SelectionKey.OP_ACCEPT);
        System.out.println("主从版 Echo on 9992（boss 1 线程 + worker 4 线程）");

        while (true) {
            boss.select();
            Iterator<SelectionKey> it = boss.selectedKeys().iterator();
            while (it.hasNext()) {
                it.next();
                it.remove();
                SocketChannel client = server.accept();         // boss 只管接入
                client.configureBlocking(false);
                int idx = Math.abs(NEXT.getAndIncrement()) % WORKERS.length;
                client.register(WORKERS[idx], SelectionKey.OP_READ);   // 轮询交给 worker
                System.out.println("连接 " + client.getRemoteAddress() + " → worker-" + idx);
            }
        }
    }
    static void workerLoop(Selector sel, int id) {              // worker 管读写
        ByteBuffer buf = ByteBuffer.allocate(256);
        while (true) {
            try {
                sel.select();
                Iterator<SelectionKey> it = sel.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (key.isReadable()) {
                        SocketChannel ch = (SocketChannel) key.channel();
                        buf.clear();
                        int r = ch.read(buf);
                        if (r == -1) { ch.close(); continue; }
                        buf.flip();
                        ch.write(buf);                          // 回显
                    }
                }
            } catch (IOException e) { e.printStackTrace(); }
        }
    }
}
// 与 day09 单线程版的对照观察：
//   多个客户端同时连接 → 日志显示连接被轮流分到 worker-0/1/2/3
//   每条连接注册后固定在同一个 worker（后续读写都在它身上）= 连接绑定
//   ★这就是 Netty bossGroup/workerGroup 的骨架
```

```powershell
cd D:\mywork\springbootai\learning\month03-net-mysql
javac MasterSlaveEcho.java; java MasterSlaveEcho
java NioClient              # 复用昨天的客户端（改端口 9992）观察分配日志
# 记录：连接分配轮转 + 同一连接固定 worker 的证据
```

### 3.3 中间件对号表（抄进笔记）

```text
| 中间件 | 形态 | 细节 |
|--------|------|------|
| Redis(<6.0) | 单 Reactor 单线程 | 命令执行单线程=天然原子 |
| Redis(6.0+) | 多线程 IO + 单线程执行 | 读写解析多线程，命令仍单线程 |
| Netty | 主从 Reactor 多线程 | bossGroup/workerGroup 默认 2×核 |
| Nginx | master-worker 进程 | 每 worker 一个 epoll，SO_REUSEPORT |
| Kafka | Acceptor+Processor+Handler | 网络层主从，请求队列+业务池 |
```

## 4. 面试连接

**Q：讲讲 Netty 的线程模型？**
> 主从 Reactor 多线程：bossGroup（通常 1 线程）监听端口只干 accept，新连接轮询分给 workerGroup（默认 2×核数）的某个 EventLoop；此后这条连接的所有 IO 事件固定在该 EventLoop 线程串行执行——天然线程安全。加分句："我手写过 5 个 Selector 的主从版 Echo（MasterSlaveEcho），亲眼看连接固定分配。"再接业务线程池建议：CPU 密集/阻塞业务别占 IO 线程，加 EventExecutorGroup。

**Q：为什么说"一连接一线程"的 handler 不用加锁？**
> 连接绑定（connection binding）：同一连接的事件永远由同一个 EventLoop 串行处理，handler 里的状态对该连接天然线程封闭（month02 线程封闭模式的网络版）。跨连接共享的才需要并发容器/锁。这个"无锁串行"正是高性能框架的共同设计。

**Q：Reactor 和 Proactor 的区别？**
> 一句话："Reactor 通知你可以开始读（同步 IO 的通知），Proactor 通知你已经读完了（异步 IO 的完成通知）。"Reactor 家：epoll/kqueue/Java NIO；Proactor 家：Windows IOCP、Linux io_uring。生产主流是 Reactor 系（生态+跨平台），AIO 生态弱（衔接 day08 的结论）。

## 5. 今日验收清单

- [ ] 三张 Reactor 结构图手绘完成
- [ ] MasterSlaveEcho 跑通并记录连接分配日志
- [ ] 中间件对号表默写（Redis/Netty/Nginx/Kafka）
- [ ] "连接绑定=无锁串行"能展开讲
- [ ] `git add . && git commit -m "day10: reactor x3"`

---
[← Day 09](day09-JavaNIO与epoll.md) | [本月目录](README.md) | [Day 11 · Netty 核心组件 →](day11-Netty核心组件.md)
