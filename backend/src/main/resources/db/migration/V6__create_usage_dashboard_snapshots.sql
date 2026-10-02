-- ============================================================
-- V6: 账号用量看板快照
-- ============================================================
-- 设计说明：
--   跨平台语义一致的指标使用强类型公共列，平台特有明细统一放入 metrics JSONB。
--   这样新增平台接入时无需 ALTER TABLE，只需调整写入映射。
--   newapi 仅提供 total_requests / total_cost / balance，其余列允许为 NULL。

CREATE TABLE account_usage_dashboard_snapshots
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id          INTEGER      NOT NULL,
    collection_run_id   BIGINT,
    platform_type       VARCHAR(50)  NOT NULL,

    -- 公共指标：各平台能对齐的语义
    balance             NUMERIC(30, 8),
    frozen_balance      NUMERIC(30, 8),
    total_requests      BIGINT,
    total_tokens        BIGINT,
    total_cost          NUMERIC(30, 8),
    total_actual_cost   NUMERIC(30, 8),

    -- 平台特有明细：token 分类、today_*、rpm/tpm、api_keys 等
    metrics             JSONB        NOT NULL DEFAULT '{}'::jsonb,
    platform_stats      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    raw_data            JSONB        NOT NULL DEFAULT '{}'::jsonb,

    collected_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_usage_dashboard_account
        FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE,
    CONSTRAINT fk_usage_dashboard_run
        FOREIGN KEY (collection_run_id) REFERENCES collection_runs (id) ON DELETE SET NULL
);

COMMENT ON TABLE account_usage_dashboard_snapshots IS '账号用量看板时序快照，公共指标强类型，平台特有明细放 metrics';
COMMENT ON COLUMN account_usage_dashboard_snapshots.platform_type IS '平台类型：newapi、sub2api 等';
COMMENT ON COLUMN account_usage_dashboard_snapshots.balance IS '可用余额，';
COMMENT ON COLUMN account_usage_dashboard_snapshots.frozen_balance IS '冻结余额，';
COMMENT ON COLUMN account_usage_dashboard_snapshots.total_requests IS '累计请求数';
COMMENT ON COLUMN account_usage_dashboard_snapshots.total_tokens IS '累计 token 总数';
COMMENT ON COLUMN account_usage_dashboard_snapshots.total_cost IS '累计消耗，';
COMMENT ON COLUMN account_usage_dashboard_snapshots.total_actual_cost IS '累计实际成本，，平台未提供时为空';
COMMENT ON COLUMN account_usage_dashboard_snapshots.metrics IS '平台特有明细，JSONB 对象';
COMMENT ON COLUMN account_usage_dashboard_snapshots.platform_stats IS '按平台拆分的统计明细，JSONB 数组';
COMMENT ON COLUMN account_usage_dashboard_snapshots.raw_data IS '上游原始用量看板响应，JSONB';
COMMENT ON COLUMN account_usage_dashboard_snapshots.collected_at IS '采集时间';

CREATE INDEX account_usage_dashboard_account_time_idx
    ON account_usage_dashboard_snapshots (account_id, collected_at DESC);
CREATE INDEX account_usage_dashboard_run_idx
    ON account_usage_dashboard_snapshots (collection_run_id);
CREATE INDEX account_usage_dashboard_platform_time_idx
    ON account_usage_dashboard_snapshots (platform_type, collected_at DESC);