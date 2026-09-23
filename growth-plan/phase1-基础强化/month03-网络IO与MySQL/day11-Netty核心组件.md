# Day 11 · Netty 核心组件（bind 到收到消息的完整链路）

> **今日目标**：昨天手写了主从 Reactor 的骨架，今天看它的工业级实现——Netty。认识 EventLoopGroup/Channel/Pipeline/ByteBuf 六大组件，跑通第一个 Netty Echo，并画出"一条消息在 Netty 里的流转路径"。
> **时长**：理论 1.5h / 实操 1.5h / 输出 0.5h
> **今日产出**：NettyEcho（服务端+客户端）+ 消息流转图 + 组件职责卡

## 1. 知识地图

```
Netty 组件全家福（每个组件 = day10 图里的一个角色）：

  EventLoopGroup ── 线程组（bossGroup=MainReactor / workerGroup=SubReactor 池）
    └─ EventLoop ──── 单线程事件循环 = 1 线程 + 1 Selector（NioEventLoop）
         │             ★还兼任务队列：channel.eventLoop().execute(task)
         ▼
  Channel ───────── 连接的抽象（NioServerSocketChannel/NioSocketChannel）
         │           ★channel.attr(key) 可挂连接级状态（线程安全靠绑定）
         ▼
  ChannelPipeline ── Handler 链（入站事件头→尾，出站事件尾→头）
         └─ Handler 入站: channelRead / 出站: write / 双向: 均可
            ★解码器=入站Handler，编码器=出站Handler

  ByteBuf ───────── 池化字节容器（对比 NIO Buffer 的增强）：
     │   ① 读写双指针（readerIndex/writerIndex，不用 flip！）
     │   ② 池化 Arena 减 GC（PooledByteBufAllocator 默认开）
     │   ③ 引用计数 refCnt：release() 归还池（内存泄漏监控 leak 的由来）
     ▼
  Future/Promise ── 异步结果（Promise 可手动 set，Netty 版更强大）

bind() 启动主线（源码级 5 步，面试画这条线）：
  bind(9991)
    → ① newChannel：创建 NioServerSocketChannel（内含原生 ServerSocketChannel）
    → ② init：给 pipeline 加 handler（ServerBootstrapAcceptor）
    → ③ register：把 channel 注册到 boss 的某个 EventLoop 的 Selector
    → ④ doBind0：底层 bind 端口
    → ⑤ 收到 OP_ACCEPT → ServerBootstrapAcceptor 把 accept 的
        SocketChannel 轮询 register 给 worker EventLoop（=day10 的主从分发！）

一条消息的流转路径（收+回两程）：
  网卡 → epoll 就绪 → worker EventLoop read → ByteBuf
      → pipeline 入站头→尾（decoder → bizHandler）
      → bizHandler ctx.writeAndFlush
      → pipeline 出站尾→头（encoder）→ EventLoop write → 网卡
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| EventLoopGroup | 事件循环组 | 一组 EventLoop（线程） |
| ChannelPipeline | 管道 | Handler 的双向链表 |
| Inbound / Outbound | 入站/出站 | 读事件 / 写事件两条方向 |
| ByteBuf | 字节缓冲 | 双指针+池化+引用计数 |
| Reference Counting | 引用计数 | release 防泄漏，retain 加一 |
| Promise | 可写 Future | 异步结果的手动完成器 |

## 3. 动手实操

### 3.1 主菜：Netty Echo（服务端 + 客户端）

```java
// NettyEchoServer.java —— 工业级版（对照 day10 的 MasterSlaveEcho）
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.buffer.ByteBuf;

public class NettyEchoServer {
    public static void main(String[] args) throws Exception {
        EventLoopGroup boss = new NioEventLoopGroup(1);        // MainReactor：1 线程
        EventLoopGroup worker = new NioEventLoopGroup();       // SubReactor：默认 2×核
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(boss, worker)
             .channel(NioServerSocketChannel.class)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override protected void initChannel(SocketChannel ch) {
                     ch.pipeline().addLast(new NettyEchoHandler());
                 }
             })
             .bind(9991).sync()
             .channel().closeFuture().sync();
        } finally {
            boss.shutdownGracefully(); worker.shutdownGracefully();
        }
    }
}

class NettyEchoHandler extends SimpleChannelInboundHandler<ByteBuf> {
    @Override public void channelActive(ChannelHandlerContext ctx) {
        System.out.println("接入: " + ctx.channel().remoteAddress()
                + " 绑定线程: " + Thread.currentThread().getName());
    }
    @Override protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        System.out.println(Thread.currentThread().getName() + " echo: "
                + msg.toString(java.nio.charset.StandardCharsets.UTF_8));
        ctx.writeAndFlush(msg.retain());        // ★retain：透传所有权（write 后 release）
    }
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable t) {
        t.printStackTrace();
        ctx.close();
    }
}
// 观察点①：多个客户端连上来，"绑定线程"各不相同（worker 分发）
// 观察点②：同一客户端的多条消息，永远同一个线程（连接绑定实证）
```

```java
// NettyEchoClient.java
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.buffer.Unpooled;

