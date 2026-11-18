-- MySQL 8.x 最终形态 schema（docker profile）；H2 默认模式见 schema.sql。位置域无序列。
CREATE TABLE IF NOT EXISTS driver_location (
    driver_id  VARCHAR(32) PRIMARY KEY,
    lat        DOUBLE      NOT NULL,
    lng        DOUBLE      NOT NULL,
    updated_at BIGINT      NOT NULL
);

CREATE TABLE IF NOT EXISTS trip_track (
    order_id VARCHAR(32) NOT NULL,
    seq      BIGINT      NOT NULL,
    lat      DOUBLE      NOT NULL,
    lng      DOUBLE      NOT NULL,
    ts       BIGINT      NOT NULL,
    PRIMARY KEY (order_id, seq)
);