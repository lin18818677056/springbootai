# Day 15 · Seata AT 模式：两阶段提交与 undo_log

> **今日目标**：部署 seata-server，把"下单扣库存"跨服务链路 AT 化（@GlobalTransactional），实测异常自动回滚；讲清 AT 两阶段原理与 undo_log 机制。
> **时长**：部署接入 2h / AT 原理 2h / 实战回滚 1h
> **今日产出**：AT 分布式事务链路（可回滚）+ AT 原理手绘图

## 1. 知识地图

```
问题引入（本周的坑）：order 库存下单成功了，product 扣库存失败——
  本地事务管不了跨服务：两个数据库、两个进程，@Transactional 失效
  M5 学过六种方案（2PC/TCC/SAGA/本地消息表/最大努力/事务消息）——
  Seata AT = "自动化的补偿型事务"，业务零侵入（加个注解就能用）

Seata 三大角色：
  TC 事务协调器（seata-server）：独立部署，维护全局事务/分支事务状态，指挥提交或回滚
  TM 事务管理器：发起方（order），定义全局事务边界 @GlobalTransactional
  RM 资源管理器：每个参与者（order/product），管分支事务+上报状态

AT 模式一阶段（每个分支本地执行时）：
  ① 拦截业务 SQL（DataSourceProxy 数据源代理）
  ② 查前镜像（beforeImage：select 原始数据）→ 执行业务 SQL → 查后镜像（afterImage）
  ③ 生成 undo_log（前后镜像 JSON）插入同库 undo_log 表——与业务 SQL 同一个本地事务！
  ④ 本地事务提交前，向 TC 注册分支+申请"全局锁"（对修改的行）
  ——精髓：一阶段就提交本地事务（不等其他分支）！释放本地连接，这是 AT 快的根本

AT 模式二阶段（TC 收到 TM 的提交/回滚决议）：
  提交：异步批量删 undo_log（镜像没用了）——极快，异步
  回滚：按 undo_log 反向补偿——校验后镜像==当前数据（没被别人改过）→ 用前镜像还原
        校验失败=脏写（有人绕过全局锁改了数据）→ 报警人工介入

全局锁（Global Lock）——AT 防脏写的核心：
  行级锁存 TC 端（不是 DB 锁）：同一行同一时刻只允许一个全局事务持有
  效果：写隔离（未提交的全局事务持有的行，别的全局事务改不了——等锁）
  ⚠ 代价：热点行串行化——扣同一个商品库存的并发被全局锁排队（day16 实测损耗）
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| TC (Transaction Coordinator) | 事务协调器（seata-server） |
| TM (Transaction Manager) | 事务管理器（@GlobalTransactional 所在方） |
| RM (Resource Manager) | 资源管理器（各参与者） |
| undo_log | 回滚日志表（前后镜像） |
| Before/After Image | 前镜像/后镜像 |
| Global Lock | 全局锁（TC 端行锁，防脏写） |
| Branch Transaction | 分支事务（每个参与者） |
| Two-Phase Commit (2PC) | 两阶段提交 |

## 3. 动手实操：下单链路 AT 化

```powershell
# 1. 部署 seata-server（docker）：
# docker run -d --name seata-server -p 8091:8091 -p 7091:7091 `
#   -e SEATA_IP=127.0.0.1 seataio/seata-server:1.7.0
Start-Process "http://localhost:7091"    # 控制台（默认账号 seata/seata）

# 2. order/product 两个库各建 undo_log 表（官方 schema，字段：branch_id/xid/context/
#    rollback_info/log_status/log_created/log_modified）
# 3. Seata 接入配置（application.yml）：
# seata:
#   application-id: mall-order
#   tx-service-group: mall_tx_group
#   service:
#     vgroup-mapping:
#       mall_tx_group: default        # 映射到 TC 集群名
#     grouplist:
#       default: 127.0.0.1:8091
```

```java
// mall-order：全局事务发起方（TM+RM）
@GlobalTransactional(name = "create-order", timeoutMills = 60000, rollbackFor = Exception.class)
public Long createOrder(CreateOrderRequest req) {
    orderMapper.insert(buildOrder(req));                    // 分支1：订单落库（本地事务）
    Result<Boolean> r = productClient.deductStock(          // 分支2：Feign 调 product 扣库存
            new DeductRequest(req.getProductId(), req.getCount()));
    if (!r.success()) throw new BizException(5002, "扣库存失败，订单回滚");
    return order.getId();
}
// mall-product：参与者只需数据源被代理（自动）：扣库存 mapper 照常写
// （本质：seata 自动代理 DataSource → 所有 SQL 被拦截生成 undo_log + 注册分支）

// 验证实验：
// ① 正常链路：下单成功 → 两库数据一致 → undo_log 表被异步清空（二阶段提交）
// ② 异常回滚：product 扣库存抛异常 → order 的 insert 被回滚！
//     → 观察 order 库 undo_log 短暂出现回滚记录，数据回到下单前
// ③ 观察 xid 透传：日志搜 "xid" → Feign 请求头自动带 xid 给 product（Seata 集成 Feign）
```

## 4. 面试连接

**Q：AT 模式的两阶段原理？为什么说它对业务零侵入？**
> 一阶段：数据源代理拦截业务 SQL，查前后镜像生成 undo_log 与业务 SQL 同本地事务提交，同时向 TC 注册分支并申请全局行锁——关键设计是"一阶段直接提交本地事务"，不长时间持有 DB 连接。二阶段：全局提交就异步删 undo_log；回滚就按 undo_log 前镜像反补偿，回滚前校验后镜像与当前数据一致（防脏写）。零侵入的本质：所有镜像/注册/回滚都由数据源代理和框架自动完成，业务代码只有 @GlobalTransactional 一个注解。追问"和 XA 的区别"：XA 一阶段不提交（锁资源到二阶段），AT 一阶段就提交（undo_log 换资源提前释放）——AT 快的原因，代价是要全局锁+undo_log 保证正确性。

**Q：AT 怎么防脏写？全局锁机制讲一下。**
> 两个全局事务并发改同一行：后到者在本地提交前要向 TC 申请该行全局锁，拿不到就等待（默认重试）——保证同一行同一时刻只有一个未提交的全局事务，这是写隔离。回滚时还要校验后镜像==当前数据，若有人绕过 AT（直接 SQL/别的工具）改了数据，校验失败报脏写告警——这是最后防线。收尾："所以 AT 有个隐含纪律：参与库的数据尽量别绕过 Seata 直接改——我们运维修数都要先看有没有活跃全局事务。"（带纪律意识=踩过坑）

## 5. 今日验收清单

- [ ] seata-server 部署 + 控制台可见事务
- [ ] 两库 undo_log 表建好，AT 链路跑通
- [ ] 异常回滚实验：订单被回滚（截图）
- [ ] AT 两阶段+undo_log 原理手绘图
- [ ] 全局锁与脏写防御能讲清
- [ ] `git add . && git commit -m "day15: seata at"`

---
[← Day 14](day14-第二周复盘与博客.md) | [本月目录](README.md) | [Day 16 · AT深入与压测 →](day16-AT深入与压测.md)
