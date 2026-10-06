-- ============================================================
-- V13: 邮件收件人按事件场景区分
-- ============================================================
-- 不同事件使用各自的收件人：
--   BALANCE_ALERT  余额不足提醒
--   DAILY_REPORT   每日余额消耗报表
-- 已有收件人默认归入余额不足提醒，避免升级后丢配置。

ALTER TABLE mail_recipients
    ADD COLUMN scene VARCHAR(50);

UPDATE mail_recipients
SET scene = 'BALANCE_ALERT'
WHERE scene IS NULL;

ALTER TABLE mail_recipients
    ALTER COLUMN scene SET NOT NULL;

DROP INDEX IF EXISTS mail_recipients_email_uniq;

CREATE UNIQUE INDEX mail_recipients_scene_email_uniq
    ON mail_recipients (scene, lower(email));

COMMENT ON COLUMN mail_recipients.scene IS '事件场景：BALANCE_ALERT（余额不足提醒）、DAILY_REPORT（每日余额消耗报表）';