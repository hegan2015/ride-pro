# ride-pro 打车系统设计文档

版本 v1.8.0 ｜ 2026-11-18 ｜ 状态：已实现，验证记录见 §17.0、§19.6 与 §20.6

v1.8.0 变更（**Docker 中间件双模式**，详见 §20）：
- **双模式**：默认零依赖（内嵌 ZK + H2 文件库）完全保留；`./run.sh --docker` 切换 claim-shared 容器栈——MySQL 8（3306，ride_* 5 库）+ ZooKeeper 3.9（2182，独立容器与数据卷），docker 模式 9 进程（无内嵌 registry）。`run/mode` 标记供混沌脚本重启继承。
- **方言双兼容**：4 处 MERGE→INSERT...ON DUPLICATE KEY UPDATE；5 个 nextId 改 XML `databaseId="h2"/"mysql"` 双语句（注解与 XML 不能共享 statement id）；MySQL 序列=sequences 表+nextval() 函数（原子行锁+连接局部，deploy/mysql-init.sql root 建）；每域 schema-mysql.sql 终态建表；Hikari 池 5/域（共享 MySQL max_connections=400）。
- **数据迁移（方案B）**：deploy/MigrateH2ToMySQL.java 把现有 H2 数据只读迁往 MySQL（17 表行数比对+序列 base_value−increment 校准，可重跑）；**H2 文件原样保留**，随时可回内嵌模式（数据为迁移时点快照）。
- **运维注册**：ops-pro Registry 新增 zookeeper-ride、mysql usedBy+ride-pro；ops 停应用超时 60s→240s（ride 10 进程逐个优雅停最坏 200s，曾超时杀脚本留孤儿进程）。
- **密钥出仓库**：~/.config/ride-pro/docker.env（600），仓库只留 config/docker.env.example。
- **验证**：单测 **41/41**；chaos **60/60**（docker）；verify-integration **24/24**（ride×pp 双 docker，F 段 pp 重启自动继承 --docker）；浏览器三端迁移数据实测；ops 中间件/应用启停 + 内嵌模式回归全过（§20.6）。

v1.7.0 变更（充值/提现升级为**资金单**，详见 §19.3 与 payment-pro DESIGN §17）：
- **充值/提现都有交易单+支付单**：镜像由 `account.topup`/`account.withdraw`（仅账务分录）切换为 `fund.topup`/`fund.withdraw`——payment-pro 走完整 TCC 生成交易单（biz_type=TOPUP/WITHDRAW）+ 支付单（SYS_FUND 系统资金商户、fee=0、BALANCE），与商户交易 PAY 同构。回执由账务分录 id/refKey 统一改为 **pp 支付单 id**，落 `ledger.pp_receipt`。
- **对账口径不变**：资金单账务 bizNo=支付单 id、refId=交易单 id，借贷净额与旧 account.topup/withdraw 完全一致，ride 增量核对与 pp 五方对账自然通过（资金单天然满足结算单/交易单 PAID/借贷守恒，BALANCE 免渠道_capture）。
- **验证**：verify-integration.py **24/24**（[A]/[D] 增校验资金单 TOPUP/WITHDRAW 交易单 PAID + 支付单）；单测 **19/19**（同步回执 7，提现回执断言 pp 支付单 id）。

v1.6.0 变更（双账本由异步镜像升级为**同步调用→回执→落流水**，详见 §19）：
- **同步回执流程**：资金操作先在本地事务落 **PENDING 流水**（不动账）+ 冻结负向腿 + mirror_outbox(NEW)，随后**同步调用** payment-pro OpenAPI；拿到回执后第二个事务确认动账、流水转 **CONFIRMED** 并回填 `pp_receipt`。彻底取代 v1.5.0「先动账再异步镜像」的火忘模式。
- **三态终局**：pp 业务拒绝（4xx）→ 流水 **FAILED** + 解冻退回（提现单同步置 FAILED，根治历史超额提现 DEAD）；通信异常/风控（不确定）→ 留 **PENDING**，MirrorDispatcher 宽限 15s 后按退避续传确认，耗尽置 DEAD 可人工重放/关闭。
- **权威口径收紧**：isPaid/isRefunded/审计/统计/退款校验/日累计一律只认 CONFIRMED 流水；PENDING 不计入余额与对账。
- **trade.create 幂等**：payment-pro 新增 outTradeNo（merchant_id+out_trade_no 唯一索引），重试不再产生孤儿 CREATED 单。
- **管理端**：资金流水表新增「状态」（已确认/待回执/已失败徽章）与「支付回执」两列。
- **验证**：verify-integration.py **22/22**（含宕机 PENDING→恢复 CONFIRMED 混沌）；单测 **19/19**（一致性 12 + 同步回执 7）。

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
| 零外部部署依赖 | **默认模式**注册中心用**内嵌 ZooKeeper**（进程内启动），数据库用 **H2 文件库**（每域独立库文件），无需安装任何中间件；可选 `--docker` 模式复用 claim-shared 容器 MySQL/ZK（§20，用户拍板的显式例外，默认行为不变） |
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

