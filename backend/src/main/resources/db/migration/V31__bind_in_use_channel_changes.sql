-- ============================================================
-- V31: 渠道的「使用中」也改成「我自己的平台在用它」
-- ============================================================
-- 旧口径：上游把渠道标成启用（在售）就算使用中 —— 那其实只是「活跃」。
-- 结果「我根本没用过的渠道」涨跌也会进实时告警，噪声大。
-- 新口径：这个渠道里有本平台号池账号绑定的上游 Key（与密钥口径一致，见 V30）。
-- 已有的 GROUP 事件按当前绑定关系重算：
--   * field_name = 'is_active' 的渠道下线事件保持 false（上游已经没有它了）；
--   * 其余（倍率 / 改名 / 状态 / 描述）按「渠道里有没有我绑定的 Key」重算。
-- 注：这里用渠道名做等值匹配；采集侧运行时判定还会兜住「上游改过渠道名」的前缀情况。

UPDATE upstream_change_events e
SET in_use = EXISTS (SELECT 1
                       FROM upstream_groups g
                       JOIN account_api_keys k
                         ON k.account_id = e.account_id
                        AND k.group_name = g.group_name
                       JOIN pool_accounts pa ON pa.bound_key_id = k.id
                      WHERE g.account_id = e.account_id
                        AND g.external_group_id = e.entity_key)
WHERE e.entity_type = 'GROUP'
  AND (e.field_name IS DISTINCT FROM 'is_active');

COMMENT ON COLUMN upstream_change_events.in_use IS
    '变更是否涉及「本平台正在使用」的对象：密钥按号池账号绑定判定（V30），渠道按该渠道下有没有绑定的 Key 判定（V31）；上游自身是否启用在页面上标「活跃」';
