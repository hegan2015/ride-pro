# ride-pro
ride-pro 打车系统 参考滴滴领域拆分 · Spring Boot + Dubbo + Netty + H2 + 内嵌 ZooKeeper
# ride-pro 打车系统设计文档

版本 v1.2.0 ｜ 2026-11-16 ｜ 状态：已实现并全量验证（REST 冒烟 + 司乘端浏览器实测 + 启停实测 + 断连补偿实测 + 支付一致性混沌实测）

v1.2.0 变更：服务端支付一致性加固——确定性幂等键 PAY-{orderId} + CAS 行数检查 + outbox 异步投递 + 对账调度兜底（§7.4，原 L1 双扣缺陷已修复）；WS 顶号下发 KICKED 防互踢活锁（§9.2）。
v1.1.0 变更：修复推送断连无补偿缺陷（connectWS 自动重连 + CONNECTED 重同步，见 §9.4）。

---

## 1. 概述

### 1.1 目标

参考滴滴的领域拆分，用 Java + Spring Boot + Maven + Dubbo + Netty + H2 实现一个**可直接运行**的分布式打车系统，覆盖「叫车 → 派单 → 抢单 → 行程 → 计价 → 支付」完整闭环，并提供司机端与乘客端两个 H5 客户端。

### 1.2 硬约束

| 约束 | 落地方式 |
|---|---|
| 零外部部署依赖 | 注册中心用**内嵌 ZooKeeper**（进程内启动），数据库用 **H2 文件库**（每域独立库文件），无需安装任何中间件 |
| 可直接运行 | `./run.sh` 一键编译 + 启动 10 进程 + 就绪等待；`./stop.sh` 一键优雅停止 |
| 实时司乘体验 | Netty WebSocket 长连接推送（进程内库，非外部组件），H5 零安装由网关静态托管 |
| 资金正确性 | 全链路金额以「分」为单位的 long 运算；单库本地事务原子转账；余额守卫防透支；幂等防重复入账 |
| 数据保护 | `data/` 与 H2 库文件只增不删；停止服务不触碰数据 |

### 1.3 技术栈

| 组件 | 版本 | 用途 |
|---|---|---|
| Spring Boot | 3.2.5 | 进程骨架、配置、调度、静态资源 |
| Apache Dubbo | 3.2.14 | 域间 RPC（dubbo 协议 + ZooKeeper 注册） |
| MyBatis Spring Boot Starter | 3.0.3 | 注解式 Mapper（CAS SQL 直接可见） |
| H2 | 2.2.224 | 文件模式数据库，`MODE=MySQL;DATABASE_TO_LOWER=TRUE` |
| ZooKeeper | 3.8.4 | 内嵌注册中心（Curator5 客户端） |
| Netty | 4.1.109.Final（Boot BOM 管理） | WebSocket 推送服务器 |
| Lombok | Boot BOM 管理 | DTO/DO/配置类去样板 |
| JDK | 17（`maven.compiler.release=17`） | 构建与运行 |

---

## 2. 领域拆分与进程拓扑

### 2.1 对标滴滴的域划分

| 域 | 模块 | 对标滴滴 | 职责 | 独立数据库 |
|---|---|---|---|---|
| 乘客 | ride-passenger | 乘客用户系统 | 注册、查询 | passenger.mv.db |
| 司机 | ride-driver | 司机系统 | 注册（含车辆）、上下线、服务状态 | driver.mv.db |
| 位置 LBS | ride-location | 位置服务 | 司机位置上报、就近在线司机查询 | location.mv.db |
| 计价 | ride-pricing | 计价引擎 | 预估价/最终价（无状态） | 无 |
| 订单 | ride-order | 订单/行程核心 | 状态机编排、幂等、超时改派 | order.mv.db |
| 派单调度 | ride-dispatch | 派单引擎 | 就近匹配 + 抢单邀约广播（无状态） | 无 |
| 支付钱包 | ride-payment | 支付/钱包 | 充值、原子扣款/入账/抽成、流水 | payment.mv.db |
| 消息推送 | ride-push | 消息推送 | Netty WebSocket 长连接与事件下发 | 无 |
| 网关 | ride-gateway | 接入层 | REST 聚合、H5 托管、健康聚合 | 无 |
| 注册中心 | ride-registry | 基础设施 | 内嵌 ZooKeeper + HTTP 探针 | data/zookeeper |

公共模块：`ride-common`（Result/错误码/异常/工具/TraceFilter）、`ride-facade`（全部 Dubbo 契约 + DTO + HealthFacade 实现）。共 **12 个 Maven 模块、10 个运行进程**。

### 2.2 端口表

| 进程 | HTTP | Dubbo | 其他 |
|---|---|---|---|
| ride-registry | 8092（/health 探针） | — | ZK 2181 |
| ride-passenger | — | 20881 | |
| ride-driver | — | 20882 | |
| ride-location | — | 20883 | |
| ride-pricing | — | 20884 | |
| ride-order | — | 20885 | |
| ride-dispatch | — | 20886 | |
| ride-payment | — | 20887 | |
| ride-push | — | 20888 | WebSocket 9100 |
| ride-gateway | 8080（REST + H5） | 20880 | |

环境变量可覆盖：`ZK_PORT`、`ZK_HTTP_PORT`、`DATA_DIR`、`GATEWAY_PORT`、`GATEWAY_DUBBO_PORT`、`ORDER_DUBBO_PORT`、`PUSH_DUBBO_PORT`、`PUSH_WS_PORT`（见各模块 application.yml）。

### 2.3 调用拓扑

```
 乘客端 H5 ──HTTP──┐                    ┌──WS 9100── 乘客端 H5
                  ├─ ride-gateway(8080) │
 司机端 H5 ──HTTP──┘        │ Dubbo      └──WS 9100── 司机端 H5
                           ▼
        ┌────────────── ride-order（编排中枢）──────────────┐
        ▼            ▼           ▼           ▼            ▼
 ride-passenger  ride-pricing  ride-driver  ride-dispatch  ride-payment
                                          │        │
                                          ▼        ▼
                                    ride-location  ride-push ──WS──► 司乘端
                           所有进程 ──注册/发现──► ride-registry(内嵌 ZK 2181)
```

依赖方向单向：gateway → 各域；order → passenger/pricing/driver/dispatch/payment/push；dispatch → location/push；location → driver。**无环依赖**；派单域不反查订单详情，由订单域在 `DispatchRequest` 中携带快照（ride-facade/.../dispatch/DispatchRequest.java:8）。

### 2.4 Dubbo 约定