`ledger_entry` 每笔充值/扣款/入账留痕，`balance_after` 为该笔后钱包余额，可按 user_id / order_id 索引回放对账。v1.6.0 增 `status`（PENDING/CONFIRMED/FAILED，默认 CONFIRMED）与 `pp_receipt`（支付回执）两列：**集成模式下流水先落 PENDING（不动账），凭 pp 回执确认后才转 CONFIRMED 并回填 balance_after/pp_receipt**；`isPaid`/审计/统计/退款校验只认 CONFIRMED 腿（详见 §19.2）。standalone 模式仍同事务直接落 CONFIRMED。

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
| balance_after | BIGINT | NOT NULL | 该笔后余额（仅 CONFIRMED 有效，PENDING/FAILED 前端显示 --） |
| request_id | VARCHAR(64) | 可空 | 幂等键回溯 |
| status | VARCHAR(16) | NOT NULL DEFAULT 'CONFIRMED' | v1.6.0：PENDING（待回执）/ CONFIRMED（已确认动账）/ FAILED（pp 拒绝退回）；权威查询只认 CONFIRMED |
| pp_receipt | VARCHAR(64) | 可空 | v1.6.0：payment-pro 回执（充值=分录 id / 支付=ppPaymentId / 退款=RFD-键 / 提现=bizNo） |
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
./run.sh             # 默认零依赖：mvnw package -DskipTests + 内嵌 ZK + H2，启动 10 进程 + 就绪等待
./run.sh --no-build  # 跳过编译
./run.sh --docker    # Docker 模式：容器 MySQL(ride_* 5 库) + 容器 ZK(2182)，9 进程（§20）
./stop.sh            # 依赖序优雅停止，数据保留（两种模式通用）
```

run.sh 流程：JAVA_HOME 优先 corretto-18（release=17；默认 PATH 的 java 26 不可用于构建）→ mkdir run/logs/data → 写 `run/mode`（embedded/docker，供混沌脚本重启继承）→ registry 先启并等 8093/health（60s，**docker 模式跳过内嵌 registry**，改探测容器 ZK 2182 ruok/imok 与 MySQL 3306 可达）→ 8 域顺次启动（不等就绪）→ gateway 启动等 8090（90s）→ 轮询 /api/health 的 allOk（120×2s）→ 打印三个 URL。pid 落 run/*.pid，日志落 logs/<域>.log。docker 模式先 `set -a; . ~/.config/ride-pro/docker.env` 注入 DB 口令并 `export SPRING_PROFILES_ACTIVE=docker`。

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

## 19. payment-pro 双账本集成（同步调用→回执→落流水，v1.6.0；充值/提现资金单 v1.7.0）

### 19.1 模式与边界

**双账本 + 同步回执**：ride-pro 钱包仍是本地资金权威，但每笔资金操作不再「先动账再异步镜像」，而是**先落 PENDING 流水 → 同步调用 payment-pro → 凭回执确认动账**。本地账本与 payment-pro 账户在回执确认后才同时生效，杜绝「本地已动账、pp 未到账」的窗口。

- 可选集成：`config/payment-pro.properties` 不存在 → `PaymentProClient.enabled=false`，走 standalone 分支（同事务直接动账 + CONFIRMED 流水 + 域 outbox，不产生镜像），既有单测/混沌不受影响。
- 顺序与幂等：每步 bizNo 确定性（`M-{类型}-{refKey}`，PAY 分成 `PAY-DRV-`、退款追回 `RFD-CLAW-`、退款入账 `RFD-` 前缀）；pp 侧 fund.topup/fund.withdraw 按 requestId(=refKey) 幂等（支付单 request_id 唯一 + 交易单 outTradeNo 唯一）、account.transfer/post 按 bizNo 幂等、trade.create/trade.pay 按 outTradeNo 幂等，重试不重复入账。
- 退款镜像**不走 trade.refund**：ride 退款语义是"司机追回 min(分成,可用) + 平台兜底"，与标准原路退款不同，故按本地实际借贷腿用 `account.transfer` 精确镜像。
- 平台=商户：`SYS_PLATFORM` 直接映射入驻商户账户；payment-pro 支付手续费（fee-bps，入 SYS_FEE）是支付系统营收，核对口径中从平台净额扣除。
- 回调仅加速展示：payment-pro 异步通知 POST `/api/callbacks/payment-pro` → `payment_pro_notify` 留痕（event+bizId 幂等）；终态以**同步回执 + 对账**为准。

### 19.2 同步回执三段式（run / confirmMirror / failMirror）

每笔集成资金操作经 `PaymentService.run(bizType, refKey, userId, orderId, legs)`：

1. **阶段 A 本地事务**（不动账）：幂等占位（result_json=null）→ 确保钱包存在 + **冻结负向腿**（可用余额守卫，不足拒绝）→ 落 **PENDING 流水腿** → `mirror_outbox` upsert(NEW, nextRetryAt=now+`SYNC_GRACE_MS`=15s)。
2. **同步执行** `MirrorExecutor.execute(row)`：按类型调 pp OpenAPI（见 §19.3），返回回执字符串。
3. **确认事务** `confirmMirror(row, receipt)`（mirror NEW 守卫）：负向腿 `debitAndUnfreeze`（CAS 行数=0 抛错）+ 正向腿 ensure+credit → 流水 `confirm(balanceAfter, ppReceipt)` 转 CONFIRMED → mirror SENT → 域 outbox（PAY→pay_outbox / REFUND→refund_outbox）→ 提现 CAS PROCESSING→SUCCESS → 幂等结果合并。

**失败分支**：
- `PaymentProRejectedException`（HTTP 4xx 且 error 不含「风控拒绝」）= 终态业务拒绝 → `failMirror`：解冻 PENDING 负向腿 + 流水 `fail` 转 FAILED + mirror FAILED + 幂等 `deletePending`（提现单置 FAILED「支付系统拒绝出金」）。**同键重试**经 mirror upsert 把 FAILED 重置 NEW 重新驱动（旧 FAILED 腿留存，pendingLegs 只取 PENDING）。
- 其它异常（网络/5xx/风控 B0001）= 不确定 → 留 PENDING，抛「支付系统通信异常，流水已受理（PENDING），后台将自动续传确认」；`MirrorDispatcher` 宽限期后 `selectPending` 续传 execute+confirmMirror，退避 `min(30s, 1s<<min(retries,5))`，`outboxMaxRetries` 耗尽置 DEAD。
- 管理端 `GET /api/admin/mirror`（统计）、`/mirror/dead`、`POST /mirror/replay?key=`（重放）、`POST /mirror/close?key=&reason=`（`abandonMirror`：DEAD 守卫 → 回滚本地解冻+流水 FAILED → mirror CLOSED 销账）。

**权威口径**：`ledger_entry.status` ∈ PENDING/CONFIRMED/FAILED（旧数据默认 CONFIRMED）；isPaid/isRefunded/audit/stats/退款累计校验/日累计风控全部 `AND status='CONFIRMED'`。PENDING 不计余额、不入对账。

### 19.3 镜像语义映射与回执

| ride 操作 | payment-pro OpenAPI | 回执（落 ledger.pp_receipt） |
|---|---|---|
| TOPUP | fund.topup（资金单：交易单 TOPUP + 支付单，SYS_FUND/fee=0/BALANCE） | pp 支付单 id |
| PAY | ensureAccount(乘客/司机/平台) → trade.create(outTradeNo=refKey) → trade.pay(BALANCE) → account.transfer(PAY-DRV-) | ppPaymentId（pp_order_id/pp_payment_id 落 mirror 断点续传） |
| REFUND | account.transfer ×1~2（RFD-CLAW- 司机追回 + RFD- 乘客入账） | `RFD-{refKey}` |
| WITHDRAW | fund.withdraw（资金单：交易单 WITHDRAW + 支付单，D 账户 / C SYS_CASH），**成功路径同步先扣 pp 再 debitAndUnfreeze** | pp 支付单 id |
| TRANSFER | account.transfer（借贷腿定向划转） | refKey |

提现受理仍只冻结（不变）；模拟渠道按 bizNo 哈希确定性失败（withdrawFailBps，不变）。集成模式下渠道成功后**同步**调 pp fund.withdraw：pp 拒绝 → 提现单 FAILED + 解冻（根治 v1.5.0 历史超额提现镜像 DEAD）；不确定 → 留 PENDING 由 WithdrawProcessor/MirrorDispatcher 续传。充值/提现资金单的账务 bizNo=pp 支付单 id、refId=pp 交易单 id（不再是 ride bizNo），ride 侧凭 outTradeNo=refKey 在 pp 交易单列表反查资金单。

### 19.4 账户映射与对账口径

`payment_pro_account`（user_id→member_id/account_id）懒开户：首次执行时 `account.open`（name=userId 服务端幂等）+ 落映射 + **pp_balance_snapshot 基线快照**（开户时 pp 余额，平台行在 trade.pay 入账前落快照防重复计入）。

PaymentMonitor 30s **增量核对**：`pp余额 == 基线快照 + 已确认(SENT 镜像的 CONFIRMED 腿)净额`（平台另扣 pp 手续费，经 trade.query 回查）。只统计 CONFIRMED 腿，PENDING/FAILED 不计；兼容接入前历史余额；在途镜像跳过避免竞态；历史无快照行自校准回填。另有镜像 DEAD/积压超龄告警。

### 19.5 HTTPS 与入驻

payment-pro 网关双连接器：HTTP 8080（控制台）不变，HTTPS 8443（run.sh 首次 keytool 自签 RSA2048/PKCS12，SAN=localhost/127.0.0.1，导出公钥 `config/payment-pro-cert.pem`）；ride-pro JDK HttpClient SSLContext 仅信任该证书。签名两侧同构：TreeMap 排序、跳过 null/sign、k=v 以 & 连接、HmacSHA256 小写 hex，timestamp ±5min。

入驻：`payment-pro ./run.sh` → `ride-pro ./integrate-payment.sh`（注册/复用 ride-pro 商户、开户、拷证书、写配置）→ `ride-pro ./run.sh`。**清库重建后须先删除旧 `config/payment-pro.properties`**（旧 merchant/account/appId 已失效，脚本检测到文件存在会跳过入驻）。

### 19.6 验证记录（2026-11-17，v1.7.0）

`verify-integration.py` **24/24**：[A] 充值同步回执（双账本增量一致 + 流水 CONFIRMED 带 ppReceipt + **pp 资金单 TOPUP 交易单 PAID + 支付单** + 账务流水按 refId 可见）；[B] 订单全链路支付三方收敛 + pp 交易单 PAID + 平台抽成扣手续费；[C] 部分退款司机追回+平台兜底；[D] 提现同步出金 SUCCESS + **pp 资金单 WITHDRAW 交易单 PAID + 支付单** + pp D 向流水；[E] 回调留痕；[F] **混沌**（停 pp → 充值返回 PENDING 且钱包不动账 + PENDING 留痕 → 重启续传 CONFIRMED 双账本一致）；[G] ride 账面守恒 + 镜像队列清空 + pp 对账全过（结算 outbox 异步在途，对账轮询等待收敛）。单测 **19/19**（PaymentConsistencyTest 12 + PaymentSyncReceiptTest 7：充值回执确认/拒绝终态+可重试、支付三腿确认+outbox、通信失败 PENDING→Dispatcher 恢复、提现拒绝 FAILED 解冻、提现回执 SUCCESS 断言 pp 支付单 id、退款追回+乘客入账）。管理端流水「状态/支付回执」列浏览器实测渲染真实回执（充值/提现显示 pp 支付单 id）；jar 静态资源与源码一致；三端 H5 正常加载。

联调修复记录：① 拒绝后同键重试撞 mirror_outbox 旧 FAILED 主键 → run 阶段 A 改 `upsert`（MERGE KEY(biz_no)）重置 NEW 重新驱动；② Mockito 对已 thenThrow 的 mock 重桩用 `doReturn().when()`（when() 会触发旧桩泄漏异常）；③ trade.create 增 outTradeNo 幂等消除重试孤儿单；④ 提现成功路径同步先扣 pp，pp 拒绝即 FAILED 解冻，根治历史超额提现 DEAD。

---

## 20. Docker 中间件双模式（v1.8.0）

### 20.1 模式与范围

用户拍板（2026-11-18）：ride 对接**已有 claim-shared Docker 栈**，范围 **ZK + MySQL**，数据**方案B 迁移现有 H2**。**默认零依赖模式完全保留**（内嵌 ZK + H2），`--docker` 为显式切换，不是替换。

| 项 | 默认（embedded） | Docker 模式 |
|---|---|---|
| 启动 | `./run.sh` | `./run.sh --docker`（可叠 `--no-build`） |
| 注册中心 | 内嵌 ZK（2182/8093，ride-registry 进程） | 容器 `claim-shared-zookeeper-ride`（2182→2181，独立数据卷） |
| 数据库 | H2 文件库 data/*.mv.db | 容器 MySQL 8（3306，ride_passenger/driver/order/payment/location 5 库） |
| 进程数 | 10 | 9（无 ride-registry） |
| profile | 无 | `SPRING_PROFILES_ACTIVE=docker`（application-docker.yml 覆盖数据源/注册中心） |
| 模式标记 | run/mode=embedded | run/mode=docker（chaos-test.sh 重启 order 时继承） |

compose 权威定义在 **ops-pro/deploy/middleware-compose.yml**（与 claim/cs/payment 共享同一 MySQL 实例，单实例多库；ZK 打车独立容器，与 payment 的 2181 错开）。**停止不删数据**；删卷/重置须用户明确确认。密钥 `~/.config/ride-pro/docker.env`（目录 700/文件 600：DB_HOST/DB_PORT/DB_USER/DB_PASSWORD），仓库只留 `config/docker.env.example`。

### 20.2 SQL 方言双兼容（同一套代码跑 H2 与 MySQL）

- **MERGE→ODKU**：4 处 upsert 改 `INSERT ... ON DUPLICATE KEY UPDATE col=VALUES(col)`（H2 MODE=MySQL 与 MySQL 8 均支持）：driver_location、idempotent_record（order/payment 两域）、mirror_outbox。
- **序列 nextId**：MyBatis **注解与 XML 不能共享 statement id**（databaseId 覆盖仅 XML-vs-XML 生效）→ 5 个 Mapper 接口（Passenger/Driver/Order/Ledger/WithdrawOrder）移除 `@Select`，改 `resources/mapper/*.xml` 双语句：`databaseId="h2"` 走 `SELECT NEXT VALUE FOR seq_x`，`databaseId="mysql"` 走 `SELECT nextval('seq_x')`；4 个 Application 注册 `VendorDatabaseIdProvider`（H2→h2、MySQL→mysql），`mybatis.mapper-locations=classpath:mapper/*.xml`。location 域无序列不改。
- **MySQL 序列仿真**：`sequences(name PK, val)` 表 + `nextval()` 函数——`UPDATE sequences SET val=LAST_INSERT_ID(val+1) WHERE name=seq; RETURN LAST_INSERT_ID()`，行锁原子 + LAST_INSERT_ID 连接局部，并发安全。函数由 **deploy/mysql-init.sql 以 root 建**（需 `log_bin_trust_function_creators=1`，compose 已持久化该参数）；ride 账号库级 ALL 含 EXECUTE。**种子 val=0**：首次 nextval=1，与 H2 `START WITH 1` 首调一致。
- **建表**：每域 `schema-mysql.sql` 终态 `CREATE TABLE IF NOT EXISTS`（含全部历史增量列与内联索引；MySQL 索引不支持 IF NOT EXISTS，CLOB→TEXT），`spring.sql.init.schema-locations` 按 profile 切换；H2 路径沿用原 schema.sql 幂等增量。
- **连接池**：共享 MySQL `max_connections=400`（compose 持久化），ride 每域 Hikari 池 **5**，与 claim/cs/payment 同实例共存（实测总连接约 46）。JDBC URL `useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai`，mysql-connector-j 8.3.0（Boot BOM）。

### 20.3 注册中心与健康探测

docker 模式 Dubbo registry 指向容器 ZK 2182；gateway `registry.health-url: zk://127.0.0.1:2182`，HealthService 对 `zk://` 前缀走 **TCP 四字探针**（connect 1.5s → 写 `ruok` → 读回含 `imok`），其余沿用 HTTP /health（内嵌模式 8093）。run.sh docker 分支启动前预检 ZK ruok 与 MySQL 端口可达，缺失即报错退出。

### 20.4 数据迁移（方案B，H2 文件保留）

`deploy/MigrateH2ToMySQL.java`（单文件源码直跑，`java -cp h2.jar:mysql-connector-j.jar deploy/MigrateH2ToMySQL.java`，先导出 docker.env）：

- H2 **只读**打开（`ACCESS_MODE_DATA=r`），17 表逐表 TRUNCATE + 批量 INSERT（500/批，`rewriteBatchedStatements=true`，CLOB→getString），**行数比对**不一致即退出码 1；可重跑。
- 序列校准：H2 2.2 的 information_schema.sequences **无 current_value 列**，`CURRENT VALUE FOR` 又是会话局部——正确口径 **val = base_value − increment**（默认 cache=1），迁后 MySQL  nextval 与 H2 下一值严格衔接（实测 5 域序列 2/3/3/34/4）。
- **H2 文件原样保留**（迁移只读不写）：内嵌模式随时可回，数据为迁移时点冻结快照；两模式数据各自演进，不做双向同步。

### 20.5 运维注册与模式切换（ops-pro）

ops-pro Registry：mysql usedBy +ride-pro（ride_* 按域分库），新增 `zookeeper-ride`（2182，compose 服务 zookeeper-ride）。应用启停经 ops 调 ride run.sh/stop.sh；**ops 停应用脚本超时 240s**（AppService，ride 10 进程逐个优雅停最坏 10×20s=200s；原 60s 曾把 stop.sh 中途杀掉留 6 个孤儿域进程，run.sh 再启时跳过存活进程造成「混合模式」）。

模式切换顺序（**ZK 端口 2182 两模式互斥**）：
- docker→embedded：ops 停 ride → ops 停 zookeeper-ride（释放 2182）→ ops 启 ride（run.sh 默认内嵌）。
- embedded→docker：ops 停 ride → ops 启 zookeeper-ride → `./run.sh --docker --no-build`。
- **勿在 ride 运行时先停 ZK**：业务进程 Dubbo shutdown hook 会阻塞在注册中心销毁上（stop.sh 注释同理），导致停机超时。

### 20.6 验证记录（2026-11-18，v1.8.0）

- **单测 41/41**（全量 `clean package`，H2 路径；payment 两测试类显式 `cfg.setDatabaseId("h2")` + XMLMapperBuilder 装配 XML）。
- **混沌 60/60**（docker 模式，chaos-test.sh 12 节；重启块按 run/mode 继承 docker.env+profile；outbox 断言改 30×1s 轮询消除退避竞态）。
- **verify-integration 24/24**（ride docker × pp docker）：A 充值资金单回执 / B 订单三方收敛+平台抽成扣手续费 / C 部分退款追回+兜底 / D 提现资金单出金 / E 回调留痕 / F 停 pp→PENDING 不动账→重启续传 CONFIRMED / G 两边对账全过。
- **容器 ZK 注册树**：zkCli ls /dubbo 9 个 facade 全注册；jar 内静态页与源码 diff 一致。
- **浏览器三端**（evaluate_script 隔离驱动）：乘客 P1 钱包 ¥5387.80+迁移流水渲染、司机 D1 收入 ¥199.17+11-17 提现记录、管理端账面守恒/outbox 0/服务健康（registry zk:// 探针正常），WS 均实时在线。
- **ops 启停实测**：停 zookeeper-ride → gateway registry.ok=false 正确降级 → 启动恢复 allOk；经 ops 停/启 ride 回归**内嵌默认模式**（10 进程、P1=341336 恰为 H2 冻结快照值，证明读 H2 而非 MySQL）；恢复 docker 后 P1=538780、mirror 0/0、账面守恒。
- **联调修复记录**：① pp 并行 Docker 迁移重新播种致会员名漂移（M2 不再叫 P1），ride 旧映射按名找不到 pp 账户 → 用户拍板删 ride 侧 6 行旧映射（D1/D2/D3/P1/P2，备份 ~/.config/ride-pro/pp-account-mapping-backup-1941.sql，保留 SYS_PLATFORM→商户 A1），懒开户自动重建后 24/24；② verify F 段重启 pp 原不带 --docker 会退回 local → 脚本新增 `pp_docker_mode()` 探测（ps 命令行 -Dspring.profiles.active / 环境变量）自动继承；③ ops stop 超时 60→240s（见 §20.5）；④ pp 商户 appSecret 漂移致签名 400 → ride config/payment-pro.properties 对齐 pp 库值。

## 21. 司乘端体验升级（v1.9.0，对标滴滴）

### 21.1 支撑域 ride-support
新增第 9 个业务域（Dubbo 20910，H2 `data/support` / MySQL `ride_support` 双模式），承载体验与运营层公共能力，交易核心仍在原有各域：
- 表：poi / address / coupon_template / coupon / safety_contact / safety_event / support_config / admin_user / audit_log / invoice / member / im_message / notification / driver_doc / ticket_link。
- 契约：`SupportFacade`（ride-facade/support），网关暴露 `/api/support/*`（司乘）与 `/api/admin/support/*`（管理，受 X-Admin-Token 保护，操作写审计并带 X-Admin-User）。
- 种子：8 个 POI 地标（含拼音别名）、9 项默认配置、默认管理员 admin/admin123（SHA-256 存储，角色 ADMIN/OPS/FINANCE/SUPPORT）。
- MySQL 序列：`deploy/mysql-init.sql` 增 ride_support 段（nextval 函数），建库/授权一次性执行。

### 21.2 三端前端升级（单文件 H5，零外部依赖）
- **乘客端**：地图首页+底部抽屉叫车；POI 搜索联想/常用地址(家/公司)/快捷回家去公司；分车型实时预估+多车型接力呼叫（首选无应答自动切换备选）；优惠券选择/领券中心；司机卡片+接驾 ETA+联系司机 IM(号码保护中转)；安全中心（紧急联系人/SOS/录音/分享/110）；行程偏航守护；标签评价入口、投诉工单(转 cs-pro)、发票、会员积分、消息中心、长辈模式。
- **司机端**：出车/收车大按钮+出车确认；听单偏好(车型/最远接驾/顺路)；语音播报新单(浏览器 TTS)；邀约卡倒计时+抢单/拒绝；导航演示(自动驶向目标+轨迹)；到达等待计时；结算收入(80%)；收入趋势图/服务分分项/奖励任务(积分)；合规证件提交审核；疲劳提醒(连续在线>4h)；热区/接单指引；联系乘客 IM；紧急求助。
- **管理端**：在原 6 页签基础上新增 8 页签——双账本镜像(统计/DEAD 重放/销账关闭)、用户与司机(列表+证件准入审核)、配置中心、优惠券(模板/已发券)、风控安全(告警总览+安全事件处置)、客服工单(ride linkage+cs 深链)、权限审计(RBAC 登录/管理员/操作审计)、报表趋势(近 7 日订单/GMV 双柱图+订单 CSV)。

### 21.3 客服配套（cs-pro）
乘客投诉经 `CsTicketClient` 以 X-Open-Key 签名转 cs-pro `POST /api/open/tickets`（requestId 幂等、source=ride-pro、类别映射到 cs 限定枚举）；cs 不可达时本地 linkage 受理并通知"恢复后自动同步"。cs-pro 侧新增 open 收单/回查端点、admin 工单页签、agent 工单池 ride 上下文（见 cs-pro README §7.1）。ride 侧 open key 存于 support_config `support.csOpenKey`（管理端脱敏显示）。

### 21.4 验证记录（2026-11-19，v1.9.0）
- **单测 41/41 全绿**（common/pricing/order/payment），全模块 clean package 通过（JDK corretto-18）。
- **支撑域闭环 curl**：POI 搜索/配置脱敏/RBAC 登录/券模板创建→发券→最优券→核销/安全事件/IM/工单离线降级与在线转发（cs T27，source=ride-pro）。
- **行程端到端**：O30 叫车→抢单→到达→开始→结束→支付→PAID；期间 pp 并行重启致镜像短暂 PENDING，恢复后自动续传确认（mirror pending0/dead0），验证两阶段续传韧性。
- **浏览器三端**（evaluate_script 驱动）：乘客搜索→报价→我的/券/安全/钱包余额联动；司机收入图表/服务分/任务/证件弹层；管理端 8 新页签真实数据加载+报表图表渲染；无 console 报错。
- **已知边界**：真实地图瓦片/导航/虚拟号拨打/录音为演示联动（事件落库供管理端处置），未接外部 SDK；pp 重播种致 D2/D3/P2 双账本基线漂移（pp 余额 20000 vs 期望 40000）属并行会话数据问题，待对齐，非本次升级引入。

### 21.5 第二/三批 P0/P1 补齐（2026-11-19 续）
- **标签化评价+匿名**：RatingRequest/RatingDTO/order_rating 增 tags/anonymous；common.js ratingModalTags（星级+标签 chips+匿名）；司乘端接入；ratingsHtml 展示标签/匿名。修复 RatingMapper 派生表缺别名（MySQL 方言，H2 容忍）。
- **司乘封禁管理**：passenger/driver 增 blocked；PassengerFacade/DriverFacade.setBlocked；司机封禁强制下线、出车/叫车服务端拦截；管理端用户/司机表封禁按钮。
- **RBAC 角色拦截**：SupportFacade.adminByUsername；AdminSupportController 按 X-Admin-User 角色拦截（配置/管理员/证件审核=ADMIN，工单/安全事件=ADMIN|SUPPORT，券模板=ADMIN|OPS），未知账号拒绝（403 A0301）。
- **配置真正生效**：订单域读 support config——取消费 cancel.feeAmount/freeWindowSec、派单 dispatch.mode/radiusKm/offerTtlSec、司机等待补偿 cancel.freeWaitSec；计价域读 pricing.<svc>.baseFee/baseKm/perKmFee/perMinFee/minFare 与 pricing.surgeEnabled（字段注入、不可达回落本地默认，单测不受影响）。实测 fee 300→500、baseFee 1300→2000 生效。
- **多车型真·同呼（服务端接力）**：CallRequest.serviceTypes；ride_order.fallback_types；超轮次无应答自动 switchService 接力备选（重新计价+重置派单+SERVICE_FALLBACK 推送），无备选才 EXPIRED。实测 PREMIER→EXPRESS 接力成功。
- **修改目的地**：OrderFacade.modifyDest（ACCEPTED/ARRIVED/IN_PROGRESS 重新计价更新预估+DEST_CHANGED 推送）；乘客端「改目的地」入口。实测预估 3028→3922。
- **司机等待超时补偿**：ARRIVED 超 freeWaitSec 后司机取消向乘客收取消费补偿司机。实测 fee=300。
- **司机口碑/等级/消息/证件到期**：OrderFacade.driverRatings 聚合乘客评价；司机端我的口碑/等级权益/消息中心/证件到期提醒/自动抢单/邀约方向预览。
- **乘客搜索历史/上车点推荐/动态调价标签**：localStorage 搜索历史 chips；最近地标上车点推荐采纳；peakRate>1 显示高峰系数标签。
- **管理端批量处置/对账报表**：订单多选+批量强制取消/批量全额退款；报表页增账面核对对账表。
- **验证**：41/41 单测、verify-integration 24/24（两批复跑）、上述 curl 闭环与浏览器三端抽查全过。

### 21.6 第四批 P1/P2 扫尾（2026-11-19 续）
- **车型扩充+拼车**：pricing 增 CARPOOL/TEHUI/YOUXIANG/LUXURY 费率；派单与 accept 统一 serviceMatches（拼车/特惠→快车司机，优享/豪华→专车司机）；乘客端 7 车型多选同呼。实测 CARPOOL 报价 1888、快车司机接拼车单成功。
- **舒适偏好/加价调度/支付渠道**：order 增 comfort/tip_fee/pay_channel；CallRequest 携带；完单加价经 payment transfer 划转司机（TIP_CREDIT/TIP_DEBIT 流水）；pay?channel= 标记渠道（非钱包为演示标记）。实测 tip=500 落账、channel=ALIPAY。
- **打赏**：乘客 PAID 后打赏 ¥5 经 /api/wallets/transfer（TIP）划转司机。
- **退款审批流**：support refund_request 表 + 申请/审批端点；批准即执行订单域退款并置 EXECUTED；管理端订单详情「走审批退款」+ 审批列表批准/驳回。实测批准后退 100 落账。
- **排队信息/GPS/剩余 ETA**：派单轮次+等待时长展示；navigator.geolocation 真实 GPS 设起点；行程中剩余里程/ETA 客户端计算。
- **真实录音**：司乘 MediaRecorder 麦克风录音（浏览器原生，零依赖），安全中心/导航设置可回放；事件落库。
- **司机安全/运营**：偏航检测、超速提醒、导航转向提示、出车人脸核验（演示确认+事件）、安全培训考试（≥2/3 通过+积分）、充电/加油 POI 导航、机场蓄车池排队位次、邀请司机积分、高峰时段奖。
- **乘客营销/无障碍/多语言**：活动 banner（support.campaign.* 配置驱动）、邀请有礼积分、高对比度无障碍模式、中英切换（关键文案）。
- **管理端**：实时运力大屏（在线司机位置 5s 刷新）、活动配置卡、退款审批列表。
- **验证**：41/41 单测、verify-integration 24/24（三批复跑）、curl 闭环（拼车/加价/渠道/审批/能源 POI）+ 浏览器三端抽查全过。
- **剩余边界（需外部 SDK/真实通道，未接）**：真实地图瓦片、真实接驾/送驾导航 SDK、虚拟号真实拨打、微信/支付宝/银行卡真实收单、人脸核验活体 SDK、城市多城市开通、报表定时订阅。其余 P0/P1/P2 均已落地。

### 21.7 地图底图高德化（2026-11-19 续）
- **拍板**：「地图瓦片不收费就接高德，其他先不做」。栅格瓦片免费、无需 key，故落地；官方 JS API/导航 SDK 需注册 key（个人免费配额、商用需授权）本次不接。
- **MapView 升级**（common.js，各页零改动）：高德免 key 栅格瓦片底图（webrd0X.is.autonavi.com，style=8 浅色路网）+ Web Mercator 投影替换原线性 bbox 投影；按 canvas 尺寸自动选 zoom（3–17）并居中 BBOX；retina 降采样保清晰；`crossOrigin=anonymous`（瓦片带 `Access-Control-Allow-Origin: *`，canvas 不被 taint，可像素读回）；模块级瓦片缓存跨实例复用；瓦片加载失败回退原网格底图（离线零依赖仍可运行）；右下角 © 高德地图 attribution。标记/轨迹/点击取坐标 API 保持不变。
- **覆盖四面**：乘客首页地图、司机首页地图、管理端实时运力大屏、订单抽屉轨迹小地图（行程分享页同 MapView）。
- **验证**：41/41 单测、verify-integration 24/24；浏览器结构化验证（面板隐藏改像素/状态读回）：乘客 35/35 瓦片 z11、467 种颜色（高德浅色路网配色）、投影往返精确、点击取坐标=BBOX 中心；司机 35/35；大屏 18/18 z10 + 9 个司机标记；抽屉 18/18 + 轨迹折线；应用无 console 错。
- **配套（pp 鉴权升级适配）**：pp 并行会话上线控制台 Bearer 会话鉴权（/api/** 除 health/login/logout 均需登录+权限点）。verify-integration.py 补 pp 登录态：口令读 `PP_CONSOLE_PASSWORD` 或 `~/.config/ride-pro/pp-console.env`（600，仓库不落口令），全部 PAY 请求带 `Authorization: Bearer`。ride→pp 镜像走 /openapi HMAC 独立链，不受控制台鉴权影响（双账本红线完好）。
- **剩余边界更新**：真实地图瓦片已接（免费栅格底图）；仍余真实导航 SDK、虚拟号真实拨打、真实收单、活体人脸 SDK、多城市开通、报表定时订阅。

### 21.8 地图点选定位详细位置 + 真实地名（2026-11-19 续）
- **需求**：地图选取起终点时放大定位到详细位置，并把「地图起点/地图终点」改为真实地名。
- **MapView 交互升级**：点击取点自动 `focus(lat,lng,16)` 放大到街道级；新增画布控件 +/−/⌂（缩放/复位全城 BBOX）与滚轮以光标为锚缩放（zoom 3–18）；控件命中优先于取点点击；`resetView()` 回退_fit 视图。
- **逆地理编码（免费免 key，多源回退）**：Photon（OSM，街道/POI/门牌级、中文）→ BigDataCloud（区级粗粒度，名称加「附近」）→ 本地 PRESETS 地标「XX附近」→ 坐标文案；6s AbortController 超时；成功缓存 + 并发去重、失败不缓存以便重试；离线零依赖兜底仍可用。通道实测：Photon 间歇可达、BigDataCloud 需跟随重定向、Nominatim 不可达、高德 restapi regeo 需 key（未用）——故做多源回退。
- **乘客端**：点选/GPS 设点后先「定位中…」再回填真实地名到 originTxt/destTxt/searchDestTxt 与 origin/dest.name（下单携带真实地名）；**司机端**：点选后 home-top 新增 posPill「📍 真实地名」+ pos.name 同步。
- **验证**：41/41 单测、verify-integration 24/24；浏览器实测：乘客点选 z16 + 30 张 z16 瓦片 + 「锦鸿阁」回填、滚轮 16→17、⌂ 复位 z11/center=null、终点双文案回填；司机 posPill「📍 锦鸿阁」；模拟 fetch 拒绝→「国贸附近」/「地图选点(坐标)」；恢复后街道门牌级「北四环西路辅路46号」；管理大屏 18/18 瓦片 + 3 控件 + 9 标记；无 console 错。

### 21.9 接单后车辆行驶轨迹全程可见（2026-11-20）
- **问题**：接单后看不到车辆行驶轨迹。根因三处：司机导航演示需手动开启（接单后车不动、无轨迹产生）；乘客端仅 IN_PROGRESS 画轨迹（接驾段无线）；司机端从不画轨迹线。
- **修复（纯前端，服务端既有机制够用）**：司机 `grab`/`act('start')`/`loadActiveOrder`（重载续驶）自动 `startSim()` 驶向目标（接驾→上车点、开始→终点），导航按钮仍可手动停/开；司机端 3s 上报间隔同步拉 `trip_track` 画蓝色行驶线；乘客端 4s 轮询对 ACCEPTED/ARRIVED/IN_PROGRESS 全程画线（接驾绿 `#00b578`、行程蓝 `#10aeff`），订单终态清空轨迹与取景锁；两端订单激活时自动取景（乘客 z13 起终点/司机质心、司机 z14 当前位置+目标质心）；乘客恢复活跃订单时回填 origin/dest 与起终点标记/文案。location 域接单即逐点落 `trip_track` 并 WS 转发 DRIVER_LOCATION，无需改动。
- **验证**：41/41 单测、verify-integration 24/24；端到端实测（专用身份 P6/D10，避开用户 Chrome 页签同身份并行操作）：接单自动驶向乘客（望京→国贸方向移动）、司机端画线 17 点、重载自动续驶、乘客接驾绿线 17 点 + z13 + 司机标记、开始行程后蓝线 39 点 = 落库 39 点、支付后轨迹清空；标记回填复验「起点/终点/司机」三标记 + 起终点文案；无 console 错。
- **环境教训**：内置浏览器面板隐藏时 Chrome 节流 setInterval（模拟行驶/上报停摆），验证改手动泵送步点；真实用户页签可见不受影响。zsh 不对未加引号变量词分裂（`for + set --` 传坐标致 lat 整串 400）。
## 22. 收银台开放对接（v1.9.2，payment-pro 不自带 C 端 H5 → ride 乘客端渲染）

> 完整对接说明见 **`docs/cashier-integration.md`**（9 方法映射 / 幂等 / 错误 / 沙箱短信 / 视图清单 / 兼容红线）。

### 22.1 分层与新增文件
- `PaymentProClient` **新增 9 个 `cashier*` 方法**，复用既有 TreeMap+HmacSHA256 签名、毫秒 timestamp、HTTPS 自签信任、10s 超时与错误语义（未另起签名实现）。
- `CashierService`（新）：账户映射沿用 `PaymentProIntegration.ensureAccount`（商户侧用既有 `merchantAccountId`）；pp 交易单以既有 `trade.create`（`outTradeNo=PAY-{orderId}`）幂等确保，与镜像路径**同一张单**。
- `MirrorExecutor.executePay` **前置分支**：存在 `cashier_request` 留痕 → `executeCashierPay`（`cashier.payVerified`/`cashier.scanPay`），否则原 `trade.create/trade.pay` 路径**逐字节不变**。
- `PaymentService` **零改动**：收银台受理仍走 `pay()` 的「冻结+PENDING → 同步受理 → 回执 CONFIRMED 动账 → 域 outbox」，BALANCE 同步语义与手续费口径不变。
- 新表 `cashier_request` / `cashier_code`（H2 + MySQL 双 schema，**只 additive**）；`PaymentFacade` 新增 10 方法；`CashierController`（新，`/api/cashier/**`），**WalletController 既有端点零改动**。
- 前端 `static/cashier.js`（新）+ `passenger.html` 支付入口改接收银台（脚本缺失自动退回 `legacyPayOrder`）。

### 22.2 幂等与留痕
- **支付幂等键 `PAY-{rideOrderId}`**，与订单域 `OrderService.payKey()` 一致：收银台先付后订单域 `/pay` 命中幂等（`repeated=true`）**不重复扣款**；反之亦然。
- 付款码键 `CSHC-{orderId}-{type}`（ACTIVE 期间复用同键返同一码，过期/核销后换新键）；验证键 `CSHV-{orderId}-{rand8}` **每次尝试独立**，避免失败会话被幂等复用。
- 留痕：`cashier_request`（幂等键/pp 单号/一次性 verifyToken/**脱敏卡号**/状态）+ `cashier_code`（码归属与状态）+ 既有 `idempotent_record`/`ledger_entry.pp_receipt`/`mirror_outbox`/`pay_outbox`。
- 崩溃恢复：已有 `pp_payment_id` 直接复用；否则同键重放；令牌已核销致 403/409 时以 `trade.query` 终态兜底（`settledPaymentId`）。
- **绝不落库/回显/入日志**：支付口令、短信码、生物令牌、appSecret。

