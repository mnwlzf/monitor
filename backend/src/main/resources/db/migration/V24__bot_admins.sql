-- ============================================================
-- V24: 自定义管理员（监控项目侧）
-- ============================================================
-- 背景：
--   自建 Sub2API 只允许一个管理员账号，但实际运维往往需要多人分担。
--   因此在监控项目里再维护一份「自定义管理员」名单，按邮箱授权。
--
-- 生效规则（两者取并集）：
--   - Sub2API public.users.role = 'admin' 的用户；
--   - 本表登记的管理员邮箱。
--   自定义管理员即使不是 Sub2API 用户，也能以监控助手（管理员）身份使用 QQ 机器人。

CREATE TABLE bot_admins
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email      VARCHAR(255) NOT NULL,
    remark     VARCHAR(200),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE bot_admins IS '监控项目侧的自定义管理员名单（按邮箱授权，与 Sub2API 的管理员取并集）';
COMMENT ON COLUMN bot_admins.email IS '管理员邮箱，大小写不敏感';
COMMENT ON COLUMN bot_admins.remark IS '备注，便于多人协作时区分';

-- 邮箱大小写不敏感：统一按小写建唯一索引
CREATE UNIQUE INDEX bot_admins_email_uniq ON bot_admins (lower(email));