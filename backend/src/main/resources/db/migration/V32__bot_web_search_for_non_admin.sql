-- ============================================================
-- V32: 普通人是否允许使用联网搜索（页面可配，保存即生效）
-- ============================================================
-- 联网搜索按次计费，管理员默认可用；普通人默认关闭，由页面开关控制。
-- 放在 bot_settings 里是为了跟其它机器人开关一样「改完立即生效」，不用重启容器。
-- 注意：还需要服务端配置 DeepSeek 官方 Key（MONITOR_WEB_SEARCH_API_KEY），否则工具不会挂载。

ALTER TABLE bot_settings
    ADD COLUMN web_search_for_non_admin BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN bot_settings.web_search_for_non_admin IS
    '是否允许非管理员（平台普通用户 / 陌生人）使用联网搜索；还需服务端配置 DeepSeek Key';
