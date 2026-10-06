-- ============================================================
-- V10: 采集任务拆分（余额 / 分组渠道倍率 / API Key）
-- ============================================================
-- 设计说明：
--   原先只有「全量账号采集」一个任务，一次把余额、分组倍率、API Key 全采一遍，
--   无法分别控制频率。这里按上游实际接口把采集拆成三个互不重叠的任务：
--     1) collect-balances   余额：newapi /api/user/self（余额+用量看板同源）；
--                                 sub2api /api/v1/auth/me + /usage/dashboard/stats
--     2) collect-groups     分组倍率：newapi /api/user/self/groups；
--                                 sub2api /api/v1/groups/available
--     3) collect-api-keys   API Key：newapi /api/token/ 及每个 Key 明文接口；
--                                 sub2api /api/v1/keys + /usage/dashboard/api-keys-usage
--   旧的「全量账号采集」保留为手动全量刷新入口，默认停用。

-- 采集批次增加范围字段，便于区分每条记录属于哪个任务
ALTER TABLE collection_runs
    ADD COLUMN scope VARCHAR(20) NOT NULL DEFAULT 'FULL';

COMMENT ON COLUMN collection_runs.scope IS '采集范围：FULL、BALANCE、GROUPS、API_KEYS';

-- 停用旧的默认全量任务（处理器仍保留，可在页面手动开启）
UPDATE scheduled_tasks
SET enabled = FALSE,
    updated_at = now()
WHERE task_code = 'collect-all-accounts';

-- 新增三个拆分任务
INSERT INTO scheduled_tasks (task_name, task_code, cron_expression, timezone, enabled, description)
VALUES ('余额采集', 'collect-balances', '0 */5 * * * ?', 'Asia/Shanghai', TRUE,
        '并发采集所有启用账号的余额、额度与用量看板'),
       ('分组·渠道倍率采集', 'collect-groups', '0 */30 * * * ?', 'Asia/Shanghai', TRUE,
        '并发采集所有启用账号的分组与渠道倍率'),
       ('API Key 采集', 'collect-api-keys', '0 0 * * * ?', 'Asia/Shanghai', TRUE,
        '并发采集所有启用账号的 API Key 与用量');