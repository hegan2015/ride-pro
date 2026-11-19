CREATE SEQUENCE IF NOT EXISTS seq_passenger START WITH 1;

CREATE TABLE IF NOT EXISTS passenger (
    id         VARCHAR(32) PRIMARY KEY,
    name       VARCHAR(64) NOT NULL,
    phone      VARCHAR(32),
    created_at BIGINT      NOT NULL
);
-- v1.4.0：免密自动扣款授权（幂等迁移，保数据）。
ALTER TABLE passenger ADD COLUMN IF NOT EXISTS auto_pay BOOLEAN NOT NULL DEFAULT FALSE;
-- v1.9.0：管理端封禁标记（幂等迁移，保数据）。
ALTER TABLE passenger ADD COLUMN IF NOT EXISTS blocked BOOLEAN NOT NULL DEFAULT FALSE;
