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
 * 本平台（你自己搭建的 Sub2API）账号 → 所属分组 → 倍率。
 *
 * <p>对应 Sub2API 的表结构（见 {@code sub2api/backend/ent/schema}）：</p>
 * <ul>
 *     <li>{@code account_groups(account_id, group_id)} —— 账号与分组是<strong>多对多</strong>，
 *         一个账号可以同时属于多个分组；</li>
 *     <li>{@code groups.rate_multiplier} —— 分组倍率；</li>
 *     <li>{@code accounts.rate_multiplier} —— 账号自身倍率。</li>
 * </ul>
 *
 * <p>一次请求的最终结算倍率是两者<strong>相乘</strong>（Sub2API 的 usage_logs 里也同时存了
 * {@code rate_multiplier} 与 {@code account_rate_multiplier}，可对照）。</p>
 *
 * <p>需要只读角色对 {@code accounts} / {@code account_groups} 有 SELECT 权限；
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
     * 一个账号在一个分组下的倍率。
     *
     * @param accountId    本平台账号 ID（Sub2API accounts.id）
     * @param accountRatio 账号倍率
     * @param groupId      分组 ID
     * @param groupName    分组名
     * @param groupRatio   分组倍率
     */
    public record AccountGroupRate(long accountId, BigDecimal accountRatio, long groupId, String groupName,
                                   BigDecimal groupRatio) {

        /** 最终结算倍率 = 分组倍率 × 账号倍率。 */
        public BigDecimal effectiveRatio() {
            if (groupRatio == null) {
                return null;
            }
            return accountRatio == null ? groupRatio : groupRatio.multiply(accountRatio);
        }
    }

    public boolean isAvailable() {
        return jdbc.hasConnection();
    }

    /**
     * 每个账号所属的分组（含分组倍率与账号倍率），按结算倍率从高到低。
     */
    public Map<Long, List<AccountGroupRate>> ratesByAccount(Collection<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty() || !isAvailable()) {
            return Map.of();
        }
        Long[] ids = accountIds.stream().filter(Objects::nonNull).distinct().toArray(Long[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.length, "?"));
        String sql = """
                SELECT a.id                AS account_id,
                       a.rate_multiplier   AS account_ratio,
                       g.id                AS group_id,
                       g.name              AS group_name,
                       g.rate_multiplier   AS group_ratio
                  FROM accounts a
                  JOIN account_groups ag ON ag.account_id = a.id
                  JOIN groups g ON g.id = ag.group_id
                 WHERE a.id IN (%s)
                   AND g.deleted_at IS NULL
                 ORDER BY a.id, g.rate_multiplier DESC
                """.formatted(placeholders);

        Map<Long, List<AccountGroupRate>> result = new LinkedHashMap<>();
        try {
            jdbc.template().query(sql, rs -> {
                long accountId = rs.getLong("account_id");
                result.computeIfAbsent(accountId, key -> new ArrayList<>())
                        .add(new AccountGroupRate(
                                accountId,
                                rs.getBigDecimal("account_ratio"),
                                rs.getLong("group_id"),
                                rs.getString("group_name"),
                                rs.getBigDecimal("group_ratio")));
            }, Arrays.stream(ids).toArray());
        } catch (RuntimeException ex) {
            if (!permissionWarned) {
                permissionWarned = true;
                log.warn("读取 Sub2API 账号分组失败（通常是只读角色缺少 accounts / account_groups 的 SELECT 权限）: {}",
                        ex.getMessage());
            }
            return Map.of();
        }
        return result;
    }
}