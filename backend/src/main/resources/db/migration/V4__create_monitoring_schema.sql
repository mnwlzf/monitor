-- ============================================================
-- V4: 监控数据持久化
-- ============================================================

-- accounts 与 platforms 建立可选关联，保留原 platform 字符串以兼容已有数据。
ALTER TABLE accounts
    ADD COLUMN platform_id INTEGER;

ALTER TABLE accounts
    ADD CONSTRAINT fk_accounts_platform
        FOREIGN KEY (platform_id) REFERENCES platforms (id) ON DELETE SET NULL;

CREATE INDEX accounts_platform_id_idx ON accounts (platform_id);

COMMENT ON COLUMN accounts.platform_id IS '关联平台表，历史数据允许为空';

-- ============================================================
-- 1. 采集批次
-- ============================================================
CREATE TABLE collection_runs
(
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id     INTEGER      NOT NULL,
    platform_type  VARCHAR(50)  NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
    started_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ,
    duration_ms    BIGINT,
    error_code     VARCHAR(100),
    error_message  TEXT,
    metadata       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT fk_collection_runs_account
        FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE
);

COMMENT ON TABLE collection_runs IS '上游账号采集批次';
COMMENT ON COLUMN collection_runs.id IS '采集批次主键';
COMMENT ON COLUMN collection_runs.account_id IS '账号 ID';
COMMENT ON COLUMN collection_runs.platform_type IS '平台类型：newapi、sub2api 等';
COMMENT ON COLUMN collection_runs.status IS '执行状态：RUNNING、SUCCESS、PARTIAL、FAILED、SKIPPED';
COMMENT ON COLUMN collection_runs.started_at IS '采集开始时间';
COMMENT ON COLUMN collection_runs.finished_at IS '采集结束时间';
COMMENT ON COLUMN collection_runs.duration_ms IS '采集耗时，毫秒';
COMMENT ON COLUMN collection_runs.error_code IS '失败错误码';
COMMENT ON COLUMN collection_runs.error_message IS '失败错误信息';
COMMENT ON COLUMN collection_runs.metadata IS '采集扩展信息，JSONB';

CREATE INDEX collection_runs_account_time_idx
    ON collection_runs (account_id, started_at DESC);
CREATE INDEX collection_runs_status_time_idx
    ON collection_runs (status, started_at DESC);

-- ============================================================
-- 2. 账号指标快照
-- ============================================================
CREATE TABLE account_metric_snapshots
(
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id           INTEGER      NOT NULL,
    collection_run_id    BIGINT,
    platform_type        VARCHAR(50)  NOT NULL,
    balance              NUMERIC(30, 8),
    frozen_balance       NUMERIC(30, 8),
    quota                NUMERIC(30, 8),
    used_quota           NUMERIC(30, 8),
    aff_quota            NUMERIC(30, 8),
    aff_history_quota    NUMERIC(30, 8),
    request_count        BIGINT,
    quota_unit           VARCHAR(30),
    raw_data             JSONB        NOT NULL DEFAULT '{}'::jsonb,
    content_hash         VARCHAR(64),
    collected_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_account_metric_snapshots_account
        FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE,
    CONSTRAINT fk_account_metric_snapshots_run
        FOREIGN KEY (collection_run_id) REFERENCES collection_runs (id) ON DELETE SET NULL
);

COMMENT ON TABLE account_metric_snapshots IS '账号余额、额度、请求量等指标时序快照';
COMMENT ON COLUMN account_metric_snapshots.balance IS '可用余额，Sub2API 等平台使用';
COMMENT ON COLUMN account_metric_snapshots.frozen_balance IS '冻结余额，Sub2API 等平台使用';
COMMENT ON COLUMN account_metric_snapshots.quota IS '剩余额度，New API 等平台原始值';
COMMENT ON COLUMN account_metric_snapshots.used_quota IS '历史使用额度，New API 等平台原始值';
COMMENT ON COLUMN account_metric_snapshots.aff_quota IS '邀请额度';
COMMENT ON COLUMN account_metric_snapshots.aff_history_quota IS '历史邀请额度';
COMMENT ON COLUMN account_metric_snapshots.request_count IS '累计请求数';
COMMENT ON COLUMN account_metric_snapshots.quota_unit IS '额度单位或换算说明，如 USD、CREDIT、TOKEN';
COMMENT ON COLUMN account_metric_snapshots.raw_data IS '原始账号响应，JSONB';
COMMENT ON COLUMN account_metric_snapshots.content_hash IS '规范化内容哈希，用于去重和变化判断';
COMMENT ON COLUMN account_metric_snapshots.collected_at IS '指标采集时间';

CREATE INDEX account_metric_snapshots_account_time_idx
    ON account_metric_snapshots (account_id, collected_at DESC);
CREATE INDEX account_metric_snapshots_platform_time_idx
    ON account_metric_snapshots (platform_type, collected_at DESC);
CREATE INDEX account_metric_snapshots_run_idx
    ON account_metric_snapshots (collection_run_id);

-- ============================================================
-- 3. 上游渠道/分组当前状态
-- ============================================================
CREATE TABLE upstream_groups
(
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id        INTEGER      NOT NULL,
    platform_type     VARCHAR(50)  NOT NULL,
    external_group_id VARCHAR(255) NOT NULL,
    group_name        VARCHAR(255) NOT NULL,
    description       TEXT,
    platform          VARCHAR(50),
    current_ratio     NUMERIC(30, 8),
    current_base_ratio NUMERIC(30, 8),
    status            VARCHAR(50),
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    first_seen_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_changed_at   TIMESTAMPTZ,
    metadata          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT fk_upstream_groups_account
        FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE,
    CONSTRAINT uk_upstream_groups_identity
        UNIQUE (account_id, platform_type, external_group_id)
);

