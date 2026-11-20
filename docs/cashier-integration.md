# ride-pro × payment-pro 收银台对接说明（v1.9.2）

> **定位**：payment-pro **不自带 C 端 H5**（`/h5/**` 已摘除）。收银台由 **ride 乘客端渲染**，
> 通过 payment-pro 开放的 9 个 `cashier.*` 商户方法完成「渲染 / 付款码 / 扫码 / 验证 / 受理」。
> 权威契约见 payment-pro `docs/cashier-openapi.md`（只读参照，ride 侧不改 payment 任何文件）。

---

## 1. 分层与文件

| 层 | 文件 | 职责 |
|---|---|---|
| pp 客户端 | `ride-payment/.../client/PaymentProClient.java` | 新增 9 个 `cashier*` 方法，**复用既有 TreeMap+HmacSHA256 签名 / 毫秒 timestamp / HTTPS 自签信任 / 10s 超时 / 错误语义**，未另起签名实现 |
| 编排 | `ride-payment/.../service/CashierService.java`（新） | 账户映射、pp 交易单幂等确保、验证令牌化、留痕、错误码透传 |
| 资金 | `ride-payment/.../service/PaymentService.java`（**未改**） | 仍走 `pay()`：冻结 + PENDING 流水 → 同步受理 → 回执确认 CONFIRMED 动账 → 域 outbox |
| 镜像分支 | `ride-payment/.../service/MirrorExecutor.java` | `executePay` 前置判断：存在 `cashier_request` 留痕 → 走 `executeCashierPay`（cashier.*），否则**原 trade.create/trade.pay 路径逐字节不变** |
| 留痕 | `cashier_request` / `cashier_code`（additive 新表，H2 + MySQL 双 schema） | 幂等键、pp 单号、一次性令牌、脱敏卡号、码状态 |
| Facade | `PaymentFacade` / `PaymentFacadeImpl` | 新增 10 个方法（9 能力 + 受理留痕查询），既有方法签名与语义不变 |
| 网关 | `ride-gateway/.../controller/CashierController.java`（新） | `/api/cashier/**`，**WalletController 既有端点零改动** |
| 前端 | `static/cashier.js`（新）+ `static/passenger.html`（支付入口改接） | 收银台 6 视图 + 纯 JS 二维码编码器（内联 SVG，零 CDN） |

---

## 2. 9 方法映射表（pp method → ride 端点）

| # | payment-pro method | ride 网关端点 | ride facade | 说明 |
|---|---|---|---|---|
| 1 | `cashier.methods` | `GET /api/cashier/methods?userId&orderId` | `cashierMethods` | 返回 pp 裸数据 + `rideOrder`（金额/优惠/预估）+ `walletBalance` |
| 2 | `cashier.code.create` | `POST /api/cashier/code` | `cashierCodeCreate` | `type=USER_PRESENTED`（乘客出示）/`MERCHANT_PRESENTED`（商户出码） |
| 3 | `cashier.code.parse` | `POST /api/cashier/code/parse` | `cashierCodeParse` | 只读解析，不核销；附 `rideOrderId` |
| 4 | `cashier.code.query` | `GET /api/cashier/code/{code}` | `cashierCodeQuery` | 轮询 ACTIVE/CONSUMED/EXPIRED，同步本地留痕 |
| 5 | `cashier.scanPay` | `POST /api/cashier/scan-pay` | `cashierScanPay` | 核销 + 验证 + 幂等支付；校验码与订单绑定 |
| 6 | `cashier.verify` | `POST /api/cashier/verify` | `cashierVerify` | 四型；`pass=false` 返 200 携原因供前端分支 |
| 7 | `cashier.payVerified` | `POST /api/cashier/pay` | `cashierPayVerified` | 验证前置受理（BALANCE/CARD） |
| 8 | `cashier.payPassword.set` | `POST /api/cashier/pay-password` | `cashierPayPasswordSet` | 6 位数字；口令只入不出 |
| 9 | `cashier.sms.send` | `POST /api/cashier/sms` | `cashierSmsSend` | 默认掩码；`sandbox=true` 才返明文 |
| + | `trade.query`（既有冻结方法） | `GET /api/cashier/result?userId&orderId` | `cashierReceipt` | 结果页轮询：ride 留痕 + pp 支付单终态 + 订单状态 |

**签名**：全部经 `PaymentProClient.call()` —— canonical = TreeMap 升序 `k=v&`（剔 `sign`、跳 `null`）→ HMAC-SHA256 小写 hex；
`timestamp` 毫秒；`nonce` 不传（payment 侧允许省略，保持既有兼容口径）。可选参数一旦传入即参与签名（`sandbox` 亦然）。

---

## 3. 幂等策略与留痕

### 3.1 requestId 由 ride 侧确定性生成

