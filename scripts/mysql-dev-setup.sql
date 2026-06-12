-- Run this with a MySQL administrator account when bootRun fails with:
-- Access denied for user 'tomy_app'@'localhost'
--
-- Example:
-- mysql -u root -p < scripts/mysql-dev-setup.sql

CREATE DATABASE IF NOT EXISTS tomy_db
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'tomy_app'@'localhost' IDENTIFIED BY 'StrongPass!123';
CREATE USER IF NOT EXISTS 'tomy_app'@'127.0.0.1' IDENTIFIED BY 'StrongPass!123';

ALTER USER 'tomy_app'@'localhost' IDENTIFIED BY 'StrongPass!123';
ALTER USER 'tomy_app'@'127.0.0.1' IDENTIFIED BY 'StrongPass!123';

GRANT ALL PRIVILEGES ON tomy_db.* TO 'tomy_app'@'localhost';
GRANT ALL PRIVILEGES ON tomy_db.* TO 'tomy_app'@'127.0.0.1';

FLUSH PRIVILEGES;
