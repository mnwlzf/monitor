-- ============================================================
-- V40: 智能运维事故中心与故障自愈最小闭环
-- ============================================================

CREATE TABLE ops_incidents
(
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fingerprint    VARCHAR(200) NOT NULL,
    title          VARCHAR(200) NOT NULL,
    severity       VARCHAR(10)  NOT NULL DEFAULT 'P2',
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    scope_type     VARCHAR(40)  NOT NULL DEFAULT 'ERROR_FINGERPRINT',
    scope_id       VARCHAR(200),
    root_cause_code VARCHAR(200),
    impact_summary TEXT,
    evidence_json  TEXT,
    first_seen_at  TIMESTAMPTZ  NOT NULL,
    last_seen_at   TIMESTAMPTZ  NOT NULL,
    resolved_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ops_incidents_severity CHECK (severity IN ('P0', 'P1', 'P2', 'P3')),
    CONSTRAINT ops_incidents_status CHECK (status IN ('ACTIVE', 'MITIGATING', 'RESOLVED'))
);

COMMENT ON TABLE ops_incidents IS '智能运维事故主表';
COMMENT ON COLUMN ops_incidents.fingerprint IS '稳定事故指纹，用于同一故障去重';
COMMENT ON COLUMN ops_incidents.evidence_json IS '生成事故时的结构化证据快照';

CREATE UNIQUE INDEX ops_incidents_active_fingerprint_uniq
    ON ops_incidents (fingerprint)
    WHERE status <> 'RESOLVED';
CREATE INDEX ops_incidents_status_seen_idx
    ON ops_incidents (status, last_seen_at DESC);
CREATE INDEX ops_incidents_severity_seen_idx
    ON ops_incidents (severity, last_seen_at DESC);

CREATE TABLE ops_incident_events
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id BIGINT       NOT NULL REFERENCES ops_incidents (id) ON DELETE CASCADE,
    event_type  VARCHAR(40)  NOT NULL,
    actor       VARCHAR(200),
    content     TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE ops_incident_events IS '事故时间线事件';

CREATE INDEX ops_incident_events_incident_idx
    ON ops_incident_events (incident_id, created_at DESC);

CREATE TABLE ops_action_plans
(
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id       BIGINT       NOT NULL REFERENCES ops_incidents (id) ON DELETE CASCADE,
    plan_code         VARCHAR(40)  NOT NULL,
    action_type       VARCHAR(40)  NOT NULL,
    target_type       VARCHAR(40),
    target_id         BIGINT,
    target_name       VARCHAR(200),
    risk_level        VARCHAR(10)  NOT NULL DEFAULT 'LOW',
    status            VARCHAR(30)  NOT NULL DEFAULT 'PENDING_APPROVAL',
    summary           TEXT,
    expected_effect   TEXT,
    rollback_plan_json TEXT,
    before_json       TEXT,
    after_json        TEXT,
    confirmation_code VARCHAR(20)  NOT NULL,
    expires_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(200),
    approved_by       VARCHAR(200),
    approved_at       TIMESTAMPTZ,
    executed_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ops_action_plans_risk CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ops_action_plans_status CHECK (
        status IN ('PENDING_APPROVAL', 'EXECUTING', 'EXECUTED', 'FAILED', 'EXPIRED', 'CANCELLED')
    )
);

COMMENT ON TABLE ops_action_plans IS '故障自愈动作计划及确认信息';

CREATE UNIQUE INDEX ops_action_plans_plan_code_uniq
    ON ops_action_plans (plan_code);
CREATE INDEX ops_action_plans_incident_idx
    ON ops_action_plans (incident_id, created_at DESC);
CREATE INDEX ops_action_plans_status_expires_idx
    ON ops_action_plans (status, expires_at);

CREATE TABLE ops_action_executions
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_id       BIGINT       NOT NULL REFERENCES ops_action_plans (id) ON DELETE CASCADE,
    status        VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
    request_json  TEXT,
    response_json TEXT,
    error_message TEXT,
    started_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,

    CONSTRAINT ops_action_executions_status CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED'))
);

COMMENT ON TABLE ops_action_executions IS '故障自愈动作执行记录';

CREATE INDEX ops_action_executions_plan_idx
    ON ops_action_executions (plan_id, started_at DESC);

INSERT INTO scheduled_tasks (task_name, task_code, cron_expression, timezone, enabled, description)
SELECT '智能运维事故巡检', 'ops-incident-refresh', '0 */5 * * * ?', 'Asia/Shanghai', TRUE,
       '每 5 分钟从 Sub2API 错误指纹生成/更新事故，自动关闭已恢复事故'
WHERE NOT EXISTS (SELECT 1 FROM scheduled_tasks WHERE task_code = 'ops-incident-refresh' OR task_name = '智能运维事故巡检');