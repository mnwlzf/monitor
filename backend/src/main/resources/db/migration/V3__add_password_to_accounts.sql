-- ============================================================
-- V2: accounts 表添加 password 字段
-- ============================================================

ALTER TABLE accounts
    ADD COLUMN password VARCHAR(255);

COMMENT ON COLUMN accounts.password IS '登录密码（加密存储）';