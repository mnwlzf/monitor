-- ============================================================
-- V1: 创建 accounts 表
-- ============================================================

CREATE TABLE accounts
(
    id         INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email      VARCHAR(255) NOT NULL,
    username   VARCHAR(50)  NOT NULL,
    platform   VARCHAR(50)  NOT NULL,
    url        VARCHAR(500),
    status     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT
ON TABLE  accounts            IS '账号表';
COMMENT
ON COLUMN accounts.id         IS '主键，自增';
COMMENT
ON COLUMN accounts.email      IS '邮箱';
COMMENT
ON COLUMN accounts.username   IS '用户名';
COMMENT
ON COLUMN accounts.platform   IS '平台';
COMMENT
ON COLUMN accounts.url        IS '平台地址';
COMMENT
ON COLUMN accounts.status     IS '是否启用检测，true=启用';
COMMENT
ON COLUMN accounts.created_at IS '创建时间';
COMMENT
ON COLUMN accounts.updated_at IS '更新时间';

-- 同一平台下邮箱 / 用户名唯一
CREATE UNIQUE INDEX accounts_platform_email_uniq
    ON accounts (platform, email);
CREATE UNIQUE INDEX accounts_platform_username_uniq
    ON accounts (platform, username);

-- 按平台查询的索引
CREATE INDEX accounts_platform_idx ON accounts (platform);