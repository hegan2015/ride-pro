-- MySQL 8.x 最终形态 schema（docker profile）；H2 默认模式见 schema.sql。
-- 序列以 sequences 表 + nextval() 函数模拟，函数由 root 经 deploy/mysql-init.sql 创建。
CREATE TABLE IF NOT EXISTS sequences (
    name VARCHAR(64) PRIMARY KEY,
    val  BIGINT NOT NULL
);
INSERT INTO sequences(name,val) VALUES('seq_passenger',0) ON DUPLICATE KEY UPDATE name=name;

CREATE TABLE IF NOT EXISTS passenger (
    id         VARCHAR(32) PRIMARY KEY,
    name       VARCHAR(64) NOT NULL,
    phone      VARCHAR(32),
    created_at BIGINT      NOT NULL,
    auto_pay   BOOLEAN     NOT NULL DEFAULT FALSE
);