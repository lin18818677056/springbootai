# Day 24 · 行为型模式：状态机与策略接力

> **今日目标**：掌握五个高频行为型模式（策略/模板方法/责任链/观察者/状态）；订单状态机枚举落地（README 验收⑤："非法流转双重拦截"——兑现 M2 day05 状态模式伏笔）。
> **时长**：模式学习 2h / 状态机落地 2.5h / 辨析整理 0.5h
> **今日产出**：OrderStatus 状态机（流转表+双重拦截）+ 策略支付改造收口

## 1. 知识地图

```
行为型模式回答："对象之间怎么分工协作？"——五个高频，全是老朋友的正式亮相：

① 策略（Strategy）：一族算法各自封装、运行时替换——day22 工厂的"行为侧"接力
   支付渠道落位全景（三周改造的总收口）：
   PayChannel 接口 = 策略抽象 / WechatPayChannel/AlipayChannel = 具体策略
   PayChannelFactory = 策略解析器 / 适配器封装 SDK（day23）
   ——创建侧（day22）+结构侧（day23）+行为侧（今天）= "策略+工厂+适配器"组合拳骨架

② 状态（State）：行为随状态改变——订单状态机（M2 day05 伏笔的最大实战）
   枚举版状态机（比类版状态模式更轻）：流转表放枚举里，非法流转在枚举层就拦截
   双重拦截设计（README 验收⑤）：
   第一道：OrderStatus.canTransferTo()——枚举层静态规则，工具方法也能查
   第二道：Order.pay()/cancel() 里的 assertStatus——聚合层业务语义拦截
   为什么两道：枚举层拦"数据态错误"（DB 脏数据修复时用），聚合层拦"业务态错误"（运行时主防线）

③ 模板方法（Template Method）：骨架固定，步骤子类实现——M6 day19 对账的正式冠名
   AbstractReconcileJob.finalize 流程：拉源数据→对齐→分级差异→处理→上报
   钩子方法（hook）：子类可覆盖的空实现步骤——模板的"留白"

④ 责任链（Chain of Responsibility）：请求沿链传递——M6 day05 Gateway 过滤器链的正式冠名
   下单校验链：LoginFilter→RiskFilter→StockFilter→LimitFilter——每节点可断链
   与装饰器辨析：链上节点互不知道彼此（顺序敏感），装饰器是层层包裹（嵌套敏感）

⑤ 观察者（Observer）：一对多依赖，状态变化通知——day18 Spring Event 的正式冠名
   进程内事件就是观察者模式的框架化——同步/异步/AFTER_COMMIT 三种投递姿势

低频眼熟：命令（请求对象化——MQ 消息本质）、迭代器、中介者、备忘录、访问者——认识即可
```

## 2. 核心概念（中英对照）

| 概念 | 说明 |
|------|------|
| Strategy | 策略（算法族替换） |
| State | 状态（行为随状态改变） |
| Transition Table | 流转表（合法状态迁移清单） |
| Template Method | 模板方法（骨架固定步骤下沉） |
| Chain of Responsibility | 责任链（沿链传递可断链） |
| Observer | 观察者（事件通知） |

## 3. 动手实操：订单状态机（双重拦截）

```java
// ① 状态枚举：流转表内聚（learning 与 mall-order 双份落地）
public enum OrderStatus {
    CREATED, PAID, SPLIT, SHIPPED, DELIVERED, COMPLETED,
    CANCELLED, CLOSED, REFUNDING, REFUNDED;

    // 流转表：谁→能去哪（一张 Map 就是状态机的全部规则——day13 状态机草图的代码化）
    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
        CREATED,   EnumSet.of(PAID, CANCELLED, CLOSED),      // 待支付：付/取消/超时关
        PAID,      EnumSet.of(SPLIT, REFUNDING),             // 已支付：拆单/退款
        SPLIT,     EnumSet.of(SHIPPED, REFUNDING),
        SHIPPED,   EnumSet.of(DELIVERED),
        DELIVERED, EnumSet.of(COMPLETED, REFUNDING),         // 签收：完成/售后
        REFUNDING, EnumSet.of(REFUNDED, DELIVERED),          // 退款：成功/被驳回回签收
        COMPLETED, EnumSet.noneOf(OrderStatus.class),        // 终态
        CANCELLED, EnumSet.noneOf(OrderStatus.class),
        CLOSED,    EnumSet.noneOf(OrderStatus.class),
        REFUNDED,  EnumSet.noneOf(OrderStatus.class));

    public boolean canTransferTo(OrderStatus target) {       // 第一道拦截：静态可查
        return ALLOWED.getOrDefault(this, EnumSet.noneOf(OrderStatus.class)).contains(target);
    }
}

// ② 聚合层：第二道拦截（业务语义+事件副作用）
public class Order {
    public void pay(Money paid) {
        transition(OrderStatus.PAID);                        // 统一走状态迁移闸口
        registerEvent(new OrderPaid(id.value(), paid));      // 合法流转才伴随事件
    }
    public void cancel() {
        // ……30 分钟窗口校验（day19 R2）
        transition(OrderStatus.CANCELLED);
        registerEvent(new OrderCancelled(id.value()));
    }
    private void transition(OrderStatus target) {
        if (!status.canTransferTo(target))                   // ← 第一道：流转表
            throw new DomainException(status + " 不允许流转到 " + target);
        this.status = target;                                // ← 唯一写状态的地方
    }
    // assertStatus 保持（day19）：部分方法只要求"处于某态"而非"迁移"，两工具并存
}

// ③ 防御实验：绕过聚合直改 DB 的脏数据也会被第二道闸口拦住
// UPDATE order SET status='PAID' WHERE status='COMPLETED'  ← 脏数据
// → 加载后调 cancel() → transition 抛"COMPLETED 不允许流转到 CANCELLED"（枚举层规则兜住）
```

