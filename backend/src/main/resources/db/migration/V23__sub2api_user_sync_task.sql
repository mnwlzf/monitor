-- ============================================================
-- V23: Sub2API 平台用户同步（QQ 机器人身份识别）
-- ============================================================
-- 背景：
--   QQ 机器人需要区分「自己平台的用户」和「陌生人」：
--   前者以监控助手的身份回答，后者只能是普通聊天，且绝不能接触任何平台信息。
--   判定依据是邮箱 —— 把自建 Sub2API 的 public.users 邮箱同步到 Redis 即可。
--
-- 说明：
--   该任务默认启用，但只有在配置了 Sub2API 只读库
--   （monitor.pool.ingest.url / username）时才真正工作，未配置时每轮直接跳过。
--   只读库账号需要对 public.users 有 SELECT 权限：
--       GRANT SELECT ON public.users TO monitor_ro;

INSERT INTO scheduled_tasks (task_name, task_code, cron_expression, timezone, enabled, description)
VALUES ('Sub2API 用户同步', 'sub2api-user-sync', '0 */10 * * * ?', 'Asia/Shanghai', TRUE,
        '同步自建 Sub2API 的平台用户邮箱与角色到 Redis，供 QQ 机器人识别身份；未配置只读库时自动跳过');