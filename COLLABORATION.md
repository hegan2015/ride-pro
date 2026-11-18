# ride-pro 协作指南 —— 用户指导 AI 开发要点复盘

> 整理日期：2026-11-18 ｜ 来源：跨会话真实协作记录复盘
> 用途：新会话开工前读本文件，快速对齐用户的工作方式与本项目已拍板的决策，避免重复试探和回退。

## 一、项目定位与硬约束

- 参考**滴滴**领域拆分的分布式打车系统：Java 17 + Spring Boot 3.2 + Maven 多模块 + Dubbo + Netty WS + H2 文件库 + 内嵌 ZooKeeper，司乘端 H5 由 gateway 静态托管。
- **零外部依赖硬约束**：不引入需要独立部署的中间件（Seata/XA 协调器、外部 MySQL/ZK、MQ 等已被明确排除）。Netty 是用户点名的**进程内库例外**，勿据此扩大例外范围。
- **数据神圣**：停服务可以，删除/重置 data/ 与 H2 库必须先经用户明确确认。
- 端口：8090（gateway）/2182（ZK）/8093（ZK HTTP）/2090x（Dubbo）/9101（WS），与 payment-pro 完全错开可同跑（2026-11-17 迁移后）。

## 二、关键指导决策（已拍板，勿回退）

1. **支付一致性走 outbox 路线**（v1.2.0，2026-11-16）：用户在 outbox/TCC/Saga/XA 权衡对比后明确拍板「加上 outbox」。结论：TCC 对短事务收益低、Saga 退款方向与已履约行程不符、XA/Seata 违反零依赖。**勿再建议替换为 TCC**（TCC 组件化只针对 payment-pro）。机制：确定性幂等键 `PAY-{orderId}` + pay_outbox 同事务 + 1s 投递 + 5s 对账兜底。
2. **可靠性必须服务端兜底**：源于用户质疑「前端修复不可靠」「乘客扣款完成时服务重启了怎么办」。前端重连/轮询只管展示实时性，不得作为一致性方案交付。
3. **迭代节奏 = 建议→筛选→拍板→实施**：用户先要「功能细化、优化建议」，补充「管理端流水日志、界面布局」「司乘端订单记录查询」，再「按你的功能建议及我的布局建议开始优化」；v1.4.0 确认「全部建议」范围（资金安全 + 体验闭环）。
4. **双账本集成**（v1.5.0–v1.7.0，2026-11-17）：用户指令「双账本模式帮我改造，通过 https 的 api 对接」，在「钱包全托管 vs 双账本」中拍板**双账本**——ride 钱包本地权威，同事务 mirror_outbox 镜像到 payment-pro OpenAPI（HTTPS 8443 + HMAC）。后续「充值提现 pay 不都应该有一笔交易与支付单吗」「流水要能查总的并且要分页」→ 选**完整重构**：v1.6.0 同步回执、v1.7.0 充值/提现资金单 + 全局分页流水。pp 宕机不影响本地资金流，恢复自动追平。
5. **文档权威**：DESIGN.md 为准，README.md 是其映射副本（GitHub 首页展示用）；DESIGN 更新后需手动重新同步 README。
6. **git 听指令**：让提交才提交、让推送才推送；SSH 通道 `git@github.com:hegan2015/ride-pro.git`。嵌套克隆目录的删除经用户确认后执行。
7. **Docker 中间件双模式**（2026-11-18）：用户指令「打车对接 docker 中间件」「zk 也对接」「依赖更新到 ops」，拍板范围 **ZK + MySQL**、对接**已有 claim-shared 栈**、数据**方案B 迁移现有 H2**（H2 文件保留）。零依赖默认模式不变（内嵌 ZK + H2），`run.sh --docker` 切容器 MySQL(3306, ride_* 5 库) + 容器 ZK(2182, claim-shared-zookeeper-ride)。方言策略：MERGE→ODKU 双兼容、nextId 走 XML databaseId(h2/mysql)、MySQL 序列=sequences 表+nextval() 函数（deploy/mysql-init.sql root 建）。迁移工具 deploy/MigrateH2ToMySQL.java（H2 只读、可重跑、行数比对）。密钥 ~/.config/ride-pro/docker.env（600），仓库只留 config/docker.env.example。共享 MySQL max_connections=400（compose 持久化），ride 每域池 5。**payment-pro 的 Docker 迁移由并行会话负责，本会话不碰。**

## 三、纠正与教训（用户纠正 → 规则）