- `provider/consumer retries: 0`：写操作不允许框架级重试放大副作用，重试语义交给幂等键与改派调度。
- `consumer check: false`：启动不强制依赖方在线，配合 run.sh 就绪轮询达成最终一致就绪。
- `qos-enable: false`：关闭 QoS 端口，避免 10 进程端口冲突。
- 超时：订单域 provider/consumer 8000ms、push 5000ms（其余域见各自 application.yml），健康探针 reference 1500ms。
- HealthFacade 以 `group="${spring.application.name}"` 暴露，网关按 group 精确引用各域探针（HealthService.java:24-39）。

---

## 3. 统一契约与横切机制

### 3.1 Result 与错误码

RPC 边界不抛异常（阿里规约）：Facade 实现一律 `FacadeSupport.execute` 包装（FacadeSupport.java:18-27），BizException 透传错误码，未知异常归一 `B0002` 并落日志。消费方用 `Result.orThrow()` 在编排链路快速失败（Result.java:53-58）。

| 码 | 含义 | HTTP 映射（GatewaySupport.java:23-35） |
|---|---|---|
| 00000 | 成功 | 200（body 为 data 本身） |
| A0400 | 参数错误 | 400 |
| A0404 | 资源不存在 | 404 |
| A0409 | 状态冲突 | 409 |
| B0001 | 业务失败 | 400 |
| B0002 | 系统内部错误 | 500 |
| C0001 | 下游服务异常 | 502 |

失败 HTTP body 统一 `{error, code}`；网关另有 GlobalExceptionHandler 兜底 RpcException→503、坏 JSON→400、未知→500。

### 3.2 全链路 traceId

`TraceFilter`（TraceFilter.java:22-37）经 Dubbo SPI（ride-common/src/main/resources/META-INF/dubbo/org.apache.dubbo.rpc.Filter）在 provider/consumer 双端激活：优先取服务端 attachment，其次客户端 attachment，缺失则生成 UUID；落 MDC 并向下游透传，finally 清理。网关 TraceWebFilter 为 HTTP 入口生成同一 key，实现 HTTP→RPC 全链路日志串联。

### 3.3 金额与地理工具

- `MoneyUtil.fee(amount, bps) = (amount*bps + 5000) / 10000`（四舍五入，MoneyUtil.java:17-19）；`requireValid` 限定 1 ~ 1_000_000_000_000 分（MoneyUtil.java:21-26）。
- `GeoUtil.distanceKm`：Haversine 球面距离（GeoUtil.java:15-22）；`estimateMinutes = max(1, round(km/30*60))`，按城市均速 30km/h 估时（GeoUtil.java:25-27）。演示系统以直线距离近似路网距离（生产应接路径规划）。

---

## 4. 订单状态机

### 4.1 状态与流转

```
CREATED ─► DISPATCHING ─► ACCEPTED ─► ARRIVED ─► IN_PROGRESS ─► COMPLETED ─► PAID
   └──────────┴────────────┴───────────┘
                    └──► CANCELLED（行程开始前任意态）
```

**实现说明**：叫车在单个本地事务内「建单 + 落幂等记录」并直接置 `DISPATCHING`（OrderService.java:95、102-106），创建与派单提交原子化，因此当前流程不产生 CREATED 落库记录；CREATED 保留在状态机定义与取消兼容集合中（OrderMapper.java:66），作为语义初态与未来「先建单后派单」拆分的兼容位。

### 4.2 流转表（全部 CAS，守卫写在 SQL WHERE 中）

| 动作 | From → To | SQL 守卫 | 触发方 | 提交后副作用 |
|---|---|---|---|---|
| call | （新建）→ DISPATCHING | insert + 幂等记录同事务 | 乘客 | dispatch 广播 + ORDER_CREATED |
| accept | DISPATCHING → ACCEPTED | `status='DISPATCHING'`（OrderMapper.java:43-46） | 司机抢单 | 司机→BUSY；DRIVER_ACCEPTED/ORDER_ACCEPTED |
| arrive | ACCEPTED → ARRIVED | `status='ACCEPTED' AND driver_id=#{driverId}`（:48-50） | 司机 | DRIVER_ARRIVED |
| startTrip | ARRIVED → IN_PROGRESS | `status='ARRIVED' AND driver_id=…`（:52-54） | 司机 | TRIP_STARTED |
| completeTrip | IN_PROGRESS → COMPLETED | `status='IN_PROGRESS' AND driver_id=…`（:56-59） | 司机 | 重计价写 actual_fee；TRIP_COMPLETED 双端 |
| pay | COMPLETED → PAID | `status='COMPLETED'`（:61-62） | 乘客 | 支付域转账；司机→ONLINE；ORDER_PAID 双端 |
| cancel | CREATED/DISPATCHING/ACCEPTED/ARRIVED → CANCELLED | `status IN (…)`（:65-67） | 乘客 | 已接单则释放司机→ONLINE；ORDER_CANCELLED 双端 |

CAS 返回 0 行即抛 `A0409`，把「并发抢单仅一人成功」「非本司机不可操作」「状态不符不可流转」全部下沉到数据库行锁，服务层无锁。

### 4.3 司机状态机

`OFFLINE ⇄ ONLINE → BUSY → ONLINE`（支付/取消释放后回 ONLINE）。

- 注册初始 OFFLINE（DriverService.java:35）。
- `setOnline`：BUSY 时拒绝上下线切换（DriverService.java:52-54），防止服务中司机消失。
- `setStatus`：仅接受 ONLINE/BUSY/OFFLINE 三值（DriverService.java:63-65），由订单编排驱动（accept→BUSY，pay/cancel→ONLINE）。
- 接单前置校验司机必须 ONLINE（OrderService.java:125-127），BUSY 司机不可再抢单。

---

## 5. 派单引擎（抢单模式）

### 5.1 流程

1. 订单域 `dispatch()` 先 `markDispatched` 刷新本轮半径与时间（仅 DISPATCHING 生效，OrderMapper.java:70-72），再携快照调派单域（OrderService.java:277-295）。
2. 派单域 `locationFacade.nearby(起点, radius, limit=offerLimit)` 拉取就近**在线**司机（DispatchService.java:34-40）。
3. 逐司机推送 `ORDER_OFFER` 邀约帧；单个推送失败仅 warn 不中断广播（DispatchService.java:54-59）。
4. 司机端 H5 渲染抢单卡片，点击调 `POST /api/orders/{id}/accept`；订单 CAS 保证仅一人成功，其余收 409。
5. 无人接单时订单域 `@Scheduled` 改派兜底（见 5.3）。

### 5.2 参数（order 前缀，OrderProperties.java:11-17 / ride-order application.yml:38-45）

| 参数 | 默认 | 含义 |
|---|---|---|
| default-radius-km | 5.0 | 首轮广播半径 |
| max-radius-km | 20.0 | 半径上限 |
| radius-step-km | 3.0 | 每轮扩半径步长 |
| dispatch-timeout-ms | 15000 | 本轮无接单判定超时 |
| redispatch-interval-ms | 5000 | 改派调度扫描间隔 |
| dispatch.offer-limit | 20 | 单轮最多邀约司机数（DispatchService.java:31） |

