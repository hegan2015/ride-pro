# ride-pro 详细设计说明书

版本基线：v1.9.2｜更新：2026-11-20
配套：[需求规格](requirements.md)｜[架构设计](architecture.md)｜[DESIGN.md](../DESIGN.md)｜[收银台对接](cashier-integration.md)

> 分工约定：DESIGN.md §1–§14 为交易核心的字段级字典与代码走读（权威）；本文补齐 **support 域、收银台、配置生效、前端、RBAC、安全与测试** 的详细设计，并给出全量接口/事件/幂等键索引。两者冲突时以代码为准并回修文档。

---

## 1. 域详细设计

### 1.1 passenger / driver
- 表：passenger(id,name,phone,auto_pay,blocked,created_at)；driver(id,name,phone,car_no,car_model,service_type,status,service_score,decline_count,blocked,…)。字段级见 DESIGN §11.1/11.2。
- 守卫：setOnline 对 BUSY 拒绝（A0409）；casStatus(id,from,to) 为抢单前置；blocked=true 时叫车/出车服务端拦截；setBlocked 同时强制司机 OFFLINE。
- 评价驱动：乘客评价 → driver.updateScore 滑动更新服务分；拒单 recordDecline 计数影响 assign 评分。

### 1.2 location
- 表：driver_location(driver_id PK,lat,lng,updated_at) upsert；trip_track(order_id,seq,lat,lng,ts) 逐点 insert，seq=MAX+1。
- report：坐标校验（A0400）→ upsert → 携 orderId 时落轨迹 + pushFacade.pushToPassenger(DRIVER_LOCATION)（尽力而为，失败仅 warn）。
- nearby：包围盒预过滤 + Haversine 半径精算升序；track 按 seq 回放。
- 消费方：司机端 3s 上报；乘客端 4s 轮询 + WS 双通道；完单轨迹计价读 track。

### 1.3 pricing
- 无状态；quote(FareRequest) 支持 distanceKmOverride/durationMinOverride（轨迹计价复用）。
- 费率：EXPRESS/PREMIER/CARPOOL/TEHUI/YOUXIANG/LUXURY 七车型 baseFee/baseKm/perKmFee/perMinFee/minFare + 高峰/夜间时段 + 远途加价 + 规则版本。
- 配置覆盖：SupportFacade 读 `pricing.<svc>.baseFee|baseKm|perKmFee|perMinFee|minFare` 与 `pricing.surgeEnabled`；**null 安全回落 yml 默认**（support 不在线不影响计价）。
- 快照：下单 fare_json、完单 actual_fare_json 落订单，前端 fareHtml 渲染明细。

### 1.4 order（编排中枢）
- 表：ride_order（含 fallback_types/comfort/tip_fee/pay_channel/reminded_at/refunded_total…）、idempotent_record、order_rating（tags/anonymous）。
- 状态机流转与 CAS 守卫：DESIGN §4.2（call/accept/arrive/startTrip/completeTrip/pay/cancel/driverCancel/forceCancel/refund）。新增语义：
  - **接力**：CallRequest.serviceTypes → fallback_types；redispatch 超轮次先 tryServiceFallback→switchService（重报价、轮次清零、推 SERVICE_FALLBACK），无候选才 EXPIRED。
  - **改址**：modifyDest 重计价推 DEST_CHANGED。
  - **渠道**：pay(orderId,channel) 记录 pay_channel（非钱包渠道为标记，真实收单走收银台）。
  - **加价**：completeTrip 内 transfer(TIP, requestId=TIP-{orderId}) 划转司机。
  - **等待补偿**：driverCancel 按 `cancel.freeWaitSec` 计等待补偿。
- 配置读取：cfg/cfgLong/cfgStr 读 support_config（dispatch.mode/radiusKm/offerTtlSec、cancel.feeAmount/freeWindowSec/freeWaitSec），缺失回落 OrderProperties 默认。
- 对账兜底：reconcilePaid 每 5s 扫 COMPLETED 超 10s 宽限订单，以 payment.isPaid（ledger PAY_DEBIT）为证据补完结。
- 退款权威同步：onRefundSucceeded 以 payment.refundSummary 聚合值同步 refunded_total（防部分退款累计漂移）。