| 事件 | 确立的规则 |
|---|---|
| 前端重连修复被质疑「不可靠」 | 一致性方案必须服务端机制为主体，先讲清崩溃窗口再给兜底 |
| 前端改动晚于打包，jar 内静态页旧版 | 交付前核对 jar 内静态资源与源码一致；必须根目录全量 `clean package`（`-pl` 单模块会从 .m2 取旧 facade） |
| stop.sh 先停 registry 致全员 SIGKILL | 停机顺序 gateway→业务域→registry 逐个停 |
| 并行会话推进 DESIGN/端口/记忆 | 提交/交付/汇报前重读文件当前状态，勿信本会话快照 |
| 用户 Chrome 常开司乘端页签 | 同身份会顶号，验证推送先查连接归属（lsof -nP -i :9101） |
| ops 停 ride 超时 60s 杀掉 stop.sh 留 6 孤儿进程，再启成「混合模式」 | 多进程应用停止超时必须覆盖最坏（ops 已改 240s）；模式切换先停应用再停 ZK，勿在 ride 运行时先停 ZK（Dubbo shutdown 阻塞） |
| pp 并行 Docker 迁移重播种致会员名漂移，ride 旧映射按名找不到账户 | 跨系统身份映射依赖对方数据稳定；漂移时经用户拍板删 ride 侧旧映射行（先备份）懒开户重建，不碰对方数据 |
| verify-integration F 段重启 pp 不带 --docker 退回 local | 重启他系统须探测并继承其运行模式（pp_docker_mode() ps 探测） |

## 四、通用协作原则（四系统一致）

1. **开箱即用**：零配置、一键启动脚本，交付前实际编译+测试+启动+UI 验证。
2. **方案拍板制**：给权衡选项（含推荐与理由）由用户拍板升级幅度，不擅自上重方案。
3. **数据保护**：删/重置数据先确认；验证尽量用隔离实例，演示库只留种子数据。
4. **密钥出仓库**：敏感配置放 `~/.config/<app>/`（700/600），仓库只留 .example；gitignore 不算安全。
5. **实测文化**：单测 + 冒烟 + 混沌（SIGSTOP/杀进程制造崩溃窗口）+ 浏览器全流程，全绿才算交付。
6. **设计类按商用标准**：架构/方案咨询默认生产级 + 主流正式框架（与构建类零依赖偏好区分）；确定性代码管判定与资金，LLM 只做理解/建议/解释。
7. **共享浏览器守则**：用户会实时操作内置浏览器；自动化用隐藏 iframe + localStorage 隔离 + no-store，不劫持用户页签；长等待拆两次调用（15s 超时）。
8. **中文交流 + 教学式回答**：操作类给可复制命令并实测演示；概念类通俗解释 + 代码 file:line；框架机制讲完整装配链路并指明定义处。
9. **git 听指令**：提交/推送均须用户明示；不 force push；不提交 data/logs/密钥。
10. **并行会话复查**：多会话同改一工作区，动手前先查现状。

## 五、指令模板与协作优化

**一行指令模板**：【构建/设计/运维】+ 目标 + 标准档位 + 完成定义 + 授权范围。
例：「构建，给 ride 加评价申诉功能，零依赖，完成=单测+实测+DESIGN 同步，提交推送一起做，删数据先问我。」

**已识别的优化方向**（2026-11-18 复盘）：
- 会话分工：一个会话只负责一个仓库，或开工声明范围；真并行用 worktree/分支隔离。
- 版本锚点：全量验证后打 git tag（如 ride-v1.7.0-verified）。
- README/DESIGN 同步自动化（脚本或 pre-commit 检查）。
- 测试数据与演示数据分离（推广 yiwu 的隔离实例模式）。
- 设计交付落盘成文档，不只留在对话里。
- 收掉脆弱依赖（正式 node 运行时、仓库可见性收尾、PAT 备选）。

## 六、运行与构建速查

- 构建：`JAVA_HOME=corretto-18`（release=17；默认 PATH java 26 会踩坑），`./mvnw clean package` 全量 reactor。
- 启停：`./run.sh`（pid 检测跳过存活服务）/ `./stop.sh`（依赖倒序）；`./run.sh --docker` 切容器 MySQL+ZK（密钥 ~/.config/ride-pro/docker.env，run/mode 标记供混沌重启继承；切换先停应用再动 ZK，2182 两模式互斥）。
- 双账本启用：payment-pro 先起 → `./integrate-payment.sh` → 重启 ride；未配置 config/payment-pro.properties 时完全 standalone。
- 验证：`run/chaos-test.sh`（12 节 60 项）、`verify-integration.py`（24 项，增量对账口径）。
- 对账口径：pp 余额 == 开户基线快照 + SENT 镜像本地流水净额（绝对余额比对是错的）。