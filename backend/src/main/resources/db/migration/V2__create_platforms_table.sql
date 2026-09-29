-- ============================================================
-- V2: 创建平台表
-- ============================================================

CREATE TABLE platforms
(
    id            INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    platform_name VARCHAR(100) NOT NULL,
    url           VARCHAR(500) NOT NULL,
    platform_type VARCHAR(50)  NOT NULL,
    status        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT
ON TABLE  platforms               IS '平台表';
COMMENT
ON COLUMN platforms.id            IS '主键，自增';
COMMENT
ON COLUMN platforms.platform_name IS '平台名称';
COMMENT
ON COLUMN platforms.url           IS '平台地址';
COMMENT
ON COLUMN platforms.platform_type IS '平台类型，如 sub2api、newapi 等';
COMMENT
ON COLUMN platforms.status        IS '此平台是否启用，true=启用';
COMMENT
ON COLUMN platforms.created_at    IS '创建时间';
COMMENT
ON COLUMN platforms.updated_at    IS '更新时间';

-- 平台名称唯一
CREATE UNIQUE INDEX platforms_platform_name_uniq
    ON platforms (platform_name);

-- 按类型查询
CREATE INDEX platforms_platform_type_idx
    ON platforms (platform_type);