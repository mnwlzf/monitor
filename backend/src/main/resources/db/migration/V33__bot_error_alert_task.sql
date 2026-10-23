-- ============================================================
-- V33: Sub2API 错误突增主动告警任务
-- ============================================================
INSERT INTO scheduled_tasks (task_name, task_code, cron_expression, timezone, enabled, description)
SELECT 'Sub2API 错误告警', 'bot-error-alert', '0 */5 * * * ?', 'Asia/Shanghai', TRUE,
       '每 5 分钟检查错误、429/529 和入口拒绝是否突增，按页面配置推送到群或私聊；未配置接收人或只读库时自动跳过'
WHERE NOT EXISTS (SELECT 1 FROM scheduled_tasks WHERE task_code = 'bot-error-alert' OR task_name = 'Sub2API 错误告警');
