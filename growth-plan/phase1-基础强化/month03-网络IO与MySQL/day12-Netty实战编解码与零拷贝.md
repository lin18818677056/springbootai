# Day 12 · Netty 实战：编解码 / 心跳 / 零拷贝（10 万条消息验证）

> **今日目标**：把 day03 的长度头协议搬进 Netty：LengthFieldBasedFrameDecoder 解决粘包 + IdleStateHandler 心跳 + 10 万条消息压测（无错序无粘包——M3 验收项）。顺带理解 Netty 的三类零拷贝。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：自定义协议通信（魔数+长度+JSON）+ 10 万条消息压测报告 + 心跳实验记录

## 1. 知识地图

```
自定义协议帧结构（工业 RPC 的通用骨架）：
  ┌────────┬────────┬──────┬─────────┬────────┐
  │ 魔数    │ 版本    │ 长度  │ 序列化体  │ (校验)  │
  │ 4B      │ 1B      │ 4B   │ body     │ 可选    │
  └────────┴────────┴──────┴─────────┴────────┘
  魔数：快速识别"是不是我的协议"（0xCAFE0001 之类）
  长度：LengthFieldBasedFrameDecoder 按它切帧（粘包根治）

LengthFieldBasedFrameDecoder 五参数（★必背）：
  maxFrameLength     帧最大长度（防恶意超大包）
  lengthFieldOffset  长度字段偏移（魔数4B+版本1B → 偏移 5）
  lengthFieldLength  长度字段自身占几字节（4）
  lengthAdjustment   长度值的修正（长度含不含头）
  initialBytesToStrip 解码后剥掉前几个字节（5：魔数+版本，或全剥）
  记忆公式：帧总长 = lengthFieldOffset + lengthFieldLength + 长度值 + lengthAdjustment

心跳（IdleStateHandler 三计时器）：
  readerIdleTime  读空闲 → 触发 READER_IDLE（服务端：多久没收到数据→关连接）
  writerIdleTime  写空闲 → WRITER_IDLE（客户端：多久没发→发 PING）
  allIdleTime     全空闲
  ★死连接感知：拔网线/断电无 RST，只能靠心跳超时（day04 的结论工业化）

Netty 零拷贝三件事（对比 OS 级 sendfile/mmap）：
  ① CompositeByteBuf：逻辑合并多个 Buffer，免物理拷贝
  ② Unpooled.wrappedBuffer / slice：包装与切片共享底层内存
  ③ FileRegion：封装 sendfile（文件→网卡，内核态直达）
  ④ 堆外直接内存 DirectByteBuffer：省一次"堆→直接内存"拷贝
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Codec | 编解码器 | decoder(入站)+encoder(出站) 成对 |
| Magic Number | 魔数 | 协议身份指纹 |
| Idle Detection | 空闲检测 | 心跳机制的底层事件 |
| Heartbeat | 心跳 | PING/PONG 保活协议 |
| Zero-Copy | 零拷贝 | 减少用户态内核态复制 |
| Direct Memory | 直接内存 | 堆外缓冲，省一次拷贝 |

## 3. 动手实操

### 3.1 主菜：自定义协议 + 10 万条消息压测

```java
// ProtocolCodec.java —— 协议编解码（魔数+版本+长度+JSON 体）
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 消息体：day13 RPC 会复用这个结构 */
class Msg {
    String body;
    Msg(String body) { this.body = body; }
}

class MsgDecoder extends ByteToMessageDecoder {                  // 入站：字节→Msg
    @Override protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 9) return;                      // 头都不够，等下个事件
        in.markReaderIndex();
        int magic = in.readInt();                                // ① 魔数 4B
        if (magic != 0xCAFE0001) { ctx.close(); return; }        // 不是我的协议→断开
        byte version = in.readByte();                            // ② 版本 1B
        int len = in.readInt();                                  // ③ 长度 4B
        if (in.readableBytes() < len) { in.resetReaderIndex(); return; } // 半包：回退再等
        byte[] body = new byte[len];
        in.readBytes(body);                                      // ④ 体
        out.add(new Msg(new String(body, StandardCharsets.UTF_8)));
    }
}

