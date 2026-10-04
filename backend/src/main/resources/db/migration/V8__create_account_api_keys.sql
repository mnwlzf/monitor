-- ============================================================
-- V8: 账号 API Key 级用量监控
-- ============================================================
-- 设计说明：
--   沿用 V4/V6 的「当前态 + 时序快照」模式。
--   API Key 的完整明文由采集端获取后使用 AES-GCM 加密存储，
--   同时保存 SHA-256 哈希用于判断密钥是否被轮换。
--   公共指标使用强类型列，平台特有明细统一放入 metrics JSONB。

CREATE TABLE account_api_keys
(
    id                        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id                INTEGER      NOT NULL,
    platform_type             VARCHAR(50)  NOT NULL,
    external_key_id           VARCHAR(100) NOT NULL,

    -- 展示与识别信息
    key_name                  VARCHAR(255),
    key_masked                VARCHAR(160),
    key_hash                  VARCHAR(64),

    -- 完整明文密钥的加密存储（AES-GCM）
    key_encryption_algorithm  VARCHAR(50),
    key_encrypted_payload     TEXT,
    key_initialization_vector VARCHAR(120),
    key_key_version           INTEGER,

    -- 状态与归属
    status                    VARCHAR(30),
    upstream_status           VARCHAR(30),
    group_name                VARCHAR(255),
    group_platform            VARCHAR(120),

    -- 额度指标（统一换算为 USD；quota_unit 记录原始单位）
    unlimited_quota           BOOLEAN,
    remain_quota              NUMERIC(30, 8),
    used_quota                NUMERIC(30, 8),
    quota_unit                VARCHAR(20),

    -- 限制与时间
    model_limits_enabled      BOOLEAN,
    model_limits              TEXT,
    allow_ips                 TEXT,
    expires_at                TIMESTAMPTZ,
    upstream_created_at       TIMESTAMPTZ,
    last_used_at              TIMESTAMPTZ,

    -- 本地状态
    is_active                 BOOLEAN      NOT NULL DEFAULT TRUE,
    first_seen_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_changed_at           TIMESTAMPTZ,

    -- 平台特有明细与原始响应
    metrics                   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    raw_data                  JSONB        NOT NULL DEFAULT '{}'::jsonb,

    CONSTRAINT fk_api_key_account
        FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE
);

COMMENT ON TABLE account_api_keys IS '账号 API Key 当前状态，完整密钥 AES-GCM 加密存储';
COMMENT ON COLUMN account_api_keys.external_key_id IS '上游密钥 ID';
COMMENT ON COLUMN account_api_keys.key_masked IS '脱敏后的密钥，用于界面展示';
COMMENT ON COLUMN account_api_keys.key_hash IS '完整密钥的 SHA-256，用于判断是否轮换';
COMMENT ON COLUMN account_api_keys.key_encrypted_payload IS '完整密钥 AES-GCM 密文';
COMMENT ON COLUMN account_api_keys.status IS '归一化状态：ACTIVE、DISABLED、EXPIRED、EXHAUSTED、UNKNOWN';
COMMENT ON COLUMN account_api_keys.upstream_status IS '上游原始状态值';
COMMENT ON COLUMN account_api_keys.quota_unit IS '额度单位，统一列按 USD 存储';
COMMENT ON COLUMN account_api_keys.metrics IS '平台特有明细，JSONB 对象';
COMMENT ON COLUMN account_api_keys.raw_data IS '上游原始响应，JSONB';

CREATE UNIQUE INDEX account_api_keys_account_external_uniq
    ON account_api_keys (account_id, platform_type, external_key_id);
CREATE INDEX account_api_keys_account_active_idx
    ON account_api_keys (account_id, is_active);
CREATE INDEX account_api_keys_platform_idx
    ON account_api_keys (platform_type);

CREATE TABLE account_api_key_snapshots
(
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    api_key_id        BIGINT       NOT NULL,
    account_id        INTEGER      NOT NULL,
    collection_run_id BIGINT,
    platform_type     VARCHAR(50)  NOT NULL,
    external_key_id   VARCHAR(100) NOT NULL,

    key_name          VARCHAR(255),
    status            VARCHAR(30),
    group_name        VARCHAR(255),
    unlimited_quota   BOOLEAN,
    remain_quota      NUMERIC(30, 8),
    used_quota        NUMERIC(30, 8),
    quota_unit        VARCHAR(20),

    metrics           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    raw_data          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    collected_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_api_key_snapshot_key
        FOREIGN KEY (api_key_id) REFERENCES account_api_keys (id) ON DELETE CASCADE,
    CONSTRAINT fk_api_key_snapshot_account
        FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE,
    CONSTRAINT fk_api_key_snapshot_run
        FOREIGN KEY (collection_run_id) REFERENCES collection_runs (id) ON DELETE SET NULL
);

COMMENT ON TABLE account_api_key_snapshots IS 'API Key 用量时序快照';
COMMENT ON COLUMN account_api_key_snapshots.metrics IS '平台特有明细，JSONB 对象';
COMMENT ON COLUMN account_api_key_snapshots.raw_data IS '上游原始响应，JSONB';
COMMENT ON COLUMN account_api_key_snapshots.collected_at IS '采集时间';

CREATE INDEX account_api_key_snapshots_key_time_idx
    ON account_api_key_snapshots (api_key_id, collected_at DESC);
CREATE INDEX account_api_key_snapshots_account_time_idx
    ON account_api_key_snapshots (account_id, collected_at DESC);
CREATE INDEX account_api_key_snapshots_run_idx
    ON account_api_key_snapshots (collection_run_id);
