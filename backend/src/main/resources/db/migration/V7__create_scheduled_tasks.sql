-- ============================================================
-- V7: 页面可配置的定时任务
-- ============================================================
CREATE TABLE scheduled_tasks
(
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_name         VARCHAR(100) NOT NULL,
    task_code         VARCHAR(100) NOT NULL,
    cron_expression   VARCHAR(120) NOT NULL,
    timezone          VARCHAR(50)  NOT NULL DEFAULT 'Asia/Shanghai',
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    description       VARCHAR(255),
    last_run_at       TIMESTAMPTZ,
    last_run_status   VARCHAR(20),
    last_run_message  TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE scheduled_tasks IS '页面可配置的定时任务';
COMMENT ON COLUMN scheduled_tasks.task_code IS '任务处理器编码，由后端注册';
COMMENT ON COLUMN scheduled_tasks.cron_expression IS '秒级六段 Cron 表达式';
COMMENT ON COLUMN scheduled_tasks.timezone IS 'Cron 时区';
COMMENT ON COLUMN scheduled_tasks.last_run_status IS '最近执行状态：RUNNING、SUCCESS、FAILED';

CREATE UNIQUE INDEX scheduled_tasks_name_uniq ON scheduled_tasks (task_name);
CREATE INDEX scheduled_tasks_enabled_idx ON scheduled_tasks (enabled);

INSERT INTO scheduled_tasks (task_name, task_code, cron_expression, timezone, enabled, description)
VALUES ('全量账号采集', 'collect-all-accounts', '0 */10 * * * ?', 'Asia/Shanghai', TRUE, '每 10 分钟并发采集所有启用账号');
