-- ============================================================
-- V39: QQ 机器人邮箱验证绑定与每日消费抽奖
-- ============================================================

CREATE TABLE bot_user_bindings
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel             VARCHAR(20)  NOT NULL DEFAULT 'QQ',
    external_user_id    VARCHAR(64)  NOT NULL,
    email               VARCHAR(320) NOT NULL,
    verification_method VARCHAR(30)  NOT NULL,
    verified_at         TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE bot_user_bindings IS '机器人用户与平台邮箱的已验证绑定';
COMMENT ON COLUMN bot_user_bindings.verification_method IS 'EMAIL_CODE / QQ_LOCAL_PART';

CREATE UNIQUE INDEX bot_user_bindings_external_uniq
    ON bot_user_bindings (channel, external_user_id);
CREATE UNIQUE INDEX bot_user_bindings_email_uniq
    ON bot_user_bindings (channel, lower(email));

CREATE TABLE bot_lottery_settings
(
    id               SMALLINT        PRIMARY KEY DEFAULT 1,
    enabled          BOOLEAN         NOT NULL DEFAULT FALSE,
    min_usage_daily  NUMERIC(20, 10) NOT NULL DEFAULT 10,
    payout_ratio     NUMERIC(10, 6)  NOT NULL DEFAULT 0.03,
    multiplier_min   NUMERIC(10, 4)  NOT NULL DEFAULT 0.8,
    multiplier_max   NUMERIC(10, 4)  NOT NULL DEFAULT 1.2,
    max_reward       NUMERIC(20, 4)  NOT NULL DEFAULT 5,
    daily_budget     NUMERIC(20, 4)  NOT NULL DEFAULT 50,
    updated_at       TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT bot_lottery_settings_single_row CHECK (id = 1),
    CONSTRAINT bot_lottery_settings_threshold CHECK (min_usage_daily >= 0),
    CONSTRAINT bot_lottery_settings_ratio CHECK (payout_ratio > 0 AND payout_ratio <= 1),
    CONSTRAINT bot_lottery_settings_multiplier CHECK (multiplier_min > 0 AND multiplier_max >= multiplier_min),
    CONSTRAINT bot_lottery_settings_caps CHECK (max_reward > 0 AND daily_budget > 0)
);

COMMENT ON TABLE bot_lottery_settings IS 'QQ 机器人每日消费抽奖配置，全局单行';
COMMENT ON COLUMN bot_lottery_settings.min_usage_daily IS '前一个完整自然日最低 actual_cost 门槛';
COMMENT ON COLUMN bot_lottery_settings.payout_ratio IS '基础奖励比例，0.03 表示 3%';
COMMENT ON COLUMN bot_lottery_settings.multiplier_min IS '随机系数下限';
COMMENT ON COLUMN bot_lottery_settings.multiplier_max IS '随机系数上限';
COMMENT ON COLUMN bot_lottery_settings.max_reward IS '单人单次最高奖励';
COMMENT ON COLUMN bot_lottery_settings.daily_budget IS '全平台每日奖励总预算';

INSERT INTO bot_lottery_settings (id)
VALUES (1)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE bot_lottery_draws
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel             VARCHAR(20)    NOT NULL DEFAULT 'QQ',
    external_user_id    VARCHAR(64)    NOT NULL,
    email               VARCHAR(320)   NOT NULL,
    platform_user_id    BIGINT         NOT NULL,
    draw_date           DATE           NOT NULL,
    usage_window_start  TIMESTAMPTZ    NOT NULL,
    usage_window_end    TIMESTAMPTZ    NOT NULL,
    eligible_usage      NUMERIC(20, 10) NOT NULL,
    payout_ratio        NUMERIC(10, 6) NOT NULL,
    multiplier          NUMERIC(10, 4) NOT NULL,
    reward_amount       NUMERIC(20, 4) NOT NULL,
    status              VARCHAR(20)    NOT NULL,
    idempotency_key     VARCHAR(128)   NOT NULL,
    failure_reason      TEXT,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    credited_at         TIMESTAMPTZ,

    CONSTRAINT bot_lottery_draws_status CHECK (status IN ('PENDING', 'CREDITED', 'FAILED', 'NO_PRIZE'))
);

COMMENT ON TABLE bot_lottery_draws IS 'QQ 机器人每日消费抽奖记录和发奖状态';
COMMENT ON COLUMN bot_lottery_draws.status IS 'PENDING / CREDITED / FAILED / NO_PRIZE';

CREATE UNIQUE INDEX bot_lottery_draws_user_date_uniq
    ON bot_lottery_draws (channel, external_user_id, draw_date);
CREATE UNIQUE INDEX bot_lottery_draws_idempotency_uniq
    ON bot_lottery_draws (idempotency_key);
CREATE INDEX bot_lottery_draws_date_status_idx
    ON bot_lottery_draws (draw_date, status);
CREATE INDEX bot_lottery_draws_email_date_idx
    ON bot_lottery_draws (email, draw_date);
