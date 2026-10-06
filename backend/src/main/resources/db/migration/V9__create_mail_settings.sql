-- ============================================================
-- V9: 页面可配置的 SMTP 邮件设置
-- ============================================================
-- 设计说明：
--   SMTP 配置由管理端页面维护，全局单行（id = 1）。
--   密码使用与上游凭证一致的 AES-GCM 加密存储，密文 / IV / 算法 / 密钥版本分开保存，
--   接口只回传「是否已配置」，不回传明文，避免敏感信息泄露到前端。

CREATE TABLE mail_settings
(
    id                             SMALLINT     PRIMARY KEY DEFAULT 1,
    enabled                        BOOLEAN      NOT NULL DEFAULT FALSE,
    host                           VARCHAR(255),
    port                           INTEGER      NOT NULL DEFAULT 587,
    username                       VARCHAR(255),

    -- SMTP 密码的 AES-GCM 加密存储
    password_encryption_algorithm  VARCHAR(50),
    password_encrypted_payload     TEXT,
    password_initialization_vector VARCHAR(120),
    password_key_version           INTEGER,

    from_address                   VARCHAR(320),
    from_name                      VARCHAR(120),
    use_tls                        BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at                     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT mail_settings_single_row CHECK (id = 1)
);

COMMENT ON TABLE mail_settings IS '页面可配置的 SMTP 邮件设置，全局单行';
COMMENT ON COLUMN mail_settings.host IS 'SMTP 服务器地址';
COMMENT ON COLUMN mail_settings.port IS 'SMTP 服务器端口，默认 587';
COMMENT ON COLUMN mail_settings.password_encrypted_payload IS 'SMTP 密码 AES-GCM 密文';
COMMENT ON COLUMN mail_settings.password_initialization_vector IS 'AES-GCM 初始化向量';
COMMENT ON COLUMN mail_settings.from_address IS '发件人邮箱，为空时回退到 username';
COMMENT ON COLUMN mail_settings.from_name IS '发件人显示名称';
COMMENT ON COLUMN mail_settings.use_tls IS 'true=隐式 TLS（465）；false=机会式 STARTTLS（587/25）';

INSERT INTO mail_settings (id, enabled, port, use_tls, from_name)
VALUES (1, FALSE, 587, FALSE, 'Monitor');