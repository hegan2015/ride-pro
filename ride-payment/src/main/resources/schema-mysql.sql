-- MySQL 8.x 最终形态 schema（docker profile）；H2 默认模式见 schema.sql。
-- 序列以 sequences 表 + nextval() 函数模拟，函数由 root 经 deploy/mysql-init.sql 创建。
CREATE TABLE IF NOT EXISTS sequences (
    name VARCHAR(64) PRIMARY KEY,
    val  BIGINT NOT NULL
);
INSERT INTO sequences(name,val) VALUES('seq_ledger',0) ON DUPLICATE KEY UPDATE name=name;
INSERT INTO sequences(name,val) VALUES('seq_withdraw',0) ON DUPLICATE KEY UPDATE name=name;

CREATE TABLE IF NOT EXISTS wallet (
    user_id       VARCHAR(32) PRIMARY KEY,
    balance       BIGINT      NOT NULL,
    updated_at    BIGINT      NOT NULL,
    frozen_amount BIGINT      NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS ledger_entry (
    id            VARCHAR(32) PRIMARY KEY,
    user_id       VARCHAR(32) NOT NULL,
    order_id      VARCHAR(32),
    biz_type      VARCHAR(32) NOT NULL,
    amount        BIGINT      NOT NULL,
    balance_after BIGINT      NOT NULL,
    request_id    VARCHAR(64),
    created_at    BIGINT      NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED',
    pp_receipt    VARCHAR(64),
    INDEX idx_ledger_user (user_id),
    INDEX idx_ledger_order (order_id)
);

CREATE TABLE IF NOT EXISTS idempotent_record (
    request_id  VARCHAR(64) PRIMARY KEY,
    biz_type    VARCHAR(16) NOT NULL,
    result_json TEXT,
    created_at  BIGINT      NOT NULL
);

CREATE TABLE IF NOT EXISTS pay_outbox (
    order_id      VARCHAR(32) PRIMARY KEY,
    request_id    VARCHAR(64) NOT NULL,
    status        VARCHAR(16) NOT NULL,
    retries       INT         NOT NULL,
    next_retry_at BIGINT      NOT NULL,
    created_at    BIGINT      NOT NULL,
    INDEX idx_outbox_pending (status, next_retry_at)
);

CREATE TABLE IF NOT EXISTS withdraw_order (
    id          VARCHAR(32) PRIMARY KEY,
    user_id     VARCHAR(32) NOT NULL,
    amount      BIGINT      NOT NULL,
    status      VARCHAR(16) NOT NULL,
    biz_no      VARCHAR(64) NOT NULL,
    fail_reason VARCHAR(256),
    created_at  BIGINT      NOT NULL,
    updated_at  BIGINT      NOT NULL,
    UNIQUE INDEX idx_withdraw_biz (biz_no),
    INDEX idx_withdraw_status (status, updated_at)
);

CREATE TABLE IF NOT EXISTS refund_outbox (
    request_id    VARCHAR(64) PRIMARY KEY,
    order_id      VARCHAR(32) NOT NULL,
    status        VARCHAR(16) NOT NULL,
    retries       INT         NOT NULL,
    next_retry_at BIGINT      NOT NULL,
    created_at    BIGINT      NOT NULL,
    INDEX idx_refund_outbox_pending (status, next_retry_at)
);

CREATE TABLE IF NOT EXISTS payment_pro_account (
    user_id             VARCHAR(32) PRIMARY KEY,
    member_id           VARCHAR(32) NOT NULL,
    account_id          VARCHAR(32) NOT NULL,
    created_at          BIGINT      NOT NULL,
    pp_balance_snapshot BIGINT
);

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
    created_at    BIGINT       NOT NULL,
    INDEX idx_mirror_pending (status, next_retry_at)
);

CREATE TABLE IF NOT EXISTS payment_pro_notify (
    event      VARCHAR(32)   NOT NULL,
    biz_id     VARCHAR(64)   NOT NULL,
    payload    VARCHAR(2048) NOT NULL,
    created_at BIGINT        NOT NULL,
    PRIMARY KEY (event, biz_id)
);