# ride-pro 架构设计说明书

版本基线：v1.9.2｜更新：2026-11-20
配套：[需求规格](requirements.md)｜[详细设计](detailed-design.md)｜[DESIGN.md](../DESIGN.md)（权威设计+验证日志）｜[收银台对接](cashier-integration.md)

---

## 1. 系统上下文

```
        乘客 H5 ─HTTP/WS─┐                         ┌─HTTPS OpenAPI(HMAC)─► payment-pro(8080)
        司机 H5 ─HTTP/WS─┼─► ride-pro gateway:8090 ─┤
        管理 H5 ─HTTP────┘        │                ├─HTTP X-Open-Key────► cs-pro(8096) 工单
                                  │                └─被注册/监控───────── ops-pro(8098)
                                  ├─► 高德栅格瓦片 webrd0X.is.autonavi.com（免费免 key，浏览器直连）
                                  └─► 逆地理 Photon / BigDataCloud（免费免 key，浏览器直连，多源回退）
        共享中间件（--docker 模式）：claim-shared MySQL:3306 / ZK:2182 / Redis / RocketMQ / MinIO
```

- ride-pro 是**业务编排方**：支付权威账本在 payment-pro，客服知识/工单权威在 cs-pro，运维控制面在 ops-pro；ride 只持有本地钱包镜像账本与业务库。
- 浏览器直连的第三方仅两类免费只读服务（地图瓦片、逆地理），均有本地降级，不构成运行依赖。

## 2. 架构风格与关键决策（ADR 摘要）

| 决策 | 选择 | 理由 / 拍板 |
|---|---|---|
| 服务拆分 | 按滴滴领域拆 9 业务域 + 网关 + 注册中心，Dubbo RPC | 用户点名对标滴滴领域拆分 |
| 跨域支付一致性 | **outbox + 确定性幂等键 + 周期对账** | 用户在 TCC/Saga/XA/outbox 权衡后拍板；TCC 对短事务收益低、Saga 补偿方向与已履约行程不符、XA/Seata 违反零依赖。**勿再建议替换** |
| 注册中心 | 内嵌 ZooKeeper（默认）/ 容器 ZK（docker） | 零依赖硬约束；Docker 双模式为用户拍板例外 |
| 数据库 | 每域独立 H2 文件库（默认）/ 共享 MySQL 分库（docker） | 零依赖 + 可迁移；双方言同一套代码 |
| 推送 | 进程内 Netty WebSocket | 用户点名 Netty，属允许例外 |
| 双账本 | 同步调用→回执→落流水两阶段；权威只认 CONFIRMED | 异步火忘曾致不平，v1.6.0 升级 |
| 收银台 | pp 开放 cashier.* 商户方法，**ride 乘客端渲染** | pp 不自带 C 端 H5；平台不自建 C 端、改开放 OpenAPI |
| 地图 | 高德免费栅格瓦片 + 自绘 canvas 覆盖层 | 不收费拍板；官方 JS API 需 key 未接 |
| 前端 | 单文件 H5 + common.js 组件库，零 CDN | 零依赖 + 网关 jar 内托管 |

## 3. 模块与进程视图

**13 个 Maven 模块、11 个运行进程**（ride-support 为 v1.9.0 新增第 9 业务域）。

| 模块 | 进程 | HTTP | Dubbo | 其他 | 数据库 |
|---|---|---|---|---|---|
| ride-common | —（公共库） | | | | |
| ride-facade | —（契约库） | | | | |
| ride-registry | ✔ | 8093 探针 | — | ZK 2182 | data/zookeeper |
| ride-passenger | ✔ | — | 20901 | | passenger |
| ride-driver | ✔ | — | 20902 | | driver |
| ride-location | ✔ | — | 20903 | | location |
| ride-pricing | ✔ | — | 20904 | | 无状态 |
| ride-order | ✔ | — | 20905 | | order |
| ride-dispatch | ✔ | — | 20906 | | 无状态 |
| ride-payment | ✔ | — | 20907 | | payment |
| ride-push | ✔ | — | 20908 | WS 9101 | 无状态 |
| ride-support | ✔ | — | 20910 | | support |
| ride-gateway | ✔ | 8090 | 20900 | | 无状态 |

端口与 payment-pro（8080/2181/2090x 之外段）完全错开，可同跑；环境变量可覆盖（ZK_PORT/GATEWAY_PORT/…）。

