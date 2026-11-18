-- MySQL 8.x 最终形态 schema（docker profile）；H2 默认模式见 schema.sql。
-- 序列以 sequences 表 + nextval() 函数模拟，函数由 root 经 deploy/mysql-init.sql 创建。
CREATE TABLE IF NOT EXISTS sequences (
    name VARCHAR(64) PRIMARY KEY,
    val  BIGINT NOT NULL
);
INSERT INTO sequences(name,val) VALUES('seq_order',0) ON DUPLICATE KEY UPDATE name=name;

CREATE TABLE IF NOT EXISTS ride_order (
    id                  VARCHAR(32) PRIMARY KEY,
    passenger_id        VARCHAR(32) NOT NULL,
    passenger_name      VARCHAR(64),
    driver_id           VARCHAR(32),
    driver_name         VARCHAR(64),
    driver_car_no       VARCHAR(32),
    origin_name         VARCHAR(128),
    origin_lat          DOUBLE      NOT NULL,
    origin_lng          DOUBLE      NOT NULL,
    dest_name           VARCHAR(128),
    dest_lat            DOUBLE      NOT NULL,
    dest_lng            DOUBLE      NOT NULL,
    distance_km         DOUBLE,
    estimate_fee        BIGINT,
    actual_fee          BIGINT,
    status              VARCHAR(16) NOT NULL,
    request_id          VARCHAR(64),
    cancel_reason       VARCHAR(256),
    dispatch_radius     DOUBLE,
    last_dispatch_at    BIGINT,
    created_at          BIGINT      NOT NULL,
    accepted_at         BIGINT,
    arrived_at          BIGINT,
    started_at          BIGINT,
    completed_at        BIGINT,
    paid_at             BIGINT,
    cancelled_at        BIGINT,
    service_type        VARCHAR(16),
    fare_json           TEXT,
    actual_distance_km  DOUBLE,
    actual_duration_min BIGINT,
    dispatch_round      INT,
    offered_driver_ids  VARCHAR(1024),
    declined_driver_ids VARCHAR(1024),
    cancel_fee          BIGINT,
    expired_at          BIGINT,
    refunded_at         BIGINT,
    refunded_amount     BIGINT      NOT NULL DEFAULT 0,
    refund_count        INT         NOT NULL DEFAULT 0,
    reminded_at         BIGINT,
    actual_fare_json    TEXT,
    INDEX idx_order_passenger (passenger_id),
    INDEX idx_order_driver (driver_id),
    INDEX idx_order_status (status)
);

CREATE TABLE IF NOT EXISTS idempotent_record (
    request_id  VARCHAR(64) PRIMARY KEY,
    biz_type    VARCHAR(16) NOT NULL,
    result_json TEXT,
    created_at  BIGINT      NOT NULL
);

CREATE TABLE IF NOT EXISTS order_rating (
    order_id   VARCHAR(32)  NOT NULL,
    role       VARCHAR(16)  NOT NULL,
    score      INT          NOT NULL,
    comment    VARCHAR(512),
    created_at BIGINT       NOT NULL,
    PRIMARY KEY (order_id, role)
);