### 1.5 dispatch
- 无状态；grab 广播候选（半径内在线+车型 serviceMatches）/ assign 评分 Top1（服务分/拒单/距离）。
- serviceMatches：CARPOOL/TEHUI→EXPRESS 司机；YOUXIANG/LUXURY→PREMIER；其余精确匹配。**派单过滤与 accept 守卫共用同一谓词**（曾不一致致接单 409）。
- 邀约 TTL（offerTtlSec）过期顺延；拒单加入排除名单；DispatchRequest 携带订单快照不反查。

### 1.6 payment（钱包 + 镜像 + 收银台）
- 原子转账：单库本地事务，余额/冻结守卫写 UPDATE WHERE；流水 ledger_entry 同事务（biz_type/requestId/pp_receipt/balance_after）。
- 幂等键体系：

| 键 | 场景 |
|---|---|
| PAY-{orderId} | 行程支付（订单域与收银台共用，repeated=true 不重复扣款） |
| REFUND-{orderId}-{seq} | 部分退款序号幂等 + 累计可退守卫 |
| CANCEL-{orderId} | 取消费划转 |
| TIP-{orderId} | 加价/打赏划转（TIP_CREDIT/TIP_DEBIT） |
| 客户端 bizNo | 充值/提现必填幂等 |
| CSHC-{orderId}-{type} | 付款码（ACTIVE 期复用同键） |
| CSHV-{orderId}-{rand8} | 收银台验证（每次尝试独立，防失败会话被幂等复用） |

- outbox：pay_outbox/refund_outbox 与业务同事务；Dispatcher 1s 投递、退避封顶 30s、DEAD 人工重放；mirror_outbox 承载 pp 镜像续传（upsert 防重试撞主键）。
- 镜像三段式：run（落 PENDING 腿）→ 同步调 pp → confirmMirror（回执 pp_receipt、CONFIRMED 动账）/ failMirror（FAILED 解冻）；权威只认 CONFIRMED；账户映射 payment_pro_account（ensureAccount 懒开户）。
- 提现：withdraw_order ACCEPTED→PROCESSING→SUCCESS/FAILED；受理冻结 frozen_amount；pp fund.withdraw 资金单回执确认终态；限额（单笔/日累计/频次）A0409。
- 收银台（v1.9.2）：CashierService + PaymentProClient 9 个 cashier* 方法（复用既有签名链）；MirrorExecutor.executePay 前置分支 cashier_request 留痕 → executeCashierPay，否则原 trade 路径不变；新表 cashier_request/cashier_code（additive）；**PaymentService 零改动**，BALANCE 同步与手续费口径不变。方法映射见 [cashier-integration.md](cashier-integration.md) §映射表（methods/code.create/code.parse/code.query/scanPay/verify/payVerified/payPassword.set/sms.send + trade.query 结果轮询）。
- 监控：PaymentMonitor 账面审计（balance vs SUM(ledger)）；周期对账不得每轮逐条远程查 pp——不变量缓存 + 按热点限流阈值节流 + 未补全显式跳过。

### 1.7 push
- Netty WS 9101，路径 /ws/{role}/{id} 精确匹配（query 会握手挂起）；IdleStateHandler 空闲检测；PING→PONG。
- ConnectionRegistry 同身份后到覆盖：旧连接先 KICKED 再 close；客户端收 KICKED 停止重连。
- PushAudit 500 条环形缓冲（key/event/orderId/sent）；离线返回成功但跳过投递。
- 事件全集：DESIGN §9.3 + v1.9.x 新增 **SERVICE_FALLBACK**（接力换车型）、**DEST_CHANGED**（改址）。