public class NettyEchoClient {
    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            Bootstrap b = new Bootstrap();
            b.group(group).channel(NioSocketChannel.class)
             .handler(new ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
                 @Override protected void initChannel(io.netty.channel.socket.SocketChannel ch) {
                     ch.pipeline().addLast(new SimpleChannelInboundHandler<io.netty.buffer.ByteBuf>() {
                         @Override public void channelActive(ChannelHandlerContext ctx) {
                             for (int i = 1; i <= 5; i++) {
                                 ctx.writeAndFlush(Unpooled.wrappedBuffer(
                                         ("netty-hello-" + i).getBytes()));
                             }
                         }
                         @Override protected void channelRead0(ChannelHandlerContext ctx,
                                 io.netty.buffer.ByteBuf msg) {
                             System.out.println("client echo: " + msg.toString(
                                     java.nio.charset.StandardCharsets.UTF_8));
                         }
                     });
                 }
             })
             .connect("localhost", 9991).sync().channel().closeFuture().sync();
        } finally { group.shutdownGracefully(); }
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month03-net-mysql
# Netty jar 已在 README 环境准备下载到 lib\netty-all.jar
javac -cp "lib/netty-all.jar" -d out NettyEchoServer.java NettyEchoClient.java
java -cp "out;lib/netty-all.jar" NettyEchoServer      # 终端1
java -cp "out;lib/netty-all.jar" NettyEchoClient      # 终端2
# 观察记录：
#   □ 服务端打印的绑定线程名（nioEventLoopGroup-3-1-N）
#   □ 多客户端时线程名不同、单客户端多消息线程相同
```

### 3.2 消息流转路径图（手绘任务）

```text
收到 "netty-hello-1" 的完整旅程（对照 §1 的路径图手绘）：
  [网卡] → worker EventLoop epoll 就绪
    → NioSocketChannel.read → ByteBuf(池化)
    → pipeline: [头] → NettyEchoHandler.channelRead0 → [尾]
    → ctx.writeAndFlush(msg.retain())
    → pipeline 出站: [尾] → [头] → SocketChannel.write
  两个标注：
    ① retain 的原因：writeAndFlush 之后框架会 release，透传要 +1
    ② 全程在同一个 EventLoop 线程（除非 pipeline 里显式 addLast(其他线程池, h)）
```

### 3.3 组件职责卡（抄进笔记）

```text
| 组件 | 一句话职责 | 对应 day10 概念 |
|------|----------|----------------|
| EventLoopGroup | 一组事件循环线程 | SubReactor 池 |
| EventLoop | 1 线程+1 Selector 死循环 | Reactor 本体 |
| Channel | 连接抽象 + attr 状态 | 连接绑定载体 |
| Pipeline | Handler 双向链 | 责任链处理 |
| ByteBuf | 双指针池化缓冲 | 数据运输车 |
| Promise | 异步结果完成器 | 异步编程接口 |
```

## 4. 面试连接

**Q：讲一下 Netty 的工作流程/消息流转？**
> 两条主线：启动线（bind → newChannel → init → register 到 boss Selector → Acceptor 分发给 worker）与消息线（epoll 就绪 → read 到 ByteBuf → pipeline 入站 → 业务 → 出站编码 → write）。全程强调"同一连接固定一个 EventLoop 线程"。加分实证："我先用原生 NIO 手写主从版（day10），再看 Netty 的 ServerBootstrapAcceptor——逻辑一模一样。"

**Q：ByteBuf 相比 NIO 的 ByteBuffer 好在哪？**
> 三点：① 读写双指针（readerIndex/writerIndex），告别 flip；② 池化 Arena 复用堆外内存，降低 GC 压力；③ 引用计数管理生命周期，配 ResourceLeakDetector 抓泄漏。坑提醒：引用计数用错=内存泄漏或 IllegalReferenceCountException——所以 day12 的编解码里要注意 msg 的所有权。

**Q：Pipeline 的入站和出站顺序？**
> 入站事件从 head 到 tail（按 add 顺序），出站事件从 tail 到 head（逆序）。所以：decoder 放前面（靠近 head 先解码），encoder 放后面（靠近 tail——出站时从 tail 走到它）。记忆法："数据从网卡来走入站，从网卡出走走出站，管道方向相反。"

## 5. 今日验收清单

- [ ] NettyEcho 双端跑通（绑定线程观察记录）
- [ ] 消息流转路径图手绘
- [ ] 组件职责卡默写
- [ ] ByteBuf retain/release 所有权规则能讲
- [ ] `git add . && git commit -m "day11: netty core components"`

---
[← Day 10](day10-Reactor模式三形态.md) | [本月目录](README.md) | [Day 12 · Netty 实战编解码与零拷贝 →](day12-Netty实战编解码与零拷贝.md)