### 5.3 超时改派

`redispatch()`（OrderService.java:262-275）每 5s 扫 DISPATCHING 订单：`now - last_dispatch_at >= 15s` 则半径 `min(max, current+step)` 重新广播（5→8→11→14→17→20 封顶）。派单 RPC 失败仅 warn（OrderService.java:292-294），因 `markDispatched` 已刷新时间，下一轮超时后自动重试——**失败不丢单**。

### 5.4 ORDER_OFFER 帧数据

`orderId, passengerName, originName, originLat, originLng, destName, estimateFee, distanceKm, distanceToPassenger`（DispatchService.java:44-53），司机端据此渲染「距乘客 x km / 预估 ¥y」抢单卡片。

---

## 6. 计价引擎

无状态域，公式（PricingService.java:27-31）：

```
distanceKm  = haversine(起点, 终点)            // 保留两位小数展示
durationMin = max(1, round(distanceKm/30*60))
billableKm  = max(0, distanceKm - baseKm)
distanceFee = round(billableKm * perKmFee)
timeFee     = durationMin * perMinFee
totalFee    = baseFee + distanceFee + timeFee
```

参数（pricing 前缀，PricingProperties.java:12-18）：baseFee=1300 分（¥13）、baseKm=3.0、perKmFee=230 分/km、perMinFee=50 分/min。

**算例**（国贸→中关村，14.77km）：duration=30min；billable=11.77km；distanceFee=round(11.77×230)=2707；timeFee=1500；total=1300+2707+1500=**5507 分（¥55.07）**，与冒烟实测一致。

预估与最终价共用同一 `quote`：completeTrip 时重计价写 actual_fee（OrderService.java:166-167）。演示场景起终点不变，故 actualFee==estimateFee；接入真实轨迹后仅需替换 quote 入参。

---

## 7. 支付与钱包

### 7.1 原子转账（单库本地事务）

`PaymentService.pay`（PaymentService.java:102-128）在同一 H2 事务内顺序完成：

1. `ensure(乘客)` 建零余额钱包（若不存在）→ `debit(乘客, amount)`，**SQL 带 `balance>=amount` 守卫**（WalletMapper.java:22-24），0 行即抛 A0409「乘客余额不足」，杜绝并发透支；
2. 流水 `PAY_DEBIT −amount`；
3. `ensure(司机)` + `credit(司机, amount−fee)`，流水 `PAY_CREDIT`；
4. `ensure(SYS_PLATFORM)` + `credit(平台, fee)`，流水 `PLATFORM_FEE`；
5. 读乘客余额组装 PayResultDTO，落幂等记录（requestId 主键）；
6. **写 outbox 事件 `pay_outbox`（NEW）**，与扣款同事务提交（:126），供异步投递通知订单域完结（§7.4）。

任一步失败整体回滚，**不存在「扣了乘客没入司机」的中间态**。fee 与司机入账由 `MoneyUtil.fee(amount, feeBps)` 决定：feeBps=2000（20%），platformUserId=SYS_PLATFORM（PaymentProperties.java:11-15）。

**守恒验证**：冒烟以 5507 分支付 → fee=(5507×2000+5000)/10000=1101、司机入 4406、乘客 10000−5507=4493；4493+4406+1101=10000（初始充值），分毫不差。

### 7.2 流水审计

`ledger_entry` 每笔充值/扣款/入账留痕，`balance_after` 为该笔后钱包余额（PaymentService.java:148-160），可按 user_id / order_id 索引回放对账。**PAY_DEBIT 流水与扣款同事务提交，流水存在即该单钱已划转**——这是对账判定依据（`isPaid`，PaymentService.java:144-146）。

### 7.3 充值

`topup` 同样事务化：ensure + credit + TOPUP 流水 + 幂等记录（bizNo 键），重复 bizNo 返回原结果（PaymentService.java:41-71）。`wallet(userId)` 查询亦 ensure（零余额钱包懒创建，PaymentService.java:78）——GET 带建钱包副作用，属演示简化（见 §14）。

### 7.4 跨域支付一致性：确定性幂等键 + outbox + 对账

支付域转账与订单域完结分属两个 H2 本地事务，中间存在崩溃窗口（扣款已提交、订单 CAS 未执行时进程重启/超时）。三道防线全部在服务端，**不依赖客户端重试或前端补偿**：

1. **确定性幂等键**：订单 pay 以 `PAY-{orderId}` 作为两域 requestId（OrderService.java:398-400），一单一支付——任意重试/并发（无论客户端带什么 requestId）都在支付域唯一键上回放首次结果，杜绝双扣（原 L1 由此根治）。
2. **outbox 异步投递（主路径，秒级）**：`pay_outbox` 事件与扣款同事务落库（PaymentService.java:126）；`OutboxDispatcher` 每 1s 轮询 NEW 事件（OutboxDispatcher.java:27-42），调 `OrderFacade.onPaySucceeded` 幂等完结（已 PAID 则无操作），成功置 SENT，失败指数退避（1s×2 封顶 30s）。事件持久化，进程重启后继续投递。
3. **对账调度（兜底）**：订单域每 5s 扫 COMPLETED 超宽限期（10s）的订单，经 `PaymentFacade.isPaid` 查 ledger PAY_DEBIT，已扣款则 `finalizePaid`（OrderService.java:295-311）。覆盖 outbox 投递长期失败等极端场景。

`finalizePaid`（OrderService.java:218-236）幂等：事务内 CAS pay **行数检查**（0 行=已被完结，重放无副作用）+ MERGE 幂等记录（upsert）→ 司机回 ONLINE → 双端 ORDER_PAID。支付路径、outbox 事件、对账三条路径可重复执行。

崩溃时机语义：**扣款提交前**崩溃 → H2 回滚，钱未动，订单仍 COMPLETED，重试正常支付；**扣款提交后**崩溃 → outbox 事件已在库，恢复后 ~1s 自愈（实测 §17 O17），对账最迟 15s 兜底。

---

## 8. 幂等设计

三处写入口共用同一模式：**读幂等表快路径 → 事务内「业务写 + 幂等记录写」→ 捕 DuplicateKeyException 回读原结果**。

| 入口 | 幂等键 | biz_type | 落表 |
|---|---|---|---|
| 叫车 call | requestId（缺省生成 UUID） | CALL | order 库 idempotent_record |
| 订单支付 pay | **PAY-{orderId}**（确定性，与客户端 requestId 无关） | PAY | order 库 idempotent_record（MERGE upsert） |
| 钱包充值 topup | bizNo | TOPUP | payment 库 idempotent_record |
| 支付域 pay | requestId（订单域传入 PAY-{orderId}） | PAY | payment 库 idempotent_record |

