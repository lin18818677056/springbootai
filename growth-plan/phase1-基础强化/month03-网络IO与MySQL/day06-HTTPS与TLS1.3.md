# Day 06 · HTTPS 与 TLS 1.3（一次抓包看懂加密握手）

> **今日目标**：day05 的 ALPN 就藏在 TLS 握手里。今天抓一次完整的 TLS 1.3 握手（1-RTT），看懂"非对称换密钥、对称传数据"的组合逻辑与证书链校验，最后对比 1.2/1.3 差异。
> **时长**：理论 1h / 实操 1.5h / 输出 0.5h
> **今日产出**：TLS 1.3 握手抓包标注 + 证书链查看记录 + 1.2/1.3 对比表

## 1. 知识地图

```
加密组合拳（为什么两把锁混着用）：
  非对称（RSA/ECDHE）：安全但慢 → 只用来"协商出对称密钥"
  对称（AES-GCM/ChaCha20）：快 → 协商好后传业务数据
  摘要（SHA-256）：保证完整性 + 签名

TLS 1.3 握手（1-RTT）：
  客户端                                     服务端
    │ ① ClientHello                            │
    │   [支持的套件 + key_share(ECDHE公钥)] ────→ │
    │                                          │ 选套件、做证书签名
    │ ② ServerHello + {证书} + {Finished} ←──── │
    │   [选定套件 + 服务端 key_share + 签名证书]  │
    │ ③ {Finished} + [加密数据立刻开跑] ────────→ │
    └ 双方 ECDHE 私钥交换 → 会话密钥，1 个 RTT 完成
  ★方括号[]=明文部分，花括号{}=已加密部分（ClientHello 后的都加密了）

  TLS 1.2 的差距（2-RTT）：协商完还要一次往返确认才发数据；
  前向保密（Forward Secrecy）：1.3 强制 ECDHE（临时密钥）——
  即使服务器私钥日后泄露，历史抓包也无法解密（每次会话密钥独立）
  （1.2 若用静态 RSA 密钥交换则无前向保密——已淘汰）

证书链（Certificate Chain）：
  根 CA（OS/浏览器内置信任） ← 中间 CA ← 站点证书
  校验：逐级验签 + 域名匹配 + 有效期 + 吊销状态(OCSP)
  ★服务器发的是"站点+中间"证书，根证书在用户机器里
```

## 2. 核心概念（中英对照）

| 英文 | 中文 | 要点 |
|------|------|------|
| TLS Handshake | TLS 握手 | 1.3 用 1-RTT，支持 0-RTT 恢复 |
| ECDHE | 椭圆曲线临时密钥交换 | 每次会话临时密钥 → 前向保密 |
| Cipher Suite | 密码套件 | 如 TLS_AES_128_GCM_SHA256 |
| Certificate Chain | 证书链 | 根→中间→站点，逐级验签 |
| Forward Secrecy | 前向保密 | 私钥泄露不解密历史流量 |
| SNI | 服务器名称指示 | ClientHello 里带域名（一个 IP 多站） |
| ALPN | 应用层协议协商 | h2/http1.1 在握手时定（day05 伏笔） |

## 3. 动手实操

### 3.1 主菜：Wireshark 抓 TLS 1.3 握手并逐包标注

```powershell
# ① 清缓存确保新握手
ipconfig /flushdns
# ② Wireshark 开始捕获，过滤：tls.handshake
# ③ 浏览器无痕窗口访问 https://httpbin.org/get（无痕避免会话复用）
# ④ 停止捕获，逐包记录（对照下面清单填写实际值）：
```

```text
抓包标注清单（把实际包号与字段填入）：
  □ Client Hello：
      TLS 版本 advertised = TLS 1.3（legacy_version 字段看 1.2 的兼容写法）
      Cipher Suites 数量 = ___（1.3 套件通常 3-5 个）
      key_share 扩展 = x25519（椭圆曲线名）
      ALPN 扩展 = h2, http/1.1（day05 伏笔回收）
      SNI 扩展 = httpbin.org
  □ Server Hello：
      选定 Cipher Suite = TLS_AES_128_GCM_SHA256（或你抓到的实际值）
      key_share = 服务端公钥
  □ Encrypted Extensions / Certificate / Certificate Verify / Finished
      （1.3 里这些全部加密了！1.2 的证书是明文可看的）
      ★这就是"1.3 更安全"的可视证据：连证书都不给中间人看
  □ Application Data：之后全是加密记录
  ★总 RTT：ClientHello → 第一个 Application Data = 1 个往返 = 1-RTT 达成
```

