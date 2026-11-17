CREATE TABLE IF NOT EXISTS driver_location (
    driver_id  VARCHAR(32) PRIMARY KEY,
    lat        DOUBLE      NOT NULL,
    lng        DOUBLE      NOT NULL,
    updated_at BIGINT      NOT NULL
);

-- 行程轨迹：行程中位置上报逐点落库，完单按轨迹里程计价，管理端/分享页可回放。
CREATE TABLE IF NOT EXISTS trip_track (
    order_id VARCHAR(32) NOT NULL,
    seq      BIGINT      NOT NULL,
    lat      DOUBLE      NOT NULL,
    lng      DOUBLE      NOT NULL,
    ts       BIGINT      NOT NULL,
    PRIMARY KEY (order_id, seq)
);