```powershell
# 状态机验证：
cd D:\mywork\springbootai\learning\month07-ddd-architecture
javac -encoding UTF-8 -d out src\OrderStateMachineLab.java ; java -cp out OrderStateMachineLab
# 实验清单：
# ① CREATED→PAID 通过；COMPLETED→CANCELLED 拒绝（流转表拦截）
# ② 全状态穷举：双层遍历打印合法迁移矩阵（对照 day13 时间线草图核对无遗漏）
# ③ 脏数据实验：手工改库造 COMPLETED→CANCELLED 意图 → 聚合层拦截截图
cd D:\mywork\springbootai\practice-projects\02-microservice-mall\mall ; .\gradlew.bat :mall-order:test
git add . ; git commit -m "day24: state machine & behavior patterns"
```

## 4. 面试连接

**Q：策略模式和状态模式有什么区别？**
> 结构几乎相同（接口+一族实现），分水岭是"谁决定换实现"：策略由外部客户端选择（下单时用户选了 wechat，工厂路由到 WechatPayChannel——实现之间互不知晓）；状态由对象自身内部驱动（订单付了钱自己变 PAID，下一个可用行为跟着变——状态之间有迁移关系）。判别口诀："换的是'怎么做'是策略，换的是'我现在是谁'是状态。"实战里两者常合体：订单聚合的状态机（State）决定暴露哪些行为方法，支付动作内部再按渠道路由（Strategy）——一个管身份，一个管手段。

**Q：订单状态机怎么防止非法流转？（双重拦截）**
> 两道闸口各管一层：第一道在 OrderStatus 枚举的流转表——Map<状态,合法目标集合>，canTransferTo() 静态可查，好处是"规则可被工具使用"：数据修复脚本、后台管理页都能先查表再动手，不用拉起整个聚合；第二道在聚合的 transition() 闸口——所有状态变更必须经它，拦完还要挂事件副作用（合法流转才伴随 OrderPaid 这类事件，day18 的聚合攒事件从这里统一走）。我做过脏数据实验：手工把库里的单改成 PAID 再试图取消，第二道直接拒绝——枚举层管规则、聚合层管语义+副作用，两道合起来才是"非法流转双重拦截"。全状态穷举矩阵和 day13 事件风暴的时间线草图核对过，没有遗漏迁移。

**Q：责任链和模板方法在你项目里怎么用的？**
> 责任链最典型的就是 M6 的 Gateway 过滤器链：鉴权→灰度→限流依次过，每层可断链（AuthGlobalFilter 401 直接短路）——节点互不感知、顺序敏感；业务侧我用来做下单校验链（登录/风控/库存/限购），新增校验节点插链即可，主流程零改。模板方法的代表是 M6 的对账 Job：AbstractReconcileJob 定死"拉数→对齐→分级→处理→上报"五步骨架（finalize 防子类乱改流程），订单对账和积分对账只实现差异化的拉数与处理步骤——骨架即规范，新对账类型半天上线。两者的选择：流程是"固定骨架+可变步骤"用模板方法，是"动态串节点+可短路"用责任链。

## 5. 今日验收清单

- [ ] OrderStatus 流转表+canTransferTo（枚举层）
- [ ] transition 闸口统一（聚合层第二道拦截+事件挂载）
- [ ] 全状态穷举矩阵对照时间线草图
- [ ] 脏数据实验截图；策略/工厂/适配器组合拳全景能画
- [ ] `git add . && git commit -m "day24: state machine"`

---
[← Day 23](day23-结构型模式.md) | [本月目录](README.md) | [Day 25 · 组合拳与规则引擎 →](day25-组合拳与规则引擎.md)
