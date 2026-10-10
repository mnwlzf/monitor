-- ============================================================
-- V25: QQ 机器人消息存档
-- ============================================================
-- 背景：
--   机器人会替人回答问题、也可能被拿来做纠纷举证。需要能事后查证
--   「谁在什么时候说了什么、机器人回了什么、当时是什么身份」。
--
-- 说明：
--   一条用户消息 + 一条机器人回复各存一行，用 correlation_id 关联。
--   correlation_id 由应用生成（不是数据库自增），因此两条记录可以**完全独立地异步写入** ——
--   存档绝不能拖慢机器人回复，也不能因为写库顺序影响关联。
--   写入是 best-effort：队列满或写失败只记日志。

CREATE TABLE bot_messages
(
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- IN = 用户发给机器人，OUT = 机器人回复
    direction    VARCHAR(8)  NOT NULL,
    -- private / group
    message_type VARCHAR(16) NOT NULL,
    group_id     BIGINT,
    user_id      BIGINT      NOT NULL,
    -- 身份识别结果（存档时快照，便于日后追溯当时的权限）
    sender_email VARCHAR(255),
    sender_role  VARCHAR(16),
    -- 机器人回复时用的人设（仅 OUT 有值）
    persona      VARCHAR(24),
    -- 消息内容；图片消息记一段可读的摘要（不存图片本身）
    content      TEXT        NOT NULL,
    -- TEXT / IMAGE / COMMAND
    content_kind VARCHAR(16) NOT NULL,
    -- 同一问一答共用的关联 ID（应用生成），OUT 与 IN 靠它配对
    correlation_id VARCHAR(32),
    -- OneBot 侧的消息 ID，便于和 QQ 客户端对账
    message_id   VARCHAR(64)
);

COMMENT ON TABLE bot_messages IS 'QQ 机器人消息存档（一问一答各一行，correlation_id 关联）';
COMMENT ON COLUMN bot_messages.direction IS 'IN=用户发给机器人，OUT=机器人回复';
COMMENT ON COLUMN bot_messages.sender_role IS '存档时的身份快照：ADMIN / USER / GUEST';
COMMENT ON COLUMN bot_messages.persona IS '回复时用的人设：MONITOR_ADMIN / MONITOR_USER / CHAT';
COMMENT ON COLUMN bot_messages.content_kind IS 'TEXT=纯文本，IMAGE=图片（content 是可读摘要），COMMAND=命令';
COMMENT ON COLUMN bot_messages.correlation_id IS '同一问一答共用的关联 ID，由应用生成，使两条记录可独立异步写入';

CREATE INDEX bot_messages_correlation_idx ON bot_messages (correlation_id);

CREATE INDEX bot_messages_created_idx ON bot_messages (created_at DESC);
CREATE INDEX bot_messages_user_idx ON bot_messages (user_id, created_at DESC);
CREATE INDEX bot_messages_group_idx ON bot_messages (group_id, created_at DESC);
-- 每天凌晨 4 点清理超过保留天数的存档（保留天数在配置里，非正数时任务自动跳过）
INSERT INTO scheduled_tasks (task_name, task_code, cron_expression, timezone, enabled, description)
VALUES ('机器人消息存档清理', 'bot-archive-cleanup', '0 0 4 * * ?', 'Asia/Shanghai', TRUE,
        '按 monitor.bot.archive.retention-days 清理旧存档；保留天数非正数时跳过')