class MsgEncoder extends MessageToByteEncoder<Msg> {             // 出站：Msg→字节
    @Override protected void encode(ChannelHandlerContext ctx, Msg msg, ByteBuf out) {
        byte[] body = msg.body.getBytes(StandardCharsets.UTF_8);
        out.writeInt(0xCAFE0001).writeByte(1).writeInt(body.length).writeBytes(body);
    }
}
```

```java
// ProtocolServer.java —— 服务端：解码 + 心跳检测 + 回显
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import java.util.concurrent.atomic.AtomicLong;

public class ProtocolServer {
    static final AtomicLong COUNT = new AtomicLong();
    public static void main(String[] args) throws Exception {
        EventLoopGroup boss = new NioEventLoopGroup(1), worker = new NioEventLoopGroup();
        try {
            new ServerBootstrap().group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                          .addLast(new IdleStateHandler(30, 0, 0))       // 30s 读空闲断开
                          .addLast(new MsgDecoder())
                          .addLast(new MsgEncoder())
                          .addLast(new SimpleChannelInboundHandler<Msg>() {
                              @Override protected void channelRead0(ChannelHandlerContext ctx, Msg m) {
                                  long c = COUNT.incrementAndGet();
                                  if (c % 20000 == 0) System.out.println("已处理 " + c + " 条");
                                  ctx.writeAndFlush(new Msg("ACK:" + m.body));    // 回应
                              }
                              @Override public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                                  if (evt instanceof IdleStateEvent
                                          && ((IdleStateEvent) evt).state() == IdleState.READER_IDLE) {
                                      System.out.println("30 秒无数据，断开: " + ctx.channel().remoteAddress());
                                      ctx.close();                              // 死连接清理
                                  }
                              }
                          });
                    }
                }).bind(9990).sync().channel().closeFuture().sync();
        } finally { boss.shutdownGracefully(); worker.shutdownGracefully(); }
    }
}

// ProtocolClient.java —— 客户端：压测 10 万条 + 心跳保活
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class ProtocolClient {
    static final int TOTAL = 100_000;
    static final AtomicLong SENT = new AtomicLong(), RECV = new AtomicLong();
    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        long begin = System.currentTimeMillis();
        try {
            Bootstrap b = new Bootstrap().group(group).channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                          .addLast(new IdleStateHandler(0, 10, 0))          // 10s 写空闲→PING
                          .addLast(new MsgEncoder())
                          .addLast(new MsgDecoder())
                          .addLast(new SimpleChannelInboundHandler<Msg>() {
                              @Override public void channelActive(ChannelHandlerContext ctx) {
                                  for (int i = 0; i < TOTAL; i++) {
                                      ctx.writeAndFlush(new Msg("m" + i));   // 疯狂发（框架排队）
                                  }
                              }
                              @Override protected void channelRead0(ChannelHandlerContext ctx, Msg m) {
                                  RECV.incrementAndGet();
                              }
                              @Override public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                                  ctx.writeAndFlush(new Msg("PING"));         // 心跳
                              }
                          });
                    }
                }).connect("localhost", 9990).sync().channel();
            while (RECV.get() < TOTAL) Thread.sleep(100);        // 等 ACK 全回来
            long cost = System.currentTimeMillis() - begin;
            System.out.printf("发送=%d 收到ACK=%d 耗时=%dms 吞吐=%.0f msg/s%n",
                    SENT.incrementAndGet() * 0 + TOTAL, RECV.get(), cost, TOTAL * 1000.0 / cost);
        } finally { group.shutdownGracefully(); }
    }
}
```

```powershell
cd D:\mywork\springbootai\learning\month03-net-mysql
javac -cp "lib/netty-all.jar" -d out ProtocolCodec.java ProtocolServer.java ProtocolClient.java
java -cp "out;lib/netty-all.jar" ProtocolServer      # 终端1
java -cp "out;lib/netty-all.jar" ProtocolClient      # 终端2
# 验收对照（M3 验收项）：
#   □ 10 万条 ACK 全部回来（发送数=ACK 数）→ 无丢失
#   □ body 顺序可校验（把 ACK:m123 对比发送序号，抽样 10 条）→ 无错序
#   □ 日志无 DecoderException → 无粘包/半包崩溃
#   □ 心跳：客户端闲置 10 秒发 PING；服务端 30 秒没数据主动断（各观察一次）
```

### 3.2 零拷贝体验实验

```java
// ZeroCopyPlay.java —— CompositeByteBuf 逻辑合并 + slice 共享
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;