COMMENT ON TABLE upstream_groups IS '上游渠道/分组当前状态，用于快速对比和展示';
COMMENT ON COLUMN upstream_groups.external_group_id IS '上游稳定标识：Sub2API 使用 id，New API 使用分组名';
COMMENT ON COLUMN upstream_groups.group_name IS '上游分组名称';
COMMENT ON COLUMN upstream_groups.current_ratio IS '当前倍率';
COMMENT ON COLUMN upstream_groups.current_base_ratio IS '当前基础倍率';
COMMENT ON COLUMN upstream_groups.status IS '上游状态';
COMMENT ON COLUMN upstream_groups.is_active IS '当前是否仍被上游返回';
COMMENT ON COLUMN upstream_groups.first_seen_at IS '首次发现时间';
COMMENT ON COLUMN upstream_groups.last_seen_at IS '最近一次发现时间';
COMMENT ON COLUMN upstream_groups.last_changed_at IS '最近一次倍率或状态变化时间';
COMMENT ON COLUMN upstream_groups.metadata IS '上游分组原始扩展字段，JSONB';

CREATE INDEX upstream_groups_account_active_idx
    ON upstream_groups (account_id, is_active);
CREATE INDEX upstream_groups_name_idx
    ON upstream_groups (group_name);

-- ============================================================
-- 4. 上游渠道/分组快照
-- ============================================================
CREATE TABLE upstream_group_snapshots
(
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id          BIGINT      NOT NULL,
    collection_run_id BIGINT,
    ratio             NUMERIC(30, 8),
    base_ratio        NUMERIC(30, 8),
    status            VARCHAR(50),
    is_active         BOOLEAN     NOT NULL DEFAULT TRUE,
    raw_data          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    content_hash      VARCHAR(64),
    collected_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_upstream_group_snapshots_group
        FOREIGN KEY (group_id) REFERENCES upstream_groups (id) ON DELETE CASCADE,
    CONSTRAINT fk_upstream_group_snapshots_run
        FOREIGN KEY (collection_run_id) REFERENCES collection_runs (id) ON DELETE SET NULL
);

COMMENT ON TABLE upstream_group_snapshots IS '上游渠道/分组指标时序快照';
COMMENT ON COLUMN upstream_group_snapshots.ratio IS '采集时倍率';
COMMENT ON COLUMN upstream_group_snapshots.base_ratio IS '采集时基础倍率';
COMMENT ON COLUMN upstream_group_snapshots.is_active IS '采集时是否有效';
COMMENT ON COLUMN upstream_group_snapshots.raw_data IS '原始分组响应，JSONB';
COMMENT ON COLUMN upstream_group_snapshots.content_hash IS '规范化内容哈希，用于去重和变化判断';
COMMENT ON COLUMN upstream_group_snapshots.collected_at IS '采集时间';

CREATE INDEX upstream_group_snapshots_group_time_idx
    ON upstream_group_snapshots (group_id, collected_at DESC);
CREATE INDEX upstream_group_snapshots_run_idx
    ON upstream_group_snapshots (collection_run_id);

-- ============================================================
-- 5. 变更事件
-- ============================================================
CREATE TABLE upstream_change_events
(
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id        INTEGER      NOT NULL,
    platform_type     VARCHAR(50)  NOT NULL,
    collection_run_id BIGINT,
    entity_type       VARCHAR(30)  NOT NULL,
    entity_id         BIGINT,
    entity_key        VARCHAR(255),
    change_type       VARCHAR(50)  NOT NULL,
    field_name        VARCHAR(100),
    old_value         JSONB,
    new_value         JSONB,
    severity          VARCHAR(20)  NOT NULL DEFAULT 'INFO',
    message           TEXT,
    detected_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    metadata          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT fk_upstream_change_events_account
        FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE,
    CONSTRAINT fk_upstream_change_events_run
        FOREIGN KEY (collection_run_id) REFERENCES collection_runs (id) ON DELETE SET NULL
);

COMMENT ON TABLE upstream_change_events IS '采集后产生的渠道、倍率、账号指标等变更事件';
COMMENT ON COLUMN upstream_change_events.entity_type IS '实体类型：ACCOUNT、GROUP、API_KEY';
COMMENT ON COLUMN upstream_change_events.entity_id IS '本地实体 ID，可为空';
COMMENT ON COLUMN upstream_change_events.entity_key IS '上游稳定标识，如分组名或外部 ID';
COMMENT ON COLUMN upstream_change_events.change_type IS '变更类型：GROUP_ADDED、GROUP_REMOVED、RATE_CHANGED、STATUS_CHANGED 等';
COMMENT ON COLUMN upstream_change_events.field_name IS '发生变化的具体字段';
COMMENT ON COLUMN upstream_change_events.old_value IS '变化前值，JSONB';
COMMENT ON COLUMN upstream_change_events.new_value IS '变化后值，JSONB';
COMMENT ON COLUMN upstream_change_events.severity IS '事件级别：INFO、WARNING、CRITICAL';
COMMENT ON COLUMN upstream_change_events.detected_at IS '检测到变化的时间';

CREATE INDEX upstream_change_events_account_time_idx
    ON upstream_change_events (account_id, detected_at DESC);
CREATE INDEX upstream_change_events_type_time_idx
    ON upstream_change_events (change_type, detected_at DESC);
CREATE INDEX upstream_change_events_entity_time_idx
    ON upstream_change_events (entity_type, entity_id, detected_at DESC);