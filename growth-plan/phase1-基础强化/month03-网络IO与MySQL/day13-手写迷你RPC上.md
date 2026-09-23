# Day 13 · 手写迷你 RPC（上）：动态代理 + 协议 + 反射调用

> **今日目标**：综合 month02 的动态代理、day11 的 Netty、day12 的自定义协议，写出能跑的迷你 RPC——`user.findName(42)` 一行代码背后是完整的远程调用。今天完成"代理+序列化+反射"三大件，明天加负载均衡双节点。
> **时长**：理论 1h / 实操 2h / 输出 0.5h
> **今日产出**：RpcServer/RpcClient 双端跑通 + RPC 全链路图 + 一次调用的双端日志

## 1. 知识地图

```
RPC = Remote Procedure Call，目标：调用远程方法像调用本地方法一样

一行代码背后的六步（面试主线，背下来）：
  user.findName(42)                     ← 调用方视角：就这一行
    │ ① $Proxy0.findName(42)            JDK 运行期生成的代理类接住调用
    │ ② InvocationHandler.invoke(...)   所有方法调用收口到这一个入口
    │ ③ 打包 RpcRequest(接口名,方法名,参数类型,实参) → 序列化成字节
    │ ④ 装进 day12 协议帧（魔数+版本+长度+体）→ Netty 发出
    ▼  ════════════════ 网络 ════════════════
    │ ⑤ 服务端解码 → 注册中心(Map) 查到实现类实例
    │ ⑥ 反射 method.invoke(impl, args) → 结果序列化原路返回
    ▼
  返回 "张三-42"                        ← 调用方毫无察觉

★屏蔽网络细节的功劳全在"动态代理"——RPC 的灵魂组件

迷你版 ↔ 生产框架对号表（贴笔记）：
  ┌───────────────────────┬─────────────────────────┐
  │ 迷你版（本周）          │ Dubbo / 生产             │
  ├───────────────────────┼─────────────────────────┤
  │ Map<String,Object>    │ ZK/Nacos 注册中心         │
  │ 原生序列化+Base64     │ hessian2/kryo/protobuf   │
  │ 单连接单飞行+Latch    │ request id + Promise 多飞行│
  │ 单节点（今天）          │ 集群+负载均衡（明天）       │
  │ 同步阻塞              │ 同步/异步/泛化调用         │
  └───────────────────────┴─────────────────────────┘
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| RPC | 远程过程调用 | 屏蔽网络细节的方法调用 |
| Stub | 存根 | 代理的学名：consumer 端代理 |
| Dynamic Proxy | 动态代理 | 运行期生成代理类，统一转发 |
| Serialization | 序列化 | 对象 ↔ 字节（跨网络必经） |
| Service Registry | 服务注册中心 | 接口名 → 实现的映射 |
| Reflection | 反射 | 按名字调用方法（服务端定位用） |

## 3. 动手实操

### 3.1 RpcDefine.java —— 消息定义 + 编解码 + 演示服务

```java
// RpcDefine.java —— RPC 消息体 + 序列化工具 + 演示接口（双端共用）
import java.io.*;
import java.util.Base64;

/** 请求体：Java 原生序列化（教学用；生产用 protobuf，见面试 Q3） */
class RpcRequest implements Serializable {
    String service;              // 接口全限定名
    String method;               // 方法名
    Class<?>[] paramTypes;       // 参数类型（反射定位必需）
    Object[] args;               // 实参
    RpcRequest(String service, String method, Class<?>[] pt, Object[] args) {
        this.service = service; this.method = method;
        this.paramTypes = pt; this.args = args;
    }
}

/** 响应体：data 与 error 二选一 */
class RpcResponse implements Serializable {
    Object data;                 // 正常返回值
    String error;                // 有值 = 调用失败（把异常传回调用方）
}

