-- ============================================================
-- V19: 号池监控源标记
-- ============================================================
-- 背景：
--   管理员密钥不是 Sub2API 的通用属性 —— 只有「用户自己搭建的 Sub2API」
--   才会提供 /api/v1/admin/* 这类管理员只读接口。其余的 Sub2API / New API
--   平台只是给这个自建 Sub2API 提供上游 Key，不需要、也拿不到管理员密钥。
--   因此用显式开关标记「哪个平台是号池监控源」，而不是按平台类型去猜。

ALTER TABLE platforms
    ADD COLUMN pool_monitoring_enabled BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN platforms.pool_monitoring_enabled IS
    '是否作为号池监控源（用户自建的 Sub2API）；只有该平台需要配置管理员密钥';

-- 已经配置过管理员密钥的平台自动视为号池监控源，升级后不丢配置
UPDATE platforms
SET pool_monitoring_enabled = TRUE,
    updated_at             = now()
WHERE admin_key_encrypted_payload IS NOT NULL;