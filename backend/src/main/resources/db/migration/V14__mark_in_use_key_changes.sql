-- ============================================================
-- V14: 变更事件标记「正在使用」的密钥
-- ============================================================
-- 目的：
--   1. 变更记录页对「正在使用（启用）」密钥发生的变更高亮显示，便于快速查看；
--   2. 密钥采集任务结束后，对这类变更发送邮件提醒。
-- 说明：
--   in_use 仅在 entity_type = 'API_KEY' 的事件上有意义：
--   当密钥在变更前后任一时刻处于启用（ACTIVE）状态时记为 true。

ALTER TABLE upstream_change_events
    ADD COLUMN in_use BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN upstream_change_events.in_use IS '变更是否涉及正在使用的密钥（API_KEY 事件专用）';

CREATE INDEX upstream_change_events_in_use_key_idx
    ON upstream_change_events (detected_at DESC)
    WHERE in_use;
