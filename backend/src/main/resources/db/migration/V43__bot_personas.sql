-- ============================================================
-- V43: QQ 机器人人设库
-- ============================================================
-- 人设只影响自然语言回复的称呼、性格和表达风格，不影响：
--   * 发送者身份与平台查询权限
--   * 工具挂载与数据脱敏
--   * 回复长度、命令路由、禁词过滤
-- 群聊通过 bot_settings.group_reply_policies[].personaId 绑定人设；
-- personaId 为空或人设不可用时回退到默认人设。

CREATE TABLE bot_personas
(
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(80)  NOT NULL,
    description      VARCHAR(300),
    character_prompt TEXT         NOT NULL,
    style_prompt     VARCHAR(2000),
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    is_default       BOOLEAN      NOT NULL DEFAULT FALSE,
    version          INTEGER      NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT bot_personas_name_nonblank CHECK (btrim(name) <> ''),
    CONSTRAINT bot_personas_character_prompt_nonblank CHECK (btrim(character_prompt) <> '')
);

CREATE UNIQUE INDEX bot_personas_single_default
    ON bot_personas (is_default)
    WHERE is_default = TRUE;

COMMENT ON TABLE bot_personas IS 'QQ 机器人可复用的人设库；仅影响自然语言表达，不承载权限';
COMMENT ON COLUMN bot_personas.character_prompt IS '角色设定：身份、性格、职责边界；不能覆盖系统权限规则';
COMMENT ON COLUMN bot_personas.style_prompt IS '表达风格：称呼、语气、长度偏好等';
COMMENT ON COLUMN bot_personas.is_default IS '默认人设；群未绑定或绑定失效时使用';
COMMENT ON COLUMN bot_personas.version IS '人设版本；修改后递增，用于隔离客户端缓存与会话记忆';

INSERT INTO bot_personas
    (name, description, character_prompt, style_prompt, enabled, is_default, version)
VALUES
    ('默认运维助手',
     '适合平台日常查询与运维问答的默认人设',
     '你是一名可靠、克制的平台助手。你的职责是理解用户问题、使用系统允许的工具获取真实数据，并清楚说明结论和限制。不要扮演与平台职责冲突的角色。',
     '使用简体中文，直接给结论；语气专业、友好、简洁。不要夸张、卖萌或使用与数据无关的修饰。',
     TRUE,
     TRUE,
     1);
