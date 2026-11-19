-- MySQL 8.x 最终形态 schema（docker profile）；H2 默认模式见 schema.sql。
-- 序列以 sequences 表 + nextval() 函数模拟，函数由 root 经 deploy/mysql-init.sql 创建。
CREATE TABLE IF NOT EXISTS sequences (
    name VARCHAR(64) PRIMARY KEY,
    val  BIGINT NOT NULL
);
INSERT INTO sequences(name,val) VALUES('seq_support',0) ON DUPLICATE KEY UPDATE name=name;

CREATE TABLE IF NOT EXISTS poi (
    id       VARCHAR(32) PRIMARY KEY,
    name     VARCHAR(64) NOT NULL,
    alias    VARCHAR(64),
    category VARCHAR(32),
    lat      DOUBLE,
    lng      DOUBLE
);
CREATE TABLE IF NOT EXISTS address (
    id         VARCHAR(32) PRIMARY KEY,
    user_id    VARCHAR(32) NOT NULL,
    label      VARCHAR(32),
    name       VARCHAR(64) NOT NULL,
    lat        DOUBLE,
    lng        DOUBLE,
    created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS coupon_template (
    id         VARCHAR(32) PRIMARY KEY,
    name       VARCHAR(64) NOT NULL,
    type       VARCHAR(32) NOT NULL,
    benefit    BIGINT NOT NULL,
    min_amount BIGINT NOT NULL DEFAULT 0,
    valid_days INT    NOT NULL DEFAULT 7,
    total      INT    NOT NULL DEFAULT 0,
    issued     INT    NOT NULL DEFAULT 0,
    status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS coupon (
    id          VARCHAR(32) PRIMARY KEY,
    template_id VARCHAR(32) NOT NULL,
    user_id     VARCHAR(32) NOT NULL,
    code        VARCHAR(32),
    status      VARCHAR(16) NOT NULL DEFAULT 'UNUSED',
    order_id    VARCHAR(32),
    expire_at   BIGINT,
    created_at  BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS safety_contact (
    id         VARCHAR(32) PRIMARY KEY,
    user_id    VARCHAR(32) NOT NULL,
    name       VARCHAR(64) NOT NULL,
    phone      VARCHAR(32),
    relation   VARCHAR(32),
    created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS safety_event (
    id         VARCHAR(32) PRIMARY KEY,
    user_id    VARCHAR(32),
    order_id   VARCHAR(32),
    type       VARCHAR(32) NOT NULL,
    detail     VARCHAR(512),
    status     VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS support_config (
    cfg_key    VARCHAR(64) PRIMARY KEY,
    cfg_value  TEXT,
    updated_at BIGINT
);
CREATE TABLE IF NOT EXISTS admin_user (
    id            VARCHAR(32) PRIMARY KEY,
    username      VARCHAR(64) NOT NULL,
    password_hash VARCHAR(128),
    role          VARCHAR(16) NOT NULL DEFAULT 'OPS',
    status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at    BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS audit_log (
    id         VARCHAR(32) PRIMARY KEY,
    admin      VARCHAR(64),
    action     VARCHAR(64),
    target     VARCHAR(64),
    detail     VARCHAR(512),
    created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS invoice (
    id         VARCHAR(32) PRIMARY KEY,
    user_id    VARCHAR(32) NOT NULL,
    order_ids  VARCHAR(512),
    title      VARCHAR(128),
    tax_no     VARCHAR(64),
    amount     BIGINT,
    status     VARCHAR(16) NOT NULL DEFAULT 'ISSUED',
    created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS member (
    user_id    VARCHAR(32) PRIMARY KEY,
    points     BIGINT NOT NULL DEFAULT 0,
    level      VARCHAR(16) NOT NULL DEFAULT '普通会员',
    total_spent BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT
);
CREATE TABLE IF NOT EXISTS im_message (
    id         VARCHAR(32) PRIMARY KEY,
    order_id   VARCHAR(32) NOT NULL,
    from_role  VARCHAR(8),
    from_id    VARCHAR(32),
    text       VARCHAR(512),
    created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS notification (
    id         VARCHAR(32) PRIMARY KEY,
    user_id    VARCHAR(32) NOT NULL,
    type       VARCHAR(32),
    title      VARCHAR(128),
    body       VARCHAR(512),
    read_flag  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS driver_doc (
    id         VARCHAR(32) PRIMARY KEY,
    driver_id  VARCHAR(32) NOT NULL,
    type       VARCHAR(32) NOT NULL,
    doc_no     VARCHAR(64),
    expiry     BIGINT,
    status     VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    file_url   VARCHAR(256),
    reason     VARCHAR(256),
    created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS ticket_link (
    id           VARCHAR(32) PRIMARY KEY,
    order_id     VARCHAR(32),
    user_id      VARCHAR(32),
    cs_ticket_id VARCHAR(32),
    category     VARCHAR(32),
    status       VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at   BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS refund_request (
    id           VARCHAR(32) PRIMARY KEY,
    order_id     VARCHAR(32) NOT NULL,
    amount       BIGINT,
    reason       VARCHAR(256),
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    requested_by VARCHAR(64),
    approved_by  VARCHAR(64),
    created_at   BIGINT NOT NULL
);

INSERT IGNORE INTO poi(id,name,alias,category,lat,lng) VALUES
 ('POI1','国贸','guomao gd','商圈',39.908,116.459),
 ('POI2','中关村','zhongguancun zgc','商圈',39.983,116.316),
 ('POI3','望京','wangjing wj','商圈',39.998,116.470),
 ('POI4','西单','xidan xd','商圈',39.907,116.374),
 ('POI5','北京南站','beijingnanzhan bjnz','枢纽',39.865,116.378),
 ('POI6','首都机场','shoudujichang sdjc','枢纽',40.080,116.603),
 ('POI7','三里屯','sanlitun slt','商圈',39.937,116.447),
 ('POI8','回龙观','huilongguan hlg','居住',40.070,116.330),
 ('POI9','国贸充电站','chongdian cdz','充电',39.909,116.461),
 ('POI10','望京充电站','chongdian cdz','充电',39.999,116.472),
 ('POI11','西单加油站','jiayou jyz','加油',39.906,116.372),
 ('POI12','中关村加油站','jiayou jyz','加油',39.984,116.318);

INSERT IGNORE INTO support_config(cfg_key,cfg_value,updated_at) VALUES
 ('dispatch.mode','GRAB',0),
 ('dispatch.radiusKm','5',0),
 ('dispatch.offerTtlSec','15',0),
 ('cancel.freeWindowSec','120',0),
 ('cancel.feeAmount','300',0),
 ('pricing.surgeEnabled','false',0),
 ('safety.recordingDefault','true',0),
 ('support.csBaseUrl','http://127.0.0.1:8096',0),
 ('support.csOpenKey','',0);

INSERT IGNORE INTO admin_user(id,username,password_hash,role,status,created_at) VALUES
 ('AD1','admin','240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9','ADMIN','ACTIVE',0);