- **支付幂等键 = `PAY-{rideOrderId}`**，与订单域 `OrderService.payKey()` **完全一致**。
  - 收银台先付 → 订单域后续 `/api/orders/{id}/pay` 命中 `idempotent_record` 直接返回快照（`repeated=true`），**不重复扣款**；
  - 反之订单域先付 → 收银台 `payResult(orderId)` 非空即幂等回读。
- **pp 交易单**：以既有 `trade.create`、`outTradeNo=PAY-{orderId}`、`subject=ride-{orderId}` 幂等创建。
  收银台与镜像路径**共用同一张 pp 单**，不产生重复订单、不重复受理。
- **付款码幂等键**：`CSHC-{orderId}-{type}`（同订单同类型且码仍 ACTIVE 时复用 → pp 返回同一有效码）；
  码已 EXPIRED/CONSUMED 时追加时间戳换新键重发。
- **验证 requestId**：`CSHV-{orderId}-{8位随机}`，**每次尝试独立**——避免「先输错口令再输对」被幂等复用成旧的失败会话。
  验证不动资金，重复验证无扣款风险。

### 3.2 留痕（沿用既有 outbox / 流水风格）

| 载体 | 内容 |
|---|---|
| `cashier_request`（新） | `request_id`(PK) / order_id / user_id / pp_order_id / pp_account_id / amount / mode(VERIFIED\|SCAN) / method / code / **card_no（仅后四位）** / verify_type / **verify_token（一次性）** / status(NEW→SENT\|FAILED) / pp_payment_id / last_error / 时间戳 |
| `cashier_code`（新） | code(PK) / order_id / user_id / pp_order_id / type / amount / status / request_id / expire_at |
| `idempotent_record`（既有） | `PAY-{orderId}` → 支付结果 JSON（幂等回读权威） |
| `ledger_entry`（既有） | PAY_DEBIT / PAY_CREDIT / PLATFORM_FEE 三腿，PENDING→CONFIRMED，`pp_receipt` = pp 支付单号 |
| `mirror_outbox`（既有） | `M-PAY-PAY-{orderId}`，`pp_order_id`/`pp_payment_id` 断点续传，NEW→SENT |
| `pay_outbox`（既有） | 通知订单域完结订单（收银台支付同样触发） |

**绝不落库**：支付口令、短信验证码、生物令牌、appSecret。

### 3.3 崩溃恢复

`MirrorDispatcher` 重试时凭 `cashier_request` 续传：
1. 已有 `pp_payment_id` → 直接复用，不再调 pp；
2. 否则以同一 `requestId` + 存储的 `verifyToken` 重放 → pp 同键返回同一支付单（`repeated=true`）；
3. 令牌一次性已核销导致 `403/409` → 以 `trade.query` 查该单，若已 SUCCESS/PROCESSING 则取其支付单号按幂等回执续传（`settledPaymentId`），否则保持原拒绝语义。

---

## 4. 错误处理

- `PaymentProClient` 语义不变：4xx（非风控）→ `PaymentProRejectedException`；5xx/超时/风控 → `RuntimeException` 走恢复重试。
- `CashierService.guard()` 捕获拒绝异常，从 `HTTP {status} {json}` 中提取 pp 的 `code`/`error`，
  转 `BizException(code, error)` **原样透传业务码**：`A0400` 参数 / `A0403` 验证无效或超免密 / `A0404` 码或订单不存在 /
  `A0409` 码过期或已核销或订单重复支付 / `A0429` 限流 / `B0001` 业务失败（余额不足/风控）/ `B0003` 熔断。
- `GatewaySupport.statusOf` **additive** 补齐 `A0403→403`、`A0429→429`、`B0003→503`（既有码映射不变）。
- 前端：失败给明确原因 + 「重试支付」；`A0403`/验证类文案自动跳转验证视图；网络异常（无 status）进入**处理中轮询**而非重复提交；
  风控「频繁」提示按窗口等待重试，**不放宽阈值**。
- 受理失败：`cashier_request` 置 FAILED 并记 `last_error`；本地冻结由既有 `failMirror` 解冻、流水 FAILED（不留悬挂资金）。

---

## 5. 鉴权与密钥边界

- 沿用 ride 乘客端既有会话（`localStorage.ride.passengerId` → `userId` 入参），**不引入新鉴权体系**。
- 每个端点做**订单归属校验**：`order.passengerId == userId`，否则 `A0403`。
- **金额 / 乘客 / 司机一律由订单域回填**（`OrderFacade.get`），前端传值忽略 → 无法改额、无法代付他人订单。
- pp `appId`/`appSecret` 仅存于 `config/payment-pro.properties`（由 `~/.config/ride-pro/` 注入），
  只在 `PaymentProClient` 内使用；**不下发前端、不入日志、不拼进 subprocess argv**。
