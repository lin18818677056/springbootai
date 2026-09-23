# Day 14 · 手写迷你 RPC（下）：负载均衡双节点 + 第二周复盘

> **今日目标**：给 RPC 加第二个 Provider 节点，实现轮询负载均衡（面试题"负载均衡策略"的亲手实证）；然后发布《Netty 手写 RPC》博客（旧大纲指定产出②）并完成第二周复盘——Week2「IO + Netty + RPC」收官。
> **时长**：实操 1.5h / 博客 1.5h / 复盘 1h
> **今日产出**：双节点轮询日志 + 博客发布链接 + 第二周十题自测

## 1. 知识地图

```
负载均衡的位置：调用方 与 多个服务节点 之间
                  ┌─→ Provider-A :9989（day13 的 RpcServer）
  client ── 轮询 ──┤
                  └─→ Provider-B :9988（今天起的第二个节点）

策略四件套（面试必背）：
  轮询 Round-Robin    顺序轮流——今天实现（简单公平，不感知负载）
  随机 Random         随机挑——Dubbo 默认（次数够多时≈轮询）
  一致性哈希          相同参数落同一节点（有状态/缓存友好）
  最少活跃调用        谁手上活少挑谁（慢节点自动少接活，最智能）

容错策略（配合用）：
  failover   失败换节点重试（Dubbo 默认，重试 2 次）——幂等接口才敢用
  failfast   失败立即报错（非幂等场景：下单、扣款）
  failsafe   失败忽略记日志（写审计日志这类可丢操作）

真实集群还需要（今天不做，知道即可）：
  健康检查+自动摘除 ← 心跳（day12 的 IdleStateHandler 就是原料）
  权重              新机器权重低预热 / 机器强弱配比
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| Load Balancing | 负载均衡 | 多节点间的流量分配 |
| Round-Robin | 轮询 | 顺序轮流选择 |
| Consistent Hashing | 一致性哈希 | 环上定位，节点增减只影响相邻段 |
| Failover | 故障转移 | 失败自动换节点重试 |
| Sticky Connection | 粘性连接 | 同一调用方固定连同一节点 |
| Service Discovery | 服务发现 | 注册中心推送可用节点列表 |

## 3. 动手实操

### 3.1 主菜：RpcBalanceClient.java —— 双节点 + 轮询

```java
// RpcBalanceClient.java —— 维持两条连接，轮询选节点（在 day13 基础上扩展）
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RpcBalanceClient {
    static final Channel[] CHANNELS = new Channel[2];       // 两个 Provider 连接
    static final int[] PORTS = {9989, 9988};                // 节点端口表（生产=注册中心推送）
    static final AtomicInteger NEXT = new AtomicInteger();  // 轮询指针
    static volatile RpcResponse resp;
    static volatile CountDownLatch latch = new CountDownLatch(1);

    @SuppressWarnings("unchecked")
    static <T> T getProxy(Class<T> clazz) {
        return (T) Proxy.newProxyInstance(clazz.getClassLoader(), new Class<?>[]{clazz},
            (proxy, method, args) -> {
                int idx = Math.floorMod(NEXT.getAndIncrement(), CHANNELS.length); // ① 轮询选节点
                System.out.println("→ 选中节点 :" + PORTS[idx]);
                RpcRequest req = new RpcRequest(clazz.getName(), method.getName(),
                                                method.getParameterTypes(), args);
                RpcResponse r = call(idx, req);             // ② 在选中节点上调用
                if (r.error != null) throw new RuntimeException("RPC 失败: " + r.error);
                return r.data;
            });
    }

    static RpcResponse call(int idx, RpcRequest req) throws Exception {
        latch = new CountDownLatch(1);
        CHANNELS[idx].writeAndFlush(new Msg(RpcCodec.encode(req)));
        if (!latch.await(3, TimeUnit.SECONDS)) throw new RuntimeException("RPC 超时");
        return resp;
    }

    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            for (int i = 0; i < CHANNELS.length; i++) connect(group, i);
            UserService user = getProxy(UserService.class);
            for (int i = 1; i <= 6; i++) {                  // 连发 6 次 → 应 3:3 分配
                System.out.println("findName(" + i + ") = " + user.findName(i));
            }
        } finally { group.shutdownGracefully(); }
    }

    static void connect(EventLoopGroup group, int idx) throws Exception {
        Bootstrap b = new Bootstrap().group(group).channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new MsgEncoder()).addLast(new MsgDecoder())
                      .addLast(new SimpleChannelInboundHandler<Msg>() {
                          @Override protected void channelRead0(ChannelHandlerContext ctx, Msg m) {
                              resp = RpcCodec.decode(m.body);
                              latch.countDown();
                          }
                      });
                }
            });
        CHANNELS[idx] = b.connect("localhost", PORTS[idx]).sync().channel();
    }
}
```

### 3.2 运行观察：轮询分配实证

```powershell
cd D:\mywork\springbootai\learning\month03-net-mysql
javac -cp "lib/netty-all.jar" -d out ProtocolCodec.java RpcDefine.java RpcServer.java RpcBalanceClient.java
java -cp "out;lib/netty-all.jar" RpcServer 9989    # 终端1：节点 A
java -cp "out;lib/netty-all.jar" RpcServer 9988    # 终端2：节点 B
java -cp "out;lib/netty-all.jar" RpcBalanceClient  # 终端3：客户端