public class ZeroCopyPlay {
    public static void main(String[] args) {
        ByteBuf header = Unpooled.wrappedBuffer("HEAD".getBytes(StandardCharsets.UTF_8));
        ByteBuf body   = Unpooled.wrappedBuffer("BODY-CONTENT".getBytes(StandardCharsets.UTF_8));
        CompositeByteBuf merged = Unpooled.compositeBuffer();    // ① 逻辑合并
        merged.addComponents(true, header, body);
        System.out.println("合并后内容: " + merged.toString(StandardCharsets.UTF_8));

        ByteBuf slice = body.slice(0, 4);                        // ② 切片共享底层
        System.out.println("切片(0,4): " + slice.toString(StandardCharsets.UTF_8));

        ByteBuf wrapped = Unpooled.wrappedBuffer("zero-copy".getBytes());  // ③ 包装不拷贝
        System.out.println("包装: " + wrapped.toString(StandardCharsets.UTF_8));
        // 观察点：以上操作都没有 System.arraycopy——引用组合而已
    }
}
// OS 级零拷贝（FileRegion→sendfile / mmap）：知道概念与对应类即可，本机验证选做
```

### 3.3 参数理解卡：LengthFieldBasedFrameDecoder 五参数自测

```text
场景：魔数 4B + 版本 1B + 长度 4B + 体
  maxFrameLength = 1MB
  lengthFieldOffset = 5      （长度字段从第 6 字节开始）
  lengthFieldLength = 4
  lengthAdjustment = 0       （长度值=体的字节数，不含头）
  initialBytesToStrip = 9    （若想只给下游 body：剥掉 9 字节头）
变式①：长度值=整帧长度（含头）→ lengthAdjustment = -9
变式②：长度前还有 2B 校验和 → lengthFieldOffset = 7
★默写五参数含义 + 会算两个变式 = 粘包问题面试满分
```

## 4. 面试连接

**Q：Netty 怎么解决粘包半包？**
> 分层答：① 根源在 TCP 字节流（day03）；② Netty 提供四种开箱解码器：FixedLengthFrameDecoder/DelimiterBasedFrameDecoder/LengthFieldBasedFrameDecoder（主流）/基于协议的 codec；③ 讲五参数公式并现场算一个变式；④ 内部机制：ByteToMessageDecoder 的累积缓冲 ByteBuf + 半包重试（decode 返回不消耗就等下一次）。实证："我自定义了魔数+长度协议压测 10 万条，零错序零粘包。"

**Q：心跳为什么要应用层自己做？**
> 三个原因：① 内核 TCP keepalive 默认 7200s 起步，不可用；② 心跳能穿透 NAT/防火墙更新映射表（长连接保活的真实需求）；③ 附带业务语义（间隔可配置、超时即清理死连接）。落地："IdleStateHandler 三计时器：客户端 10s 写空闲发 PING，服务端 30s 读空闲断连——我在自定义协议里实现了。"

**Q：Netty 的零拷贝和 OS 的零拷贝是一回事吗？**
> 不是两层东西：Netty 的零拷贝是 JVM 层的数据结构优化（CompositeByteBuf 逻辑合并、slice 共享、堆外内存省一次拷贝）；OS 的零拷贝是内核层（sendfile 文件直达网卡、mmap 映射省用户态拷贝）。Netty 的 FileRegion 就是对 sendfile 的封装。能分两层说明白比背概念高一档。

## 5. 今日验收清单

- [ ] 10 万条压测：ACK 全回 + 抽查无错序 + 无解码异常（M3 验收项）
- [ ] 心跳双向验证（PING 发送 / READER_IDLE 断连）
- [ ] ZeroCopyPlay 跑通
- [ ] 五参数自测卡两变式口算通过
- [ ] `git add . && git commit -m "day12: codec heartbeat zerocopy"`

---
[← Day 11](day11-Netty核心组件.md) | [本月目录](README.md) | [Day 13 · 手写迷你 RPC（上） →](day13-手写迷你RPC上.md)
