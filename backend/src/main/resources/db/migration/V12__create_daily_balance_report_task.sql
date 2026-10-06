-- ============================================================
-- V12: 每日余额消耗报表定时任务
-- ============================================================
-- 每天凌晨 00:30 发送前一天各平台、各账号的余额消耗报表。
-- 收件人沿用 mail_recipients（页面可编辑），无需额外配置。

INSERT INTO scheduled_tasks (task_name, task_code, cron_expression, timezone, enabled, description)
VALUES ('每日余额消耗报表', 'daily-balance-report', '0 30 0 * * ?', 'Asia/Shanghai', TRUE,
        '每天凌晨发送前一天各平台、各账号的余额消耗报表');