- 快路径：命中且 result_json 非空直接反序列化返回（OrderService.java:73-77、186-190；PaymentService.java:89-95）。
- 并发双写：唯一主键使第二个事务抛 DuplicateKeyException，回读胜者结果（OrderService.java:107-113；PaymentService.java:132-140）；回读仍空（胜者未提交完）抛 A0409「处理中」，客户端可重试。
- 支付域重复命中额外置 `repeated=true`（PaymentService.java:93、136），供调用方区分首付与回放。
- 订单 pay 另有状态快路径：已 PAID 直接返回当前订单（OrderService.java:191-193）。
- 支付完结记录用 MERGE 写入（IdempotentMapper.java:18-20），支付/outbox/对账三路重复完结均安全（§7.4）。

---

## 9. Netty 推送域

### 9.1 服务器与 pipeline

`NettyWebSocketServer` 随 Spring 容器启停（InitializingBean/DisposableBean），pipeline（NettyWebSocketServer.java:54-58）：

```
HttpServerCodec → HttpObjectAggregator(65536) → UriCaptureHandler
→ WebSocketServerProtocolHandler("/ws", null, true, 65536, false, true)
→ WebSocketFrameHandler(@Sharable)
```

- **身份走路径** `/ws/{role}/{id}`（role=passenger|driver）。该 Netty 版本握手对路径精确匹配，query 传身份会握手挂起；六参构造 `checkStartsWith=true` 开启前缀匹配（:57）。
- `UriCaptureHandler` 在握手消费 HttpRequest 之前把 URI 存入 Channel Attribute（:80-88），供握手完成后解析。
- boss/worker 均用 **daemon 线程工厂**（:46-47）：SIGTERM 后 JVM 可正常退出、9100 端口立即释放（非 daemon 曾导致停服务残留占端口）。

### 9.2 连接注册与帧处理

- 握手完成事件 → 解析 role/id → 非法即 close（WebSocketFrameHandler.java:35-46）→ `registry.register(role:id, channel)` → 回 `CONNECTED` 欢迎帧（:47-52）。
- `ConnectionRegistry`：ConcurrentHashMap<role:id, Channel>；**多端登录以后到覆盖，顶号时先向旧连接下发 KICKED 帧再 close**（ConnectionRegistry.java:29-43）——新版客户端收到 KICKED 即停止重连，避免两个自动重连客户端互相顶号形成活锁；注销用 `remove(key, channel)` 防误删新连接（:44-51）；离线 send 返回 false（:53-61）。
- 客户端文本帧含 `PING` → 回 `PONG`（WebSocketFrameHandler.java:59-64）；channelInactive 注销；exceptionCaught warn+close。
- 帧协议统一 JSON：`{event, data, timestamp}`（WebSocketFrameHandler.java:78-84；PushFacadeImpl.java:43-48）。

### 9.3 事件清单

| event | 接收方 | 产生点 |
|---|---|---|
| CONNECTED / PONG | 握手客户端 | WebSocketFrameHandler.java:52 / :62 |
| KICKED | 被顶号的旧连接 | ConnectionRegistry.java:34-39 |
| ORDER_CREATED | 乘客 | OrderService.java:118 |
| ORDER_OFFER | 候选司机 | DispatchService.java:55 |
| DRIVER_ACCEPTED / ORDER_ACCEPTED | 乘客 / 司机 | OrderService.java:139-140 |
| DRIVER_ARRIVED | 乘客 | OrderService.java:150 |
| TRIP_STARTED | 乘客 | OrderService.java:160 |
| TRIP_COMPLETED | 乘客+司机 | OrderService.java:174-175 |
| ORDER_PAID | 乘客+司机 | OrderService.java:233-234 |
| ORDER_CANCELLED | 乘客+司机 | OrderService.java:247-250 |

### 9.4 投递语义：尽力而为 + 重连重同步 + 轮询兜底

推送失败/客户端离线仅记日志不重试（OrderService.java:353-370；PushFacadeImpl.java:48-53）——推送是**状态变更的通知**而非状态本身，真相在订单库/钱包库。补偿分三层：

1. **自动重连**：common.js `connectWS` 断线后指数退避重连（1s 起、×2、封顶 15s）；返回 handle（close/send/readyState），`close()` 为主动放弃不再重连（切换身份时用）；**收到 KICKED（同身份被顶号）亦停止重连**并提示用户。
2. **CONNECTED 重同步**：服务端每次握手成功下发 CONNECTED；司乘端收到后立即以 DB 为准重同步钱包 + 当前订单（无订单则 loadActiveOrder），**断连期间错过的事件（含 ORDER_PAID）由此一次性追平**，不依赖事件补发。
3. **轮询兜底**：司机端每 3s 上报位置、每 4s 刷新身份/钱包/订单、每 25s WS PING；乘客端事件驱动刷新 + 手动刷新。注意后台标签页定时器被浏览器节流（可达分钟级），故轮询只是兜底，实时性靠 1+2。

**资金与推送解耦**：转账在支付域本地事务内完成，严格先于订单落库与推送；推送失败不影响入账（ledger 逐单可证）。**跨域支付一致性由服务端保证**（确定性幂等键 + outbox + 对账，§7.4），前端三层只负责展示实时性，不承担一致性责任。

---

## 10. 位置 LBS

- 上报：`MERGE INTO driver_location … KEY(driver_id)` upsert（DriverLocationMapper），每司机仅最新一点；校验司机 ID 与经纬度范围（LocationService.java:33-45）。
- 就近：`nearby` 先取司机域全量中 status=ONLINE 的 ID 集合（LocationService.java:69-74），再对位置表全量行算 haversine、过滤半径（默认 5km）、升序、limit（默认 10）（:47-59）。**内存全扫描为演示规模简化**（见 §14）。
- BUSY/OFFLINE 司机天然不在候选集，无需位置域感知订单状态。

---

## 11. 数据字典（字段级）

所有库：H2 文件模式 `jdbc:h2:file:${DATA_DIR:./data}/<域>;MODE=MySQL;DATABASE_TO_LOWER=TRUE`，启动执行 `schema.sql`（`IF NOT EXISTS` 幂等建表）。时间戳统一 BIGINT 毫秒 epoch；金额统一 BIGINT 分。

### 11.1 passenger 库

**passenger**（序列 seq_passenger，ID 前缀 P）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | VARCHAR(32) | PK | P+序列 |
| name | VARCHAR(64) | NOT NULL | 姓名 |
| phone | VARCHAR(32) | 可空 | 手机号 |
| created_at | BIGINT | NOT NULL | 注册毫秒 |

### 11.2 driver 库

