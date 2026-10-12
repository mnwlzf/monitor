package com.monitor.platform.pool.ingest;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Sub2API 只读库的共享访问入口。
 *
 * <p>项目里有多个功能都要直连同一个 Sub2API 只读库：号池增量采集（{@code usage_logs}）、
 * 渠道状态、平台用户用量、订单查询。它们以前各自持有一个 Hikari 连接池，池的创建、
 * 懒初始化与关闭逻辑完全重复；这里统一成一个共享连接池，既消除重复，也避免同一个只读库
 * 被开出多个池。</p>
 *
 * <p><strong>刻意不注册 {@code DataSource} / {@code JdbcTemplate} Bean</strong>：应用里已有
 * 主数据源，再注册一个 {@code DataSource} 类型的 Bean 会被 Flyway 等自动配置按类型注入，
 * 导致主库迁移跑到这个只读库上。因此这里以普通 {@code @Component} 持有连接池，只对外暴露
 * {@link JdbcTemplate}，并做懒初始化（首次查询时才建池）。</p>
 *
 * <p>池化失败不 fail-fast：上游库临时不可达时只在查询处报错，不影响 monitor 启动。</p>
 */
@Component
public class Sub2ReadOnlyJdbc {

    private static final Logger log = LoggerFactory.getLogger(Sub2ReadOnlyJdbc.class);

    private final PoolIngestProperties properties;

    /** 只读连接池，懒初始化。 */
    private volatile HikariDataSource dataSource;

    public Sub2ReadOnlyJdbc(PoolIngestProperties properties) {
        this.properties = properties;
    }

    /** 是否配置了可用的只读库连接（不看增量采集开关）。 */
    public boolean hasConnection() {
        return properties.hasConnection();
    }

    /** 是否已启用直连只读库增量采集（enabled 且连接信息完整）。 */
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /** 默认查询模板：只设查询超时。 */
    public JdbcTemplate template() {
        return template(0);
    }

    /**
     * 查询模板，可选 fetchSize。
     *
     * @param fetchSize 单次抓取条数；{@code <= 0} 表示不设置（走驱动默认值）
     */
    public JdbcTemplate template(int fetchSize) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        jdbcTemplate.setQueryTimeout(Math.max(1, (int) properties.getQueryTimeout().toSeconds()));
        if (fetchSize > 0) {
            jdbcTemplate.setFetchSize(fetchSize);
        }
        return jdbcTemplate;
    }

    private HikariDataSource dataSource() {
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
        return current;
    }

    private HikariDataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("sub2api-ro");
        config.setJdbcUrl(properties.getUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setMaximumPoolSize(Math.max(1, properties.getMaximumPoolSize()));
        config.setMinimumIdle(1);
        // 只读双保险：连接池 readOnly + 上游给只读角色
        config.setReadOnly(true);
        config.setAutoCommit(true);
        config.setConnectionTimeout(properties.getConnectTimeout().toMillis());
        config.setInitializationFailTimeout(-1);
        log.info("初始化 Sub2API 只读数据源: url={}, maxPoolSize={}",
                properties.getUrl(), config.getMaximumPoolSize());
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
