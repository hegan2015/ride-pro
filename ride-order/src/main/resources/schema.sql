CREATE SEQUENCE IF NOT EXISTS seq_order START WITH 1;

CREATE TABLE IF NOT EXISTS ride_order (
    id              VARCHAR(32) PRIMARY KEY,
    passenger_id    VARCHAR(32) NOT NULL,
    passenger_name  VARCHAR(64),
    driver_id       VARCHAR(32),
    driver_name     VARCHAR(64),
    driver_car_no   VARCHAR(32),
    origin_name     VARCHAR(128),
    origin_lat      DOUBLE      NOT NULL,
    origin_lng      DOUBLE      NOT NULL,
    dest_name       VARCHAR(128),
    dest_lat        DOUBLE      NOT NULL,
    dest_lng        DOUBLE      NOT NULL,
    distance_km     DOUBLE,
    estimate_fee    BIGINT,
    actual_fee      BIGINT,
    status          VARCHAR(16) NOT NULL,
    request_id      VARCHAR(64),
    cancel_reason   VARCHAR(256),
    dispatch_radius DOUBLE,
    last_dispatch_at BIGINT,
    created_at      BIGINT      NOT NULL,
    accepted_at     BIGINT,
    arrived_at      BIGINT,
    started_at      BIGINT,
    completed_at    BIGINT,
    paid_at         BIGINT,
    cancelled_at    BIGINT
);
CREATE INDEX IF NOT EXISTS idx_order_passenger ON ride_order (passenger_id);
CREATE INDEX IF NOT EXISTS idx_order_driver ON ride_order (driver_id);
CREATE INDEX IF NOT EXISTS idx_order_status ON ride_order (status);

-- 幂等记录：叫车/支付按 requestId 去重，结果回填供重复请求直接返回。
CREATE TABLE IF NOT EXISTS idempotent_record (
    request_id  VARCHAR(64) PRIMARY KEY,
    biz_type    VARCHAR(16) NOT NULL,
    result_json CLOB,
    created_at  BIGINT      NOT NULL
);

-- v1.3.0 增量：服务类型/计价快照/轨迹里程/派单轮次/邀约与拒单名单/取消费/EXPIRED/REFUNDED（幂等迁移）。
ALTER TABLE ride_order ADD COLUMN IF NOT EXISTS service_type VARCHAR(16);
ALTER TABLE ride_order ADD COLUMN IF NOT EXISTS fare_json CLOB;
ALTER TABLE ride_order ADD COLUMN IF NOT EXISTS actual_distance_km DOUBLE;
ALTER TABLE ride_order ADD COLUMN IF NOT EXISTS actual_duration_min BIGINT;
ALTER TABLE ride_order ADD COLUMN IF NOT EXISTS dispatch_round INT;
ALTER TABLE ride_order ADD COLUMN IF NOT EXISTS offered_driver_ids VARCHAR(1024);
ALTER TABLE ride_order ADD COLUMN IF NOT EXISTS declined_driver_ids VARCHAR(1024);
ALTER TABLE ride_order ADD COLUMN IF NOT EXISTS cancel_fee BIGINT;
ALTER TABLE ride_order ADD COLUMN IF NOT EXISTS expired_at BIGINT;
ALTER TABLE ride_order ADD COLUMN IF NOT EXISTS refunded_at BIGINT;

-- 行程评价：一单一角色一评（唯一键幂等），乘客评价驱动司机服务分。
CREATE TABLE IF NOT EXISTS order_rating (
    order_id   VARCHAR(32)  NOT NULL,
    role       VARCHAR(16)  NOT NULL,
    score      INT          NOT NULL,
    comment    VARCHAR(512),
    created_at BIGINT       NOT NULL,
    PRIMARY KEY (order_id, role)
);
-- v1.4.0：部分退款累计/笔数、催付标记、完单实际计价明细（幂等迁移，保数据）。
ALTER TABLE ride_order ADD COLUMN IF NOT EXISTS refunded_amount BIGINT NOT NULL DEFAULT 0;
-- v1.9.0：标签化评价/匿名（幂等迁移，保数据）。
ALTER TABLE order_rating ADD COLUMN IF NOT EXISTS tags VARCHAR(256);
ALTER TABLE order_rating ADD COLUMN IF NOT EXISTS anonymous BOOLEAN NOT NULL DEFAULT FALSE;
-- v1.9.0：多车型接力备选（幂等迁移，保数据）。
ALTER TABLE ride_order ADD COLUMN IF NOT EXISTS fallback_types VARCHAR(128);
-- v1.9.0：舒适偏好/加价调度费/支付渠道（幂等迁移，保数据）。
ALTER TABLE ride_order ADD COLUMN IF NOT EXISTS comfort VARCHAR(64);
ALTER TABLE ride_order ADD COLUMN IF NOT EXISTS tip_fee BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ride_order ADD COLUMN IF NOT EXISTS pay_channel VARCHAR(16);
ALTER TABLE ride_order ADD COLUMN IF NOT EXISTS refund_count INT NOT NULL DEFAULT 0;
ALTER TABLE ride_order ADD COLUMN IF NOT EXISTS reminded_at BIGINT;
ALTER TABLE ride_order ADD COLUMN IF NOT EXISTS actual_fare_json CLOB;