**driver**（序列 seq_driver，ID 前缀 D；索引 idx_driver_status(status)）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | VARCHAR(32) | PK | D+序列 |
| name | VARCHAR(64) | NOT NULL | 姓名 |
| phone | VARCHAR(32) | 可空 | 手机号 |
| car_no | VARCHAR(32) | NOT NULL | 车牌 |
| car_model | VARCHAR(64) | 可空 | 车型 |
| status | VARCHAR(16) | NOT NULL | OFFLINE/ONLINE/BUSY |
| created_at | BIGINT | NOT NULL | 注册毫秒 |

### 11.3 location 库

**driver_location**

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| driver_id | VARCHAR(32) | PK | 每司机一行（upsert） |
| lat | DOUBLE | NOT NULL | 纬度 |
| lng | DOUBLE | NOT NULL | 经度 |
| updated_at | BIGINT | NOT NULL | 最后上报毫秒 |

### 11.4 order 库

**ride_order**（序列 seq_order，ID 前缀 O；索引 idx_order_passenger / idx_order_driver / idx_order_status）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | VARCHAR(32) | PK | O+序列 |
| passenger_id | VARCHAR(32) | NOT NULL | 乘客 ID |
| passenger_name | VARCHAR(64) | 可空 | 下单时快照 |
| driver_id | VARCHAR(32) | 可空 | 接单后填充 |
| driver_name | VARCHAR(64) | 可空 | 接单时快照 |
| driver_car_no | VARCHAR(32) | 可空 | 接单时快照 |
| origin_name | VARCHAR(128) | 可空 | 起点名 |
| origin_lat / origin_lng | DOUBLE | NOT NULL | 起点坐标 |
| dest_name | VARCHAR(128) | 可空 | 终点名 |
| dest_lat / dest_lng | DOUBLE | NOT NULL | 终点坐标 |
| distance_km | DOUBLE | 可空 | 计价距离（两位小数） |
| estimate_fee | BIGINT | 可空 | 预估价（分） |
| actual_fee | BIGINT | 可空 | 完单重计价（分） |
| status | VARCHAR(16) | NOT NULL | 八态状态机 |
| request_id | VARCHAR(64) | 可空 | 叫车幂等键 |
| cancel_reason | VARCHAR(256) | 可空 | 取消原因 |
| dispatch_radius | DOUBLE | 可空 | 当前广播半径 km |
| last_dispatch_at | BIGINT | 可空 | 本轮派单毫秒（改派判超时） |
| created_at | BIGINT | NOT NULL | 建单毫秒 |
| accepted_at / arrived_at / started_at / completed_at / paid_at / cancelled_at | BIGINT | 可空 | 各流转时刻 |

**idempotent_record**（order 库）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| request_id | VARCHAR(64) | PK | 幂等键 |
| biz_type | VARCHAR(16) | NOT NULL | CALL / PAY |
| result_json | CLOB | 可空 | 胜者结果 JSON，供回放 |
| created_at | BIGINT | NOT NULL | 毫秒 |

### 11.5 payment 库

**wallet**

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| user_id | VARCHAR(32) | PK | 乘客/司机/SYS_PLATFORM |
| balance | BIGINT | NOT NULL | 余额（分），debit 带 >= 守卫 |
| updated_at | BIGINT | NOT NULL | 最后变动毫秒 |

**ledger_entry**（序列 seq_ledger，ID 前缀 L；索引 idx_ledger_user / idx_ledger_order）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | VARCHAR(32) | PK | L+序列 |
| user_id | VARCHAR(32) | NOT NULL | 钱包主体 |
| order_id | VARCHAR(32) | 可空 | 关联订单（充值为空） |
| biz_type | VARCHAR(16) | NOT NULL | TOPUP / PAY_DEBIT / PAY_CREDIT / PLATFORM_FEE |
| amount | BIGINT | NOT NULL | 带符号金额（分） |
| balance_after | BIGINT | NOT NULL | 该笔后余额 |
| request_id | VARCHAR(64) | 可空 | 幂等键回溯 |
| created_at | BIGINT | NOT NULL | 毫秒 |

**idempotent_record**（payment 库）：结构同 order 库，biz_type 为 TOPUP / PAY。

**pay_outbox**（事务 outbox，索引 idx_outbox_pending(status, next_retry_at)）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| order_id | VARCHAR(32) | PK | 一单一事件（确定性） |
| request_id | VARCHAR(64) | NOT NULL | PAY-{orderId} |
| status | VARCHAR(16) | NOT NULL | NEW / SENT |
| retries | INT | NOT NULL | 投递失败次数 |
| next_retry_at | BIGINT | NOT NULL | 下次可投递毫秒（退避） |
| created_at | BIGINT | NOT NULL | 与扣款同事务写入 |

---

## 12. 接口契约清单

### 12.1 Dubbo Facade（ride-facade，全部返回 Result<T>）

| 域 | 方法 | 语义 / 主要错误 |
|---|---|---|
| passenger | create(PassengerCreateRequest) / get(id) / list() | 注册姓名必填 A0400；不存在 A0404 |
| driver | create(DriverCreateRequest) / get / list | 姓名、车牌必填 A0400 |
| driver | setOnline(id, online) | BUSY 拒绝 A0409 |
| driver | setStatus(id, status) | 非法三值外 A0400 |
| location | report(LocationReportRequest) | 坐标非法 A0400；upsert |
| location | nearby(NearbyRequest) / get(driverId) | 半径默认 5、limit 默认 10；升序 |
| pricing | quote(FareRequest) | 坐标非法 A0400；返回 FareDTO 明细 |
| order | call(CallRequest) | requestId 幂等；坐标/乘客校验 |
| order | accept(orderId, driverId) | 司机非 ONLINE A0409；CAS 败 A0409 |
| order | arrive / startTrip / completeTrip(orderId, driverId) | 状态不符或非本司机 A0409 |
| order | pay(orderId) | 确定性键 PAY-{orderId}；未 COMPLETED A0409；余额不足 A0409（支付域透传） |
| order | onPaySucceeded(orderId) | outbox 事件回调：COMPLETED 幂等完结，已 PAID 无操作 |
| order | cancel(orderId, reason) | 行程开始后 A0409 |
| order | get / list / listByPassenger / listByDriver / listByStatus | 不存在 A0404 |
| dispatch | dispatch(DispatchRequest) | 返回 DispatchResultDTO{orderId, offeredDrivers, radiusKm} |
| payment | topup(TopupRequest) | bizNo 幂等；金额越界 A0400 |
| payment | wallet(userId) | 懒建零余额钱包 |
| payment | pay(PayRequest) | requestId 幂等；余额不足 A0409；返回 PayResultDTO{amount, fee, driverIncome, passengerBalance, repeated} |
| payment | isPaid(orderId) | ledger 是否有该单 PAY_DEBIT（对账证据） |
| push | pushToPassenger / pushToDriver(id, event, data) | 离线返回成功但跳过投递 |
| push | onlineCount() | 当前 WS 连接数 |
| 各域 | HealthFacade.ping()（group=应用名） | 探针 |

