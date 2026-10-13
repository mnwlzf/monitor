package com.monitor.platform.pool;

import com.monitor.platform.pool.ingest.Sub2ReadOnlyJdbc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 本平台（你自己搭建的 Sub2API）账号 → 所属分组 → 分组倍率。
 *
 * <p>对应 Sub2API 的表结构（见 {@code sub2api/backend/ent/schema}）：
 * {@code account_groups(account_id, group_id)} 是<strong>多对多</strong>中间表 ——
 * 一个账号可以同时属于多个分组；分组倍率取 {@code groups.rate_multiplier}。</p>
 *
 * <p>口径：<strong>只按分组倍率</strong>，不再乘账号倍率（业务上确认账号倍率不参与计算）。</p>
 *
 * <p>需要只读角色对 {@code account_groups} 有 SELECT 权限（{@code groups} 已有）；
 * 没权限或查询失败时返回空 Map，调用方降级处理。</p>
 */
@Component
public class Sub2AccountGroupRateReader {

    private static final Logger log = LoggerFactory.getLogger(Sub2AccountGroupRateReader.class);

    private final Sub2ReadOnlyJdbc jdbc;

    /** 权限不足只提示一次，避免每轮采集刷日志。 */
    private volatile boolean permissionWarned;

    public Sub2AccountGroupRateReader(Sub2ReadOnlyJdbc jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 一个账号所属的一个分组。
     *
     * @param accountId  本平台账号 ID（Sub2API accounts.id）
     * @param groupId    分组 ID
     * @param groupName  分组名
     * @param groupRatio 分组倍率
     */
    public record AccountGroup(long accountId, long groupId, String groupName, BigDecimal groupRatio) {
    }

    public boolean isAvailable() {
        return jdbc.hasConnection();
    }

    /** 每个账号所属的分组（含分组倍率），按倍率从高到低。 */
    public Map<Long, List<AccountGroup>> groupsByAccount(Collection<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty() || !isAvailable()) {
            return Map.of();
        }
        Long[] ids = accountIds.stream().filter(Objects::nonNull).distinct().toArray(Long[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.length, "?"));
        String sql = """
                SELECT ag.account_id   AS account_id,
                       g.id            AS group_id,
                       g.name          AS group_name,
                       g.rate_multiplier AS group_ratio
                  FROM account_groups ag
                  JOIN groups g ON g.id = ag.group_id
                 WHERE ag.account_id IN (%s)
                   AND g.deleted_at IS NULL
                 ORDER BY ag.account_id, g.rate_multiplier DESC
                """.formatted(placeholders);

        Map<Long, List<AccountGroup>> result = new LinkedHashMap<>();
        try {
            jdbc.template().query(sql, rs -> {
                long accountId = rs.getLong("account_id");
                result.computeIfAbsent(accountId, key -> new ArrayList<>())
                        .add(new AccountGroup(
                                accountId,
                                rs.getLong("group_id"),
                                rs.getString("group_name"),
                                rs.getBigDecimal("group_ratio")));
            }, Arrays.stream(ids).toArray());
        } catch (RuntimeException ex) {
            if (!permissionWarned) {
                permissionWarned = true;
                log.warn("读取 Sub2API 账号分组失败（通常是只读角色缺少 account_groups 的 SELECT 权限）: {}",
                        ex.getMessage());
            }
            return Map.of();
        }
        return result;
    }
}