/** 序列化工具：字节进 day12 协议的 body（Base64 是教学偷懒，见注释） */
class RpcCodec {
    static String encode(Object obj) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(obj);
            // Base64 让二进制安全过 String 协议体（代价 +1/3 体积；
            // 生产协议直接放二进制，无此开销）
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    @SuppressWarnings("unchecked")
    static <T> T decode(String text) {
        byte[] bytes = Base64.getDecoder().decode(text);
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (T) ois.readObject();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}

/** 演示服务：接口 + 实现（真实项目里通常拆成 api 模块和 provider 模块） */
interface UserService {
    String findName(long id);
    int calc(int a, int b);
}
class UserServiceImpl implements UserService {
    public String findName(long id) { return "张三-" + id; }
    public int calc(int a, int b)   { return a + b; }
}
```

### 3.2 RpcServer.java —— 注册中心 + 反射调用

```java
// RpcServer.java —— RPC 服务端（支持端口参数，明天起第二节点用）
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RpcServer {
    /** 迷你注册中心：接口全名 → 实现实例（生产 = ZK/Nacos + 多节点 + 心跳摘除） */
    static final Map<String, Object> SERVICES = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        SERVICES.put(UserService.class.getName(), new UserServiceImpl());   // 服务注册

        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9989;     // 节点端口可配
        EventLoopGroup boss = new NioEventLoopGroup(1), worker = new NioEventLoopGroup();
        try {
            new ServerBootstrap().group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                          .addLast(new MsgDecoder())        // 复用 day12 协议栈
                          .addLast(new MsgEncoder())
                          .addLast(new RpcServerHandler()); // RPC 业务
                    }
                }).bind(port).sync();
            System.out.println("RPC Server on " + port + "，已注册: " + SERVICES.keySet());
            new java.util.Scanner(System.in).nextLine();    // 回车退出
        } finally { boss.shutdownGracefully(); worker.shutdownGracefully(); }
    }
}

class RpcServerHandler extends SimpleChannelInboundHandler<Msg> {
    @Override protected void channelRead0(ChannelHandlerContext ctx, Msg m) {
        RpcRequest req = RpcCodec.decode(m.body);                        // ① 反序列化
        System.out.println("[" + ctx.channel().localAddress()
                + "] 收到调用: " + req.service + "#" + req.method);
        RpcResponse resp = new RpcResponse();
        try {
            Object impl = RpcServer.SERVICES.get(req.service);           // ② 查注册中心
            if (impl == null) throw new RuntimeException("服务未注册: " + req.service);
            Method method = impl.getClass().getMethod(req.method, req.paramTypes); // ③ 定位
            resp.data = method.invoke(impl, req.args);                   // ④ 反射执行
        } catch (Exception e) {
            resp.error = e.getCause() != null
                    ? e.getCause().toString() : e.toString();            // 异常跨网络传回
        }
        ctx.writeAndFlush(new Msg(RpcCodec.encode(resp)));               // ⑤ 回写响应
    }
}
```

### 3.3 RpcClient.java —— 动态代理 + 同步等待

```java
// RpcClient.java —— 客户端：代理屏蔽网络 + Latch 同步等响应（单飞行教学版）
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class RpcClient {
    static Channel channel;
    static volatile RpcResponse resp;            // volatile：IO 线程写 / 调用线程读
    static volatile CountDownLatch latch = new CountDownLatch(1);

    /** ★核心：JDK 动态代理——把"远程调用"伪装成"本地方法调用" */
    @SuppressWarnings("unchecked")
    static <T> T getProxy(Class<T> clazz) {
        return (T) Proxy.newProxyInstance(clazz.getClassLoader(), new Class<?>[]{clazz},
            (proxy, method, args) -> {                       // InvocationHandler
                RpcRequest req = new RpcRequest(clazz.getName(), method.getName(),
                                                method.getParameterTypes(), args);
                RpcResponse r = call(req);                   // 阻塞直到响应回来
                if (r.error != null) throw new RuntimeException("RPC 失败: " + r.error);
                return r.data;
            });
    }

    static RpcResponse call(RpcRequest req) throws Exception {
        latch = new CountDownLatch(1);                       // 复位同步器（单飞行）
        channel.writeAndFlush(new Msg(RpcCodec.encode(req)));
        if (!latch.await(3, TimeUnit.SECONDS)) throw new RuntimeException("RPC 超时");
        return resp;
    }

    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            Bootstrap b = new Bootstrap().group(group).channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new MsgEncoder()).addLast(new MsgDecoder())
                          .addLast(new SimpleChannelInboundHandler<Msg>() {
                              @Override protected void channelRead0(ChannelHandlerContext ctx, Msg m) {
                                  resp = RpcCodec.decode(m.body);   // 收到响应
                                  latch.countDown();                 // 放行等待者
                              }
                          });
                    }
                });
            channel = b.connect("localhost", 9989).sync().channel();
            UserService user = getProxy(UserService.class);         // ★像本地一样调用
            System.out.println("findName(42)  = " + user.findName(42));
            System.out.println("calc(19, 23) = " + user.calc(19, 23));
            channel.close();
        } finally { group.shutdownGracefully(); }
    }
}
// 局限（诚实列表，也是加分谈资）：
//   ① 单飞行：一次只有一个未决请求 → 生产用 request id + Map<Long,Promise>
//   ② 同步阻塞 Latch → 生产可异步回调/CompletableFuture
//   ③ 异常只传 message → 生产传堆栈+异常类型
```

### 3.4 编译运行与观察

```powershell
cd D:\mywork\springbootai\learning\month03-net-mysql
# 连同 day12 的协议类一起编译（Msg/MsgDecoder/MsgEncoder 在里面）
javac -cp "lib/netty-all.jar" -d out ProtocolCodec.java RpcDefine.java RpcServer.java RpcClient.java
java -cp "out;lib/netty-all.jar" RpcServer          # 终端1：服务端 9989
java -cp "out;lib/netty-all.jar" RpcClient          # 终端2：客户端