**启动顺序与就绪门禁**：registry → 业务域 → gateway；run.sh 轮询 `/api/health` 至 allOk；stop.sh 反序（gateway→业务域→registry），registry 先死会阻塞业务进程 Dubbo shutdown hook。EventLoopGroup/消费线程必须 daemon，否则 SIGTERM 不释放端口。

## 4. 调用拓扑与依赖方向

```
gateway ──► passenger/driver/location/pricing/order/dispatch/payment/push/support
order ──► passenger/pricing/driver/dispatch/payment/push/support(配置)
dispatch ──► location/push
location ──► driver
pricing ──► support(费率覆盖/高峰开关，null 安全回落)
payment ──► order（仅 outbox 异步回调 onPaySucceeded/onRefundSucceeded）
payment ──► payment-pro OpenAPI（HTTPS HMAC）
gateway ──► cs-pro open API（投诉工单）
全部进程 ──► registry（注册/发现）
```

- 同步依赖单向无环；**payment→order 仅异步 outbox 回调**，形成运行期 Dubbo 循环引用（双方 check=false，启动无碍），属有意取舍：支付完结事件必须回订单域。
- 派单域不反查订单：订单域在 DispatchRequest 携带快照，保持 dispatch 无状态。
- Dubbo 约定：retries=0（写操作防重试放大）、check=false、qos 关闭、order 8s/push 5s 超时、HealthFacade 按 group 精确引用。

## 5. 数据架构

| 库 | 表 |
|---|---|
| passenger | passenger |
| driver | driver |
| order | ride_order、idempotent_record、order_rating |
| location | driver_location、trip_track |
| payment | wallet、ledger_entry、idempotent_record、pay_outbox、refund_outbox、withdraw_order、mirror_outbox、payment_pro_account、payment_pro_notify、cashier_request、cashier_code |
| support | poi、address、coupon_template、coupon、safety_contact、safety_event、support_config、admin_user、audit_log、invoice、member、im_message、notification、driver_doc、ticket_link、refund_request |

- **双模式**：默认 H2 文件库（data/<域>）；`--docker` 切 claim-shared MySQL 的 ride_* 分库 + 容器 ZK。run/mode 标记供混沌/ops 重启继承。
- **双方言**：MERGE→ODKU 4 处；nextId 注解改 XML databaseId=h2/mysql；MySQL 序列=sequences 表+nextval()（deploy/mysql-init.sql 建，种子对齐 H2 首调=1）。
- **迁移**：deploy/MigrateH2ToMySQL.java（H2 只读、17 表行数比对、序列 base_value−increment）；H2 文件保留不删。
- **schema 变更规范**：additive only（新表/新列），H2 与 MySQL 双 schema 同步 + 既有 SchemaUpgrade 挂接；索引级迁移须幂等且零行改。
- **数据保护**：data/ 与库文件删改须用户确认；分片/冲正只增不删。

## 6. 关键机制视图

1. **订单状态机**：全部 CAS（守卫写 SQL WHERE）；CREATED 为语义初态（叫车同事务直置 DISPATCHING）；司机取消回 DISPATCHING 排除该司机；超轮次 EXPIRED。详见 DESIGN §4。
2. **派单双模**：grab 广播抢单 / assign 评分指派；邀约 TTL、拒单顺延、多车型接力（fallback_types + switchService 重报价）。配置 dispatch.mode/radiusKm/offerTtlSec 生效。
3. **计价**：分车型费率+高峰/夜间+远途+最低消费；规则版本+下单快照 fare_json；完单按轨迹里程重计价 actual_fare_json；support pricing.<svc>.* 覆盖与 surgeEnabled 开关。
4. **支付一致性**：单库本地事务原子转账（余额守卫写 WHERE）；确定性幂等键（PAY-{orderId}/REFUND-{orderId}-{seq}/CANCEL-{orderId}/TIP-{orderId}/bizNo）；pay_outbox/refund_outbox 同事务投递+退避封顶 30s+DEAD 人工重放；订单域 reconcilePaid 5s 兜底；PaymentMonitor 账面审计。
5. **双账本镜像**：本地 PENDING 腿 → 同步调 pp（trade/fund/account）→ 回执回填 pp_receipt 转 CONFIRMED 才动账；拒绝 FAILED 解冻；mirror_outbox 续传；权威只认 CONFIRMED；对账按增量比对+共享户按单比。
6. **收银台分支**：MirrorExecutor.executePay 前置判断 cashier_request 留痕 → cashier.payVerified/scanPay，否则原 trade 路径逐字节不变；与镜像**同一张 pp 交易单**（outTradeNo=PAY-{orderId}）。
7. **提现状态机**：受理冻结 ACCEPTED→PROCESSING→SUCCESS/FAILED（frozen_amount）；pp 资金单回执确认终态。
8. **推送**：Netty WS /ws/{role}/{id}；尽力而为+环形审计+重连重同步+轮询兜底；KICKED 先下发再 close 防互踢活锁。
9. **配置生效链路**：support_config → SupportFacade → order/pricing 字段注入读取（cfg/cfgLong/cfgStr，null 安全回落默认）→ 管理端配置页写入即生效。
10. **轨迹链路**：司机 3s 上报（携 orderId）→ location 逐点落 trip_track + WS 转发乘客 → 司乘两端画线（接驾绿/行程蓝）+ 自动取景；完单轨迹计价复用同表。

