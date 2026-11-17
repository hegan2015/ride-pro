# ride-pro 打车系统设计文档

版本 v1.5.0 ｜ 2026-11-17 ｜ 状态：已实现，验证记录见 §17.0 与 §19.5

v1.5.0 变更（payment-pro 双账本集成，详见 §19）：
- **HTTPS OpenAPI 镜像**：本地资金操作同事务写 mirror_outbox，单线程顺序镜像到 payment-pro（8443 自签证书 + HMAC 签名）；本地账本权威不变，payment-pro 宕机不影响本地资金流，恢复自动追平。
- **可选集成**：未配置 config/payment-pro.properties 时完全 standalone；integrate-payment.sh 一键入驻（商户注册/开户/证书/配置）。
- **增量对账**：开户基线快照 + 已投递镜像净额（平台扣 pp 手续费），兼容接入前历史余额；镜像 DEAD/积压告警 + 管理端重放/关闭。
- **验证**：verify-integration.py 15/15（含宕机混沌）；单测 34/34；payment-pro 侧 smoke 79/79、verify-pro 22/22 回归通过。

v1.4.0 变更（资金安全 + 体验闭环，全部服务端兜底）：
- **资金安全**：退款前置校验（无 PAY_DEBIT 流水不可退）+ 支付域累计可退守卫（sumRefundCredit+本次≤实付）；topup/withdraw/pay/transfer/refund 幂等键**必填**（缺省拒绝，杜绝随机 bizNo 重试双入账）；单笔/日累计/频次风控限额（payment.risk.*）。
- **部分退款**：`REFUND-{orderId}-{seq}` 序号幂等键支持多次退款；手续费按退款比例退；订单域以支付域 `refundSummary`（REFUND_CREDIT 流水聚合）为权威同步 refunded_amount/refund_count，退满置 REFUNDED、部分退保持 PAID；`refund_outbox`（request_id 主键）与退款动账同事务，投递 `onRefundSucceeded`，对账兜底。
- **提现状态机**：受理冻结（frozen_amount，可用余额=balance−frozen）→ ACCEPTED→PROCESSING→SUCCESS/FAILED；成功=扣款+解冻+WITHDRAW 流水同事务 CAS，失败=仅解冻；模拟渠道失败率按 bizNo 哈希确定性判定（withdraw-fail-bps，重试不翻转）；WithdrawProcessor 1s 调度，崩溃重驱动幂等。
- **催付 + 免密自动扣款**：乘客 auto_pay 授权（passenger 表增量列）；订单域对账调度顺带扫描 COMPLETED——超 60s 未付推送 PAYMENT_REMINDER（reminded_at 防重），授权乘客超 30s 服务端自动 pay，失败转催付并按 60s 间隔重试。
- **审计/告警自动化**：PaymentMonitor 30s 账面不平/DEAD/积压超龄 log.error；outbox 重试耗尽（10 次）置 DEAD，管理端 DEAD 列表 + 人工重放（pay_outbox/refund_outbox 双表）。
- **前端闭环**：乘客端支付确认单（实际计价明细）+ 网络错误「支付结果确认中」短轮询 + 余额不足差额一键充值续付 + 充值防重（幂等键意图内复用）+ 免密开关 + 退款/催付提示；司机端冻结/可用余额 + 提现防重与「已受理」语义 + 提现记录状态徽章 3s 轮询 + 到账金额用服务端 driverIncome；管理端一致性页 5s 自刷新 + 阈值告警（积压>10s 或 DEAD 才红）+ DEAD 重放按钮 + 退款弹窗（金额默认剩余可退/已退展示/原因）。
- **测试**：单测扩至 34（新增部分退款累计守卫/提现冻结成功失败守恒/风控限额/幂等键必填/无支付退款拒绝）；chaos-test.sh 扩至 12 节（新增提现状态机/部分退款/风控/免密自动扣款/退款窗口杀 order 的 refund outbox 自愈）。

v1.3.0 变更：
- **正确性加固**：抢单改为「司机 CAS(ONLINE→BUSY) 前置 + 订单 CAS + 失败回滚 + 回收调度」（根治 L2）；新增 EXPIRED 终态（超轮次无人接单）；钱包查询改只读（根治 L6）；nearby 包围盒预筛；WS 服务端 IdleStateHandler(90s) + 客户端 30s PING 心跳。
- **业务功能**：司机取消回派单、乘客取消费（ARRIVED/超免费窗口）、邀约 TTL + 拒单 + 评分指派双模（grab/assign）、司乘互评、提现、分车型计价 + 高峰/夜间系数 + 远途费 + 最低消费 + 规则版本快照、轨迹上报与按实际里程计价、管理端退款/强制取消。
- **管理端**：admin.html 六模块（仪表盘/资金流水/订单管理/钱包总览/系统日志/一致性监控）+ X-Admin-Token 鉴权 + 限流 + 播种演示数据 + HMAC 行程分享页。
- **前端重构**：司乘端页签化布局 + 订单记录查询（状态/时间筛选/分页/详情抽屉）+ 通用组件库（toast/抽屉/弹窗/时间线/分页/地图轨迹）。
- **可观测**：全链路 traceId 落日志 pattern（HTTP→RPC 串联检索）；推送审计环（最近 500 事件，送达/离线跳过）；账面守恒核对（wallet 余额 vs ledger 回放求和）。
- **测试**：MoneyUtil/计价/订单状态机 CAS/支付一致性 30 单测（H2 内存 + 真实事务）；run/chaos-test.sh 32 项 REST 级 + 杀 order 进程 outbox/对账兜底混沌。

v1.2.0 变更：服务端支付一致性加固——确定性幂等键 PAY-{orderId} + CAS 行数检查 + outbox 异步投递 + 对账调度兜底（§7.4，原 L1 双扣缺陷已修复）；WS 顶号下发 KICKED 防互踢活锁（§9.2）。
v1.1.0 变更：修复推送断连无补偿缺陷（connectWS 自动重连 + CONNECTED 重同步，见 §9.4）。

---

## 1. 概述

### 1.1 目标

参考滴滴的领域拆分，用 Java + Spring Boot + Maven + Dubbo + Netty + H2 实现一个**可直接运行**的分布式打车系统，覆盖「叫车 → 派单 → 抢单/指派 → 行程 → 轨迹计价 → 支付 → 评价/分享」完整闭环，并提供乘客端、司机端、管理端、行程分享、门户五个零安装 H5 页面。

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
| 司机 | ride-driver | 司机系统 | 注册（含车辆/车型）、上下线、服务状态 CAS、服务分/拒单计数 | driver.mv.db |
| 位置 LBS | ride-location | 位置服务 | 司机位置上报、就近在线司机查询（包围盒）、行程轨迹落库/回放 | location.mv.db |
| 计价 | ride-pricing | 计价引擎 | 分车型费率+高峰/夜间+远途+最低消费、规则版本、轨迹计价（无状态） | 无 |
| 订单 | ride-order | 订单/行程核心 | 状态机编排、幂等、超时改派/过期、取消费、评价、退款/强取消、对账 | order.mv.db |
| 派单调度 | ride-dispatch | 派单引擎 | grab 广播抢单 / assign 评分指派、邀约 TTL、拒单顺延（无状态） | 无 |
| 支付钱包 | ride-payment | 支付/钱包 | 充值/提现、原子扣款/入账/抽成、转账/退款、流水、账面核对、outbox | payment.mv.db |
| 消息推送 | ride-push | 消息推送 | Netty WebSocket 长连接、事件下发、空闲检测、推送审计 | 无 |
| 网关 | ride-gateway | 接入层 | REST 聚合、五页面 H5 托管、健康聚合、管理端鉴权/限流、分享签名 | 无 |
| 注册中心 | ride-registry | 基础设施 | 内嵌 ZooKeeper + HTTP 探针 | data/zookeeper |

公共模块：`ride-common`（Result/错误码/异常/工具/TraceFilter）、`ride-facade`（全部 Dubbo 契约 + DTO + HealthFacade 实现）。共 **12 个 Maven 模块、10 个运行进程**。

### 2.2 端口表

| 进程 | HTTP | Dubbo | 其他 |
|---|---|---|---|
| ride-registry | 8093（/health 探针） | — | ZK 2182 |
| ride-passenger | — | 20901 | |
| ride-driver | — | 20902 | |
| ride-location | — | 20903 | |
| ride-pricing | — | 20904 | |
| ride-order | — | 20905 | |
| ride-dispatch | — | 20906 | |
| ride-payment | — | 20907 | |
| ride-push | — | 20908 | WebSocket 9101 |
| ride-gateway | 8090（REST + H5） | 20900 | |

> **端口隔离（2026-11-17）**：ride-pro 整体迁至 8090/2182/8093/2090x/9101 段，与 payment-pro（8080/2181/8092/2088x）完全错开，**两套系统可同时运行**。历史版本（≤v1.4.0 验证记录）中的 8080/9100/2181/2088x 均指迁移前端口。

环境变量可覆盖：`ZK_PORT`、`ZK_HTTP_PORT`、`DATA_DIR`、`GATEWAY_PORT`、`GATEWAY_DUBBO_PORT`、`ORDER_DUBBO_PORT`、`PUSH_DUBBO_PORT`、`PUSH_WS_PORT`（见各模块 application.yml）。

### 2.3 调用拓扑

```
 乘客端 H5 ──HTTP──┐                    ┌──WS 9101── 乘客端 H5
                  ├─ ride-gateway(8090) │
 司机端 H5 ──HTTP──┘        │ Dubbo      └──WS 9101── 司机端 H5
                           ▼
        ┌────────────── ride-order（编排中枢）──────────────┐
        ▼            ▼           ▼           ▼            ▼
 ride-passenger  ride-pricing  ride-driver  ride-dispatch  ride-payment
                                          │        │
                                          ▼        ▼
                                    ride-location  ride-push ──WS──► 司乘端
                           所有进程 ──注册/发现──► ride-registry(内嵌 ZK 2182)
```

依赖方向单向：gateway → 各域；order → passenger/pricing/driver/dispatch/payment/push；dispatch → location/push；location → driver。**无环依赖**；派单域不反查订单详情，由订单域在 `DispatchRequest` 中携带快照（ride-facade/.../dispatch/DispatchRequest.java:10）。

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
CREATED ─► DISPATCHING ─► ACCEPTED ─► ARRIVED ─► IN_PROGRESS ─► COMPLETED ─► PAID ─► REFUNDED
   └──────────┴────────────┴───────────┘                          │
                    └──► CANCELLED（行程开始前任意态，可含取消费）    └─（管理端退款，仅 PAID）
              DISPATCHING ─► EXPIRED（超 maxDispatchRounds 无人接单）
              ACCEPTED/ARRIVED ─► DISPATCHING（司机取消，排除该司机重新派单）