### 12.2 REST（ride-gateway，成功 body 为 data，失败 `{error, code}`）

| Method | Path | 参数 | 说明 |
|---|---|---|---|
| POST | /api/passengers | body{name, phone?} | 注册乘客 |
| GET | /api/passengers | — | 列表 |
| GET | /api/passengers/{id} | — | 详情 |
| POST | /api/drivers | body{name, phone?, carNo, carModel?} | 注册司机 |
| GET | /api/drivers | — | 列表 |
| GET | /api/drivers/{id} | — | 详情 |
| POST | /api/drivers/{id}/online | ?online=true\|false | 上下线 |
| POST | /api/drivers/{id}/status | ?status=ONLINE\|BUSY\|OFFLINE | 状态直改（运维口） |
| POST | /api/locations | body{driverId, lat, lng} | 位置上报 |
| GET | /api/locations/nearby | ?lat&lng&radiusKm?&limit? | 就近在线司机 |
| GET | /api/locations/{driverId} | — | 单位置 |
| POST | /api/pricing/quote | body{originLat, originLng, destLat, destLng} | 计价明细 |
| POST | /api/wallets/topup | body{userId, amount, bizNo?} | 充值（分） |
| GET | /api/wallets/{userId} | — | 钱包余额 |
| POST | /api/orders | body CallRequest{passengerId, origin*, dest*, requestId?} | 叫车 |
| GET | /api/orders | ?passengerId\|driverId\|status（互斥优先序） | 列表过滤 |
| GET | /api/orders/{id} | — | 订单详情 |
| POST | /api/orders/{id}/accept | ?driverId | 抢单 |
| POST | /api/orders/{id}/arrive | ?driverId | 到达 |
| POST | /api/orders/{id}/start | ?driverId | 开始行程 |
| POST | /api/orders/{id}/complete | ?driverId | 结束行程 |
| POST | /api/orders/{id}/pay | — | 支付（订单维度幂等，无需 requestId） |
| POST | /api/orders/{id}/cancel | ?reason? | 取消 |
| GET | /api/push/online | — | WS 在线数 |
| GET | /api/health | — | 健康聚合 {registry:{ok}, services:[{service,port,ok}], allOk} |

### 12.3 WebSocket 协议

- 接入：`ws://host:9100/ws/{role}/{id}`，role∈{passenger, driver}；非法握手直接 close。
- 下行帧：`{event, data, timestamp}`（事件清单见 §9.3）。
- 上行帧：文本含 `PING` → 下行 `PONG`；其余忽略。
- 多端登录：同 role:id 后到覆盖，旧连接先收 `KICKED` 再被 close；新版客户端收到 KICKED 停止重连（防互踢活锁）。

---

## 13. 核心代码走读

### 13.1 叫车 call（OrderService.java:65-120）

校验乘客 ID 与起终点坐标（:66-70）→ requestId 缺省补 UUID（:71）→ 幂等快路径（:73-77）→ 乘客快照 + 计价（:79-80）→ 组装 OrderDO 直接 DISPATCHING、带默认半径与派单时间（:83-99）→ **事务内 insert 订单 + insert 幂等记录**（:102-106）→ DuplicateKey 回读胜者（:107-113）→ 提交后 dispatch 广播 + ORDER_CREATED 推送（:117-118）。副作用全部在事务外且 try/catch 降级：推送/派单失败不影响已落库订单，改派调度兜底。

### 13.2 抢单 accept（OrderService.java:122-142）

读订单 → 读司机并校验 ONLINE（:124-127）→ `orderMapper.accept` CAS（:128），0 行抛 A0409（:129-131）→ `safeSetDriverStatus(BUSY)`（:132，失败仅 warn）→ 双端推送司机快照（:135-140）。并发抢单的正确性完全由 SQL 的 `WHERE status='DISPATCHING'` 保证。

### 13.3 支付 pay（OrderService.java:183-206）

幂等键=`PAY-{orderId}`（:398-400）→ 快路径（:186-190）→ 已 PAID 直接返回（:191-193）→ 非 COMPLETED 抛 A0409（:194-196）→ 调支付域原子转账（:203，余额不足在此透传 A0409）→ `finalizePaid`（:218-236）：事务内 CAS pay **行数检查**（:222，0 行=已被完结，幂等重放）+ MERGE 幂等记录（:226）→ 司机回 ONLINE（:228）→ 双端 ORDER_PAID（:233-234）。**先转账后改状态**：转账失败订单保持 COMPLETED 可重试；转账成功后崩溃由 outbox 事件（onPaySucceeded，:208-216）秒级完结、对账调度（:295-311）兜底，确定性键保证任何路径不双扣（§7.4）。

### 13.4 支付域事务（PaymentService.java:102-128）

见 §7.1。`recordLedger` 在事务内重读钱包取 balance_after（:148-160），保证流水与余额同事务一致；**outbox 事件与扣款同事务写入**（:126），投递任务 OutboxDispatcher 每 1s 轮询、失败退避封顶 30s（OutboxDispatcher.java:27-42）。

### 13.5 改派 redispatch（OrderService.java:276-289）

`@Scheduled(fixedDelayString="${order.redispatch-interval-ms:5000}")`；仅扫 DISPATCHING；超时判定用落库的 last_dispatch_at 而非内存态——**重启不丢改派进度**；半径单调递增封顶 20km。

### 13.6 WS 握手注册（WebSocketFrameHandler.java:35-56 + NettyWebSocketServer.java:80-88）

UriCapture 先存 URI → 握手完成事件触发 parseIdentity（路径段 [2]=role、[3]=id，query 仅兜底 putIfAbsent，:87-113）→ 校验 → register（覆盖旧连接）→ CONNECTED。@Sharable 单例 handler 无每连接状态，状态全在 Channel Attribute 与注册表。

---

## 14. 异常与边界场景全集

### 14.1 已正确处理的场景

