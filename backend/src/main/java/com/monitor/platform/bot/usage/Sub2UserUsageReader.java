package com.monitor.platform.bot.usage;

import com.monitor.platform.pool.ingest.Sub2ReadOnlyJdbc;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 直连 Sub2API 只读库，按用户聚合「钱包余额消耗」。
 *
 * <p>口径（与 Sub2API 源码对齐）：{@code usage_logs.actual_cost} 是<strong>实际扣除费用</strong>
 * （见 {@code 001_init.sql} 的列注释），{@code billing_type = 0} 表示扣的是<strong>钱包余额</strong>
 * （见 {@code internal/service/usage_log.go}：{@code BillingTypeBalance int8 = 0}），
 * 1 则是订阅套餐。所以「余额消耗」= {@code SUM(actual_cost) WHERE billing_type = 0}。</p>
 *
 * <p>为什么不用上游接口：{@code GET /api/v1/admin/usage/stats} 是<strong>按单个用户</strong>查的，
 * 要覆盖「所有用户」只能逐个调用；直连库一次 {@code GROUP BY user_id} 就能出结果。</p>
 *
 * <p>取数用的列都是 {@code 001_init.sql} / {@code 005_schema_parity.sql} 就有的，
 * 不碰后续迁移新增的列，避免部署版本没跑到那些迁移时报列不存在。</p>
 *
 * <p>复用 {@code monitor.pool.ingest.*} 里已经配好的只读连接信息，但<strong>不依赖 {@code enabled} 开关</strong>；
 * 连接池统一由 {@link Sub2ReadOnlyJdbc} 提供，这里只负责取数。</p>
 *
 * <p>需要上游给只读账号授权：{@code usage_logs}（号池增量采集已经在用）。
 * 关联用户信息还需要 {@code public.users}，见 {@code GRANT SELECT ON public.users TO monitor_ro;}。</p>
 */
@Component
public class Sub2UserUsageReader {

    /** 按用户聚合余额消耗，按实际消耗倒序。 */
    private static final String SELECT_BY_USER = """
            SELECT l.user_id,
                   COALESCE(u.email, '')    AS email,
                   COALESCE(u.username, '') AS username,
                   COUNT(*)                 AS request_count,
                   COALESCE(SUM(l.input_tokens), 0)      AS input_tokens,
                   COALESCE(SUM(l.output_tokens), 0)     AS output_tokens,
                   COALESCE(SUM(l.cache_read_tokens), 0) AS cache_read_tokens,
                   COALESCE(SUM(l.total_cost), 0)        AS standard_cost,
                   COALESCE(SUM(l.actual_cost), 0)       AS actual_cost
            FROM usage_logs l
            LEFT JOIN public.users u ON u.id = l.user_id
            WHERE l.created_at >= ?
              AND l.created_at < ?
              AND l.billing_type = 0
              AND l.actual_cost <> 0
            GROUP BY l.user_id, u.email, u.username
            ORDER BY actual_cost DESC, l.user_id
            LIMIT ?
            """;

    /** 同一时间窗的汇总，与明细分开查：明细被 LIMIT 截断时汇总仍然完整。 */
    private static final String SELECT_SUMMARY = """
            SELECT COUNT(DISTINCT l.user_id)         AS user_count,
                   COUNT(*)                          AS request_count,
                   COALESCE(SUM(l.total_cost), 0)    AS standard_cost,
                   COALESCE(SUM(l.actual_cost), 0)   AS actual_cost
            FROM usage_logs l
            WHERE l.created_at >= ?
              AND l.created_at < ?
              AND l.billing_type = 0
              AND l.actual_cost <> 0
            """;

    private static final RowMapper<UserConsumption> ROW_MAPPER = (rs, rowNum) -> new UserConsumption(
            rs.getLong("user_id"),
            rs.getString("email"),
            rs.getString("username"),
            rs.getLong("request_count"),
            rs.getLong("input_tokens"),
            rs.getLong("output_tokens"),
            rs.getLong("cache_read_tokens"),
            rs.getBigDecimal("standard_cost"),
            rs.getBigDecimal("actual_cost")
    );

    private final Sub2ReadOnlyJdbc jdbc;

    public Sub2UserUsageReader(Sub2ReadOnlyJdbc jdbc) {
        this.jdbc = jdbc;
    }

    /** 是否配置了可用的只读库连接（不看增量采集开关）。 */
    public boolean isAvailable() {
        return jdbc.hasConnection();
    }

    /** 指定时间窗内按用户聚合的余额消耗，按实际消耗倒序，最多 {@code limit} 行。 */
    public List<UserConsumption> byUser(OffsetDateTime from, OffsetDateTime to, int limit) {
        return jdbc.template().query(connection -> {
            var statement = connection.prepareStatement(SELECT_BY_USER);
            statement.setObject(1, from);
            statement.setObject(2, to);
            statement.setInt(3, limit);
            return statement;
        }, ROW_MAPPER);
    }

    /** 指定时间窗的汇总（不受明细条数上限影响）。 */
    public UsageSummary summarise(OffsetDateTime from, OffsetDateTime to) {
        List<UsageSummary> rows = jdbc.template().query(connection -> {
            var statement = connection.prepareStatement(SELECT_SUMMARY);
            statement.setObject(1, from);
            statement.setObject(2, to);
            return statement;
        }, (rs, rowNum) -> new UsageSummary(
                rs.getLong("user_count"),
                rs.getLong("request_count"),
                rs.getBigDecimal("standard_cost"),
                rs.getBigDecimal("actual_cost")
        ));
        return rows.isEmpty()
                ? new UsageSummary(0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO)
                : rows.get(0);
    }

    /** 单个用户在时间窗内的余额消耗。 */
    public record UserConsumption(
            long userId,
            String email,
            String username,
            long requestCount,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            BigDecimal standardCost,
            BigDecimal actualCost
    ) {

        /** 计费口径的 Token 合计（输入 + 输出 + 缓存读取）。 */
        public long totalTokens() {
            return inputTokens + outputTokens + cacheReadTokens;
        }
    }

    /** 时间窗内的整体汇总。 */
    public record UsageSummary(
            long userCount,
            long requestCount,
            BigDecimal standardCost,
            BigDecimal actualCost
    ) {
    }
}