## 7. 部署视图

- **默认（零依赖）**：`./run.sh` → 11 进程 JVM + 内嵌 ZK + H2 文件库；无任何容器/外部服务必需。
- **Docker 双模式**：`./run.sh --docker` → 共享 claim-shared 栈（MySQL 3306、ZK 2182、Redis、RocketMQ、MinIO、XXL 等）；密钥 ~/.config/ride-pro/docker.env（600）；Hikari 每域池 5（共享 max_connections=400）。
- **模式切换约束**：先停应用再动 ZK（2182 互斥）；勿在 ride 运行时先停 ZK。
- **运维集成**：ops-pro 注册启停（stop 超时 240s 防孤儿混合模式）、监控 8090/健康聚合、模式标记继承。
- **构建约束**：JAVA_HOME=corretto-18（release 17；系统 JDK26 下 Lombok 全灭）；**必须根目录全量 `clean package`**（单模块 -pl 会从 .m2 取旧 facade 致 NoClassDefFoundError）；同仓库并发只允许一个构建者（撞 target/）。

## 8. 横切关注点

- **契约**：Facade 一律 Result<T> + FacadeSupport.execute（RPC 边界不抛异常）；错误码 00000/A0400/A0404/A0409/A0301/B0001/B0002/C0001（+收银台 additive A0403/A0429/B0003）→ HTTP 映射见 GatewaySupport。
- **traceId**：TraceFilter Dubbo SPI 双端 + 网关 TraceWebFilter，HTTP→RPC 全链路 MDC 串联。
- **鉴权**：/api/admin/** X-Admin-Token 常量时间比较；support 管理端叠加 RBAC（ADMIN/OPS/FINANCE/SUPPORT，403 A0301）；分享 HMAC-SHA256；pp OpenAPI TreeMap+HmacSHA256+毫秒 timestamp。
- **限流**：网关每 IP 每秒令牌桶；pp 控制台 Bearer 会话+权限点（ride 走 OpenAPI 链不受影响）。
- **日志**：logs/<域>.log（run.sh 覆盖写，历史证据以 DB 为准）；管理端白名单尾随/traceId 检索。

## 9. 外部依赖与降级矩阵

| 依赖 | 用途 | 不可用时 |
|---|---|---|
| 高德栅格瓦片 | 地图底图 | 回退网格底图+地标点，功能不中断 |
| Photon 逆地理 | 点选真实地名 | → BigDataCloud 区级 → 本地 PRESETS「XX附近」→ 坐标文案 |
| payment-pro | 权威账本/收银台 | 资金操作 PENDING 留痕不动账，恢复续传；收银台错误透传 |
| cs-pro | 投诉工单 | 本地 ticket_link + notification 留痕 |
| 共享 MySQL/ZK（docker） | 存储/注册 | 切回默认模式（先停应用）；H2 数据保留 |

## 10. 质量属性红线与演进

- **红线**：ride 对 pp 8 个冻结调用语义不变；BALANCE 同步不变；notify additive；每波 verify-integration 24/24；健康聚合 allOk 不被新进程打破；只 stage 自己的文件（多会话同仓）。
- **演进方向**（DESIGN §18）：真实路径规划计价、真实收单/导航/虚拟号通道、多城市、报表订阅、读写分离与分库分表（当前每域单库）、推送集群化（当前单进程环形审计）。