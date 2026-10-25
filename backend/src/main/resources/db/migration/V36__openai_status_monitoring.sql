-- ============================================================
-- V36: OpenAI 官方服务状态监控与 QQ 机器人查询
-- ============================================================
-- 每 10 分钟读取 https://status.openai.com/api/v2/summary.json：
--   * 连续异常期间只向「指定群（平台功能）」发送一次；
--   * 恢复后重置，下一次异常再发送；
--   * 发送失败不置为已发送，下一轮继续尝试。

CREATE TABLE openai_status_state (
    id                  SMALLINT PRIMARY KEY,
    alert_active        BOOLEAN NOT NULL DEFAULT FALSE,
    alert_sent          BOOLEAN NOT NULL DEFAULT FALSE,
    last_indicator      VARCHAR(32),
    last_description    VARCHAR(255),
    last_checked_at     TIMESTAMPTZ,
    last_changed_at     TIMESTAMPTZ,
    last_alerted_at     TIMESTAMPTZ,
    last_recovered_at   TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT openai_status_state_single_row CHECK (id = 1)
);

COMMENT ON TABLE openai_status_state IS 'OpenAI 服务状态监控的单行告警状态';
COMMENT ON COLUMN openai_status_state.alert_active IS '当前是否处于一次连续异常中';
COMMENT ON COLUMN openai_status_state.alert_sent IS '本次连续异常是否已成功发送过告警';
COMMENT ON COLUMN openai_status_state.last_indicator IS '最近一次状态页 indicator：none/minor/major/critical/maintenance';
COMMENT ON COLUMN openai_status_state.last_description IS '最近一次状态页原始描述（仅用于排障，展示时翻译为中文）';
COMMENT ON COLUMN openai_status_state.last_checked_at IS '最近一次成功读取状态页的时间';
COMMENT ON COLUMN openai_status_state.last_changed_at IS '最近一次在正常/异常之间切换的时间';
COMMENT ON COLUMN openai_status_state.last_alerted_at IS '本次异常告警成功发送的时间';
COMMENT ON COLUMN openai_status_state.last_recovered_at IS '最近一次恢复正常的时间';

INSERT INTO openai_status_state (id, alert_active, alert_sent)
VALUES (1, FALSE, FALSE)
ON CONFLICT (id) DO NOTHING;

INSERT INTO scheduled_tasks (task_name, task_code, cron_expression, timezone, enabled, description)
SELECT 'OpenAI 服务状态监控', 'openai-status', '0 */10 * * * ?', 'Asia/Shanghai', TRUE,
       '每 10 分钟读取 OpenAI 官方状态页；连续异常只向「指定群（平台功能）」发送一次，恢复后再次异常会重新提醒'
WHERE NOT EXISTS (
    SELECT 1 FROM scheduled_tasks
    WHERE task_code = 'openai-status' OR task_name = 'OpenAI 服务状态监控'
);