# 观察记录（★这是博客里的负载均衡实证图）：
#   □ 客户端：选中节点依次 9989/9988/9989/9988/9989/9988（3:3）
#   □ 节点 A 服务端：收到 3 次调用；节点 B：收到 3 次
#   □ 宕机实验：Ctrl+C 停掉节点 B → 客户端轮询报 RPC 超时
#     → 体会：没有健康检查的轮询 = 往死节点送流量（引出注册中心摘除机制）
```

### 3.3 博客发布：《我用 200 行 Java 手写了一个 RPC 框架》

```text
发布渠道任选其一：CSDN / 掘金 / 博客园 / 知乎专栏（旧大纲指定产出②）
内容结构模板（直接套用，材料都是本周现成的）：
  ① 引子：调用远程方法为什么能像本地一样 → 动态代理的魔法
  ② 协议层：魔数+版本+长度头根治粘包（贴 day12 的帧结构图 + 五参数公式）
  ③ 传输层：一条消息在 Netty 里的流转（贴 day11 的 pipeline 路径图）
  ④ 服务端：Map 注册中心 + 反射调用（贴 RpcServerHandler 六步代码）
  ⑤ 客户端：InvocationHandler + Latch 同步等待（贴 getProxy 代码）
  ⑥ 负载均衡：双节点轮询实证（贴今天的 3:3 分配日志 + 宕机实验）
  ⑦ 诚实清单：与 Dubbo 的差距（request id 多飞行/注册中心推送/容错策略/泛化调用）
  标题备选：《面试官：手写个 RPC？我：200 行，还带负载均衡》
发布后把链接贴到 learning 仓库 NOTES.md + 本文件末尾
```

### 3.4 第二周复盘（day08-14）：十题自测

```text
先合上笔记作答，再看答案要点：
 1. BIO/NIO/AIO 一句话区别？        → 阻塞等数据 / 轮询就绪再读(非阻塞) / 内核读完通知你
 2. epoll 比 select 强在哪？        → fd 不限量+不遍历全量+就绪列表回调(O(1))
 3. Reactor 三形态各是谁？           → 单Reactor单线程=Redis(6前)；单R多线程=少数框架；
                                      主从=Netty/Nginx/Kafka
 4. Netty 消息流转路径？             → epoll就绪→read到ByteBuf→pipeline入站→业务→
                                      出站编码→EventLoop write→网卡
 5. ByteBuf 为什么不用 flip？       → 读写双指针 readerIndex/writerIndex
 6. LengthFieldBasedFrameDecoder 五参数？→ maxFrame/offset/length/adjustment/strip
 7. 拔网线为什么必须靠心跳？         → 无 FIN/RST，只有读空闲超时能感知死连接
 8. JDK 动态代理原理？              → 运行期生成 $Proxy0，方法统一转发 InvocationHandler
 9. 生产为什么不用 Java 原生序列化？  → 体积大/跨语言差/反序列化漏洞
10. 负载均衡四策略？                 → 轮询/随机/一致性哈希/最少活跃
评分标准：答出 8 题=Week2 达标；答不出 3 题以上=标记回炉对应 day
```

## 4. 面试连接

**Q：负载均衡有哪些策略？放客户端还是服务端？**
> 策略答四件套（轮询/随机/一致性哈希/最少活跃），位置答分层：客户端负载均衡（Dubbo/Ribbon——调用方自己选，少一跳、去中心化）与服务端负载均衡（Nginx/LVS/F5——统一入口、防单点、黑盒）。加分实证："我给手写 RPC 做过双节点轮询，还故意杀掉一个节点复现了'没有健康检查的轮询=送流量给死节点'，所以注册中心必须带心跳摘除。"

**Q：一致性哈希解决什么问题？**
> 普通取模 `hash % N` 在节点扩缩容时几乎所有请求映射全变（缓存集体失效）。一致性哈希把节点和 key 都放 2^32 环上，key 顺时针找最近节点——增减节点只影响相邻段的数据（理论 1/N）。再加虚拟节点解决数据倾斜。适用场景：分布式缓存、按参数路由的 RPC。

**Q：failover 有什么坑？**
> 非幂等接口不能用：超时≠失败，可能请求已执行，换节点重试=重复扣款。所以 Dubbo 默认 failover 但读接口才安全用；写接口要么 failfast，要么业务侧幂等（唯一请求号+去重表，MySQL 月会再讲）。这个"超时语义"话题是中高级面试分水岭。

## 5. 今日验收清单

- [ ] 双节点 3:3 轮询日志记录（截图存档）
- [ ] 宕机实验完成，能讲"健康检查缺失的后果"
- [ ] 博客《Netty 手写 RPC》已发布，链接贴入本文件
- [ ] 十题自测 ≥8 题（未达标项标注对应 day 回炉）
- [ ] `git add . && git commit -m "day14: mini rpc part2 + week2 review"`

---
[← Day 13](day13-手写迷你RPC上.md) | [本月目录](README.md) | [Day 15 · InnoDB 页结构与 B+ 树 →](day15-InnoDB页与B+树.md)