### 22.3 错误与安全
- pp 拒绝异常解析出 `code`/`error` 原样透传（A0400/A0403/A0404/A0409/A0429/B0001/B0003）；`GatewaySupport` additive 补 `A0403→403`、`A0429→429`、`B0003→503`。
- 沿用乘客既有会话（userId），每端点校验**订单归属**；金额/乘客/司机由订单域回填，前端传值忽略（防改额/代付）。
- 短信默认掩码 `******`+`smsCodeMasked`，仅 `sandbox=true` 返明文；网关开关 `gateway.cashier-sandbox-enabled`（生产应置 false）。

### 22.4 乘客端收银台 6 视图 + 零 CDN 二维码
主页（金额/优惠/实付 + 9 方式，RESERVED 灰显带原因）→ 验证（口令/短信/指纹/小额免密，`NEED_SET_PASSWORD`/`LOCKED` 分支）→ 设置口令 → 付款码出示（**内联 SVG 二维码** + displayCode 分组 + 倒计时 + 2s 轮询）→ 扫一扫（parse 确认 → scanPay）→ 结果页（成功/失败/处理中轮询终态 + 重试）。
二维码由 `cashier.js` 内置**纯 JS QR 编码器**（byte 模式 / ECC-L / v1..6 / GF(256) RS / 8 掩码罚分择优 / BCH 格式信息）生成内联 SVG，**全文件零 http(s) 外链**（仅 SVG 命名空间），不引第三方库。

