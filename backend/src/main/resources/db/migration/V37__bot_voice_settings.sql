-- ============================================================
-- V37: 机器人语音回复的音色与默认语气
-- ============================================================
-- 语音转写与 MOSS-TTSD 的接线参数在环境变量中；这里只保存页面可维护的
-- 默认音色与默认语气，保存后立即刷新内存快照。

ALTER TABLE bot_settings
    ADD COLUMN default_voice VARCHAR(120) NOT NULL DEFAULT 'fnlp/MOSS-TTSD-v0.5:alex',
    ADD COLUMN default_voice_style VARCHAR(80) NOT NULL DEFAULT '自然、友好、简洁';

COMMENT ON COLUMN bot_settings.default_voice IS
    'MOSS-TTSD 默认音色，例如 fnlp/MOSS-TTSD-v0.5:alex';
COMMENT ON COLUMN bot_settings.default_voice_style IS
    '默认语音语气/风格，用于组织语音回复的文字措辞';