-- ============================================================
-- V29: QQ 消息接收人（定时任务除邮件外还要推 QQ）
-- ============================================================
-- 与 mail_recipients 一样按事件场景配置，一个场景可以配多条：
--   BALANCE_ALERT  余额不足提醒（当前唯一接线的场景）
-- 接收对象支持群（GROUP）与私聊（PRIVATE），推送走 OneBot HTTP API。

CREATE TABLE qq_recipients
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scene       VARCHAR(50) NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id   BIGINT      NOT NULL,
    name        VARCHAR(120),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT qq_recipients_target_type_check CHECK (target_type IN ('GROUP', 'PRIVATE'))
);

COMMENT ON TABLE qq_recipients IS 'QQ 消息接收人（按事件场景区分，群 / 私聊）';
COMMENT ON COLUMN qq_recipients.scene IS '事件场景：BALANCE_ALERT（余额不足提醒）等，与邮件收件人同一套场景';
COMMENT ON COLUMN qq_recipients.target_type IS '接收对象类型：GROUP（群）、PRIVATE（私聊）';
COMMENT ON COLUMN qq_recipients.target_id IS '群号或 QQ 号';
COMMENT ON COLUMN qq_recipients.name IS '备注名称，可选';

CREATE UNIQUE INDEX qq_recipients_scene_target_uniq
    ON qq_recipients (scene, target_type, target_id);