| # | 场景 | 行为 | 位置 |
|---|---|---|---|
| 1 | 叫车缺乘客/坐标越界 | A0400 | OrderService.java:66-70、392-396 |
| 2 | 重复 requestId 叫车（串行/并发） | 快路径回放 / DuplicateKey 回读，同单 | OrderService.java:73-77、107-113 |
| 3 | 并发多司机抢单 | 仅一人 CAS 成功，其余 A0409 | OrderMapper.java:43-46 |
| 4 | BUSY/OFFLINE 司机抢单 | A0409 | OrderService.java:125-127 |
| 5 | 非本司机 arrive/start/complete | CAS 0 行 → A0409 | OrderMapper.java:48-59 |
| 6 | 跳态操作（如未接单即到达） | A0409 | 同上 |
| 7 | 行程开始后取消 | A0409 | OrderMapper.java:65-67 |
| 8 | 已接单后取消 | 释放司机 ONLINE + 双端通知 | OrderService.java:243-250 |
| 9 | 未 COMPLETED 支付 | A0409 | OrderService.java:194-196 |
| 10 | 重复点击支付 | 确定性键快路径 / 已 PAID 快路径返回 | OrderService.java:186-193 |
| 11 | 并发支付（任意 requestId） | 支付域按 PAY-{orderId} 幂等回放 repeated=true，只扣一次 | PaymentService.java:89-95、132-140 |
| 12 | 余额不足 / 并发透支 | debit 守卫 0 行 → A0409，事务回滚（outbox 亦不落） | WalletMapper.java:22-24；PaymentService.java:104-106 |
| 13 | 金额 ≤0 或 >1e12 分 | A0400 | MoneyUtil.java:21-26 |
| 14 | 重复 bizNo 充值 | 回放原钱包结果 | PaymentService.java:51-55、68-70 |
| 15 | 派单 RPC 失败 | warn + 下轮超时改派重试 | OrderService.java:328-330 |
| 16 | 单个司机邀约推送失败 | 跳过该司机继续广播 | DispatchService.java:54-59 |
| 17 | 客户端离线推送 | 返回成功但跳过；前端轮询追平 | PushFacadeImpl.java:48-53 |
| 18 | 非法 WS role/id | 握手后 close | WebSocketFrameHandler.java:42-46 |
| 19 | 同用户多端登录 | 后到覆盖，旧连接收 KICKED 后 close；新版客户端停止重连 | ConnectionRegistry.java:29-43；common.js connectWS |
| 20 | 连接断开 | remove(key, channel) 精确注销 | ConnectionRegistry.java:44-51 |
| 21 | 服务中司机切上下线 | A0409 | DriverService.java:52-54 |
| 22 | 改派半径无限扩大 | min(max, cur+step) 封顶 | OrderService.java:284 |
| 23 | 停机时 ZK 先死导致 hook 阻塞 | stop.sh 依赖序停止规避 | stop.sh:5 |
| 24 | 下游域未启动时网关调用 | RpcException → 503 | GlobalExceptionHandler |
| 25 | 重启后改派/幂等进度丢失 | 全部落库（last_dispatch_at、idempotent_record） | schema.sql |
| 26 | WS 断连（服务重启/网络抖动/多端挤占） | 指数退避自动重连；CONNECTED 触发钱包+订单重同步 | common.js connectWS；driver.html/passenger.html onEvent |
| 27 | 断连期间乘客完成支付 | 入账不受影响；重连后 CONNECTED 重同步立即显示收入 | 实测 O11（§17） |
| 28 | 扣款提交后、订单完结前崩溃/超时 | outbox 同事务事件秒级投递完结；对账调度兜底；司机自动回 ONLINE | PaymentService.java:126；OutboxDispatcher.java:27-42；OrderService.java:295-311 |
| 29 | 支付/outbox/对账重复完结同一单 | CAS 行数检查 + MERGE 幂等记录，无副作用 | OrderService.java:221-227 |
| 30 | 同身份多页互踢活锁 | 顶号下发 KICKED，被踢方停止重连，连接收敛 | 实测 §17 v1.2.0 |

### 14.2 已知限制（演示取舍，含修复方向）

L1（不同 requestId 并发支付双扣）已于 v1.2.0 修复：确定性幂等键 + CAS 行数检查 + outbox/对账，见 §7.4 与 §17 实测。

| # | 限制 | 风险 | 修复方向 |
|---|---|---|---|
| L2 | accept 的司机 ONLINE 校验为 read-then-act（OrderService.java:125）；DriverMapper.casStatus（DriverMapper.java:33）已声明未启用 | 同司机并发抢两单可能都成功 | accept 内以 `casStatus(id,'ONLINE','BUSY')` 作为抢单前置 CAS |
| L3 | nearby 内存全扫描（LocationService.java:52） | 司机量大时 O(N) | Geohash 分桶 / Redis GEO / H2 空间索引 |
| L4 | 推送尽力而为、无离线消息队列 | 掉线期间事件不补发（重连 CONNECTED 重同步已追平状态与余额；事件日志本身不回放） | 离线信箱 + 重连补投（如需事件级审计回放） |
| L5 | 直线距离计价、完单重计价=预估 | 与真实路网/轨迹计费有偏差 | 接路径规划与轨迹里程 |
| L6 | wallet GET 带 ensure 建钱包副作用（PaymentService.java:75） | 查询产生写 | 查询只读、不存在返回零余额视图 |
| L7 | CREATED 态不落库（call 直接 DISPATCHING） | 状态机文档态与实态差一 | 拆分建单/派单两阶段时启用 |
| L8 | 无鉴权/签名 | 任意客户端可操作任意 ID | 网关 token + 司乘身份绑定 |
| L9 | H2 文件库单进程独占 | 同域不可多实例水平扩展 | 域库换 MySQL/PG 或按域分片 |

---

## 15. 网关与司乘端 H5

- 静态托管：`classpath:/static/`（ride-gateway application.yml:7-9），零安装零构建：index.html（门户+健康总览）、passenger.html、driver.html，共享 common.css/common.js。
- common.js：`api()` fetch 包装（失败抛 `{error}`）、`connectWS(role,id)` 拼 `ws://host:9100/ws/{role}/{id}`（断线指数退避自动重连）、MapView canvas 地图（内置北京六地标 PRESETS 与经纬度包围盒）、pushLog/toast。
- 乘客端：选乘客→WS 连接→地图点选起终点→预估价明细→叫车→时间线实时点亮（事件驱动刷新）→COMPLETED 后支付按钮可用→PAID 后禁用并刷新钱包。
- 司机端：选司机→上线→3s 位置上报→ORDER_OFFER 抢单卡片（含距乘客距离/预估价）→grab→到达/开始/完成按钮→4s 轮询补偿→钱包实时入账展示。
- 门户：3s 轮询 /api/health 与 /api/push/online，表格化展示 registry + 8 域探针与 WS 在线数。
- 健康聚合：HealthService.aggregate（HealthService.java:47-64）= registry HTTP 探针 + 8 域 Dubbo ping（1500ms 超时），allOk 供 run.sh 就绪判定。

---

## 16. 部署与运维

### 16.1 启停

```bash
./run.sh             # mvnw package -DskipTests + 启动 10 进程 + 就绪等待
./run.sh --no-build  # 跳过编译
./stop.sh            # 依赖序优雅停止，数据保留
```

