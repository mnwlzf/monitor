-- ============================================================
-- V35: Sub2API 错误主动告警改为页面配置
-- ============================================================
-- 告警开关、阈值与接收人都不再依赖环境变量：
--   * 开关/窗口/阈值/冷却保存在 bot_settings 单行；
--   * 群号/QQ 号复用 qq_recipients，场景为 ERROR_ALERT；
--   * 页面保存后立即生效，不需要重启或重建容器。

ALTER TABLE bot_settings
    ADD COLUMN error_alert_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN error_alert_window_minutes INTEGER NOT NULL DEFAULT 15,
    ADD COLUMN error_alert_min_count INTEGER NOT NULL DEFAULT 5,
    ADD COLUMN error_alert_spike_ratio NUMERIC(6, 2) NOT NULL DEFAULT 1.50,
    ADD COLUMN error_alert_cooldown_minutes INTEGER NOT NULL DEFAULT 30;

COMMENT ON COLUMN bot_settings.error_alert_enabled IS
    '是否启用 Sub2API 错误突增主动告警';
COMMENT ON COLUMN bot_settings.error_alert_window_minutes IS
    '告警当前窗口与对比窗口的分钟数';
COMMENT ON COLUMN bot_settings.error_alert_min_count IS
    '触发告警的最小错误数/429数/入口拒绝数';
COMMENT ON COLUMN bot_settings.error_alert_spike_ratio IS
    '相对上一周期的突增倍数';
COMMENT ON COLUMN bot_settings.error_alert_cooldown_minutes IS
    '同一告警的冷却分钟数';

COMMENT ON COLUMN qq_recipients.scene IS
    '事件场景：BALANCE_ALERT（余额不足）、ERROR_ALERT（Sub2API 错误告警）等';
