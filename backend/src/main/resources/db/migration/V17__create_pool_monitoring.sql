-- ============================================================
-- V17: Sub2API 号池监控（管理员密钥 + 号池账号 + 逐请求明细）
-- ============================================================
-- 背景：
--   用户自己的 sub2api 平台会把上游账号的 API Key 作为号池账号（accounts）加入。
--   需要监控每个号在自己平台上的缓存命中率、首 token 耗时等指标。
-- 数据来源：
--   用户自建 sub2api 的管理员接口，认证方式为请求头 x-api-key: <管理员密钥>
--   （不需要登录、不经过 Turnstile），因此密钥需要按平台加密保存。
-- 说明：
--   /admin/usage 的时间过滤只支持到「天」，所以这里保存「逐请求精简明细」，
--   各种时间粒度（5 分钟 / 小时 / 天）都在本地聚合，不受接口粒度限制。

-- ------------------------------------------------------------
-- 1) 平台级：Sub2API 管理员密钥（AES-GCM 加密）
-- ------------------------------------------------------------
ALTER TABLE platforms
    ADD COLUMN admin_key_encryption_algorithm  VARCHAR(50),
    ADD COLUMN admin_key_encrypted_payload     TEXT,
    ADD COLUMN admin_key_initialization_vector VARCHAR(120),
    ADD COLUMN admin_key_key_version           INTEGER;

COMMENT ON COLUMN platforms.admin_key_encrypted_payload IS 'Sub2API 管理员密钥 AES-GCM 密文，仅用于只读调用管理员接口';

-- ------------------------------------------------------------
-- 2) 号池账号（对应用户自建 sub2api 的 accounts）
-- ------------------------------------------------------------
CREATE TABLE pool_accounts
(
    id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    platform_id                 INTEGER      NOT NULL,
    external_account_id         BIGINT       NOT NULL,
    name                        VARCHAR(255),
    platform                    VARCHAR(50),
    account_type                VARCHAR(50),
    status                      VARCHAR(30),
    schedulable                 BOOLEAN      NOT NULL DEFAULT TRUE,
    error_message               TEXT,
    rate_limited_at             TIMESTAMPTZ,
    rate_limit_reset_at         TIMESTAMPTZ,
    overload_until              TIMESTAMPTZ,
    temp_unschedulable_until    TIMESTAMPTZ,
    temp_unschedulable_reason   VARCHAR(255),
    concurrency                 INTEGER,
    priority                    INTEGER,
    rate_multiplier             NUMERIC(10, 4),
    last_used_at                TIMESTAMPTZ,
    -- 与上游 Key 的绑定关系（本地 account_api_keys.id），未匹配时为空
    bound_key_id                BIGINT,
    -- 明细采集水位：该号最后一条已入库请求的 created_at
    last_sample_at              TIMESTAMPTZ,
    last_sync_error             TEXT,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_pool_account_platform
        FOREIGN KEY (platform_id) REFERENCES platforms (id) ON DELETE CASCADE,
    CONSTRAINT fk_pool_account_key
        FOREIGN KEY (bound_key_id) REFERENCES account_api_keys (id) ON DELETE SET NULL
);

COMMENT ON TABLE pool_accounts IS '用户自建 Sub2API 的号池账号';
COMMENT ON COLUMN pool_accounts.external_account_id IS '自建 sub2api 的 account id';
COMMENT ON COLUMN pool_accounts.bound_key_id IS '关联的本地上游 Key（account_api_keys.id），按密钥哈希自动匹配';
COMMENT ON COLUMN pool_accounts.last_sample_at IS '该号明细采集水位（最后入库请求时间）';
COMMENT ON COLUMN pool_accounts.temp_unschedulable_reason IS '临时不可调度原因';

CREATE UNIQUE INDEX pool_accounts_platform_external_uniq
    ON pool_accounts (platform_id, external_account_id);
CREATE INDEX pool_accounts_platform_idx
    ON pool_accounts (platform_id);
CREATE INDEX pool_accounts_bound_key_idx
    ON pool_accounts (bound_key_id);

-- ------------------------------------------------------------
-- 3) 号池逐请求明细（精简字段，不存原始 JSON）
-- ------------------------------------------------------------
CREATE TABLE pool_request_samples
(
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    platform_id           INTEGER        NOT NULL,
    external_account_id   BIGINT         NOT NULL,
    request_id            VARCHAR(64)    NOT NULL,
    api_key_id            BIGINT,
    model                 VARCHAR(100),
    channel_id            BIGINT,
    endpoint              VARCHAR(255),
    stream                BOOLEAN,
    created_at            TIMESTAMPTZ    NOT NULL,
    first_token_ms        INTEGER,
    duration_ms           INTEGER,
    input_tokens          BIGINT,
    output_tokens         BIGINT,
    cache_read_tokens     BIGINT,
    cache_creation_tokens BIGINT,
    total_cost            NUMERIC(20, 10),
    actual_cost           NUMERIC(20, 10),
    ingested_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT fk_pool_sample_platform
        FOREIGN KEY (platform_id) REFERENCES platforms (id) ON DELETE CASCADE
);

COMMENT ON TABLE pool_request_samples IS '号池逐请求精简明细，用于本地任意粒度聚合';
COMMENT ON COLUMN pool_request_samples.request_id IS '上游请求 ID，用于幂等去重';
COMMENT ON COLUMN pool_request_samples.first_token_ms IS '首 token 耗时（毫秒），仅流式请求有值';

CREATE UNIQUE INDEX pool_request_samples_request_uniq
    ON pool_request_samples (platform_id, request_id);
CREATE INDEX pool_request_samples_account_time_idx
    ON pool_request_samples (external_account_id, created_at DESC);
CREATE INDEX pool_request_samples_time_idx
    ON pool_request_samples (created_at DESC);

-- ------------------------------------------------------------
-- 4) 定时任务
-- ------------------------------------------------------------
INSERT INTO scheduled_tasks (task_name, task_code, cron_expression, timezone, enabled, description)
VALUES ('号池健康度同步', 'pool-health', '0 */5 * * * ?', 'Asia/Shanghai', TRUE,
        '同步自建 Sub2API 号池账号列表与健康状态（限流/报错/不可调度）'),
       ('号池明细采集', 'pool-samples', '0 */15 * * * ?', 'Asia/Shanghai', TRUE,
        '增量采集号池逐请求明细（缓存命中率、首 token 耗时等）');