### 22.5 验证（2026-11-20 实测）
- `./mvnw install` / `./mvnw test`：**41/41 单测全过**（含 payment 域 19 项，两处测试仅补 mapper 装配，断言未改）；ride 重启 **9/9 健康**。
- `verify-cashier-ride.py`（新，独立前缀 RDCSH+uuid，不停启任何服务）：**56 通过 / 0 失败**。证据（乘客 P7 / 专用司机 D11）：O68 余额+口令→P1829、O69 银行卡→P1830（cardNo=`**********0202`）、O70 主扫→P1831、O71 被扫内联口令→P1832，均 1611 分且终态 SUCCESS；O72 前端传 amount=1 仍按订单实付 1611 受理。断言含幂等 `repeated=true` 同单不重复扣款、订单域 `/pay` 后余额不动、rideΔ==ppΔ、流水 CONFIRMED 带 `pp_receipt`、码过期 **409**、不存在码 **404**、超免密/越权 **403**、短信默认掩码、账面守恒 + 镜像 0 积压 + pp 对账全过。
- 二维码自检：解析自生成 SVG → 独立解码（格式 BCH + 双副本一致 + 去掩码 + 蛇形取码 + 去交织 + RS 校验子全 0 + 负载逐字节比对），**11/11 通过**（10 组负载 + 零外链）。
- `verify-integration.py` **24/24** 继续全过（既有 8 冻结调用与双账本镜像语义未变）。
- **扫码内容归一**：主扫码 `qrContent` 是扫码 URL（`...?code=MPxxx&amount=…`）而非码值，`normalizeCode()` 从 `code=` 提取；直接拿 qrContent 当码值调 pp 会 404。
- **环境教训**：pp 被 F 段重启后 account→accounting 目录可能 `invokers: 0`，pp `cashier.methods` 返 500 B0002（ride 如实透传，非 ride 缺陷），需等自愈/过稳定性门禁；脚本已加域间边预热 + 瞬时 503 同键重试。新建司机默认 OFFLINE 须先上线；COMPLETED 未支付司机保持 BUSY，故改用专用测试司机。
