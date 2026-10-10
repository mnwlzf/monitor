package com.monitor.platform.bot.identity;

import com.monitor.platform.pool.ingest.PoolIngestProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 直连 Sub2API 只读库读取平台用户。
 *
 * <p>复用 {@link PoolIngestProperties} 里已经配好的只读连接信息（与号池增量采集同一个库），
 * 但不依赖 {@code enabled} 开关：关掉增量采集时，身份识别仍然可用。</p>
 *
 * <p><strong>刻意不暴露 DataSource Bean</strong>：应用里已有主数据源，再注册一个 DataSource Bean
 * 会被 Flyway 等自动配置按类型注入，导致主库迁移跑到这个只读库上。因此这里自己持有连接池，
 * 并做懒初始化（首次查询时才建池）。</p>
 *
 * <p>与 {@code Sub2ApiUsageLogReader} 各持一个池是刻意的取舍：两个功能开关互相独立，
 * 共用连接池就要把池的生命周期抽成第三个 Bean，收益不抵复杂度。只读池各 2 个连接，
 * 对上游压力可以忽略。</p>
 */
@Component
public class Sub2ApiUserReader {

    private static final Logger log = LoggerFactory.getLogger(Sub2ApiUserReader.class);

    /**
     * 只取识别身份需要的列。
     *
     * <p>{@code deleted_at} 是软删除标记，必须过滤：否则已注销用户仍会被当成平台用户。
     * 密码哈希、TOTP 密钥等敏感列一律不取。</p>
     */
    private static final String SELECT_USERS = """
            SELECT id, email, username, role, status
            FROM public.users
            WHERE deleted_at IS NULL
            ORDER BY id
            """;

    static final RowMapper<Sub2ApiUser> ROW_MAPPER = (rs, rowNum) -> new Sub2ApiUser(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getString("username"),
            rs.getString("role"),
            rs.getString("status")
    );

    private final PoolIngestProperties properties;

    /** 只读连接池，懒初始化。 */
    private volatile HikariDataSource dataSource;

    public Sub2ApiUserReader(PoolIngestProperties properties) {
        this.properties = properties;
    }

    /** 是否配置了可用的只读库连接。 */
    public boolean isAvailable() {
        return properties.hasConnection();
    }

    /** 读取全部未删除的平台用户。 */
    public List<Sub2ApiUser> fetchUsers() {
        return template().query(SELECT_USERS, ROW_MAPPER);
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
        return jdbcTemplate;
    }

    private HikariDataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("sub2api-users-ro");
        config.setJdbcUrl(properties.getUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(1);
        // 只读双保险：连接池 readOnly + 上游给只读角色
        config.setReadOnly(true);
        config.setAutoCommit(true);
        config.setConnectionTimeout(properties.getConnectTimeout().toMillis());
        config.setInitializationFailTimeout(-1);
        log.info("初始化 Sub2API 用户只读数据源: url={}", properties.getUrl());
        return new HikariDataSource(config);
    }

    @PreDestroy
    public void close() {
        HikariDataSource current = dataSource;
        if (current != null) {
            current.close();
            dataSource = null;
            log.info("已关闭 Sub2API 用户只读数据源");
        }
    }
}