# 观察记录（截图/抄日志）：
#   □ 客户端打印 findName(42)=张三-42、calc(19,23)=42 —— 远程调用像本地
#   □ 服务端打印 "收到调用: UserService#findName" —— 反射调用实证
#   □ 改一行试错：把 SERVICES.put 注释掉再跑 → 客户端收到 "服务未注册" 异常
```

## 4. 面试连接

**Q：手写一个 RPC 框架需要哪些组件？**
> 六件套：① 动态代理（屏蔽远程细节）；② 序列化（对象↔字节）；③ 协议/编解码（粘包根治，长度头）；④ 网络传输（Netty 主从 Reactor）；⑤ 注册中心（服务发现）；⑥ 负载均衡（多节点选择）。加分句："我用 200 行代码写过迷你版：Map 当注册中心、JDK 代理当 Stub、day12 的长度头协议传输、反射调用，双节点轮询跑通。"（明天的博客素材就是它）

**Q：JDK 动态代理的原理？**
> `Proxy.newProxyInstance` 在运行期生成字节码类 `$Proxy0`（继承 Proxy、实现目标接口），接口方法全部转发到 `InvocationHandler.invoke(proxy, method, args)`。对比：CGLIB 走子类继承，代理无接口的类；JDK 代理只能代理接口。Spring AOP、Dubbo consumer、MyBatis Mapper 全是这个原理——面试说出"$Proxy0 + InvocationHandler 收口"就是懂原理。

**Q：为什么生产不用 Java 原生序列化？**
> 三宗罪：① 体积大（带类元信息和字段描述）；② 跨语言差（JSON/protobuf 谁都认，原生只有 JVM 认）；③ 安全风险（反序列化漏洞重灾区）。生产主流：protobuf（跨语言+体积小，gRPC/Dubbo triple）、kryo（快，Java 系内部）、hessian2（Dubbo 经典默认）。今天的 Base64+原生序列化是教学妥协，面试要主动点破。

**Q：Dubbo 一次调用的完整流程？**
> consumer 代理 → 集群容错策略（failover 默认：失败换节点重试 2 次）→ 负载均衡（默认随机）选出 provider → 协议编码 → Netty 发送 → provider IO 线程解码 → 交业务线程池 → 反射调用实现 → 原路返回。加分句："这条链我手写复现过，生产版多出 request id 多飞行、泛化调用、地址推送这些工业化部件。"

## 5. 今日验收清单

- [ ] RpcServer/RpcClient 双端跑通，日志抄进笔记
- [ ] "服务未注册"试错实验完成（理解注册中心作用）
- [ ] RPC 全链路六步图手绘（①~⑥）
- [ ] 动态代理原理 + 原生序列化三宗罪能展开讲
- [ ] `git add . && git commit -m "day13: mini rpc part1"`

---
[← Day 12](day12-Netty实战编解码与零拷贝.md) | [本月目录](README.md) | [Day 14 · 手写迷你 RPC（下） →](day14-手写迷你RPC下与第二周复盘.md)
