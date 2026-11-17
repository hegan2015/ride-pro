CREATE SEQUENCE IF NOT EXISTS seq_driver START WITH 1;

CREATE TABLE IF NOT EXISTS driver (
    id         VARCHAR(32) PRIMARY KEY,
    name       VARCHAR(64) NOT NULL,
    phone      VARCHAR(32),
    car_no     VARCHAR(32) NOT NULL,
    car_model  VARCHAR(64),
    status     VARCHAR(16) NOT NULL,
    created_at BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_driver_status ON driver (status);

-- v1.3.0 增量：服务类型 / 服务分 / 拒单计数（幂等迁移，存量行服务分按 5.0 兜底）。
ALTER TABLE driver ADD COLUMN IF NOT EXISTS service_type VARCHAR(16);
ALTER TABLE driver ADD COLUMN IF NOT EXISTS service_score DOUBLE;
ALTER TABLE driver ADD COLUMN IF NOT EXISTS decline_count INT;
ALTER TABLE driver ADD COLUMN IF NOT EXISTS updated_at BIGINT;