### 1.8 support（v1.9.0 新增）
- 16 表：poi、address、coupon_template、coupon、safety_contact、safety_event、support_config、admin_user、audit_log、invoice、member、im_message、notification、driver_doc、ticket_link、refund_request。
- SupportFacade（Result<T>）：searchPoi/saveAddress/deleteAddress/createTemplate/issueCoupon/redeemCoupon/bestCoupon/saveContact/deleteContact/raiseEvent/handleEvent/setConfig/login/adminByUsername/saveAdmin/audit/createInvoice/getMember/addPoints/sendIm/notify/markRead/submitDoc/reviewDoc/linkTicket/updateTicketStatus/createRefundRequest/reviewRefundRequest。
- 券：模板 DISCOUNT/AMOUNT；bestCoupon 按报价选最优；redeem 核销幂等。
- 安全：raiseEvent 落库（SOS/SPEED/DEVIATION/RECORDING/FACE…）；handleEvent 管理处置。
- 工单：linkTicket 经 CsTicketClient POST {cs}/api/open/tickets（X-Open-Key、requestId 幂等、source=ride-pro、category 映射计价/投诉/其他）；cs 离线降级本地留痕 + notification；updateTicketStatus 回查同步。
- 退款审批：createRefundRequest → reviewRefundRequest（ADMIN/FINANCE）批准即调 orderFacade.refund 执行后置 EXECUTED（非仅 APPROVED）。
- RBAC：admin_user SHA-256 口令（种子 admin/admin123）；角色 ADMIN/OPS/FINANCE/SUPPORT；网关 AdminSupportController.allowed 逐端点校验，越权 403 A0301。

### 1.9 gateway
- REST 聚合 + 五页面 H5（passenger/driver/admin/index/share）+ cashier.js；AdminAuthFilter（X-Admin-Token 常量时间比较）；每 IP 限流；分享 HMAC-SHA256；TraceWebFilter；GlobalExceptionHandler（RpcException→503、坏 JSON→400）。
- HealthService 按 group 聚合 11 进程探针（含 support 20910）→ /api/health allOk。
- GatewaySupport 错误码→HTTP：00000→200、A0400→400、A0404→404、A0409→409、A0301→403、B0001→400、B0002→500、C0001→502；收银台 additive：A0403→403、A0429→429、B0003→503。

## 2. 接口索引

- **交易核心 Dubbo/REST/WS**：DESIGN §12.1/12.2/12.3（权威全表）。
- **support 公共 REST**（/api/support）：poi、addresses(/{id})、coupons( /best /issue /redeem)、templates、contacts(/{id})、safety/events、config、im、invoice、member( /points)、notifications( /read/{id})、docs、tickets。
- **support 管理 REST**（/api/admin/support，令牌+RBAC）：

| 端点 | 角色 |
|---|---|
| GET templates/coupons/config/audit/tickets/safety/events/refund-requests、POST login | 登录即可（读） |
| POST templates | ADMIN, OPS |
| POST config、admins、docs/{id}/review、passengers/{id}/block、drivers/{id}/block | ADMIN |
| POST tickets/{id}/status、safety/events/{id}/handle | ADMIN, SUPPORT |
| POST refund-requests | ADMIN, OPS, FINANCE |
| POST refund-requests/{id}/review | ADMIN, FINANCE |

