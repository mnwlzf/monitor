-- ============================================================
-- V26: 机器人「指定群」（平台功能群）
-- ============================================================
-- 背景：
--   平台功能的准入不再只看邮箱。必须在「指定群」里再匹配一次群上下文
--   （群聊取本群，群临时会话取发起群），双重命中才触发平台功能。
--
-- 说明：
--   platform_groups 是 allowed_groups 的**子集**，由页面填写并校验。
--   语义与白名单相反：**为空表示任何群都不给平台功能**（fail-closed）。

ALTER TABLE bot_settings ADD COLUMN platform_groups TEXT;

COMMENT ON COLUMN bot_settings.platform_groups IS
    '平台功能群号，逗号分隔；必须是 allowed_groups 的子集；为空表示任何群都不给平台功能';