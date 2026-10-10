-- ============================================================
-- V22: 页面可配置的 QQ 机器人设置
-- ============================================================
-- 背景：
--   白名单（允许的群 / QQ）等参数原本只能通过环境变量注入，改一次就得重建容器，
--   运维成本很高。改为全局单行表，页面上随时改、立即生效，不用重新部署。
--
-- 说明：
--   只有「部署级」的接线参数（webhook 密钥、OneBot 地址、总开关）仍留在环境变量，
--   因为它们要和 NapCat 侧保持一致；其余行为参数都搬到这张表。
--   首次启动时如果这行不存在，会用环境变量里的值初始化一行（见 BotSettingsService）。

CREATE TABLE bot_settings
(
    id               SMALLINT    PRIMARY KEY DEFAULT 1,
    enabled          BOOLEAN     NOT NULL DEFAULT TRUE,
    allowed_groups   TEXT,
    allowed_users    TEXT,
    require_mention  BOOLEAN     NOT NULL DEFAULT TRUE,
    command_prefix   VARCHAR(16) NOT NULL DEFAULT '/',
    max_reply_length INTEGER     NOT NULL DEFAULT 900,
    memory_window    INTEGER     NOT NULL DEFAULT 10,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT bot_settings_single_row CHECK (id = 1)
);

COMMENT ON TABLE bot_settings IS '页面可配置的 QQ 机器人设置，全局单行';
COMMENT ON COLUMN bot_settings.enabled IS '运行时开关：false 时机器人收到消息也不响应（总开关仍在环境变量 MONITOR_BOT_ENABLED）';
COMMENT ON COLUMN bot_settings.allowed_groups IS '允许响应的群号，逗号分隔；为空表示不限制';
COMMENT ON COLUMN bot_settings.allowed_users IS '允许响应的私聊 QQ，逗号分隔；为空表示不限制';
COMMENT ON COLUMN bot_settings.require_mention IS '群里是否必须 @机器人 才响应';
COMMENT ON COLUMN bot_settings.command_prefix IS '命令前缀，例如 /help';
COMMENT ON COLUMN bot_settings.max_reply_length IS '单条回复最大字符数，超长截断';
COMMENT ON COLUMN bot_settings.memory_window IS '每个会话保留的历史消息条数';