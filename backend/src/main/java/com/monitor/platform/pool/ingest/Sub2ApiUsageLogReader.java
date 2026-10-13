package com.monitor.platform.pool.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

/**
 * 直连 Sub2API 只读库读取用量明细。
 *
 * <p>用 {@code id > ?} 做主键范围扫描（真增量），并按 {@code account_id} 过滤到本项目监控的号池账号；
 * {@code usage_logs} 上有 {@code (account_id, created_at)} 复合索引与主键，单次查询毫秒级。</p>
 *
 * <p>连接池统一由 {@link Sub2ReadOnlyJdbc} 提供，这里只负责取数。</p>
 */
@Component
public class Sub2ApiUsageLogReader {
    private static final String SELECT_AFTER_ID = """
            SELECT id, account_id, request_id, api_key_id, model, created_at,
                   first_token_ms, duration_ms,
                   input_tokens, output_tokens, cache_read_tokens, cache_creation_tokens,
                   total_cost, actual_cost
            FROM usage_logs
            WHERE id > ?
              AND account_id = ANY (?)
            ORDER BY id
            LIMIT ?
            """;
    /**
     * 行映射。
     *
     * <p><strong>刻意不用 {@code rs.getObject(col, Long.class)}</strong>：上游 {@code usage_logs}
     * 的 {@code *_tokens} / {@code api_key_id} 在不同版本里可能是 int4 也可能是 int8，
     * 而 pgjdbc 对 int4 执行 {@code getObject(col, Long.class)} 会直接抛
     * 「conversion to class java.lang.Long from int4 not supported」，
     * 整个增量链路就会卡住、游标不再推进。这里统一按 {@link Number} 收，宽窄都能读。</p>
     */
    static final RowMapper<Sub2ApiUsageLogRow> ROW_MAPPER = (rs, rowNum) -> new Sub2ApiUsageLogRow(
            rs.getLong("id"),
            rs.getLong("account_id"),
            rs.getString("request_id"),
            longOrNull(rs, "api_key_id"),
            rs.getString("model"),
            Sub2JdbcValues.offsetDateTimeOrNull(rs, "created_at"),
            intOrNull(rs, "first_token_ms"),
            intOrNull(rs, "duration_ms"),
            longOrNull(rs, "input_tokens"),
            longOrNull(rs, "output_tokens"),
            longOrNull(rs, "cache_read_tokens"),
            longOrNull(rs, "cache_creation_tokens"),
            rs.getBigDecimal("total_cost"),
            rs.getBigDecimal("actual_cost")
    );

    /** 宽容读取整数列：int4 / int8 / numeric 都能取。 */
    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof Number number ? number.intValue() : null;
    }


    private final PoolIngestProperties properties;
    private final Sub2ReadOnlyJdbc jdbc;

    public Sub2ApiUsageLogReader(PoolIngestProperties properties, Sub2ReadOnlyJdbc jdbc) {
        this.properties = properties;
        this.jdbc = jdbc;
    }

    /** 是否已配置直连只读库。 */
    public boolean isAvailable() {
        return jdbc.isConfigured();
    }

    /**
     * 当前 usage_logs 的最大 id，用于首次启用时初始化游标（不回灌历史）。
     */
    public long currentMaxId() {
        Long max = jdbc.template().queryForObject("SELECT COALESCE(MAX(id), 0) FROM usage_logs", Long.class);
        return max == null ? 0L : max;
    }

    /**
     * 按 id 游标拉取一批明细。
     *
     * @param lastId     已同步到的最大 id（不含）
     * @param accountIds 本项目监控的号池账号 id；为空时不查
     * @param limit      单批条数
     */
    public List<Sub2ApiUsageLogRow> fetchAfter(long lastId, Collection<Long> accountIds, int limit) {
        if (accountIds == null || accountIds.isEmpty()) {
            return List.of();
        }
        JdbcTemplate jdbcTemplate = jdbc.template(properties.getBatchSize());
        Long[] ids = accountIds.toArray(Long[]::new);
        return jdbcTemplate.query(connection -> {
            var statement = connection.prepareStatement(SELECT_AFTER_ID);
            statement.setLong(1, lastId);
            statement.setArray(2, connection.createArrayOf("bigint", ids));
            statement.setInt(3, limit);
            return statement;
        }, ROW_MAPPER);
    }
}
