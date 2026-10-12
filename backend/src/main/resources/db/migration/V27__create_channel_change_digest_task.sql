-- ============================================================
-- V27: 渠道变更每日汇总定时任务
-- ============================================================
-- 只汇总【非在用】渠道的变更（在用渠道的倍率变化走实时告警，见 CHANNEL_ALERT）。
-- 默认每天上午 09:00 发一封，收件人在「系统设置 → 邮件通知」里单独配置。
--
-- 用 WHERE NOT EXISTS 保证幂等：万一已经手动建过同名任务，不会重复插入。

INSERT INTO scheduled_tasks (task_name, task_code, cron_expression, timezone, enabled, description)
SELECT '渠道变更每日汇总', 'channel-change-digest', '0 0 9 * * ?', 'Asia/Shanghai', TRUE,
       '汇总最近 24 小时非在用渠道的变更，正文由大模型总结后发邮件'
WHERE NOT EXISTS (
    SELECT 1 FROM scheduled_tasks WHERE task_code = 'channel-change-digest'
);
