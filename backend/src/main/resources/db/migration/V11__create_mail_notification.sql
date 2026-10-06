-- ============================================================
-- V11: 邮件通知（余额不足提醒 + 收件人管理）
-- ============================================================
-- 设计说明：
--   余额采集任务完成后，判断各启用平台的「账号余额合计」是否低于阈值（默认 5），
--   低于阈值时向页面维护的收件人发送提醒邮件。
--   只要余额仍低于阈值，就每隔 alert_interval_minutes 分钟重复提醒一次；
--   用 last_alert_at 记录最近一次发信时间，作为重复提醒的冷却判断依据。

-- 邮件收件人（页面可增删，支持一个或多个）
CREATE TABLE mail_recipients
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email      VARCHAR(320) NOT NULL,
    name       VARCHAR(120),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE mail_recipients IS '余额提醒邮件收件人';
COMMENT ON COLUMN mail_recipients.email IS '收件人邮箱';
COMMENT ON COLUMN mail_recipients.name IS '收件人名称，可选';

CREATE UNIQUE INDEX mail_recipients_email_uniq ON mail_recipients (lower(email));

-- 通知设置（全局单行）
CREATE TABLE notification_settings
(
    id                     SMALLINT       PRIMARY KEY DEFAULT 1,
    balance_alert_enabled  BOOLEAN        NOT NULL DEFAULT TRUE,
    balance_threshold      NUMERIC(18, 4) NOT NULL DEFAULT 5,
    alert_interval_minutes INTEGER        NOT NULL DEFAULT 360,
    last_alert_at          TIMESTAMPTZ,
    updated_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT notification_settings_single_row CHECK (id = 1)
);

COMMENT ON TABLE notification_settings IS '邮件通知设置，全局单行';
COMMENT ON COLUMN notification_settings.balance_alert_enabled IS '是否启用余额不足提醒';
COMMENT ON COLUMN notification_settings.balance_threshold IS '余额提醒阈值（平台账号余额合计低于该值时提醒）';
COMMENT ON COLUMN notification_settings.alert_interval_minutes IS '重复提醒间隔（分钟）：余额持续低于阈值时每隔该时长再次提醒';
COMMENT ON COLUMN notification_settings.last_alert_at IS '最近一次发送余额提醒的时间';

INSERT INTO notification_settings (id) VALUES (1);