```

**实现说明**：叫车在单个本地事务内「建单 + 落幂等记录」并直接置 `DISPATCHING`，创建与派单提交原子化，因此当前流程不产生 CREATED 落库记录；CREATED 保留在状态机定义与取消兼容集合中，作为语义初态与未来「先建单后派单」拆分的兼容位。

### 4.2 流转表（全部 CAS，守卫写在 SQL WHERE 中）

| 动作 | From → To | SQL 守卫 | 触发方 | 提交后副作用 |
|---|---|---|---|---|
| call | （新建）→ DISPATCHING | insert + 幂等记录同事务 | 乘客 | dispatch 广播 + ORDER_CREATED；fare_json 快照落单 |
| accept | DISPATCHING → ACCEPTED | `status='DISPATCHING'` + 车型匹配 | 司机抢单 | **先司机 CAS ONLINE→BUSY**，订单 CAS 败则回滚司机；DRIVER_ACCEPTED/ORDER_ACCEPTED |
| arrive | ACCEPTED → ARRIVED | `status='ACCEPTED' AND driver_id=#{driverId}` | 司机 | DRIVER_ARRIVED |
| startTrip | ARRIVED → IN_PROGRESS | `status='ARRIVED' AND driver_id=…` | 司机 | TRIP_STARTED |
| completeTrip | IN_PROGRESS → COMPLETED | `status='IN_PROGRESS' AND driver_id=…` | 司机 | **按轨迹里程重计价**写 actual_fee/actual_distance_km/actual_duration_min/**actual_fare_json**；TRIP_COMPLETED 双端 |
| pay | COMPLETED → PAID | `status='COMPLETED'` | 乘客 | 支付域转账；司机→ONLINE；ORDER_PAID 双端 |
| cancel | CREATED/DISPATCHING/ACCEPTED/ARRIVED → CANCELLED | `status IN (…)` | 乘客 | 释放司机→ONLINE；ARRIVED 或 ACCEPTED 超免费窗口收取消费（CANCEL-{orderId} 转账，失败则置 0 不阻断取消）；ORDER_CANCELLED 双端 |
| driverCancel | ACCEPTED/ARRIVED → DISPATCHING | `status IN (…) AND driver_id=…` | 司机 | 清司机/接单时刻、追加 declined、司机→ONLINE、立即重新派单（排除该司机）；DRIVER_CANCELLED |
| declineOffer | DISPATCHING（不变） | `status='DISPATCHING'` | 司机拒单 | 追加 declined + recordDecline；assign 模式立即顺延下一位 |
| expire | DISPATCHING → EXPIRED | `status='DISPATCHING'` | 改派调度 | 超 maxDispatchRounds 终态；ORDER_EXPIRED |
| refund | PAID → REFUNDED | `status='PAID'` | 管理端 | **先支付域退款**（REFUND-{orderId} 幂等，司机/平台反向划转、乘客全额）再 CAS；ORDER_REFUNDED 双端；崩溃由对账兜底 |
| forceCancel | CREATED/DISPATCHING/ACCEPTED/ARRIVED/IN_PROGRESS → CANCELLED | `status IN (…)`（不含 COMPLETED/终态） | 管理端 | 释放司机→ONLINE；ORDER_CANCELLED 双端（forced=true） |

CAS 返回 0 行即抛 `A0409`，把「并发抢单仅一人成功」「非本司机不可操作」「状态不符不可流转」全部下沉到数据库行锁，服务层无锁。

### 4.3 司机状态机

`OFFLINE ⇄ ONLINE → BUSY → ONLINE`（支付/取消/退款释放后回 ONLINE）。

- 注册初始 OFFLINE；`setOnline` BUSY 时拒绝切换；`setStatus` 仅接受 ONLINE/BUSY/OFFLINE。
- **抢单原子性（v1.3.0 根治 L2）**：accept 先 `driverFacade.casStatus(id,'ONLINE','BUSY')`（SQL `WHERE status='ONLINE'`），成功再订单 CAS；订单 CAS 失败则 `casStatus(id,'BUSY','ONLINE')` 回滚司机。同司机并发抢两单仅一单成功。
- **崩溃回收**：`reclaimBusyDrivers` 每 10s 扫 BUSY 且 `updated_at` 超宽限期（30s）、且无活跃订单的司机，CAS 释放回 ONLINE——覆盖「司机 CAS 成功后、订单 CAS 前进程崩溃」的悬挂 BUSY，服务端兜底不依赖客户端。
- 服务分 `service_score`（默认 5.0）由乘客评价均值更新；`decline_count` 拒单计数影响指派评分。

---

## 5. 派单引擎（grab 抢单 / assign 指派双模）

### 5.1 流程

1. 订单域 `dispatch()` 先 `markDispatched` 刷新本轮半径/轮次/时间（仅 DISPATCHING 生效），再携快照调派单域（DispatchRequest 含 mode/offerExpireAt/serviceType/destLat/destLng/excludeDriverIds）。
2. 派单域 `locationFacade.nearby(起点, radius, limit)` 拉取就近**在线**司机，排除 `excludeDriverIds`（已拒单 ∪ assign 模式已邀约）。
3. **grab 模式**：向全部候选广播 `ORDER_OFFER` 邀约帧，司机端渲染抢单卡片，点击 accept 由订单 CAS 保证仅一人成功。
   **assign 模式**：按评分排序取 Top1 逐个指派，邀约带 TTL（offerExpireAt=now+15s）；超时/拒单则顺延下一位。
4. 评分公式（DispatchService.score）：`0.6×距离分 + 0.3×服务分 + 0.1×拒单分`，距离分=1−dist/radius，服务分=serviceScore/5，拒单分=1/(1+declineCount)。
5. 无人接单时订单域 `@Scheduled` 改派兜底（见 5.3）。

### 5.2 参数（order 前缀，OrderProperties.java / ride-order application.yml）

| 参数 | 默认 | 含义 |
|---|---|---|
| default-radius-km | 5.0 | 首轮广播半径 |
| max-radius-km | 20.0 | 半径上限 |
| radius-step-km | 3.0 | 每轮扩半径步长（grab 模式） |
| dispatch-timeout-ms | 15000 | grab 本轮无接单判定超时 |
| redispatch-interval-ms | 5000 | 改派调度扫描间隔 |
| dispatch-mode | grab | grab=广播抢单；assign=评分 Top1 逐个指派 |
| offer-ttl-ms | 15000 | assign 邀约有效期 / grab 超时判定 |
| max-dispatch-rounds | 6 | 超此轮次无人接单 → EXPIRED |
| cancel-free-window-ms | 120000 | ACCEPTED 后免费取消窗口 |
| cancel-fee-amount | 300 | 取消费（分，全额归司机） |
| driver-reclaim-grace-ms | 30000 | BUSY 司机回收宽限期 |
| driver-reclaim-interval-ms | 10000 | 回收调度扫描间隔 |
| dispatch.offer-limit | 20 | 单轮最多邀约司机数 |

### 5.3 超时改派与过期

`redispatch()` 每 5s 扫 DISPATCHING 订单：
- `dispatch_round >= maxDispatchRounds` → `expire` 置 EXPIRED + ORDER_EXPIRED（终态，不再改派）。
- 否则超时判定（assign 用 offerTtlMs、grab 用 dispatchTimeoutMs）到期则重新派单；grab 模式半径 `min(max, current+step)` 递增（5→8→11→14→17→20 封顶），assign 模式顺延下一位候选。
- 派单 RPC 失败仅 warn，因 `markDispatched` 已刷新时间，下一轮自动重试——**失败不丢单**；轮次/半径/时间全部落库，**重启不丢改派进度**。

### 5.4 ORDER_OFFER 帧数据

`orderId, passengerName, originName, originLat, originLng, destName, destLat, destLng, estimateFee, distanceKm, distanceToPassenger, serviceType, serviceScore, expireAt`，司机端据此渲染「距乘客 x km / 预估 ¥y / 我的收入 ¥y×0.8」抢单卡片与倒计时进度条。

---

## 6. 计价引擎

无状态域，**分车型费率 + 高峰/夜间系数 + 远途费 + 最低消费**（PricingService.quote）。

### 6.1 公式

```
distanceKm  = distanceKmOverride ?? haversine(起点, 终点)     // 完单用轨迹实测里程覆盖
durationMin = durationMinOverride ?? max(1, round(distanceKm/30*60))
peakRate    = 命中时段系数取最大，否则 1.0
billableKm  = max(0, distanceKm - baseKm)
distanceFee = round(billableKm * perKmFee * peakRate)
timeFee     = round(durationMin * perMinFee * peakRate)
longFee     = round(max(0, distanceKm - longDistanceThresholdKm) * longDistancePerKmFee)
totalFee    = max(minFare, baseFee + distanceFee + timeFee + longFee)
```

### 6.2 车型费率（pricing 前缀，PricingProperties.java）

| serviceType | 名称 | baseFee(分) | baseKm | perKmFee(分/km) | perMinFee(分/min) | minFare(分) |
|---|---|---|---|---|---|---|
| EXPRESS | 快车 | 1300 | 3.0 | 230 | 50 | 1300 |
| PREMIER | 专车 | 2000 | 3.0 | 320 | 80 | 2000 |
| TAXI | 出租车 | 1400 | 3.0 | 250 | 60 | 1400 |

高峰/夜间时段（peakWindows，命中取最大系数）：早高峰 7–9 时 ×1.3、晚高峰 17–19 时 ×1.3、夜间 22–6 时 ×1.2（跨零点用 `start>end` 分支判定）。远途：超过 `longDistanceThresholdKm=15.0` 的部分每 km 加 `longDistancePerKmFee=100` 分。`normalizeService` 空值默认 EXPRESS，未知车型抛 A0400。

### 6.3 规则版本与快照

`version()` = 全部费率 + 时段 + 远途参数拼接串的 `hashCode` 十六进制，前缀 `v1-`（当前配置为 `v1-b78ee420`）。**配置一改版本即变**，下单时 FareDTO 整体 JSON 落订单 `fare_json`，完单沿用同版本语义，事后可追溯当时计价依据。`GET /api/pricing/rules` 返回完整规则表供前端展示。

### 6.4 预估 vs 实际（轨迹计价）

预估用起终点 haversine；`completeTrip` 拉取 `locationFacade.track(orderId)`，**≥2 个轨迹点时按相邻点 haversine 累加得实测里程、首末点时间差得实测时长**，以 `distanceKmOverride/durationMinOverride` 回灌 quote 重计价写 `actual_fee/actual_distance_km/actual_duration_min`（OrderService.completeTrip）；轨迹不足则回退起终点估算。故实际价随真实行驶里程浮动，不再恒等于预估价。

---

## 7. 支付与钱包

### 7.1 原子转账（单库本地事务）

`PaymentService.pay`（PaymentService.java:137-190）在同一 H2 事务内顺序完成：

1. `ensure(乘客)` 建零余额钱包（若不存在）→ `debit(乘客, amount)`，**SQL 带 `balance>=amount` 守卫**（WalletMapper.debit），0 行即抛 A0409「乘客余额不足」，杜绝并发透支；
2. 流水 `PAY_DEBIT −amount`；
3. `ensure(司机)` + `credit(司机, amount−fee)`，流水 `PAY_CREDIT`；
4. `ensure(SYS_PLATFORM)` + `credit(平台, fee)`，流水 `PLATFORM_FEE`；
5. 读乘客余额组装 PayResultDTO，落幂等记录（requestId 主键）；
6. **写 outbox 事件 `pay_outbox`（NEW）**，与扣款同事务提交（:181），供异步投递通知订单域完结（§7.4）。

任一步失败整体回滚，**不存在「扣了乘客没入司机」的中间态**。fee 与司机入账由 `MoneyUtil.fee(amount, feeBps)` 决定：feeBps=2000（20%），platformUserId=SYS_PLATFORM（PaymentProperties）。

**守恒验证**：以 5507 分支付 → fee=(5507×2000+5000)/10000=1101、司机入 4406、乘客 10000−5507=4493；4493+4406+1101=10000（初始充值），分毫不差。

### 7.2 流水审计

`ledger_entry` 每笔充值/扣款/入账留痕，`balance_after` 为该笔后钱包余额（PaymentService.recordLedger:394-406），可按 user_id / order_id 索引回放对账。**PAY_DEBIT 流水与扣款同事务提交，流水存在即该单钱已划转**——这是对账判定依据（`isPaid`，PaymentService.java:316-318）。

### 7.3 充值 / 提现 / 只读钱包

- `topup` 事务化：ensure + credit + TOPUP 流水 + 幂等记录（bizNo 键**必填**），重复 bizNo 返回原结果；单笔限额 risk.topupMaxPerTxn（PaymentService.topup）。
- `withdraw` **冻结状态机**（v1.4.0）：受理事务 = 风控校验（单笔/当日累计 sumActiveSince/当日频次 countSince，受理即占额度防并发绕过）→ ensure + **freeze 带 `balance-frozen_amount>=amount` 守卫** + withdraw_order(ACCEPTED) + 幂等记录（bizNo 键必填）。`WithdrawProcessor` 1s 调度：ACCEPTED→PROCESSING CAS，停留超 withdraw-process-delay-ms（默认 2s，模拟渠道时长）后 `settleWithdraw`——失败率 `Math.floorMod(bizNo.hashCode(),10000) < withdrawFailBps`（确定性，重试不翻转）；**成功** = 单据 CAS(PROCESSING→SUCCESS) + debitAndUnfreeze + WITHDRAW 流水 + MERGE 幂等（终态钱包视图）同事务；**失败** = 单据 CAS→FAILED + 仅 unfreeze + MERGE 幂等。冻结/解冻只动 frozen_amount 不动 balance，账面守恒不变式（balance=Σledger）全程成立；崩溃后 PROCESSING 单据由 selectProcessable 重驱动，CAS 保证只结算一次。
- `wallet(userId)` **只读**：查无钱包返回零余额 DTO，**不再 ensure 建钱包**（原 L6 GET 副作用已根治）；DTO 含 frozenAmount/availableBalance。`wallets()` 列全部钱包供管理端；`withdrawOrders(userId)` 提现单列表（司乘端状态徽章）。

### 7.4 跨域支付一致性：确定性幂等键 + outbox + 对账

支付域转账与订单域完结分属两个 H2 本地事务，中间存在崩溃窗口（扣款已提交、订单 CAS 未执行时进程重启/超时）。三道防线全部在服务端，**不依赖客户端重试或前端补偿**：

1. **确定性幂等键**：订单 pay 以 `PAY-{orderId}` 作为两域 requestId（OrderService.payKey:779-781），一单一支付——任意重试/并发（无论客户端带什么 requestId）都在支付域唯一键上回放首次结果，杜绝双扣（原 L1 由此根治）。
2. **outbox 异步投递（主路径，秒级）**：`pay_outbox` 事件与扣款同事务落库（PaymentService.java:181）；`OutboxDispatcher.deliver` 每 1s 轮询 NEW 事件（OutboxDispatcher.java:26-42），调 `OrderFacade.onPaySucceeded` 幂等完结（已 PAID 则无操作），成功置 SENT，失败指数退避（1s×2 封顶 30s）。事件持久化，进程重启后继续投递。
3. **对账调度（兜底）**：订单域 `reconcilePaid` 每 5s 双路扫描——COMPLETED 超宽限期（10s）经 `isPaid` 查 PAY_DEBIT 已扣款则 `finalizePaid`；PAID 超宽限期经 **`refundSummary`（REFUND_CREDIT 流水聚合，权威累计值）** 比对 refunded_amount/refund_count，落后则 `syncRefundState` 同步（退满自动 REFUNDED）。扫描顺带催付/免密自动扣款（§7.6）。覆盖 outbox 或 CAS 长期失败等极端场景。

`finalizePaid`/`finalizeRefund` 幂等：事务内 CAS **行数检查** + MERGE 幂等记录（upsert）同事务；**shouldPush 模式**——仅当 CAS 成功或首次落幂等记录才推送，outbox/对账重放不重复打扰客户端（客户端周期同步兜底展示）。ORDER_PAID 帧携带支付域权威 `driverIncome/fee`（payResult 读 PAY-{orderId} 幂等快照）。支付路径、outbox 事件、对账三条路径可重复执行。

崩溃时机语义：**扣款提交前**崩溃 → H2 回滚，钱未动，订单仍 COMPLETED，重试正常支付；**扣款提交后**崩溃 → outbox 事件已在库，恢复后 ~1s 自愈（实测 §17 O17），对账最迟 15s 兜底。

### 7.5 通用转账 / 退款 / 审计

- **transfer**（取消费等通用划转）：from `debit` 余额守卫 + to `credit` + 双流水 `{bizType}_DEBIT/{bizType}_CREDIT` + 幂等（requestId 键，biz_type=TRANSFER），同事务（PaymentService.transfer）。取消费由订单域以 `CANCEL_FEE` bizType、`CANCEL-{orderId}` 键调用，全额归司机（无抽成）。
- **refund**（管理端退款，v1.4.0 支持部分/多次）：**前置校验**——订单无 PAY_DEBIT 流水抛 A0409「订单无支付流水，不可退款」；支付域累计守卫 `sumRefundCredit + 本次 ≤ 实付`，超退抛 A0409。手续费按退款比例退 `round(原PLATFORM_FEE × amount/paidAmount)`（可显式 fee 覆盖）。司机按**可用余额**（balance−frozen，提现冻结中的钱不可追回）追回 `min(amount−fee, max(0,driverAvailable))`，**缺口平台兜底**，平台不足整体回滚。流水 `REFUND_DEBIT/REFUND_FEE_DEBIT/REFUND_CREDIT`，幂等键 `REFUND-{orderId}-{seq}`（seq=订单 refundCount+1，同键不同金额抛 A0409）；**refund_outbox 事件与动账同事务**（request_id 主键，支持一单多笔），OutboxDispatcher 投递 `OrderFacade.onRefundSucceeded`。订单域 `syncRefundState` 以 refundSummary 为权威：syncRefund CAS（只前进不后退）→ 退满 `finalizeRefund`（REFUND-FINAL-{orderId} 幂等记录 + shouldPush），部分退推送 partial=true 帧（含 refundedTotal/remaining）。
- **audit**（账面核对）：逐钱包比对 `balance` 与 `SUM(ledger.amount)`，输出差额。**PaymentMonitor 每 30s 自动审计**：不平/DEAD>0/最老积压超 60s 均 log.error（不依赖人看看板）。`stats` 按 biz_type 聚合；`outboxStats` 双表（pay_outbox+refund_outbox）NEW/SENT/**DEAD** 计数、最大重试、最老积压年龄；`deadOutbox/replayOutbox` 供管理端人工重放（DEAD→NEW 重置计数，幂等投递不重复动账）。

### 7.6 催付与免密自动扣款（v1.4.0）

乘客 `auto_pay` 授权落 passenger 表（增量列，默认 false），REST `POST /api/passengers/{id}/auto-pay?enabled=`。订单域 `reconcilePaid` 扫描 COMPLETED 时顺带 `remindOrAutoPay`：
- 未授权：完单超 remind-after-ms（60s）未付 → 推送 `PAYMENT_REMINDER`（含 actualFee）+ markReminded CAS 防重，仅提醒一次。
- 已授权：完单超 auto-pay-delay-ms（30s）→ 服务端直接 `pay(orderId)`（走 PAY-{orderId} 幂等主路径），成功即完结；失败（余额不足等）转催付并按 auto-pay-retry-ms（60s）间隔重试（reminded_at 兼作重试节流）。
- 授权查询失败降级为未授权（不影响对账主流程）。全部服务端调度，客户端离线照样扣款/提醒。

---

## 8. 幂等设计

各写入口共用同一模式：**读幂等表快路径 → 事务内「业务写 + 幂等记录写」→ 捕 DuplicateKeyException 回读原结果**。

| 入口 | 幂等键 | biz_type | 落表 |
|---|---|---|---|
| 叫车 call | requestId（缺省生成 UUID） | CALL | order 库 idempotent_record |
| 订单支付 pay | **PAY-{orderId}**（确定性，与客户端 requestId 无关） | PAY | order 库 idempotent_record（MERGE upsert） |
| 取消费划转 | **CANCEL-{orderId}** | TRANSFER | payment 库 idempotent_record |
| 管理端退款 | **REFUND-{orderId}-{seq}**（seq=refundCount+1，支持部分/多次） | REFUND | payment 库 idempotent_record |
| 退款终态完结 | **REFUND-FINAL-{orderId}** | REFUND | order 库 idempotent_record（MERGE upsert） |
| 钱包充值 topup | bizNo **必填**（种子用 SEED-TOPUP-{phone}；前端意图内复用） | TOPUP | payment 库 idempotent_record |
| 钱包提现 withdraw | bizNo **必填**（受理/终态 MERGE 更新钱包视图） | WITHDRAW | payment 库 idempotent_record + withdraw_order 单据 |
| 支付域 pay | requestId（订单域传入 PAY-{orderId}） | PAY | payment 库 idempotent_record |
| 支付域 transfer | requestId（取消费传 CANCEL-{orderId}） | TRANSFER | payment 库 idempotent_record |

- 快路径：命中且 result_json 非空直接反序列化返回（OrderService.call/pay；PaymentService.pay/topup/withdraw/transfer/refund）。
- 并发双写：唯一主键使第二个事务抛 DuplicateKeyException，回读胜者结果（OrderService.call；PaymentService.repeatedPay/repeatedWallet）；回读仍空（胜者未提交完）抛 A0409「处理中」，客户端可重试。
- 支付域重复命中额外置 `repeated=true`（PaymentService.pay/transfer/refund），供调用方区分首付与回放。
- 订单 pay 另有状态快路径：已 PAID 直接返回当前订单（OrderService.pay:254-256）。
- 支付完结记录用 MERGE 写入（IdempotentMapper.merge），支付/outbox/对账三路重复完结均安全（§7.4）。

---

## 9. Netty 推送域

### 9.1 服务器与 pipeline

`NettyWebSocketServer` 随 Spring 容器启停（InitializingBean/DisposableBean），pipeline（NettyWebSocketServer.java:55-61）：

```
HttpServerCodec → HttpObjectAggregator(65536) → UriCaptureHandler
→ WebSocketServerProtocolHandler("/ws", null, true, 65536, false, true)
→ IdleStateHandler(90, 0, 0) → WebSocketFrameHandler(@Sharable)
```

- **身份走路径** `/ws/{role}/{id}`（role=passenger|driver）。该 Netty 版本握手对路径精确匹配，query 传身份会握手挂起；六参构造 `checkStartsWith=true` 开启前缀匹配。
- `UriCaptureHandler` 在握手消费 HttpRequest 之前把 URI 存入 Channel Attribute，供握手完成后解析。
- **IdleStateHandler(90,0,0)**：90s 无任何读（含客户端 PING 心跳）判定死连接，`WebSocketFrameHandler.userEventTriggered` 收 IdleStateEvent 主动 close 回收，杜绝半开连接堆积。
- boss/worker 均用 **daemon 线程工厂**：SIGTERM 后 JVM 可正常退出、9101 端口立即释放（非 daemon 曾导致停服务残留占端口）。

### 9.2 连接注册与帧处理

- 握手完成事件 → 解析 role/id → 非法即 close → `registry.register(role:id, channel)` → 回 `CONNECTED` 欢迎帧。
- `ConnectionRegistry`：ConcurrentHashMap<role:id, Channel>；**多端登录以后到覆盖，顶号时先向旧连接下发 KICKED 帧再 close**——新版客户端收到 KICKED 即停止重连，避免两个自动重连客户端互相顶号形成活锁；注销用 `remove(key, channel)` 防误删新连接；离线 send 返回 false。
- 客户端文本帧含 `PING` → 回 `PONG`；IdleStateEvent → close；channelInactive 注销；exceptionCaught warn+close。
- 帧协议统一 JSON：`{event, data, timestamp}`（WebSocketFrameHandler / PushFacadeImpl）。

### 9.3 事件清单

| event | 接收方 | 产生点 |
|---|---|---|
| CONNECTED / PONG | 握手客户端 | WebSocketFrameHandler |
| KICKED | 被顶号的旧连接 | ConnectionRegistry |
| ORDER_CREATED | 乘客 | OrderService.call |
| ORDER_OFFER | 候选司机 | DispatchService.dispatch |
| DRIVER_ACCEPTED / ORDER_ACCEPTED | 乘客 / 司机 | OrderService.accept |
| DRIVER_ARRIVED | 乘客 | OrderService.arrive |
| TRIP_STARTED | 乘客 | OrderService.startTrip |
| TRIP_COMPLETED | 乘客+司机 | OrderService.completeTrip |
| ORDER_PAID | 乘客+司机 | OrderService.finalizePaid（v1.4.0 帧含支付域权威 driverIncome/fee；shouldPush 重放不重推） |
| ORDER_CANCELLED | 乘客+司机 | OrderService.cancel / driverCancel / forceCancel |
| DRIVER_CANCELLED | 乘客 | OrderService.driverCancel（司机取消回派单） |
| ORDER_EXPIRED | 乘客 | OrderService.redispatch（超轮次过期） |
| ORDER_REFUNDED | 乘客+司机 | OrderService.syncRefundState/finalizeRefund（部分退款帧 partial=true 含 refundAmount/refundedTotal/remaining；退满 partial=false） |
| PAYMENT_REMINDER | 乘客 | OrderService.remindOrAutoPay（完单超 60s 未付催付，reminded_at 防重；免密扣款失败亦触发，v1.4.0） |
| ORDER_RATED | 乘客+司机 | OrderService.rate（评价完成） |
| DRIVER_LOCATION | 乘客 | LocationService.report（行程中司机实时位置） |

**推送审计**：`PushAudit` 以 500 条环形缓冲记录每次投递（key/event/orderId/sent 成功与否），`PushFacade.audit(limit)` 供管理端「一致性」看板展示送达/离线跳过明细（PushFacadeImpl.push 每次 record）。

### 9.4 投递语义：尽力而为 + 重连重同步 + 轮询兜底

推送失败/客户端离线仅记日志不重试（OrderService.safePush / PushFacadeImpl）——推送是**状态变更的通知**而非状态本身，真相在订单库/钱包库。补偿分三层：

1. **自动重连**：common.js `connectWS` 断线后指数退避重连（1s 起、×2、封顶 15s）；返回 handle（close/send/readyState），`close()` 为主动放弃不再重连（切换身份时用）；**收到 KICKED（同身份被顶号）亦停止重连**并提示用户。
2. **CONNECTED 重同步**：服务端每次握手成功下发 CONNECTED；司乘端收到后立即以 DB 为准重同步钱包 + 当前订单（无订单则 loadActiveOrder），**断连期间错过的事件（含 ORDER_PAID）由此一次性追平**，不依赖事件补发。
3. **轮询兜底**：司机端每 3s 上报位置、每 4s 刷新身份/钱包/订单；common.js `connectWS` 内置 **30s PING 心跳**（匹配服务端 90s 空闲检测）；乘客端事件驱动刷新 + 手动刷新。注意后台标签页定时器被浏览器节流（可达分钟级），故轮询只是兜底，实时性靠 1+2。

**资金与推送解耦**：转账在支付域本地事务内完成，严格先于订单落库与推送；推送失败不影响入账（ledger 逐单可证）。**跨域支付一致性由服务端保证**（确定性幂等键 + outbox + 对账，§7.4），前端三层只负责展示实时性，不承担一致性责任。

---

## 10. 位置 LBS

- **上报**：`MERGE INTO driver_location … KEY(driver_id)` upsert（DriverLocationMapper），每司机仅最新一点；校验司机 ID 与经纬度范围（LocationService.report）。
- **轨迹落库**：上报携 `orderId` 时（行程中），按 `nextSeq(orderId)` 递增序号逐点写 `trip_track`（PK=order_id+seq），并携 `passengerId` 向乘客转发 `DRIVER_LOCATION` 实时位置帧（推送尽力而为，失败不影响上报）。`track(orderId)` 按序回放，供完单轨迹计价（§6.4）与分享页/详情抽屉轨迹展示。
- **就近**：`nearby` 先取司机域 status=ONLINE 的 ID 集合，再用**经纬度包围盒预过滤**（`selectInBox`，dLat=radius/111、dLng=radius/(111·cos(lat))）缩小候选行，随后 haversine 精算、过滤半径（默认 5km）、升序、limit（默认 10）（LocationService.nearby:81-96）。包围盒把全表扫描降为索引范围扫描，**内存精算仍为演示规模简化**（见 §14）。
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
| auto_pay | BOOLEAN | NOT NULL DEFAULT FALSE | 免密自动扣款授权（v1.4.0 ALTER） |
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
| service_type | VARCHAR(16) | 可空 | 服务车型 EXPRESS/PREMIER/TAXI（v1.3.0 ALTER） |
| service_score | DOUBLE | 可空 | 服务分，默认 5.0，乘客评价均值更新（v1.3.0） |
| decline_count | INT | 可空 | 拒单计数，影响指派评分（v1.3.0） |
| updated_at | BIGINT | 可空 | 最后状态变动毫秒，BUSY 回收宽限判定（v1.3.0） |

### 11.3 location 库

**driver_location**

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| driver_id | VARCHAR(32) | PK | 每司机一行（upsert） |
| lat | DOUBLE | NOT NULL | 纬度 |
| lng | DOUBLE | NOT NULL | 经度 |
| updated_at | BIGINT | NOT NULL | 最后上报毫秒 |

**trip_track**（行程轨迹，PK=order_id+seq，v1.3.0 新增）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| order_id | VARCHAR(32) | PK 之一 | 订单 ID |
| seq | BIGINT | PK 之一 | 轨迹点序号（nextSeq 递增） |
| lat / lng | DOUBLE | NOT NULL | 该点坐标 |
| ts | BIGINT | NOT NULL | 上报毫秒（完单算时长用首末差） |

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
| status | VARCHAR(16) | NOT NULL | 十态状态机（含 EXPIRED/REFUNDED） |
| request_id | VARCHAR(64) | 可空 | 叫车幂等键 |
| cancel_reason | VARCHAR(256) | 可空 | 取消原因 |
| dispatch_radius | DOUBLE | 可空 | 当前广播半径 km |
| last_dispatch_at | BIGINT | 可空 | 本轮派单毫秒（改派判超时） |
| created_at | BIGINT | NOT NULL | 建单毫秒 |
| accepted_at / arrived_at / started_at / completed_at / paid_at / cancelled_at | BIGINT | 可空 | 各流转时刻 |
| service_type | VARCHAR(16) | 可空 | 车型 EXPRESS/PREMIER/TAXI（v1.3.0 ALTER） |
| fare_json | CLOB | 可空 | 下单计价快照 FareDTO JSON（含 version，v1.3.0） |
| actual_distance_km | DOUBLE | 可空 | 完单轨迹实测里程（v1.3.0） |
| actual_duration_min | BIGINT | 可空 | 完单轨迹实测时长 min（v1.3.0） |
| dispatch_round | INT | 可空 | 已派单轮次，超 max 置 EXPIRED（v1.3.0） |
| offered_driver_ids | VARCHAR(1024) | 可空 | 已邀约司机名单（assign 顺延用，v1.3.0） |
| declined_driver_ids | VARCHAR(1024) | 可空 | 已拒单/司机取消名单（排除用，v1.3.0） |
| cancel_fee | BIGINT | 可空 | 取消费（分，全额归司机，v1.3.0） |
| expired_at / refunded_at | BIGINT | 可空 | 过期/退款时刻（v1.3.0） |
| refunded_amount | BIGINT | NOT NULL DEFAULT 0 | 累计已退（分），以支付域 refundSummary 为权威 syncRefund 只前进（v1.4.0） |
| refund_count | INT | NOT NULL DEFAULT 0 | 累计退款笔数（部分退款序号源，v1.4.0） |
| reminded_at | BIGINT | 可空 | 催付时刻（防重 + 免密重试节流，v1.4.0） |
| actual_fare_json | CLOB | 可空 | 完单实际计价明细 FareDTO JSON（支付确认单用，v1.4.0） |

**order_rating**（行程评价，PK=order_id+role，一单一角色一评，v1.3.0 新增）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| order_id | VARCHAR(32) | PK 之一 | 订单 ID |
| role | VARCHAR(16) | PK 之一 | PASSENGER / DRIVER |
| score | INT | NOT NULL | 1–5 星 |
| comment | VARCHAR(512) | 可空 | 评价文字 |
| created_at | BIGINT | NOT NULL | 毫秒 |

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
| balance | BIGINT | NOT NULL | 余额（分），debit 带 `balance-frozen_amount>=` 守卫 |
| frozen_amount | BIGINT | NOT NULL DEFAULT 0 | v1.4.0 提现冻结（可用=balance−frozen；freeze/unfreeze/debitAndUnfreeze 只在此列与 balance 间成对变动） |
| updated_at | BIGINT | NOT NULL | 最后变动毫秒 |

**ledger_entry**（序列 seq_ledger，ID 前缀 L；索引 idx_ledger_user / idx_ledger_order）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | VARCHAR(32) | PK | L+序列 |
| user_id | VARCHAR(32) | NOT NULL | 钱包主体 |
| order_id | VARCHAR(32) | 可空 | 关联订单（充值为空） |
| biz_type | VARCHAR(32) | NOT NULL | TOPUP / WITHDRAW / PAY_DEBIT / PAY_CREDIT / PLATFORM_FEE / CANCEL_FEE_DEBIT / CANCEL_FEE_CREDIT / REFUND_DEBIT / REFUND_FEE_DEBIT / REFUND_CREDIT（v1.3.0 扩列 16→32） |
| amount | BIGINT | NOT NULL | 带符号金额（分） |
| balance_after | BIGINT | NOT NULL | 该笔后余额 |
| request_id | VARCHAR(64) | 可空 | 幂等键回溯 |
| created_at | BIGINT | NOT NULL | 毫秒 |

**idempotent_record**（payment 库）：结构同 order 库，biz_type 为 TOPUP / WITHDRAW / PAY / TRANSFER / REFUND。

**pay_outbox**（事务 outbox，索引 idx_outbox_pending(status, next_retry_at)）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| order_id | VARCHAR(32) | PK | 一单一事件（确定性） |
| request_id | VARCHAR(64) | NOT NULL | PAY-{orderId} |
| status | VARCHAR(16) | NOT NULL | NEW / SENT / **DEAD**（v1.4.0：重试耗尽 10 次置 DEAD，人工重放 DEAD→NEW） |
| retries | INT | NOT NULL | 投递失败次数 |
| next_retry_at | BIGINT | NOT NULL | 下次可投递毫秒（退避） |
| created_at | BIGINT | NOT NULL | 与扣款同事务写入 |

**withdraw_order**（v1.4.0，序列 seq_withdraw，ID 前缀 W；索引 idx_withdraw_biz(biz_no) UNIQUE / idx_withdraw_status）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | VARCHAR(32) | PK | W+序列 |
| user_id | VARCHAR(32) | NOT NULL | 提现用户 |
| amount | BIGINT | NOT NULL | 提现金额（分） |
| status | VARCHAR(16) | NOT NULL | ACCEPTED / PROCESSING / SUCCESS / FAILED（全 CAS 流转） |
| biz_no | VARCHAR(64) | NOT NULL UNIQUE | 幂等键（兼确定性失败判定哈希源） |
| fail_reason | VARCHAR(128) | 可空 | 失败原因 |
| created_at / updated_at | BIGINT | NOT NULL | updated_at 兼作渠道停留计时 |

**refund_outbox**（v1.4.0，退款事务 outbox，索引 idx_refund_outbox_pending(status, next_retry_at)）：结构同 pay_outbox，但 **PK=request_id**（一单可多笔部分退款），order_id 为普通列；投递 `onRefundSucceeded(orderId)`，订单域按 refundSummary 权威同步。

---

## 12. 接口契约清单

### 12.1 Dubbo Facade（ride-facade，全部返回 Result<T>）

| 域 | 方法 | 语义 / 主要错误 |
|---|---|---|
| passenger | create(PassengerCreateRequest) / get(id) / list() | 注册姓名必填 A0400；不存在 A0404 |
| passenger | setAutoPay(id, enabled) | 免密自动扣款授权（v1.4.0），返回 PassengerDTO |
| driver | create(DriverCreateRequest) / get / list | 姓名、车牌必填 A0400 |
| driver | setOnline(id, online) | BUSY 拒绝 A0409 |
| driver | setStatus(id, status) | 非法三值外 A0400 |
| driver | casStatus(id, from, to) | 抢单前置 CAS（ONLINE→BUSY），返回是否成功 |
| driver | updateScore(id, score) / recordDecline(id) | 评价更新服务分 / 拒单计数+1 |
| location | report(LocationReportRequest) | 坐标非法 A0400；upsert；携 orderId 落轨迹+转发 DRIVER_LOCATION |
| location | nearby(NearbyRequest) / get(driverId) | 包围盒预过滤+半径精算；升序 |
| location | track(orderId) | 按 seq 回放轨迹点 |
| pricing | quote(FareRequest) | 坐标非法 A0400；支持 distanceKmOverride/durationMinOverride；返回 FareDTO 明细 |
| pricing | rules() | 完整费率表+时段+远途参数+版本 |
| order | call(CallRequest) | requestId 幂等；坐标/乘客/车型校验 |
| order | accept(orderId, driverId) | 司机 casStatus ONLINE→BUSY 前置；订单 CAS 败 A0409 |
| order | arrive / startTrip / completeTrip(orderId, driverId) | 状态不符或非本司机 A0409；completeTrip 轨迹计价 |
| order | pay(orderId) | 确定性键 PAY-{orderId}；未 COMPLETED A0409；余额不足 A0409（支付域透传） |
| order | onPaySucceeded(orderId) | outbox 事件回调：COMPLETED 幂等完结，已 PAID 无操作 |
| order | cancel(orderId, reason) | 行程开始后 A0409；ARRIVED/超窗 ACCEPTED 收取消费 |
| order | driverCancel(orderId, driverId, reason) | ACCEPTED/ARRIVED 回 DISPATCHING 重派、排除该司机；非本司机 A0409 |
| order | declineOffer(orderId, driverId) | 拒单：计数+1、加入排除名单（assign 顺延） |
| order | rate(RatingRequest) / ratings(orderId) | 一单一角色一评（PK 幂等）；乘客评价驱动司机服务分 |
| order | refund(orderId, reason, amount?) / forceCancel(orderId, reason) | 退款支持部分（amount 缺省=剩余可退；超退 A0400；退满 REFUNDED 否则保持 PAID）/ 强制取消（含 IN_PROGRESS 异常单→CANCELLED） |
| order | onRefundSucceeded(orderId) | refund outbox 事件回调：按 refundSummary 权威同步累计退款（v1.4.0） |
| order | get / list / listByPassenger / listByDriver / listByStatus | 不存在 A0404 |
| order | query(OrderQuery) | 多条件动态查询+分页，返回 PageDTO<OrderDTO> |
| order | stats() | 状态分布/营收/完单率等聚合 OrderStatsDTO |
| dispatch | dispatch(DispatchRequest) | grab 广播 / assign 评分 Top1；返回 DispatchResultDTO{orderId, offeredDrivers, radiusKm} |
| payment | topup(TopupRequest) / withdraw(TopupRequest) | bizNo **必填**幂等；金额越界 A0400；超单笔限额 A0409；提现受理冻结（可用不足/超日累计/超频次 A0409），渠道异步结算 |
| payment | wallet(userId) / wallets() | **只读**，无钱包返回零余额视图（含 frozenAmount/availableBalance）/ 列全部钱包 |
| payment | withdrawOrders(userId) | 提现单列表（受理→处理中→成功/失败，v1.4.0） |
| payment | pay(PayRequest) | requestId 幂等；余额不足 A0409；返回 PayResultDTO{amount, fee, driverIncome, passengerBalance, repeated} |
| payment | payResult(orderId) | 读 PAY-{orderId} 幂等快照（订单域推送司机实收用，v1.4.0） |
| payment | transfer(TransferRequest) | 通用原子划转（取消费）；bizType+requestId 幂等 |
| payment | refund(RefundRequest) | 无 PAY_DEBIT A0409；累计可退守卫 A0409；司机按可用余额追回+平台兜底；手续费按比例；REFUND-{orderId}-{seq} 幂等 + refund_outbox 同事务 |
| payment | refundSummary(orderId) | REFUND_CREDIT 流水聚合 {totalAmount, count}（订单域同步/对账权威值，v1.4.0） |
| payment | isPaid(orderId) / isRefunded(orderId) | ledger 是否有 PAY_DEBIT / REFUND_CREDIT（对账证据） |
| payment | ledger(LedgerQuery) | 流水分页查询 PageDTO<LedgerEntryDTO> |
| payment | audit() / stats() / outboxStats() | 账面核对 / biz_type 聚合 / outbox 双表统计（含 deadCount） |
| payment | deadOutbox() / replayOutbox(key) | DEAD 事件列表 / 人工重放（orderId 或 requestId，DEAD→NEW，v1.4.0） |
| push | pushToPassenger / pushToDriver(id, event, data) | 离线返回成功但跳过投递；每次记 PushAudit |
| push | onlineCount() / connections() / recentEvents(limit) | WS 在线数 / 连接明细 / 最近投递审计 |
| 各域 | HealthFacade.ping()（group=应用名） | 探针 |

### 12.2 REST（ride-gateway，成功 body 为 data，失败 `{error, code}`）

| Method | Path | 参数 | 说明 |
|---|---|---|---|
| POST | /api/passengers | body{name, phone?} | 注册乘客 |
| GET | /api/passengers | — | 列表 |
| GET | /api/passengers/{id} | — | 详情 |
| POST | /api/drivers | body{name, phone?, carNo, carModel?, serviceType?} | 注册司机 |
| GET | /api/drivers | — | 列表 |
| GET | /api/drivers/{id} | — | 详情 |
| POST | /api/drivers/{id}/online | ?online=true\|false | 上下线 |
| POST | /api/drivers/{id}/status | ?status=ONLINE\|BUSY\|OFFLINE | 状态直改（运维口） |
| POST | /api/locations | body{driverId, lat, lng, orderId?, passengerId?} | 位置上报（携 orderId 落轨迹+转发） |
| GET | /api/locations/nearby | ?lat&lng&radiusKm?&limit? | 就近在线司机 |
| GET | /api/locations/track/{orderId} | — | 行程轨迹回放 |
| GET | /api/locations/{driverId} | — | 单位置 |
| POST | /api/pricing/quote | body{originLat, originLng, destLat, destLng, serviceType?, distanceKmOverride?, durationMinOverride?} | 计价明细 |
| GET | /api/pricing/rules | — | 费率表+时段+远途+版本 |
| POST | /api/wallets/topup | body{userId, amount, bizNo} | 充值（分，bizNo 必填幂等键） |
| POST | /api/wallets/withdraw | body{userId, amount, bizNo} | 提现受理（分，bizNo 必填；冻结→渠道异步结算） |
| GET | /api/wallets/{userId} | — | 钱包（只读，含冻结/可用余额） |
| GET | /api/wallets/{userId}/withdraws | — | 提现单列表（v1.4.0） |
| GET | /api/wallets/{userId}/ledger | ?orderId?&bizType?&from?&to?&page?&size? | 该用户流水分页 |
| POST | /api/passengers/{id}/auto-pay | ?enabled=true\|false | 免密自动扣款授权（v1.4.0） |
| POST | /api/orders | body CallRequest{passengerId, origin*, dest*, serviceType?, requestId?} | 叫车 |
| GET | /api/orders | ?passengerId\|driverId\|status（互斥优先序） | 列表过滤 |
| GET | /api/orders/query | ?passengerId?&driverId?&status?&from?&to?&page?&size? | 多条件动态查询+分页 |
| GET | /api/orders/stats | — | 订单聚合统计 |
| GET | /api/orders/{id} | — | 订单详情 |
| POST | /api/orders/{id}/accept | ?driverId | 抢单 |
| POST | /api/orders/{id}/arrive | ?driverId | 到达 |
| POST | /api/orders/{id}/start | ?driverId | 开始行程 |
| POST | /api/orders/{id}/complete | ?driverId | 结束行程（轨迹计价） |
| POST | /api/orders/{id}/pay | — | 支付（订单维度幂等，无需 requestId） |
| POST | /api/orders/{id}/cancel | ?reason? | 乘客取消（可能收取消费） |
| POST | /api/orders/{id}/driver-cancel | ?driverId&reason? | 司机取消回派单 |
| POST | /api/orders/{id}/decline | ?driverId | 拒单 |
| POST | /api/orders/{id}/rate | body RatingRequest{role, score, comment?} | 评价 |
| GET | /api/orders/{id}/ratings | — | 该单评价列表 |
| GET | /api/push/online | — | WS 在线数 |
| POST | /api/share/{orderId} | — | 生成行程分享 token（HMAC-SHA256） |
| GET | /api/share/{orderId} | ?token | 校验 token 返回脱敏行程（分享页） |
| GET | /api/health | — | 健康聚合 {registry:{ok}, services:[{service,port,ok}], allOk} |

**管理端（/api/admin/*，需 X-Admin-Token 头，AdminAuthFilter 校验，默认 ride-admin / 环境变量 ADMIN_TOKEN）**

| Method | Path | 参数 | 说明 |
|---|---|---|---|
| GET | /api/admin/overview | — | 总览：订单统计+营收+钱包+outbox+WS 聚合 |
| GET | /api/admin/ledger | ?userId?&orderId?&bizType?&page?&size? | 全局流水分页查询 |
| GET | /api/admin/wallets | — | 全部钱包列表 |
| GET | /api/admin/audit | — | 账面守恒核对（逐钱包 balance vs SUM(ledger)） |
| GET | /api/admin/outbox | — | outbox 双表投递统计（含 deadCount） |
| GET | /api/admin/outbox/dead | — | DEAD 事件列表（v1.4.0） |
| POST | /api/admin/outbox/replay | ?key（orderId 或 requestId） | 人工重放 DEAD 事件（v1.4.0） |
| GET | /api/admin/push/connections | — | WS 连接明细 |
| GET | /api/admin/push/events | ?limit=100 | 最近推送审计（送达/离线跳过） |
| POST | /api/admin/orders/{id}/refund | ?reason?&amount?（分） | 管理端退款（amount 缺省=剩余可退；部分退保持 PAID，退满 REFUNDED） |
| POST | /api/admin/orders/{id}/force-cancel | ?reason? | 强制取消（含 IN_PROGRESS 异常单→CANCELLED） |
| GET | /api/admin/logs | ?file=gateway&lines=200 / ?traceId= | 白名单日志尾随（≤1000 行）/ 跨域 traceId 检索（≤500 命中） |
| POST | /api/admin/seed | — | 演示播种：按手机号去重只增不删，充值 bizNo 幂等 |

### 12.3 WebSocket 协议

- 接入：`ws://host:9101/ws/{role}/{id}`，role∈{passenger, driver}；非法握手直接 close。
- 下行帧：`{event, data, timestamp}`（事件清单见 §9.3）。
- 上行帧：文本含 `PING` → 下行 `PONG`；其余忽略。
- 多端登录：同 role:id 后到覆盖，旧连接先收 `KICKED` 再被 close；新版客户端收到 KICKED 停止重连（防互踢活锁）。

---

## 13. 核心代码走读

### 13.1 叫车 call（OrderService.call）

校验乘客 ID 与起终点坐标 → requestId 缺省补 UUID → 幂等快路径 → 乘客快照 + 按车型计价（fare_json 快照）→ 组装 OrderDO 直接 DISPATCHING、带默认半径/轮次/派单时间 → **事务内 insert 订单 + insert 幂等记录** → DuplicateKey 回读胜者 → 提交后 dispatch 广播 + ORDER_CREATED 推送。副作用全部在事务外且 try/catch 降级：推送/派单失败不影响已落库订单，改派调度兜底。

### 13.2 抢单 accept（OrderService.accept:152-182）

读订单校验 DISPATCHING → 读司机校验**车型匹配** → **`driverFacade.casStatus(id,'ONLINE','BUSY')` 前置 CAS**（:163，失败抛 A0409「司机当前不可接单」）→ `orderMapper.accept` 订单 CAS（:167），**0 行则 `safeCasDriverStatus(BUSY→ONLINE)` 回滚司机**再抛 A0409（:168-171）→ 双端推送司机快照（含 serviceScore）。并发抢单正确性由「司机 CAS + 订单 CAS」双重保证：同司机并发抢两单只有一个能 ONLINE→BUSY；两步间崩溃由 `reclaimBusyDrivers` 调度兜底（宽限 30s 后无活跃单即释放，原 L2 根治）。

### 13.3 支付 pay（OrderService.pay:246-268）

幂等键=`PAY-{orderId}`（payKey:780）→ 快路径 → 已 PAID 直接返回 → 非 COMPLETED 抛 A0409 → 调支付域原子转账（余额不足在此透传 A0409）→ `finalizePaid`（:281-299）：事务内 CAS pay **行数检查**（0 行=已被完结，幂等重放）+ MERGE 幂等记录 → 司机回 ONLINE → 双端 ORDER_PAID。**先转账后改状态**：转账失败订单保持 COMPLETED 可重试；转账成功后崩溃由 outbox 事件（onPaySucceeded:271-278）秒级完结、对账调度兜底，确定性键保证任何路径不双扣（§7.4）。

### 13.4 支付域事务（PaymentService.pay:137-190）

见 §7.1。`recordLedger` 在事务内重读钱包取 balance_after（:394-406），保证流水与余额同事务一致；**outbox 事件与扣款同事务写入**（:181），投递任务 OutboxDispatcher 每 1s 轮询、失败退避封顶 30s。

### 13.5 改派 redispatch（OrderService.redispatch）

`@Scheduled(fixedDelayString="${order.redispatch-interval-ms:5000}")`；仅扫 DISPATCHING；`dispatch_round>=maxDispatchRounds` 置 EXPIRED + ORDER_EXPIRED；否则超时判定（assign 用 offerTtlMs、grab 用 dispatchTimeoutMs）到期重派，grab 半径单调递增封顶 20km、assign 顺延下一候选。超时判定用落库的 last_dispatch_at 而非内存态——**重启不丢改派进度**。

### 13.6 WS 握手注册（WebSocketFrameHandler + NettyWebSocketServer）

UriCapture 先存 URI → 握手完成事件触发 parseIdentity（路径段 [2]=role、[3]=id，query 仅兜底 putIfAbsent）→ 校验 → register（覆盖旧连接，旧连接先收 KICKED）→ CONNECTED。@Sharable 单例 handler 无每连接状态，状态全在 Channel Attribute 与注册表；IdleStateEvent（90s 无读）主动 close 回收死连接。

---

## 14. 异常与边界场景全集

### 14.1 已正确处理的场景

| # | 场景 | 行为 | 位置 |
|---|---|---|---|
| 1 | 叫车缺乘客/坐标越界/未知车型 | A0400 | OrderService.call；PricingService.normalizeService |
| 2 | 重复 requestId 叫车（串行/并发） | 快路径回放 / DuplicateKey 回读，同单 | OrderService.call |
| 3 | 并发多司机抢单 | 仅一人订单 CAS 成功，其余 A0409 | OrderMapper.accept |
| 4 | 同司机并发抢两单 | 司机 casStatus ONLINE→BUSY 前置，仅一单成功（v1.3.0 根治 L2） | OrderService.accept:163 |
| 5 | BUSY/OFFLINE 司机抢单 / 车型不匹配 | A0409 | OrderService.accept:154-166 |
| 6 | 非本司机 arrive/start/complete | CAS 0 行 → A0409 | OrderMapper |
| 7 | 跳态操作（如未接单即到达） | A0409 | 同上 |
| 8 | 行程开始后取消 | A0409 | OrderMapper.cancel |
| 9 | 已接单后取消 | 释放司机 ONLINE + 双端通知；ARRIVED/超窗收 300 分取消费归司机 | OrderService.cancel:305-334 |
| 10 | 取消费划转失败（余额不足等） | 取消不受影响，cancel_fee 置 0 平台让利，流水可审计 | OrderService.chargeCancelFee:336-352 |
| 11 | 司机取消 | ACCEPTED/ARRIVED 回 DISPATCHING 重派、排除该司机、司机回 ONLINE | OrderService.driverCancel:355-369 |
| 12 | 拒单 | decline_count+1、加入排除名单，assign 顺延下一候选 | OrderService.declineOffer |
| 13 | 超 maxDispatchRounds 无人接单 | 置 EXPIRED 终态 + ORDER_EXPIRED，不再改派 | OrderService.redispatch |
| 14 | 未 COMPLETED 支付 | A0409 | OrderService.pay:257-259 |
| 15 | 重复点击支付 | 确定性键快路径 / 已 PAID 快路径返回 | OrderService.pay:249-256 |
| 16 | 并发支付（任意 requestId） | 支付域按 PAY-{orderId} 幂等回放 repeated=true，只扣一次 | PaymentService.pay |
| 17 | 余额不足 / 并发透支 | debit 守卫 0 行 → A0409，事务回滚（outbox 亦不落） | WalletMapper.debit；PaymentService.pay |
| 18 | 金额 ≤0 或 >1e12 分 | A0400 | MoneyUtil.requireValid |
| 19 | 重复 bizNo 充值/提现 | 回放原钱包结果 | PaymentService.topup/withdraw |
| 20 | 提现余额不足 | debit 守卫 0 行 → A0409 | PaymentService.withdraw:122-124 |
| 21 | 退款司机余额不足 | 按余额部分追回，缺口平台兜底，乘客全额到账；平台不足则整体回滚 A0409 | PaymentService.refund:279-293 |
| 22 | 派单 RPC 失败 | warn + 下轮超时改派重试 | OrderService.dispatch |
| 23 | 单个司机邀约推送失败 | 跳过该司机继续广播 | DispatchService.dispatch |
| 24 | 客户端离线推送 | 返回成功但跳过、记 PushAudit；前端轮询/重同步追平 | PushFacadeImpl.push |
| 25 | 非法 WS role/id | 握手后 close | WebSocketFrameHandler |
| 26 | 同用户多端登录 | 后到覆盖，旧连接收 KICKED 后 close；新版客户端停止重连 | ConnectionRegistry；common.js connectWS |
| 27 | 连接断开 / 90s 无读死连接 | remove(key,channel) 精确注销 / IdleStateHandler 主动 close | ConnectionRegistry；WebSocketFrameHandler |
| 28 | 服务中司机切上下线 | A0409 | DriverService.setOnline |
| 29 | accept 两步间崩溃致司机卡 BUSY | reclaimBusyDrivers 宽限 30s 后无活跃单即释放 ONLINE | OrderService.reclaimBusyDrivers |
| 30 | 改派半径无限扩大 | min(max, cur+step) 封顶 | OrderService.redispatch |
| 31 | 停机时 ZK 先死导致 hook 阻塞 | stop.sh 依赖序停止规避 | stop.sh |
| 32 | 下游域未启动时网关调用 | RpcException → 503 | GlobalExceptionHandler |
| 33 | 重启后改派/幂等进度丢失 | 全部落库（last_dispatch_at、dispatch_round、idempotent_record） | schema.sql |
| 34 | WS 断连（服务重启/网络抖动/多端挤占） | 指数退避自动重连；CONNECTED 触发钱包+订单重同步 | common.js connectWS；driver/passenger.html onEvent |
| 35 | 断连期间乘客完成支付 | 入账不受影响；重连后 CONNECTED 重同步立即显示收入 | 实测 O11（§17） |
| 36 | 扣款/退款划转提交后、订单 CAS 前崩溃/超时 | 支付：outbox 秒级投递完结；退款/支付：reconcilePaid 双路对账兜底（isPaid/isRefunded）；司机自动回 ONLINE | PaymentService.pay/refund；OutboxDispatcher；OrderService.reconcilePaid:587-618 |
| 37 | 支付/outbox/对账重复完结同一单 | CAS 行数检查 + MERGE 幂等记录，无副作用 | OrderService.finalizePaid |
| 38 | 同身份多页互踢活锁 | 顶号下发 KICKED，被踢方停止重连，连接收敛 | 实测 §17 v1.2.0 |
| 39 | 管理端无 token / 错 token | AdminAuthFilter 401；正确 token 放行 | AdminAuthFilter |
| 40 | 分享链接 token 伪造/篡改 | HMAC-SHA256 校验失败拒绝；乘客姓名脱敏 | ShareController |
| 41 | 日志查看越权读任意文件 | file 白名单校验，traceId 正则校验，非法 A0400 | AdminController.logs:138-159 |
| 42 | 重复播种 | 按手机号去重只增不删，充值 SEED-TOPUP-{phone} 幂等不重复加钱 | AdminController.seed |

### 14.2 已知限制（演示取舍，含修复方向）

**v1.4.0 已修复**：退款无支付流水校验缺失（任意 orderId 可退）→ PAY_DEBIT 前置 + 累计可退守卫；充值/提现随机 bizNo（网络重试双入账）→ 幂等键必填 + 前端意图内复用；提现即时扣款无渠道语义 → 冻结状态机 + 失败解冻；退款仅全额且订单侧无 outbox → 部分退款 + refund_outbox + refundSummary 权威同步；outbox 无限重试无告警 → DEAD + 自动审计告警 + 人工重放；完单不付无催收 → 催付 + 免密自动扣款。

**v1.3.0 已修复**：L2（司机抢单 read-then-act）→ casStatus 前置 CAS + reclaim 兜底；L5（直线计价、完单=预估）→ 轨迹里程计价 + 分车型/高峰/远途费率；L6（wallet GET 建钱包副作用）→ 只读零余额视图；L8（无鉴权）→ 管理端 X-Admin-Token + 分享 HMAC 签名（司乘身份绑定仍待完善）。L1（不同 requestId 并发双扣）已于 v1.2.0 修复（§7.4）。

| # | 限制 | 风险 | 修复方向 |
|---|---|---|---|
| L3 | nearby 包围盒预过滤后仍内存 haversine 精算 | 司机量极大时候选集仍偏大 | Geohash 分桶 / Redis GEO / H2 空间索引 |
| L4 | 推送尽力而为、无离线消息队列 | 掉线期间事件不补发（重连 CONNECTED 重同步已追平状态与余额；事件日志本身不回放） | 离线信箱 + 重连补投（如需事件级审计回放） |
| L7 | CREATED 态不落库（call 直接 DISPATCHING） | 状态机文档态与实态差一 | 拆分建单/派单两阶段时启用 |
| L8' | 司乘端无身份绑定鉴权 | 任意客户端可操作任意司乘 ID（管理端已鉴权） | 司乘登录 token + 身份绑定 |
| L9 | H2 文件库单进程独占 | 同域不可多实例水平扩展 | 域库换 MySQL/PG 或按域分片 |

---

## 15. 网关与前端（五页面 + 组件库）

- 静态托管：`classpath:/static/`，零安装零构建。页面：index.html（门户+健康总览）、passenger.html（乘客端）、driver.html（司机端）、admin.html（管理端）、share.html（行程分享），共享 common.css/common.js。
- **common.js 组件库**：`api()` fetch 包装（失败抛 `{error}`）；`connectWS(role,id,onEvent,onStatus)` 拼 `ws://host:9101/ws/{role}/{id}`（指数退避重连 + 30s PING + KICKED 停重连）；`MapView` canvas 地图（PRESETS 北京六地标 + BBOX 经纬度包围盒 + 轨迹/ markers 绘制）；UI 组件 `statusBadge/yuan/fmtTime/escapeHtml/toast/pushLog/openDrawer/closeDrawer/confirmDialog(message,okText)/ratingModal/renderPager`；业务渲染 `fareHtml`（计价明细）/`timelineHtml`（状态时间线）/`ledgerRowsHtml`（流水）/`ratingsHtml`（评价）/`historyRowsHtml`+`bindHistoryRows`（订单记录行）/`openOrderDrawer`（详情抽屉：车型徽章+计价快照+时间线+轨迹小地图+本人流水+评价）。
- **司乘端布局**：顶部 `.tabs` + 移动端 `.bottomnav` 双导航，`switchTab` 切换 `.tabpane`。乘客端四页：叫车（地图点选起终点→车型选择→预估价明细→叫车→时间线实时点亮→COMPLETED **支付确认单（actualFare 实际计价明细）**→PAID 评价/分享）、订单记录（状态/时间过滤+分页+消费统计+详情抽屉）、钱包（余额+充值+**免密自动扣款开关**+流水分页）、实时事件。司机端四页：接单（上线→3s 位置上报→ORDER_OFFER 抢单卡片→到达/开始/完成→模拟驾驶）、订单记录、收入（**冻结/可用余额**+累计/今日/服务分+**提现记录状态徽章（pending 3s 轮询）**+流水分页）、实时事件。
- **v1.4.0 前端资金安全交互**（展示层补偿，可靠性全部服务端兜底）：充值/提现按钮请求期间禁用 + **幂等键意图内复用**（网络错误保留键重试不双入账，成功或改金额才换新键）；支付网络错误→「支付结果确认中」2s×8 短轮询订单状态，禁止盲目重试；余额不足→差额一键充值（TOPUP-PAY-{orderId} 确定性键）续付；ORDER_REFUNDED→刷新钱包（部分退款提示本次/剩余）；PAYMENT_REMINDER 催付 toast；司机 ORDER_PAID 到账金额用服务端 driverIncome（不再前端 ×0.8 估算）。
- **管理端 admin.html 六模块**（侧边 `.tabs` 导航，需 X-Admin-Token）：仪表盘（订单统计+营收+钱包+outbox+WS 聚合+账面守恒平/不平）、资金流水（全局 ledger 多条件过滤+分页+详情抽屉+账面核对 audit+**CSV 导出**）、订单管理（query 过滤+分页+强制取消+**退款弹窗：实付/已退/剩余可退展示、金额输入默认剩余、原因**）、钱包总览、系统日志（白名单尾随+traceId 检索）、一致性监控（**5s 自刷新**+outbox 双表统计含 DEAD+**阈值告警：积压>10s 或 DEAD>0 才红**+**DEAD 列表与一键重放**+WS 连接+推送审计）。顶部「播种演示数据」只增不删。
- **share.html 行程分享**：乘客 PAID 后生成 HMAC token 链接，分享页校验 token 展示脱敏行程（乘客姓名掩码 演**）+ 起终点 + 轨迹回放 + 状态时间线，无需登录。
- 门户 index.html：3s 轮询 /api/health 与 /api/push/online，表格化展示 registry + 8 域探针与 WS 在线数；入口卡片链接乘客端/司机端/管理端，并说明行程分享（share.html）由乘客端订单卡片生成。
- 健康聚合：HealthService.aggregate = registry HTTP 探针 + 8 域 Dubbo ping（1500ms 超时），allOk 供 run.sh 就绪判定。

---

## 16. 部署与运维

### 16.1 启停

```bash
./run.sh             # mvnw package -DskipTests + 启动 10 进程 + 就绪等待
./run.sh --no-build  # 跳过编译
./stop.sh            # 依赖序优雅停止，数据保留
```

run.sh 流程：JAVA_HOME 优先 corretto-18（release=17；默认 PATH 的 java 26 不可用于构建）→ mkdir run/logs/data → registry 先启并等 8093/health（60s）→ 8 域顺次启动（不等就绪）→ gateway 启动等 8090（90s）→ 轮询 /api/health 的 allOk（120×2s）→ 打印三个 URL。pid 落 run/*.pid，日志落 logs/<域>.log。

**stop.sh 依赖序（关键约束）**：`gateway → dispatch → order → payment → push → location → pricing → driver → passenger → registry`，逐进程 SIGTERM + 20s 等待 + SIGKILL 兜底。**registry 必须最后停**：业务进程停机时若 ZK 已失联，Dubbo shutdown hook 会阻塞在注册中心销毁上（曾致全员 15s 后 SIGKILL）；改序后实测 28.7s 全优雅退出、零 SIGKILL。

### 16.2 数据与日志

- 数据：`data/{passenger,driver,location,order,payment}.mv.db` + `data/zookeeper/`；schema 幂等（`IF NOT EXISTS` 建表 + `ADD COLUMN IF NOT EXISTS` 增量迁移）、数据跨重启保留；**删除/重置需用户明确确认**。
- 日志：logback 控制台+文件，**全部 10 个 application.yml 已配置 `logging.pattern.console` 含 `%X{traceId:-}`**（MDC）；TraceFilter（Dubbo SPI）跨域透传 traceId、TraceWebFilter（网关）注入 X-Trace-Id，故同一请求在 pricing/order/payment 等各域日志带相同 traceId，管理端「系统日志」可按 traceId 跨域检索。业务关键节点 INFO（叫车/接单/流转/支付/派单/上下线/WS 上下线），副作用失败 WARN，未预期 ERROR。

### 16.3 故障处置速查

| 症状 | 处置 |
|---|---|
| 9101 被占 | 确认无残留 push 进程（daemon 线程修复后 SIGTERM 即释放）；勿直接删数据 |
| 某域 health ok=false | 看 logs/<域>.log 的 ZK 连接与 Dubbo 导出日志；必要时单域重启 |
| 全部 ok=false 且 registry ok=false | registry 未起或 2182 被占；先恢复 registry |
| 构建报 release 17 不支持 | JAVA_HOME 指向 corretto-18，勿用 PATH 默认 java 26 |
| 单域重启报 Dubbo 端口 BindException | 旧进程 shutdown hook 未释放端口，先 `kill -0` 轮询等旧进程退出再启 |

### 16.4 混沌测试脚本

`run/chaos-test.sh`（可执行）：基于 curl + python3 的 REST/进程级一致性回归，12 节。覆盖：健康检查 → 造数 → **8 路并发抢单仅一胜** → 轨迹计价完单 → **6 路并发支付仅 1 条 PAY_DEBIT** → 取消费双流水 → 司机取消回派单 → 管理鉴权+全额退款+守恒 → **提现状态机（超额 409/受理冻结/可用 0/轮询结算 SUCCESS/扣减解冻/幂等回放/WITHDRAW 流水恰一条）** → **部分退款（半额退保持 PAID+累计/笔数、超退 400、补齐 REFUNDED、REFUND_CREDIT 两笔）+ 未支付退款 409 + 充值限额 409 + 缺幂等键 400 + DEAD 接口 + 守恒** → **免密自动扣款（授权→完单→75s 内服务端自动 PAID→取消授权）** → **kill order 支付窗口 → outbox 滞留 → 重启对账 PAID/单扣/outbox 清零/守恒** → **kill order 退款窗口 → refund outbox 重试 → 重启投递/重发幂等 → REFUNDED/退款恰一次/outbox 清零/守恒**。运行：`./run/chaos-test.sh`（需服务已起，URL reason 用 ASCII 避免 Tomcat 拒绝原始中文查询串）。

---

## 17. 验证记录

### 17.0 v1.4.0（2026-11-17）

- **单元测试 34 项全过**（`./mvnw clean package`，BUILD SUCCESS）：MoneyUtilTest 3、PricingServiceTest 11、OrderStateMachineTest 8、PaymentConsistencyTest 12（新增：提现冻结→结算→幂等回放、提现失败解冻+账面守恒、部分退款累计守卫+手续费按比例+平台兜底、无支付流水退款拒绝、风控限额+幂等键必填）。
- **混沌脚本 12 节 60 项全过**（`run/chaos-test.sh`）：并发抢单唯一胜者、并发支付只扣一次、取消费、司机取消回派单、管理鉴权+退款守恒、**提现状态机**（超额 409→受理冻结→渠道 SUCCESS→幂等回放不重复扣→WITHDRAW 流水恰 1 条）、**部分退款**（退半保持 PAID→超剩余 400→退满 REFUNDED→REFUND_CREDIT 两笔）、未支付退款 409、充值限额 409、缺幂等键 400、**免密自动扣款**（完单后服务端 30s 宽限自动 PAID）、**支付窗口杀 order**（outbox/对账自愈最终 PAID、只扣一次）、**退款窗口杀 order**（refund outbox/对账自愈最终 REFUNDED、只退一次、pending=0、账面守恒）。
- **启动**：10 进程 allOk=true。期间发现并修复一处交付缺陷：最后一轮前端修改晚于打包时间，网关 jar 内静态页为旧版（支付无确认单）；`clean package` 全量重构建后重启网关恢复（注意 `-pl` 单模块打包会从 .m2 取旧 facade，必须全量 reactor 构建）。
- **三端浏览器全流程实测**（browser-use evaluate_script 驱动，乘客 P7/司机 D11 新增数据）：
  - 乘客端：注册→充值 ¥100（确认弹窗+流水 1 笔）→UI 叫车 O38（预估明细）→支付确认单展示**实际计价明细**（起步/里程/时长/远途/规则版本）→余额精确扣减；O40 余额不足（¥27.03 < ¥88.58）→弹窗「还差 ¥61.55，充值差额并继续支付」→一键差额充值 6155 分+续付成功、PAY_DEBIT 恰 1 条；免密开关服务端生效；O41 余额 0+免密开→服务端自动扣款失败→**PAYMENT_REMINDER 实时推送**→充值后 60s 重试窗口内**服务端自动 PAID**（无需客户端支付）、ORDER_PAID 事件含 driverIncome/fee。
  - 司机端：可用 ¥160.62（四单分成精确合计）/冻结 ¥0.00 双余额展示；提现 ¥50 确认弹窗（冻结语义说明）→「提现已受理」toast+冻结 ¥50+记录徽章「已受理」→3s 轮询自动变「已到账」+解冻+可用 ¥110.62；UI 抢单邀约卡（预估收入 ¥16.66）→到达/开始/结束行程按钮推进→O42 支付后 ORDER_PAID 推送 driverIncome=1080、余额自动刷新 ¥121.42。
  - 管理端：token 鉴权进入；一致性监控 outbox 待投递 0/已投递 22/DEAD 0+「无 DEAD 事件」表+WS 连接+推送审计（送达/离线跳过）；订单行详情→退款弹窗展示**实付/已退/剩余可退**（¥13.50/¥0.00/¥13.50）+金额默认剩余→部分退 ¥6.75→toast「部分退款完成 O42」→服务端 refundedAmount=675/refundCount=1、REFUND_CREDIT 675、订单保持 PAID。
- **终态审计**：`/api/admin/audit` 全部账户账面守恒=True；outbox pending=0、dead=0。
- **数据保护**：全程未删除/重置 data/ 与 H2 库；仅新增 P7/D11 及 O38–O42 测试数据。

### 17.1 v1.3.0（2026-11-17）

- **单元测试 30 项全过**（`./mvnw test`，BUILD SUCCESS）：MoneyUtilTest 3（fee 半进位边界）、PricingServiceTest 11（分车型/高峰/夜间/远途/最低消费/轨迹覆盖/版本指纹）、OrderStateMachineTest 8（H2 内存库同 schema：8 路并发抢单仅一胜、全生命周期 CAS 链、取消/强制取消守卫、司机取消回 DISPATCHING、过期/退款守卫、markDispatched 轮次、幂等主键拒重、动态查询分页）、PaymentConsistencyTest 8（Spring 事务模板：8 路并发支付恰扣一次+outbox 同事务、余额不足拒绝、转账幂等+守卫、退款平台兜底司机缺口、退款正常分账、提现守卫+幂等、只读钱包不建钱包、账面恒守恒）。
- **混沌脚本 32 项全过**（`run/chaos-test.sh`）：含进程级——kill order 模拟扣款后崩溃 → 等旧进程退出 → 重启 → outbox/对账自愈 → 最终 PAID、单条 PAY_DEBIT、outbox pending=0、账面守恒。
- **启动**：10 进程 allOk=true（gateway 含模拟驾驶续驶修复 + traceId 日志重构建）。
- **五页面浏览器全流程实测**（browser-use，take_snapshot + evaluate_script JS 点击）：
  - 门户 index：10 服务健康。
  - 管理端 admin 六模块：仪表盘统计+账面守恒平；资金流水 62 条+详情抽屉+audit 13 账户守恒；订单管理+强制取消 O20→CANCELLED；钱包总览；系统日志尾随 200 行+traceId 跨域检索命中；一致性监控 outbox/WS/推送审计（送达/离线跳过）。
  - 乘客端：身份弹窗→预设点自动报价 ¥33.93→UI 叫车 O26→支付 O27→评价弹窗 5 星持久化→分享链接 share.html 脱敏 演**+轨迹+时间线；订单记录页消费统计 ¥39.43+O27 抽屉（计价快照/时间线/31 点轨迹/本人流水）；钱包 ¥160.57。
  - 司机端：身份 D6→live WS 邀约（80% 收入）→抢单→模拟驾驶→收入 ¥231.54/今日 ¥31.54/服务分 5.00→订单记录抽屉 PAY_CREDIT +¥20.74。
- **两处缺陷修复并复测**：
  - 模拟驾驶续驶：原到达上车点即 stopSimulate，开始行程后不再驶向终点致轨迹全为起点（O26 actualFee ¥13.50/0km）；改 simArrived 待命逻辑（到上车点保持、IN_PROGRESS 自动续驶终点、到终点才停），复测 O27 8.4km/¥25.93/31 点/24 个不同纬度。
  - traceId 日志：TraceFilter/TraceWebFilter 早已存在但 10 个 yml 均未配 logging.pattern，日志无 traceId；补全 `logging.pattern.console` 含 `%X{traceId:-}` 后全量重构建重启，验证跨域透传（pricing.log 带 `[fe0e0b41…]`，admin logs?traceId 命中）。
- **数据保护**：全程未删除/重置 data/ 与 H2 库；播种只增不删。新增种子 P4 小雨/P5 小风、D6 快车张/D7 专车李/D8 出租王（各 +¥200）；O26 PAID+评价+分享、O27 PAID、O20 强制 CANCELLED。

### 17.2 历史验证（v1.0.0–v1.2.0，2026-11-16）

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

**v1.3.0 已落地**：司机抢单 CAS + BUSY 回收（L2）；全量账面核对 audit（ledger 回放 vs wallet 余额）；grab/assign 双模派单（评分=0.6 距离+0.3 服务分+0.1 拒单）+ 邀约 TTL + 拒单顺延 + 超轮次 EXPIRED；轨迹上报落库 + 轨迹里程计价 + 分车型/高峰/远途费率（L5）；nearby 包围盒预过滤（L3 部分缓解）；管理端 token 鉴权 + 分享 HMAC 签名（L8 部分）；只读钱包（L6）；WS 空闲检测 + 推送审计。

**后续方向**（受零外部依赖演示约束，以下为放开约束后的演进）：

1. **LBS 索引**：Geohash 分桶 / H2 空间索引替换内存 haversine 精算（L3 彻底解决）；接路径规划路网里程替换直线/轨迹估算。
2. **推送升级**：离线信箱 + 重连补投 + 多端广播（L4，注册表改 key→Set<Channel>），事件级审计回放。
3. **司乘身份绑定**：司乘登录 token + 身份绑定鉴权（L8'），杜绝任意客户端操作任意司乘 ID。
4. **存储扩展**：域库迁移 MySQL/PG 支持多实例水平扩展（L9），订单库按 passenger_id 分片预案。
5. **建单/派单两阶段**：启用 CREATED 落库态（L7），拆分下单与派单事务边界。

## 19. payment-pro 双账本集成（HTTPS OpenAPI 镜像，v1.5.0）

### 19.1 模式与边界

**双账本**：ride-pro 钱包仍是本地资金权威（本地事务/幂等/outbox/对账全部不变）；每笔资金操作在**同一本地事务**内追加一条 `mirror_outbox` 事件，由单线程调度器按 `created_at` 顺序镜像到 payment-pro（商户身份，HTTPS + HMAC 签名 OpenAPI）。payment-pro 宕机/镜像失败**不影响本地资金流**，恢复后自动追平。

- 可选集成：`config/payment-pro.properties` 不存在 → `PaymentProClient.enabled=false`，不产生任何镜像事件，ride-pro 完全 standalone（既有单测/混沌不受影响）。
- 顺序投递保证因果：充值先于支付、支付先于退款；每步 bizNo 确定性幂等（`M-{类型}-{refKey}`，PAY 分成 `PAY-DRV-`、退款追回 `RFD-CLAW-`、退款入账 `RFD-` 前缀）。
- 退款镜像**不走 trade.refund**：ride 退款语义是"司机追回 min(分成,可用) + 平台兜底"，与标准原路退款不同，故按本地实际借贷腿用 `account.transfer` 精确镜像。
- 平台=商户：`SYS_PLATFORM` 直接映射入驻商户账户；payment-pro 支付手续费（fee-bps，入 SYS_FEE）是支付系统营收，核对口径中从平台净额扣除。
- 回调仅加速展示：payment-pro 异步通知 POST `/api/callbacks/payment-pro` → `payment_pro_notify` 留痕（event+bizId 幂等）；终态以镜像回执 + 对账为准。

### 19.2 镜像语义映射

| ride 操作 | payment-pro OpenAPI | 说明 |
|---|---|---|
| TOPUP | account.topup | 流水 credit 腿金额，bizNo=原充值键 |
| PAY | trade.create → trade.pay(BALANCE) → account.transfer | 乘客扣款入商户；商户→司机净额分成；手续费留存商户。pp_order_id/pp_payment_id 落 outbox 断点续传 |
| REFUND | account.transfer ×1~2 | 有 REFUND_DEBIT 腿先司机→商户追回，再商户→乘客全额 |
| WITHDRAW | account.withdraw | 仅提现 SUCCESS 后镜像（D 账户 / C SYS_CASH） |
| TRANSFER | account.transfer | 借贷腿定向划转 |

投递失败退避 `min(30s, 1s<<min(retries,5))`，10 次耗尽置 DEAD；管理端 `GET /api/admin/mirror`（统计）、`/mirror/dead`、`POST /mirror/replay?key=`（重放）、`POST /mirror/close?key=&reason=`（线下处理完毕销账）。

### 19.3 账户映射与对账口径

`payment_pro_account`（user_id→member_id/account_id）懒开户：首次镜像时 `account.open`（name=userId 服务端幂等）+ 落映射 + **pp_balance_snapshot 基线快照**（开户时 pp 余额，平台行在 trade.pay 入账前落快照防重复计入）。

PaymentMonitor 30s **增量核对**：`pp余额 == 基线快照 + 已投递(SENT)镜像的本地流水净额`（平台另扣 pp 手续费，经 trade.query 回查）。兼容接入前已有历史余额的钱包；在途镜像跳过避免竞态；历史无快照行自校准回填。另有镜像 DEAD/积压超龄告警。

### 19.4 HTTPS 与入驻

payment-pro 网关双连接器：HTTP 8080（控制台）不变，新增 HTTPS 8443（run.sh 首次 keytool 自签 RSA2048/PKCS12，SAN=localhost/127.0.0.1，导出公钥 `config/payment-pro-cert.pem`）；ride-pro JDK HttpClient SSLContext 仅信任该证书。签名两侧同构：TreeMap 排序、跳过 null/sign、k=v 以 & 连接、HmacSHA256 小写 hex，timestamp ±5min。

入驻三步：`payment-pro ./run.sh` → `ride-pro ./run.sh` → `ride-pro ./integrate-payment.sh`（注册/复用 ride-pro 商户、开户、拷证书、写配置）→ 重启 ride-pro 生效。

### 19.5 验证记录（2026-11-17）

`verify-integration.py` **15/15**：充值/订单全链路支付/部分退款/提现 三方双账本增量一致、pp 交易单 PAID、回调留痕、**混沌**（停 payment-pro 本地充值正常→重启镜像追平）、ride 账面守恒 + 镜像队列清空 + payment-pro 对账全过。回归：ride-pro 单测 34/34；payment-pro smoke 79/79、verify-pro 22/22（集成改动未破坏两系统既有行为）。

联调修复记录：① trade.create 直返 OrderDTO（非 {order} 包装）客户端解析修正；② 对账由绝对余额改为基线+净额增量口径（历史余额兼容）并计入 pp 手续费；③ 补齐镜像 DEAD 重放/关闭管理端点；④ 提现镜像额受 pp 侧余额约束（接入前历史收入未镜像，超额提现镜像会 DEAD 需人工处理——生产应自接入日起全量镜像）。