- **收银台 REST**（/api/cashier/**，乘客会话+订单归属校验）：methods、code、code/parse、code/{code}、scan-pay、verify、pay、pay-password、sms、result。金额/乘客/司机由订单域回填，**前端传值忽略**（防改额/代付）。
- **管理端既有**：overview/ledger/wallets/audit/outbox(+dead/replay)/push(connections/events)/orders(refund/force-cancel)/logs/seed（DESIGN §12.2）。

## 3. 配置设计

### 3.1 support_config 键（管理端可改、即时生效）

| 键 | 默认 | 消费方 |
|---|---|---|
| dispatch.mode | GRAB | order（GRAB/ASSIGN） |
| dispatch.radiusKm | 5 | order/dispatch 候选半径 |
| dispatch.offerTtlSec | 15 | 邀约 TTL |
| cancel.freeWindowSec | 120 | 接单后免费取消窗口 |
| cancel.feeAmount | 300 | 取消费（分） |
| cancel.freeWaitSec | （yml 默认） | 司机等待免费时长→补偿 |
| pricing.surgeEnabled | false | 高峰溢价开关 |
| pricing.<svc>.baseFee/baseKm/perKmFee/perMinFee/minFare | yml 费率 | 分车型覆盖 |
| safety.recordingDefault | true | 录音默认开关 |
| support.csBaseUrl | http://127.0.0.1:8096 | 客服基址 |
| support.csOpenKey | （租户 key） | 工单 open API 鉴权 |
| support.campaign.text / enabled | — / true | 乘客活动 banner |

读取一律 null 安全回落默认（support 宕机不影响交易）。

### 3.2 环境变量与密钥
- 端口/数据目录：ZK_PORT、ZK_HTTP_PORT、DATA_DIR、GATEWAY_PORT、GATEWAY_DUBBO_PORT、ORDER_DUBBO_PORT、PUSH_DUBBO_PORT、PUSH_WS_PORT。
- 管理令牌 ADMIN_TOKEN（默认 ride-admin）；分享密钥 gateway.share-secret；pp appSecret 与 MySQL 口令在 ~/.config/ride-pro/docker.env（600）；pp 控制台口令 ~/.config/ride-pro/pp-console.env（600，门禁脚本用）；**仓库只留 example，口令不落库/不回显/不入日志**。
- 收银台沙箱开关 gateway.cashier-sandbox-enabled（生产置 false；短信默认掩码 ******）。

## 4. 前端详细设计

### 4.1 结构
单文件 H5（passenger/driver/admin/share/index）+ common.js 组件库 + common.css + cashier.js；网关 jar 内托管（**改前端必须根目录全量 clean package**，否则 jar 内旧页）。

### 4.2 MapView（common.js）
- 投影：Web Mercator（mercX/mercY/invMercY）；默认 fit BBOX（北京 39.80–40.12 / 116.20–116.65）自动 zoom 3–17；focus(lat,lng,z) 覆盖中心/zoom；resetView 回 fit。
- 瓦片：高德 webrd0{1-4} style=8，模块级缓存 AMAP_TILES、crossOrigin=anonymous（ACAO:*，canvas 不 taint）、retina 降采样（dpw=dpr/(dpr≥1.5?2:1)）、onload 重绘、失败回退网格底图；© 高德地图 attribution。
- 控件：画布 +/−/⌂（命中优先于取点点击）+ 滚轮以光标为锚缩放（zoomAt 先取光标地理点再补偿中心）。
- 覆盖层：PRESETS 点、path 折线、markers（白描边+标签白晕）。
- 逆地理：reverseGeocode（Photon→BigDataCloud，6s AbortController，成功缓存+并发去重、失败不缓存）→ placeName（粗粒度加「附近」→PRESETS 0.0004° 内「XX附近」→坐标文案）。
- 轨迹：乘客 4s 轮询 ACCEPTED/ARRIVED/IN_PROGRESS 画线（接驾 #00b578 / 行程 #10aeff）+ 首帧自动取景 z13 质心；司机 3s 上报间隔拉 track 画 #10aeff + 取景 z14；终态 setPath([]) 清线并复位取景锁；WS DRIVER_LOCATION 实时补点。
- 点选：乘客 pickOnMap→focus z16→setPoint「定位中…」→placeName 回填 origin/dest.name 与三处文案；GPS 同链路；司机点选→posPill「📍 地名」。

### 4.3 司机模拟行驶
grab/act('start')/loadActiveOrder 自动 startSim；1s tick 向 simulateTarget（ACCEPTED/ARRIVED→起点，IN_PROGRESS→终点）步进 0.004°，到达阈值 0.0015°；转向 bearing 提示、超速/偏航事件落 support；complete 或收车 stopSimulate；按钮可手动停开。**隐藏页签被 Chrome 节流**（验证环境需手动泵送，真实可见页签正常）。

### 4.4 收银台（cashier.js）
6 视图：主页（金额/优惠/实付+9 方式，RESERVED 灰显带原因）→ 验证（口令/短信/指纹/免密，NEED_SET_PASSWORD/LOCKED 分支）→ 设置口令 → 付款码出示（内联 SVG 二维码+displayCode 分组+倒计时+2s 轮询）→ 扫一扫（parse 确认→scanPay，qrContent 需 normalizeCode 提取 code=）→ 结果页（终态轮询+重试）。
二维码：纯 JS QR 编码器（byte/ECC-L/v1..6/GF(256) RS/8 掩码罚分/BCH 格式信息），**零 http(s) 外链**。视觉对标支付宝：#1677FF 胶囊按钮、6 格密码框、吸底蓝按钮、绿勾。
脚本缺失自动退回 legacyPayOrder（余额支付）。

### 4.5 状态与存储
localStorage 键：ride.passengerId、ride.driverId、ride.adminToken、ride.adminUser、ride.poiHistory、ride.inviteClaimed、ride.inviteClaimedD；设置项（长辈/高对比/语言/自动扣款）同域存储。WS connectWS 收 KICKED 停重连；重连后重同步活跃订单。

## 5. 数据库与迁移设计
- 双 schema 同步：每域 schema.sql（H2）与 schema-mysql.sql（MySQL）+ deploy/mysql-init.sql（建库/序列/nextval/种子）三处一致；变更 additive。
- 序列：MySQL sequences 表 + nextval()；种子 val=0 对齐 H2 首调=1；迁移取 base_value−increment。
- 迁移工具：deploy/MigrateH2ToMySQL.java（H2 只读、17 表行数比对）；H2 文件保留。
- 索引规范：唯一键仅用于真幂等约束；挡合法路径时先盘点其它守卫再降普通索引（幂等、零行改、双 profile、挂既有 SchemaUpgrade）。

## 6. 安全设计
- 鉴权分层：网关管理令牌 → support RBAC → pp OpenAPI HMAC → pp 控制台 Bearer+权限点（ride 不走控制台链）。
- 敏感字段**绝不落库/回显/入日志**：支付口令、短信码、生物令牌、appSecret、MySQL/管理口令；卡号脱敏 `**********0202`；短信默认掩码仅沙箱明文。
- 分享：HMAC-SHA256 token + 脱敏视图；越权校验订单归属（收银台每端点）。
- 防改额/代付：金额/乘客/司机服务端回填，前端传值忽略。
- 限流与防暴力：网关每 IP 每秒；pp 登录失败限流 429。
- 常量时间比较管理令牌防时序侧信道。

## 7. 测试设计
- **单测 41**：order 状态机/接力/计价快照、pricing 费率与覆盖、payment 转账/退款累计/提现冻结/镜像回执/收银台装配、support 券/RBAC 等（surefire 汇总口径）。
- **混沌 chaos-test.sh 12 节 60 项**：SIGSTOP/SIGCONT 支付域崩溃窗口（outbox 1s/对账 5s 自愈）、杀 order 支付/退款窗口、pp 宕机 PENDING 续传、提现状态机、部分退款、风控、免密、DEAD 重放。
- **集成 verify-integration.py 24 项**：充值/订单/部分退款/提现双账本增量一致 + 资金单/回执 + 回调留痕 + 宕机恢复 + 两边对账；pp 控制台 Bearer 登录态（口令仓库外）。
- **收银台 verify-cashier-ride.py 56 项**：9 方式终态 SUCCESS、幂等 repeated、越权 403、码过期 409/不存在 404、掩码、改额防护、对账守恒。
- **浏览器矩阵**：三端 + 管理 14 页签 + 收银台 6 视图 + 地图/轨迹/逆地理；面板隐藏时改结构化验证（像素读回/状态断言），evaluate ≤15s 拆段。
- **门禁红线**：每波改动 verify-integration 24/24 + 健康 allOk；不改库/删断言凑绿；对账全绿仍须语义抽查（共享户按单比、两笔同额核 balanceAfter 差）。