run.sh 流程：JAVA_HOME 优先 corretto-18（release=17；默认 PATH 的 java 26 不可用于构建）→ mkdir run/logs/data → registry 先启并等 8092/health（60s）→ 8 域顺次启动（不等就绪）→ gateway 启动等 8080（90s）→ 轮询 /api/health 的 allOk（120×2s）→ 打印三个 URL。pid 落 run/*.pid，日志落 logs/<域>.log。

**stop.sh 依赖序（关键约束）**：`gateway → dispatch → order → payment → push → location → pricing → driver → passenger → registry`，逐进程 SIGTERM + 20s 等待 + SIGKILL 兜底。**registry 必须最后停**：业务进程停机时若 ZK 已失联，Dubbo shutdown hook 会阻塞在注册中心销毁上（曾致全员 15s 后 SIGKILL）；改序后实测 28.7s 全优雅退出、零 SIGKILL。

### 16.2 数据与日志

- 数据：`data/{passenger,driver,location,order,payment}.mv.db` + `data/zookeeper/`；schema 幂等、数据跨重启保留；**删除/重置需用户明确确认**。
- 日志：logback 控制台+文件，pattern 含 traceId（MDC）；业务关键节点 INFO（叫车/接单/流转/支付/派单/上下线/WS 上下线），副作用失败 WARN，未预期 ERROR。

### 16.3 故障处置速查

| 症状 | 处置 |
|---|---|
| 9100 被占 | 确认无残留 push 进程（daemon 线程修复后 SIGTERM 即释放）；勿直接删数据 |
| 某域 health ok=false | 看 logs/<域>.log 的 ZK 连接与 Dubbo 导出日志；必要时单域重启 |
| 全部 ok=false 且 registry ok=false | registry 未起或 2181 被占；先恢复 registry |
| 构建报 release 17 不支持 | JAVA_HOME 指向 corretto-18，勿用 PATH 默认 java 26 |

---

## 17. 验证记录（2026-11-16）

- **编译**：12 模块 reactor BUILD SUCCESS。
- **启动**：10 进程 allOk=true。
- **REST 冒烟全过**：注册 P1/D1 → 充值 10000 → 上线/上报 → nearby 0.28km → quote 5507（14.77km/30min）→ 叫车 O1 DISPATCHING + 同 requestId 幂等同单 → accept ACCEPTED + 重复 accept 409 → arrive/start/complete → pay PAID + 幂等 → 账务守恒 → 支付后司机 ONLINE。
- **浏览器实测（司机端）**：live ORDER_OFFER 卡片 → grab O6 → ACCEPTED/BUSY/卡片清空 → 流转 COMPLETED → 支付后页面 live ¥88.12/ONLINE（logs/ui-driver.png）。
- **浏览器实测（乘客端）**：UI 叫车 O7 → REST 司机流转 → 页面 live 推送 DRIVER_ARRIVED/TRIP_STARTED/TRIP_COMPLETED → 点击支付 → PAID、余额 ¥189.86→¥134.79、时间线全点亮（logs/ui-passenger.png）。
- **启停实测**：新 stop.sh 28.7s、零 SIGKILL；order 日志收尾 `HikariPool-1 - Shutdown completed.`、registry 收尾「内嵌 ZooKeeper 已停止」；端口全释放、data/ 完好。
- **断连补偿实测（v1.1.0）**：司机端在线时停 push 制造断连 → REST 完成 O11 全链路支付（push 缺失，页面事件日志无 ORDER_PAID）→ 钱包照入 1080 分（资金与推送解耦再证）→ 恢复 push → 页面自动重连收 CONNECTED → 余额无手动操作追平 ¥234.18；随后 O13 正常路径 ORDER_OFFER→抢单→ORDER_ACCEPTED→TRIP_COMPLETED→ORDER_PAID 全 live，余额 ¥278.24、司机回 ONLINE。另验证超时改派：O12 起点超首轮半径，扩半径后邀约迟到送达。
- **历史账务核对**：O1/O6/O7/O8/O9 每单 ledger 三件套齐全（PAY_DEBIT/PAY_CREDIT/PLATFORM_FEE），D1 余额与累计入账逐分一致——不存在「推送失败导致司机未入账」。
- **支付一致性混沌实测（v1.2.0）**：
  - O14 重复/并发支付：串行 2 次 + 并发 2 次共 4 次 pay，支付域仅 1 条「行程支付」流水，其余 3 次幂等命中；P1 恰扣 5507、D1 恰入 4406。
  - O15 崩溃窗口（对账兜底路径）：SIGSTOP 支付域 → pay 8s 超时 503、订单保持 COMPLETED → SIGCONT 后冻结事务提交（钱已动）→ 对账调度 5s 内自动完结（22:37:22→22:37:27），订单 PAID、司机回 ONLINE、重试幂等不双扣。
  - O16 正常支付：outbox 秒级投递成功（retries=0），订单已 PAID 故事件回调幂等无操作。
  - O17 崩溃窗口（outbox 主路径）：同法冻结 → 恢复后 **1s 自愈**（22:48:22.790 扣款提交 → 22:48:23.794 outbox 事件完结 → 22:48:23.807 置 SENT），对账未触发；P1=9372、D1=40504 逐分正确。
  - 余额不足回滚：O17 首次冻结尝试时 P1 余额 2765<3393，事务整体回滚——无流水、无 outbox、对账正确跳过，充值后重试成功。
- **KICKED 防活锁实测（v1.2.0）**：应用内新版页面与 Chrome 旧版页签同登 D1 → 互踢一轮后新版收 KICKED 停止重连并提示「同一身份在其他页面登录」，连接收敛为单一稳定（此前旧版对旧版每 2s 互踢不止）。
- **当前账面**：P1=9372 分、D1=40504 分、SYS_PLATFORM=10124 分；O1/O6/O7/O8/O9/O11/O13/O14/O15/O16/O17 PAID，O2–O5/O10/O12 CANCELLED。

---

## 18. 演进方向

1. **资金加固**：落地 L2（司机抢单 CAS）；对账扩展为全量账面核对（ledger 回放 vs wallet 余额，现有支付对账已覆盖订单维度）。
2. **派单升级**：抢单→指派双模（评分=距离+服务分+拒单率），邀约超时回收。
3. **LBS 升级**：Geohash/Redis GEO 索引、轨迹上报与路网计价（替换 L3/L5）。
4. **推送升级**：离线信箱 + 重连补投 + 多端广播（替换 L4，注册表改 key→Set<Channel>）。
5. **接入安全**：网关鉴权、司乘身份绑定、OpenAPI 签名（复用 payment-pro 方案）。
6. **存储扩展**：域库迁移 MySQL/PG 以支持多实例（L9），订单库按 passenger_id 分片预案。
