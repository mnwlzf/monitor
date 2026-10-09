-- ============================================================
-- V21: 直连自建 Sub2API 数据库做「秒级」增量采集
-- ============================================================
-- 背景：
--   上游 /api/v1/admin/usage 只支持按天过滤且没有游标，无法做分钟级采集。
--   而自建 Sub2API 的 usage_logs 表：
--     - id 是 BIGSERIAL（单调自增）→ 天然增量游标；
--     - 有 idx_usage_logs_account_created_at (account_id, created_at) 复合索引；
--     - request_id 有唯一索引 → 落库去重天然幂等。
--   因此改为「直连只读库 + id 游标」增量同步，滞后可压到 30 秒级。
--
-- 说明：
--   该任务默认启用，但只有在配置了 monitor.pool.ingest.*（数据源）时才真正工作，
--   未配置时每轮直接跳过，不影响其他采集。

-- 增量游标（单行表）
CREATE TABLE sub2api_ingest_cursor
(
    id                SMALLINT    NOT NULL,
    last_usage_log_id BIGINT      NOT NULL DEFAULT 0,
    last_run_at       TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_sub2api_ingest_cursor PRIMARY KEY (id),
    CONSTRAINT ck_sub2api_ingest_cursor_single_row CHECK (id = 1)
);

COMMENT ON TABLE sub2api_ingest_cursor IS '直连 Sub2API 库增量采集的 id 游标（单行）';
COMMENT ON COLUMN sub2api_ingest_cursor.last_usage_log_id IS '已同步到的 usage_logs.id；0 表示尚未初始化（首轮会从当前最大值开始，不回灌历史）';

INSERT INTO sub2api_ingest_cursor (id, last_usage_log_id)
VALUES (1, 0)
ON CONFLICT (id) DO NOTHING;

-- 直连库增量任务：每 30 秒一轮
INSERT INTO scheduled_tasks (task_name, task_code, cron_expression, timezone, enabled, description)
VALUES ('号池明细增量（直连库）', 'pool-samples-db', '*/30 * * * * ?', 'Asia/Shanghai', TRUE,
        '直连自建 Sub2API 数据库，按 usage_logs.id 游标每 30 秒增量同步号池明细；未配置数据源时自动跳过');