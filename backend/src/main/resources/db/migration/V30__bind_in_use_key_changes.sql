-- ============================================================
-- V30: 密钥的「使用中」改成「被本平台号池账号绑定」
-- ============================================================
-- 旧口径（V14）是「上游把 Key 标成启用（active）就记为使用中」，但那个其实只是「活跃」：
-- 上游标成 active、我这边根本没有绑号池账号的 Key，并不代表我在用它。
-- 新口径：in_use = 存在号池账号（pool_accounts.bound_key_id）绑定到这把上游 Key。
-- 已有的 API_KEY 事件按当前绑定关系重算一遍，避免历史记录与页面标签口径不一致。

UPDATE upstream_change_events e
SET in_use = EXISTS (SELECT 1
                       FROM account_api_keys k
                       JOIN pool_accounts pa ON pa.bound_key_id = k.id
                      WHERE k.account_id = e.account_id
                        AND (k.id = e.entity_id OR k.external_key_id = e.entity_key))
WHERE e.entity_type = 'API_KEY';

COMMENT ON COLUMN upstream_change_events.in_use IS
    '变更是否涉及「本平台号池账号正在使用」的密钥（API_KEY 事件专用）：绑定关系即判定依据；上游自身是否启用是「活跃」，与使用中无关';