- 卡号：请求方向传入，响应与本地留痕**只保留后四位**（BIN 亦抹除）。

---

## 6. 沙箱短信码用法

```bash
# 默认：掩码（生产口径）
curl -s -X POST http://127.0.0.1:8090/api/cashier/sms \
  -H 'Content-Type: application/json' \
  -d '{"userId":"P1001","orderId":"O1001"}'
# → {"smsCode":"******","smsCodeMasked":true,"expireAt":...,"notice":"验证码经短信网关下发至用户手机…"}

# 联调：沙箱明文（仅测试环境）
curl -s -X POST http://127.0.0.1:8090/api/cashier/sms \
  -H 'Content-Type: application/json' \
  -d '{"userId":"P1001","orderId":"O1001","sandbox":true}'
# → {"sandbox":true,"smsCode":"123456","expireAt":...}
```

- 网关开关 `gateway.cashier-sandbox-enabled`（默认 `true`）：**置 false 后即便前端传 `sandbox=true` 也按掩码下发**，生产应关闭。
- 前端验证页「沙箱取码」勾选框仅在联调使用；**生产链路不得依赖响应取码**。
- 指纹（BIO）为端侧模拟令牌（`BIO-{ts}-{rand}`），由 pp 沙箱平台校验。

---

## 7. 乘客端收银台视图（`static/cashier.js`）

入口：行程 `COMPLETED` 时主按钮「支付车费」→ `openCashier(orderId)`；钱包页另有「🧾 待付行程收银台」。
收银台脚本不可用时自动退回原钱包快捷支付（`legacyPayOrder`），既有钱包/行程功能不受影响。

| 视图 | 内容 |
|---|---|
| ① 收银台主页 | 实付大字 + 订单金额/优惠/加价/预估/收款方/免密限额；9 支付方式（REAL 可选、RESERVED 灰显并展示 `unavailableReason`）；余额显示 pp 账户余额；CARD 选中展开卡号输入；底部「确认支付」+「付款码」+「扫一扫」 |
| ② 支付验证 | 口令 / 短信 / 指纹 / 小额免密四型切换；按 `freeVerifyLimit` 自动决定是否需验证；`NEED_SET_PASSWORD`→设置口令页；`LOCKED`→展示解锁时间并禁用提交；失败展示 `reason` + `remainAttempts` |
| ③ 设置支付口令 | 原口令（首次可空）+ 新口令 + 确认；6 位数字校验 |
| ④ 付款码出示 | **内联 SVG 二维码**（`qrContent`，被扫时等于码值）+ `displayCode` 4 位分组 + 秒级倒计时 + 2s 轮询 `code.query`；CONSUMED→结果页，EXPIRED→提示刷新 |
| ⑤ 扫一扫 | 输入/粘贴商户码 → `code.parse` 展示商户/商品/金额/类型/状态；跨单或码非 ACTIVE 直接拦截；确认后 `scan-pay`（可先验证） |
| ⑥ 结果页 | 成功/失败/处理中三态；金额、方式、**脱敏卡号**、pp 支付单号、幂等键、订单状态、钱包余额；处理中 1.5s 轮询 `/api/cashier/result` 至终态（20 次后提示稍后查看，禁止重复支付）；失败给原因 + 重试 |

**二维码零 CDN 证明**：`cashier.js` 内置纯 JS QR 编码器（byte 模式、ECC-L、version 1..6、GF(256) Reed–Solomon、
8 掩码罚分择优、BCH 格式信息），输出内联 `<svg>`；全文件除 SVG 命名空间 `http://www.w3.org/2000/svg` 外**无任何 http(s) 外链**，
不引第三方库、不发外部请求。自检方式：解析生成的 SVG → 独立解码（格式信息 BCH 校验 + 两份副本一致性 + 去掩码 + 蛇形取码 +
去交织 + RS 校验子全 0 + 字节负载逐字节比对），10 组负载（18 位数字码 / MP 主扫串 / URL / 1~134 字节边界 / 中文 UTF-8）全部通过。

**扫码内容归一（实测踩坑）**：主扫码的 `qrContent` **不等于** `code`，而是扫码 URL
（形如 `https://cashier.payment-pro.example/scan?code=MPxxxx&amount=1611`）；被扫码的 `qrContent` 才等于码值本身。
因此 `CashierService.normalizeCode()` 同时接受「裸码值」与「扫码 URL」——从 `code=` 查询参数提取并 URL 解码，
`code.parse` / `code.query` / `scan-pay` 三个入口统一归一，前端可原样粘贴二维码内容。
直接拿 `qrContent` 当码值调 pp 会返回 `404 A0404 付款码不存在`。

金额全程「分」，展示统一经 `yuan()` 转元。

---

## 8. 兼容红线自检

