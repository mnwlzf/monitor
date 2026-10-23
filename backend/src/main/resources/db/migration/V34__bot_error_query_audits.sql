-- ============================================================
-- V34: QQ 机器人错误查询审计
-- ============================================================
CREATE TABLE IF NOT EXISTS bot_error_query_audits
(
    id          BIGSERIAL PRIMARY KEY,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    qq_user_id  BIGINT,
    action      VARCHAR(64) NOT NULL,
    query_text  VARCHAR(500),
    allowed     BOOLEAN NOT NULL DEFAULT TRUE,
    duration_ms INTEGER
);

CREATE INDEX IF NOT EXISTS bot_error_query_audits_time_idx
    ON bot_error_query_audits (created_at DESC);
CREATE INDEX IF NOT EXISTS bot_error_query_audits_user_time_idx
    ON bot_error_query_audits (qq_user_id, created_at DESC);

COMMENT ON TABLE bot_error_query_audits IS 'QQ 机器人错误查询审计：谁在什么时候查了什么';
