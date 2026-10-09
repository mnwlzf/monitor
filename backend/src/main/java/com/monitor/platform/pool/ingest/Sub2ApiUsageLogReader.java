package com.monitor.platform.pool.ingest;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;

/**
 * 直连 Sub2API 只读库读取用量明细。
 *
 * <p>用 {@code id > ?} 做主键范围扫描（真增量），并按 {@code account_id} 过滤到本项目监控的号池账号；
 * {@code usage_logs} 上有 {@code (account_id, created_at)} 复合索引与主键，单次查询毫秒级。</p>
 *
 * <p><strong>刻意不暴露 DataSource / JdbcTemplate Bean</strong>：应用里已经有主数据源，
 * 再注册一个 DataSource Bean 会被 Flyway 等自动配置按类型注入，导致主库迁移跑到这个只读库上。
 * 因此这里自己持有连接池，并做懒初始化（首次查询时才建池）。</p>
 */
@Component
public class Sub2ApiUsageLogReader {

    private static final Logger log = LoggerFactory.getLogger(Sub2ApiUsageLogReader.class);

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

    /** 业务时区：created_at 若是无时区的 timestamp，按此时区解释。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

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
            offsetDateTimeOrNull(rs, "created_at"),
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

    /** created_at 可能是 timestamptz 也可能是 timestamp，两种都收。 */
    private static OffsetDateTime offsetDateTimeOrNull(ResultSet rs, String column) throws SQLException {
        try {
            return rs.getObject(column, OffsetDateTime.class);
        } catch (SQLException ignored) {
            // 无时区的 timestamp 走不通上面的转换，退回按业务时区解释
        }
        Object value = rs.getObject(column);
        if (value instanceof OffsetDateTime odt) {
            return odt;
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.atZone(ZONE).toOffsetDateTime();
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().atZone(ZONE).toOffsetDateTime();
        }
        if (value instanceof Instant instant) {
            return instant.atZone(ZONE).toOffsetDateTime();
        }
        return null;
    }

    private final PoolIngestProperties properties;

    /** 只读连接池，懒初始化。 */
    private volatile HikariDataSource dataSource;

    public Sub2ApiUsageLogReader(PoolIngestProperties properties) {
        this.properties = properties;
    }

    /** 是否已配置直连只读库。 */
    public boolean isAvailable() {
        return properties.isConfigured();
    }

    /**
     * 当前 usage_logs 的最大 id，用于首次启用时初始化游标（不回灌历史）。
     */
    public long currentMaxId() {
        Long max = template().queryForObject("SELECT COALESCE(MAX(id), 0) FROM usage_logs", Long.class);
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
        JdbcTemplate jdbcTemplate = template();
        Long[] ids = accountIds.toArray(Long[]::new);
        return jdbcTemplate.query(connection -> {
            var statement = connection.prepareStatement(SELECT_AFTER_ID);
            statement.setLong(1, lastId);
            statement.setArray(2, connection.createArrayOf("bigint", ids));
            statement.setInt(3, limit);
            return statement;
        }, ROW_MAPPER);
    }

    /**
     * 懒创建只读连接池。
     *
     * <p>池化失败不 fail-fast：上游库临时不可达时只在查询处报错，不影响 monitor 启动。</p>
     */
    private JdbcTemplate template() {
        HikariDataSource current = dataSource;
        if (current == null) {
            synchronized (this) {
                current = dataSource;
                if (current == null) {
                    current = createDataSource();
                    dataSource = current;
                }
            }
        }
        JdbcTemplate jdbcTemplate = new JdbcTemplate(current);
        jdbcTemplate.setQueryTimeout(Math.max(1, (int) properties.getQueryTimeout().toSeconds()));
        jdbcTemplate.setFetchSize(properties.getBatchSize());
        return jdbcTemplate;
    }

    private HikariDataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("sub2api-ro");
        config.setJdbcUrl(properties.getUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setMaximumPoolSize(Math.max(1, properties.getMaximumPoolSize()));
        config.setMinimumIdle(1);
        // 只读双保险：连接池 readOnly + 建议上游给只读角色
        config.setReadOnly(true);
        config.setAutoCommit(true);
        config.setConnectionTimeout(properties.getConnectTimeout().toMillis());
        config.setInitializationFailTimeout(-1);
        log.info("初始化 Sub2API 只读数据源: url={}", properties.getUrl());
        return new HikariDataSource(config);
    }

    @PreDestroy
    public void close() {
        HikariDataSource current = dataSource;
        if (current != null) {
            current.close();
            dataSource = null;
            log.info("已关闭 Sub2API 只读数据源");
        }
    }
}