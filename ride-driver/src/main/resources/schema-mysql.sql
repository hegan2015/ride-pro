-- MySQL 8.x 最终形态 schema（docker profile）；H2 默认模式见 schema.sql。
-- 序列以 sequences 表 + nextval() 函数模拟，函数由 root 经 deploy/mysql-init.sql 创建。
CREATE TABLE IF NOT EXISTS sequences (
    name VARCHAR(64) PRIMARY KEY,
    val  BIGINT NOT NULL
);
INSERT INTO sequences(name,val) VALUES('seq_driver',0) ON DUPLICATE KEY UPDATE name=name;

CREATE TABLE IF NOT EXISTS driver (
    id            VARCHAR(32) PRIMARY KEY,
    name          VARCHAR(64) NOT NULL,
    phone         VARCHAR(32),
    car_no        VARCHAR(32) NOT NULL,
    car_model     VARCHAR(64),
    status        VARCHAR(16) NOT NULL,
    created_at    BIGINT      NOT NULL,
    service_type  VARCHAR(16),
    service_score DOUBLE,
    decline_count INT,
    updated_at    BIGINT,
    INDEX idx_driver_status (status)
);