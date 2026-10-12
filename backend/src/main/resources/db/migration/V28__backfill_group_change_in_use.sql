-- ============================================================
-- V28: 回填渠道变更事件的「使用中」标记
-- ============================================================
-- 背景：in_use 这个标记以前只在密钥事件上设过，渠道事件全是 false。
-- 结果「正在使用的渠道涨价」和「早就下架的渠道改名」在数据里完全一样 ——
-- 页面上的「使用中」列全是空的，邮件也只能全发。
--
-- 口径（与写入侧 groupInUse 一致）：渠道仍然在使用 = 上游还有它（is_active）
-- 且状态为启用（newapi 给 ACTIVE，sub2api 给 active，所以用 lower 比）。
--
-- 只回填 GROUP 事件；密钥那边本来就有标记，不动。

UPDATE upstream_change_events e
SET in_use = TRUE
WHERE e.entity_type = 'GROUP'
  AND e.in_use = FALSE
  AND EXISTS (
      SELECT 1
      FROM upstream_groups g
      WHERE g.account_id = e.account_id
        AND g.external_group_id = e.entity_key
        AND g.is_active = TRUE
        AND lower(g.status) = 'active'
  );
