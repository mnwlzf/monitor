-- ============================================================
-- V16: 清洗 API Key 原始响应里的明文密钥
-- ============================================================
-- 背景：
--   account_api_keys.raw_data / account_api_key_snapshots.raw_data 存的是上游密钥列表的
--   原始响应，其中 key 字段为明文。采集侧已改为落库前脱敏（SecretMasker），
--   这里把历史数据一并清洗。
-- 说明：
--   这两个字段不通过接口下发，属于「静态存储」层面的泄露面（数据库/备份/导出）。
--   已脱敏（含 *）的值跳过，可重复执行。

UPDATE account_api_keys
SET raw_data = jsonb_set(
        raw_data,
        '{key}',
        to_jsonb(
            CASE
                WHEN length(raw_data ->> 'key') <= 12 THEN '****'
                ELSE left(raw_data ->> 'key', 6) || '****' || right(raw_data ->> 'key', 4)
            END))
WHERE jsonb_typeof(raw_data) = 'object'
  AND jsonb_exists(raw_data, 'key')
  AND (raw_data ->> 'key') IS NOT NULL
  AND (raw_data ->> 'key') NOT LIKE '%*%';

UPDATE account_api_key_snapshots
SET raw_data = jsonb_set(
        raw_data,
        '{key}',
        to_jsonb(
            CASE
                WHEN length(raw_data ->> 'key') <= 12 THEN '****'
                ELSE left(raw_data ->> 'key', 6) || '****' || right(raw_data ->> 'key', 4)
            END))
WHERE jsonb_typeof(raw_data) = 'object'
  AND jsonb_exists(raw_data, 'key')
  AND (raw_data ->> 'key') IS NOT NULL
  AND (raw_data ->> 'key') NOT LIKE '%*%';