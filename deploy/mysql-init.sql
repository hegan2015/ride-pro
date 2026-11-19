-- 以 root 执行一次：为 5 个 ride_* 库创建 nextval() 序列函数（H2 CREATE SEQUENCE 的 MySQL 等价物）。
-- binlog 默认开启时创建 MODIFIES SQL DATA 函数需放宽限制；函数按库隔离，ride 账号的库级 ALL 已含 EXECUTE。
-- 用法（口令在容器环境变量内，不回显）：
--   docker exec -i claim-shared-mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' < deploy/mysql-init.sql
-- sequences 表由各域 schema-mysql.sql 创建并种子；本脚本可重复执行（DROP IF EXISTS）。
SET GLOBAL log_bin_trust_function_creators = 1;

USE ride_passenger;
DROP FUNCTION IF EXISTS nextval;
DELIMITER //
CREATE FUNCTION nextval(seq_name VARCHAR(64)) RETURNS BIGINT
MODIFIES SQL DATA
BEGIN
    UPDATE sequences SET val = LAST_INSERT_ID(val + 1) WHERE name = seq_name;
    RETURN LAST_INSERT_ID();
END//
DELIMITER ;

USE ride_driver;
DROP FUNCTION IF EXISTS nextval;
DELIMITER //
CREATE FUNCTION nextval(seq_name VARCHAR(64)) RETURNS BIGINT
MODIFIES SQL DATA
BEGIN
    UPDATE sequences SET val = LAST_INSERT_ID(val + 1) WHERE name = seq_name;
    RETURN LAST_INSERT_ID();
END//
DELIMITER ;

USE ride_order;
DROP FUNCTION IF EXISTS nextval;
DELIMITER //
CREATE FUNCTION nextval(seq_name VARCHAR(64)) RETURNS BIGINT
MODIFIES SQL DATA
BEGIN
    UPDATE sequences SET val = LAST_INSERT_ID(val + 1) WHERE name = seq_name;
    RETURN LAST_INSERT_ID();
END//
DELIMITER ;

USE ride_payment;
DROP FUNCTION IF EXISTS nextval;
DELIMITER //
CREATE FUNCTION nextval(seq_name VARCHAR(64)) RETURNS BIGINT
MODIFIES SQL DATA
BEGIN
    UPDATE sequences SET val = LAST_INSERT_ID(val + 1) WHERE name = seq_name;
    RETURN LAST_INSERT_ID();
END//
DELIMITER ;

USE ride_location;
DROP FUNCTION IF EXISTS nextval;
DELIMITER //
CREATE FUNCTION nextval(seq_name VARCHAR(64)) RETURNS BIGINT
MODIFIES SQL DATA
BEGIN
    UPDATE sequences SET val = LAST_INSERT_ID(val + 1) WHERE name = seq_name;
    RETURN LAST_INSERT_ID();
END//
DELIMITER ;
USE ride_support;
DROP FUNCTION IF EXISTS nextval;
DELIMITER //
CREATE FUNCTION nextval(seq_name VARCHAR(64)) RETURNS BIGINT
MODIFIES SQL DATA
BEGIN
    UPDATE sequences SET val = LAST_INSERT_ID(val + 1) WHERE name = seq_name;
    RETURN LAST_INSERT_ID();
END//
DELIMITER ;
