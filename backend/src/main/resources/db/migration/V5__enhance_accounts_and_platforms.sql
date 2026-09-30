-- ============================================================
-- V5: 完善平台、账号与登录凭证设计
-- ============================================================

-- ============================================================
-- 1. 平台运行配置
-- ============================================================
ALTER TABLE platforms
    ADD COLUMN description      TEXT,
    ADD COLUMN settings         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN last_collected_at TIMESTAMPTZ,
    ADD COLUMN deleted_at       TIMESTAMPTZ;

COMMENT ON COLUMN platforms.description IS '平台说明';
COMMENT ON COLUMN platforms.settings IS '平台采集配置，JSONB，例如时区、默认请求参数等';
COMMENT ON COLUMN platforms.last_collected_at IS '该平台最近一次采集完成时间';
COMMENT ON COLUMN platforms.deleted_at IS '软删除时间，NULL 表示未删除';

CREATE INDEX platforms_status_deleted_idx
    ON platforms (status, deleted_at);

-- ============================================================
-- 2. 账号运行与调度字段
-- ============================================================
ALTER TABLE accounts
    ADD COLUMN display_name          VARCHAR(255),
    ADD COLUMN external_user_id      VARCHAR(255),
    ADD COLUMN auth_type             VARCHAR(50)  NOT NULL DEFAULT 'PASSWORD',
    ADD COLUMN credential_status     VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN settings              JSONB        NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN last_collected_at     TIMESTAMPTZ,
    ADD COLUMN last_collect_status   VARCHAR(20),
    ADD COLUMN last_collect_error    TEXT,
    ADD COLUMN consecutive_failures  INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN next_collect_at       TIMESTAMPTZ,
    ADD COLUMN deleted_at            TIMESTAMPTZ;

COMMENT ON COLUMN accounts.display_name IS '账号展示名称';
COMMENT ON COLUMN accounts.external_user_id IS '上游平台用户 ID，首次登录后回填';
COMMENT ON COLUMN accounts.auth_type IS '认证类型：PASSWORD、TOKEN、COOKIE、OAUTH';
COMMENT ON COLUMN accounts.credential_status IS '凭证状态：UNKNOWN、VALID、INVALID、LOCKED';
COMMENT ON COLUMN accounts.settings IS '账号采集配置，JSONB，例如时区、Turnstile 参数等';
COMMENT ON COLUMN accounts.last_collected_at IS '最近一次采集完成时间';
COMMENT ON COLUMN accounts.last_collect_status IS '最近一次采集状态：RUNNING、SUCCESS、PARTIAL、FAILED、SKIPPED';
COMMENT ON COLUMN accounts.last_collect_error IS '最近一次采集错误信息';
COMMENT ON COLUMN accounts.consecutive_failures IS '连续采集失败次数';
COMMENT ON COLUMN accounts.next_collect_at IS '下次计划采集时间';
COMMENT ON COLUMN accounts.deleted_at IS '软删除时间，NULL 表示未删除';
COMMENT ON COLUMN accounts.password IS '旧版密码字段，已废弃；新数据写入 account_credentials';

-- 旧唯一索引按 platform 字符串隔离，无法区分同类型的不同平台实例。
DROP INDEX IF EXISTS accounts_platform_email_uniq;
DROP INDEX IF EXISTS accounts_platform_username_uniq;

CREATE UNIQUE INDEX accounts_platform_id_email_uniq
    ON accounts (platform_id, email)
    WHERE platform_id IS NOT NULL AND deleted_at IS NULL;

CREATE UNIQUE INDEX accounts_platform_id_username_uniq
    ON accounts (platform_id, username)
    WHERE platform_id IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX accounts_platform_status_idx
    ON accounts (platform_id, status)
    WHERE deleted_at IS NULL;

CREATE INDEX accounts_collection_schedule_idx
    ON accounts (status, next_collect_at)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX accounts_platform_external_user_uniq
    ON accounts (platform_id, external_user_id)
    WHERE platform_id IS NOT NULL
      AND external_user_id IS NOT NULL
      AND deleted_at IS NULL;

-- ============================================================
-- 3. 账号登录凭证
-- ============================================================
CREATE TABLE account_credentials
(
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id            INTEGER      NOT NULL,
    credential_type       VARCHAR(50)  NOT NULL,
    encryption_algorithm  VARCHAR(30)  NOT NULL DEFAULT 'AES_GCM',
    encrypted_payload     TEXT         NOT NULL,
    initialization_vector VARCHAR(64)  NOT NULL,
    key_version           INTEGER      NOT NULL DEFAULT 1,
    expires_at            TIMESTAMPTZ,
    last_verified_at      TIMESTAMPTZ,
    verification_error    TEXT,
    is_active             BOOLEAN      NOT NULL DEFAULT TRUE,
    metadata              JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_account_credentials_account
        FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE
);

COMMENT ON TABLE account_credentials IS '账号登录凭证，敏感内容加密存储';
COMMENT ON COLUMN account_credentials.account_id IS '账号 ID';
COMMENT ON COLUMN account_credentials.credential_type IS '凭证类型：PASSWORD、TOKEN、COOKIE、OAUTH 等';
COMMENT ON COLUMN account_credentials.encryption_algorithm IS '凭证加密算法，默认 AES_GCM';
COMMENT ON COLUMN account_credentials.encrypted_payload IS 'AES-GCM 等算法加密后的凭证内容，Base64 存储';
COMMENT ON COLUMN account_credentials.initialization_vector IS '加密初始化向量，Base64 或 Hex 存储';
COMMENT ON COLUMN account_credentials.key_version IS '加密密钥版本，用于密钥轮换';
COMMENT ON COLUMN account_credentials.expires_at IS '凭证过期时间';
COMMENT ON COLUMN account_credentials.last_verified_at IS '最近一次验证成功时间';
COMMENT ON COLUMN account_credentials.verification_error IS '最近一次凭证验证失败原因';
COMMENT ON COLUMN account_credentials.is_active IS '是否为当前启用凭证';
COMMENT ON COLUMN account_credentials.metadata IS '凭证扩展信息，JSONB';

CREATE UNIQUE INDEX account_credentials_active_type_uniq
    ON account_credentials (account_id, credential_type)
    WHERE is_active = TRUE;

CREATE INDEX account_credentials_account_idx
    ON account_credentials (account_id);

CREATE INDEX account_credentials_expiry_idx
    ON account_credentials (expires_at)
    WHERE is_active = TRUE;