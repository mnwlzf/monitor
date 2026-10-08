-- ============================================================
-- V15: 清洗变更事件里的明文密钥
-- ============================================================
-- 背景：
--   API Key 新增事件曾把整块上游响应写入 old_value / new_value，其中 key 字段是明文。
--   采集侧已改为落库前脱敏（SecretMasker），这里把历史数据一并清洗，
--   避免明文密钥继续留在数据库、变更详情弹窗和提醒邮件里。
-- 说明：
--   schema 中没有直接跑 SQL 的开关，这里只对 API_KEY 事件里形如对象的
--   old_value / new_value 做 key 字段替换；已脱敏（含 *）的值跳过。

UPDATE upstream_change_events
SET old_value = jsonb_set(
        old_value,
        '{key}',
        to_jsonb(
            CASE
                WHEN length(old_value ->> 'key') <= 12 THEN '****'
                ELSE left(old_value ->> 'key', 6) || '****' || right(old_value ->> 'key', 4)
            END))
WHERE entity_type = 'API_KEY'
  AND jsonb_typeof(old_value) = 'object'
  AND jsonb_exists(old_value, 'key')
  AND (old_value ->> 'key') IS NOT NULL
  AND (old_value ->> 'key') NOT LIKE '%*%';

UPDATE upstream_change_events
SET new_value = jsonb_set(
        new_value,
        '{key}',
        to_jsonb(
            CASE
                WHEN length(new_value ->> 'key') <= 12 THEN '****'
                ELSE left(new_value ->> 'key', 6) || '****' || right(new_value ->> 'key', 4)
            END))
WHERE entity_type = 'API_KEY'
  AND jsonb_typeof(new_value) = 'object'
  AND jsonb_exists(new_value, 'key')
  AND (new_value ->> 'key') IS NOT NULL
  AND (new_value ->> 'key') NOT LIKE '%*%';