1. payment-pro **零改动**（仅只读其契约文档）。
2. ride 既有 8 个冻结 OpenAPI 调用（`account.open/balance/transfer`、`fund.topup/withdraw`、`trade.create/pay/query`）**入参与语义不变**；
   收银台只是**更早**以同一 `outTradeNo` 调用 `trade.create`（幂等，同一张单）。
3. **BALANCE 同步语义不变**：pp 受理 → PROCESSING → SUCCESS，无 WAITING/异步回执；手续费 `feeBps→SYS_FEE` 口径不变。
4. 双账本镜像语义不变：本地 PENDING→回执 CONFIRMED，司机分成仍 `account.transfer`（`PAY-DRV-{refKey}`）。
5. schema **只 additive**（两张新表），不 DROP/TRUNCATE/DELETE 既有行；两仓库 data/ 与既有数据未动。
6. `verify-integration.py` 24/24 与既有单测（含 payment 域 41 项）不得回归。

---

## 9. 待 payment 侧/运维补齐（ride 侧无法自行完成）

- `~/.config/payment-pro/sentinel-rules.json` 追加 9 个 `openapi:cashier.*` 的 `paramFlowRules`（additive，`paramIdx=0`=appId）；
  未配置时 Sentinel 放行，机制已接入但阈值待运维回填。
- pp `OpenApiScopes` 若需按方法授权登记 `cashier.*`，请确认 ride 商户 appId 已覆盖（当前实测 9 方法均可调用，appId=appM1）。
- 收款商户须已开户（ride 沿用既有 `merchantAccountId`=A1，已开户）；付款乘客首次使用需引导设置支付口令。

---

## 10. 验证记录（2026-11-20 实测）

| 项 | 结果 |
|---|---|
| `./mvnw install` / `./mvnw test` | **41/41 单测全过**（MoneyUtil 3 + Pricing 11 + OrderStateMachine 8 + PaymentConsistency 12 + PaymentSyncReceipt 7） |
| ride 重启健康 | `/api/health` **9/9 服务 + registry allOk=true** |
| `verify-cashier-ride.py` | **56 通过 / 0 失败** |
| `verify-integration.py` | **24 通过 / 0 失败**（无回归） |
| 二维码独立解码自检 | **11 通过 / 0 失败**（10 组负载 + 零外链检查） |
| 静态资源 | `GET /cashier.js` 200（39664 B）；`passenger.html` 200 且引用收银台 5 处 |

**全链路证据（批次 RDCSH66d31f3f，乘客 P7 / 专用司机 D11）**：

| 场景 | ride 订单 | 金额（分） | pp 支付单 | 终态 |
|---|---|---|---|---|
| 余额 + PASSWORD 验证（payVerified） | O68 | 1611 | P1829 | SUCCESS |
| 银行卡 BANKCARD→CARD（payVerified） | O69 | 1611 | P1830 | SUCCESS，cardNo=`**********0202` |
| 主扫 MERCHANT_PRESENTED（scanPay） | O70 | 1611 | P1831 | SUCCESS，码 CONSUMED |
| 被扫 USER_PRESENTED + 内联口令（scanPay） | O71 | 1611 | P1832 | SUCCESS，码 CONSUMED |
| 改额防护（前端传 amount=1） | O72 | 1611（按订单实付） | — | SUCCESS，pp 单金额=1611 |

关键断言：`requestId=PAY-{orderId}`；重复提交 `repeated=true` 且返回同一支付单；订单域 `/pay` 后余额分毫不动；
乘客/司机 **rideΔ == ppΔ**；本地流水 CONFIRMED 且 `pp_receipt` = pp 支付单号；
付款码 TTL 到期 `code.query=EXPIRED` → `scan-pay` **409 A0409** 且不扣款；不存在码 parse **404 A0404**；
超免密未验证 **403 A0403**；他人订单 **403 A0403**；短信默认 `******`+`smsCodeMasked=true`，`sandbox=true` 返 6 位明文；
口令/卡号全量不回显；ride 账面全部守恒 + 镜像队列 0 积压 0 DEAD + pp 对账全过。

**环境教训（跨泳道）**：payment-pro 被 `verify-integration.py` F 段重启后，其 **account→accounting 的 Dubbo 目录可能出现
`invokers: 0`  stale 状态**，此时 pp `cashier.methods` 返回 **500 B0002**（ride 侧如实透传，非 ride 缺陷）。
需等其自愈（实测数分钟）或过稳定性门禁后再验；ride 侧脚本已加**域间边预热 + 瞬时 503 同键重试**（所有写入口确定性幂等，重试不重复扣款）。
另：新建司机默认 OFFLINE 需先 `/api/drivers/{id}/online?online=true`；行程 `COMPLETED` 未支付时司机保持 BUSY，
测试脚本改用**专用司机**并在接单遇 BUSY 时等待，避免与既有演示司机/其他泳道相互干扰。