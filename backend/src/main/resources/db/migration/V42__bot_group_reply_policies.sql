-- ============================================================
-- V42: 按群聊定制机器人回复策略
-- ============================================================
-- 未单独配置的群继续沿用全局 require_mention；配置后按群覆盖：
--   MENTION    仅 @机器人 回复
--   PROACTIVE  主动回复每条群消息
--   RANDOM     按 randomPercent 概率随机回复
--   KEYWORD    命中 keywords 中任一关键词时回复
-- 明确 @机器人 始终会触发回复。

ALTER TABLE bot_settings
    ADD COLUMN group_reply_policies JSONB NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN bot_settings.group_reply_policies IS
    '按群定制的回复策略 JSONB 数组：[{groupId,strategy,randomPercent,keywords}]';
