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
 * 本平台（你自己搭建的 Sub2API）分组倍率读取。
 *
 * <p>两个数据都来自只读库：</p>
 * <ul>
 *     <li>{@code groups.rate_multiplier} —— 分组倍率；</li>
 *     <li>{@code usage_logs(account_id, group_id)} —— **账号实际服务过的分组**。
 *         Sub2API 没有暴露「账号 ↔ 分组」的静态绑定，可以读到的只有请求流水，
 *         因此用最近一段时间的流水反推影响范围：账号真的服务过哪个分组，才算它属于哪个分组。</li>
 * </ul>
 *
 * <p>只读库没配、或查询失败时返回空 Map —— 影响评估是锦上添花，不能让告警发不出去。</p>
 */
@Component
public class Sub2GroupRatioReader {

    private static final Logger log = LoggerFactory.getLogger(Sub2GroupRatioReader.class);

    /** 反推「账号服务过哪些分组」时回看的天数。 */
    private static final int LOOKBACK_DAYS = 30;

    private final Sub2ReadOnlyJdbc jdbc;

    public Sub2GroupRatioReader(Sub2ReadOnlyJdbc jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 本平台分组。
     *
     * @param id              分组 ID（Sub2API groups.id）
     * @param name            分组名
     * @param rateMultiplier  分组倍率
     */
    public record PlatformGroup(long id, String name, BigDecimal rateMultiplier) {
    }

    public boolean isAvailable() {
        return jdbc.hasConnection();
    }

    /**
     * 每个号池账号（本平台 account id）最近服务过的分组，按倍率从高到低。
     */
    public Map<Long, List<PlatformGroup>> groupsByAccount(Collection<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty() || !isAvailable()) {
            return Map.of();
        }
        Long[] ids = accountIds.stream().filter(Objects::nonNull).distinct().toArray(Long[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.length, "?"));
        String sql = """
                SELECT DISTINCT ul.account_id, g.id AS group_id, g.name AS group_name, g.rate_multiplier
                  FROM usage_logs ul
                  JOIN groups g ON g.id = ul.group_id
                 WHERE ul.account_id IN (%s)
                   AND ul.created_at >= now() - make_interval(days => ?)
                   AND g.deleted_at IS NULL
                 ORDER BY ul.account_id, g.rate_multiplier DESC
                """.formatted(placeholders);

        List<Object> args = new ArrayList<>();
        args.add(LOOKBACK_DAYS);
        args.addAll(Arrays.asList(ids));

        Map<Long, List<PlatformGroup>> result = new LinkedHashMap<>();
        try {
            jdbc.template().query(sql, rs -> {
                long accountId = rs.getLong("account_id");
                result.computeIfAbsent(accountId, key -> new ArrayList<>())
                        .add(new PlatformGroup(
                                rs.getLong("group_id"),
                                rs.getString("group_name"),
                                rs.getBigDecimal("rate_multiplier")));
            }, args.toArray());
        } catch (RuntimeException ex) {
            log.warn("读取本平台分组倍率失败: {}", ex.getMessage());
            return Map.of();
        }
        return result;
    }
}