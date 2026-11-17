CREATE TABLE IF NOT EXISTS wallet (
    user_id    VARCHAR(32) PRIMARY KEY,
    balance    BIGINT      NOT NULL,
    updated_at BIGINT      NOT NULL
);

CREATE SEQUENCE IF NOT EXISTS seq_ledger START WITH 1;

-- 资金流水：每笔充值/扣款/入账留痕，balance_after 为该笔后的钱包余额。
CREATE TABLE IF NOT EXISTS ledger_entry (
    id            VARCHAR(32) PRIMARY KEY,
    user_id       VARCHAR(32) NOT NULL,
    order_id      VARCHAR(32),
    biz_type      VARCHAR(16) NOT NULL,
    amount        BIGINT      NOT NULL,
    balance_after BIGINT      NOT NULL,
    request_id    VARCHAR(64),
    created_at    BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ledger_user ON ledger_entry (user_id);
CREATE INDEX IF NOT EXISTS idx_ledger_order ON ledger_entry (order_id);

-- v1.6.0 同步回执流程：流水先落 PENDING，payment-pro 回执后转 CONFIRMED 才动账；拒绝置 FAILED。
-- pp_receipt 记录支付系统回执号（支付单/划转 bizNo）。存量流水默认 CONFIRMED。
ALTER TABLE ledger_entry ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED';
ALTER TABLE ledger_entry ADD COLUMN IF NOT EXISTS pp_receipt VARCHAR(64);

CREATE TABLE IF NOT EXISTS idempotent_record (
    request_id  VARCHAR(64) PRIMARY KEY,
    biz_type    VARCHAR(16) NOT NULL,
    result_json CLOB,
    created_at  BIGINT      NOT NULL
);

-- 事务 outbox：支付成功事件与扣款同事务写入，投递任务异步通知订单域完结，失败退避重试。
CREATE TABLE IF NOT EXISTS pay_outbox (
    order_id      VARCHAR(32) PRIMARY KEY,
    request_id    VARCHAR(64) NOT NULL,
    status        VARCHAR(16) NOT NULL,
    retries       INT         NOT NULL,
    next_retry_at BIGINT      NOT NULL,
    created_at    BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_outbox_pending ON pay_outbox (status, next_retry_at);
-- v1.3.0：取消费/退款流水类型更长（CANCEL_FEE_CREDIT 等），扩列幂等。
ALTER TABLE ledger_entry ALTER COLUMN biz_type VARCHAR(32);

-- v1.4.0：提现冻结余额 + 提现单状态机 + 退款 outbox（幂等迁移，保数据）。
ALTER TABLE wallet ADD COLUMN IF NOT EXISTS frozen_amount BIGINT NOT NULL DEFAULT 0;

CREATE SEQUENCE IF NOT EXISTS seq_withdraw START WITH 1;

-- 提现单：受理冻结→模拟渠道异步处理→成功扣款/失败解冻，biz_no 唯一幂等。
CREATE TABLE IF NOT EXISTS withdraw_order (
    id          VARCHAR(32) PRIMARY KEY,
    user_id     VARCHAR(32) NOT NULL,
    amount      BIGINT      NOT NULL,
    status      VARCHAR(16) NOT NULL,
    biz_no      VARCHAR(64) NOT NULL,
    fail_reason VARCHAR(256),
    created_at  BIGINT      NOT NULL,
    updated_at  BIGINT      NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_withdraw_biz ON withdraw_order (biz_no);
CREATE INDEX IF NOT EXISTS idx_withdraw_status ON withdraw_order (status, updated_at);

-- 退款事务 outbox：与退款划转同事务写入，异步通知订单域同步退款状态（支持部分退款多笔）。
CREATE TABLE IF NOT EXISTS refund_outbox (
    request_id    VARCHAR(64) PRIMARY KEY,
    order_id      VARCHAR(32) NOT NULL,
    status        VARCHAR(16) NOT NULL,
    retries       INT         NOT NULL,
    next_retry_at BIGINT      NOT NULL,
    created_at    BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_refund_outbox_pending ON refund_outbox (status, next_retry_at);

-- ===== payment-pro 双账本集成（可选，未配置 config/payment-pro.properties 时不产生任何镜像事件） =====

-- 用户 → payment-pro 会员/账户映射（SYS_PLATFORM 映射到商户账户）。
-- pp_balance_snapshot：开户映射时的 payment-pro 余额基线，双账本核对按“基线+已投递镜像净额”增量比对，
-- 兼容接入前已有余额的钱包/商户账户。
CREATE TABLE IF NOT EXISTS payment_pro_account (
    user_id             VARCHAR(32) PRIMARY KEY,
    member_id           VARCHAR(32) NOT NULL,
    account_id          VARCHAR(32) NOT NULL,
    created_at          BIGINT      NOT NULL,
    pp_balance_snapshot BIGINT
);
ALTER TABLE payment_pro_account ADD COLUMN IF NOT EXISTS pp_balance_snapshot BIGINT;

-- 资金镜像 outbox：与本地动账同事务写入，单线程按 created_at 顺序投递到 payment-pro OpenAPI，
-- biz_no 确定性幂等；pp_order_id/pp_payment_id 记录多步镜像（下单→支付→分成）断点。
CREATE TABLE IF NOT EXISTS mirror_outbox (
    biz_no        VARCHAR(96) PRIMARY KEY,
    biz_type      VARCHAR(16)  NOT NULL,
    ref_key       VARCHAR(64)  NOT NULL,
    user_id       VARCHAR(32)  NOT NULL,
    order_id      VARCHAR(32),
    status        VARCHAR(16)  NOT NULL,
    retries       INT          NOT NULL,
    next_retry_at BIGINT       NOT NULL,
    pp_order_id   VARCHAR(32),
    pp_payment_id VARCHAR(32),
    last_error    VARCHAR(256),
    created_at    BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_mirror_pending ON mirror_outbox (status, next_retry_at);

-- payment-pro 异步通知留痕（回调仅加速展示，终态以镜像回执+对账为准）。
CREATE TABLE IF NOT EXISTS payment_pro_notify (
    event      VARCHAR(32)   NOT NULL,
    biz_id     VARCHAR(64)   NOT NULL,
    payload    VARCHAR(2048) NOT NULL,
    created_at BIGINT        NOT NULL,
    PRIMARY KEY (event, biz_id)
);