### 3.2 实验②：openssl 看证书链（Windows 可用 Git Bash / WSL / 容器）

```powershell
# 方式一：Docker 容器里跑 openssl（推荐，环境统一）
docker run --rm alpine/openssl s_client -connect httpbin.org:443 -servername httpbin.org `
  2>&1 | Select-String -Pattern "depth|verify|Certificate chain|s:|i:" 
# 记录：
#   □ 证书链有几层？（站点 + 中间，根在内置信任库）
#   □ 每层的主题(s:) 与签发者(i:) 对应关系
#   □ verify return code = 0（校验通过）

# 方式二：浏览器点击地址栏锁图标 → 查看证书 → 看"证书路径"树
```

### 3.3 实验③：会话恢复 0-RTT 与 1-RTT 对比（可选）

```powershell
# 连续访问两次同一站点，Wireshark 对比：
# 第一次：完整握手（ClientHello + Certificate...）
# 第二次：可能看到 Pre-shared Key 恢复（PSK），证书不再重发
# curl 两次的 time_appconnect 对比：
curl.exe -s -o NUL -w "第1次 应用层就绪=%{time_appconnect}s\n" https://httpbin.org/get
curl.exe -s -o NUL -w "第2次 应用层就绪=%{time_appconnect}s\n" https://httpbin.org/get
# 第二次更快 = 会话恢复生效（TLS 复用的价值）
```

### 3.4 输出：TLS 1.2 vs 1.3 对比表

```text
| 维度 | TLS 1.2 | TLS 1.3 |
|------|---------|---------|
| 握手往返 | 2-RTT | 1-RTT（恢复 0-RTT） |
| 密钥交换 | RSA/ECDHE 可选 | 强制 ECDHE（前向保密） |
| 证书传输 | 明文 | 加密（EncryptedExtensions 后） |
| 套件数量 | 数十个（含弱算法） | 5 个精简（全部 AEAD） |
| 会话恢复 | session id/ticket | PSK + 0-RTT |
| 已知风险 | 配置复杂易踩弱套件 | 0-RTT 有重放风险（只用于幂等请求） |
```

## 4. 面试连接

**Q：TLS 握手为什么需要非对称加密，之后又换成对称？**
> 分工：非对称解决"在不安全信道上协商出共同秘密"（安全性），但运算慢（RSA 签名/解密是 AES 的千倍级），不适合传业务数据；对称快但密钥不能明文传。所以 ECDHE 交换出会话密钥，后续全对称。加分实证："我抓包看过 1.3 的 ClientHello 带 key_share，第二次握手就全是密文了。"

**Q：TLS 1.3 比 1.2 快在哪、安全在哪？**
> 快：2-RTT→1-RTT（握手即完成），PSK 恢复 0-RTT；证书加密传输。安全：① 强制 ECDHE 前向保密（私钥泄露不解密历史流量）；② 砍掉 RSA 静态交换/CBC 等弱套件，套件从几十个精简到 5 个；③ 握手更多部分加密（中间人可见信息更少）。再补一刀："0-RTT 有重放风险，所以只允许幂等请求用。"

**Q：浏览器怎么知道证书是可信的？**
> 链式验证：站点证书由中间 CA 签名，中间 CA 由根 CA 签名，根 CA 证书预置在操作系统/浏览器信任库。校验逐级验签 + 域名 SAN 匹配 + 有效期 + OCSP/CRL 吊销检查。扩展：SNI 明文暴露域名 → ECH（加密 ClientHello）正在解决；一个 IP 部署多站点靠 SNI 区分。

## 5. 今日验收清单

- [ ] TLS 1.3 握手抓包标注完整（含 key_share/ALPN/SNI）
- [ ] 证书链查看记录（层数 + 验签链）
- [ ] 会话恢复对比数据（两次 time_appconnect）
- [ ] 1.2/1.3 对比表默写
- [ ] `git add . && git commit -m "day06: tls 1.3 handshake"`

---
[← Day 05](day05-HTTP演进与多路复用.md) | [本月目录](README.md) | [Day 07 · 第一周复盘输出 →](day07-第一周复盘输出.md)
