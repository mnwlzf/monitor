package com.monitor.platform.bot.channel;

import com.monitor.platform.pool.ingest.PoolIngestProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 直连 Sub2API 只读库读取「被动渠道监控 v2」的聚合指标。
 *
 * <p>对齐 Sub2API 自己的实现（{@code internal/service/channel_monitor_v2.go} 与
 * {@code internal/repository/channel_monitor_v2_repo.go}）：</p>
 * <ul>
 *     <li>数据源表 {@code channel_monitor_v2_metrics_1m}，主键是
 *         {@code (bucket_start, platform, group_id, model)} —— <strong>按分组聚合是它的原生维度</strong>；</li>
 *     <li>默认展示维度 {@code platform_group}（{@code ParseChannelMonitorV2GroupBy} 的默认值）；</li>
 *     <li>请求数 = 成功 + 失败；缓存率 = {@code cache_read / (input + cache_creation + cache_read)}
 *         —— <strong>分母不含 output</strong>；</li>
 *     <li>普通用户只能看<strong>自己可见的分组</strong>
 *         （对应 Sub2API 的 {@code scopeFilter}：订阅型分组要有有效订阅，
 *         标准分组默认公开、专属分组或开了 {@code restrict_public_groups} 的用户要落在
 *         {@code user_allowed_groups} 里）。</li>
 * </ul>
 *
 * <p>这张表已经是「平台 × 分组 × 模型 × 分钟」的聚合结果，<strong>不含任何用户身份</strong>，
 * 所以适合对外展示；而 {@code channel_monitor_v2_user_metrics_1m}（带 user_id）一律不碰。</p>
 *
 * <p>复用 {@link PoolIngestProperties} 里已经配好的只读连接信息，但<strong>不依赖 {@code enabled} 开关</strong>；
 * 自持一个只读连接池，与其它只读功能各自独立。</p>
 *
 * <p>需要上游给只读账号授权：{@code channel_monitor_v2_metrics_1m}、
 * {@code groups}、{@code user_subscriptions}、{@code user_allowed_groups}（只读）。</p>
 */
@Component
public class Sub2ChannelStatusReader {

    private static final Logger log = LoggerFactory.getLogger(Sub2ChannelStatusReader.class);

    /** 分组状态枚举值，见 Sub2API 的 {@code service.StatusActive}。 */
    private static final String GROUP_ACTIVE = "active";

    /** 订阅型分组标记，见 {@code service.SubscriptionTypeSubscription}。 */
    private static final String SUBSCRIPTION_TYPE = "subscription";

    /**
     * 普通用户可见的分组。
     *
     * <p>逐条对应 Sub2API 的 {@code canUserBindGroupInternal}：
     * 订阅型分组要求有有效订阅（{@code status='active'} 且未过期）；
     * 标准分组在「非专属 且 用户没开公开分组限制」时人人可见，
     * 否则要落在 {@code user_allowed_groups} 里。</p>
     */
    private static final String SELECT_VISIBLE_GROUPS = """
            SELECT g.id
            FROM groups g
            JOIN users u ON u.id = ?
            WHERE g.status = ?
              AND (
                    CASE WHEN g.subscription_type = ? THEN
                        EXISTS (SELECT 1 FROM user_subscriptions s
                                 WHERE s.user_id = u.id
                                   AND s.group_id = g.id
                                   AND s.status = ?
                                   AND s.expires_at > NOW())
                    ELSE
                        ((NOT g.is_exclusive) AND (NOT u.restrict_public_groups))
                        OR EXISTS (SELECT 1 FROM user_allowed_groups a
                                    WHERE a.user_id = u.id AND a.group_id = g.id)
                    END
                  )
            """;

    /** 按「平台 + 分组」聚合；不带分组过滤时给管理员看全量。 */
    private static final String SELECT_BY_PLATFORM_GROUP = """
            SELECT m.platform,
                   m.group_id,
                   COALESCE(g.name, '')                 AS group_name,
                   COALESCE(SUM(m.success_requests), 0) AS success_requests,
                   COALESCE(SUM(m.error_requests), 0)   AS error_requests,
                   COALESCE(SUM(m.input_tokens), 0)     AS input_tokens,
                   COALESCE(SUM(m.output_tokens), 0)    AS output_tokens,
                   COALESCE(SUM(m.cache_creation_tokens), 0) AS cache_creation_tokens,
                   COALESCE(SUM(m.cache_read_tokens), 0)     AS cache_read_tokens,
                   COALESCE(SUM(m.ttft_sum_ms), 0)      AS ttft_sum_ms,
                   COALESCE(SUM(m.ttft_count), 0)       AS ttft_count
            FROM channel_monitor_v2_metrics_1m m
            LEFT JOIN groups g ON g.id = m.group_id
            WHERE m.bucket_start >= ?
              AND m.bucket_start < ?
            GROUP BY m.platform, m.group_id, g.name
            ORDER BY (COALESCE(SUM(m.success_requests), 0) + COALESCE(SUM(m.error_requests), 0)) DESC,
                     m.platform,
                     m.group_id
            """;

    /** 同上，但把分组限制在可见范围内。 */
    private static final String SELECT_BY_PLATFORM_GROUP_SCOPED = """
            SELECT m.platform,
                   m.group_id,
                   COALESCE(g.name, '')                 AS group_name,
                   COALESCE(SUM(m.success_requests), 0) AS success_requests,
                   COALESCE(SUM(m.error_requests), 0)   AS error_requests,
                   COALESCE(SUM(m.input_tokens), 0)     AS input_tokens,
                   COALESCE(SUM(m.output_tokens), 0)    AS output_tokens,
                   COALESCE(SUM(m.cache_creation_tokens), 0) AS cache_creation_tokens,
                   COALESCE(SUM(m.cache_read_tokens), 0)     AS cache_read_tokens,
                   COALESCE(SUM(m.ttft_sum_ms), 0)      AS ttft_sum_ms,
                   COALESCE(SUM(m.ttft_count), 0)       AS ttft_count
            FROM channel_monitor_v2_metrics_1m m
            LEFT JOIN groups g ON g.id = m.group_id
            WHERE m.bucket_start >= ?
              AND m.bucket_start < ?
              AND m.group_id = ANY (?)
            GROUP BY m.platform, m.group_id, g.name
            ORDER BY (COALESCE(SUM(m.success_requests), 0) + COALESCE(SUM(m.error_requests), 0)) DESC,
                     m.platform,
                     m.group_id
            """;

    private static final RowMapper<ChannelStatusRow> ROW_MAPPER = (rs, rowNum) -> new ChannelStatusRow(
            rs.getString("platform"),
            rs.getLong("group_id"),
            rs.getString("group_name"),
            rs.getLong("success_requests"),
            rs.getLong("error_requests"),
            rs.getLong("input_tokens"),
            rs.getLong("output_tokens"),
            rs.getLong("cache_creation_tokens"),
            rs.getLong("cache_read_tokens"),
            rs.getLong("ttft_sum_ms"),
            rs.getLong("ttft_count")
    );

    private final PoolIngestProperties properties;

    /** 只读连接池，懒初始化。 */
    private volatile HikariDataSource dataSource;

    public Sub2ChannelStatusReader(PoolIngestProperties properties) {
        this.properties = properties;
    }

    /** 是否配置了可用的只读库连接（不看增量采集开关）。 */
    public boolean isAvailable() {
        return properties.hasConnection();
    }

    /** 普通用户可见的分组 ID。 */
    public List<Long> visibleGroupIds(long userId) {
        return template().query(connection -> {
            var statement = connection.prepareStatement(SELECT_VISIBLE_GROUPS);
            statement.setLong(1, userId);
            statement.setString(2, GROUP_ACTIVE);
            statement.setString(3, SUBSCRIPTION_TYPE);
            statement.setString(4, GROUP_ACTIVE);
            return statement;
        }, (rs, rowNum) -> rs.getLong("id"));
    }

    /** 管理员视角：指定时间窗内按「平台 + 分组」聚合的渠道指标。 */
    public List<ChannelStatusRow> byPlatformGroup(OffsetDateTime from, OffsetDateTime to) {
        return template().query(connection -> {
            var statement = connection.prepareStatement(SELECT_BY_PLATFORM_GROUP);
            statement.setObject(1, from);
            statement.setObject(2, to);
            return statement;
        }, ROW_MAPPER);
    }

    /** 普通用户视角：只统计给定分组。 */
    public List<ChannelStatusRow> byPlatformGroup(OffsetDateTime from, OffsetDateTime to, Collection<Long> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return List.of();
        }
        Long[] ids = groupIds.toArray(Long[]::new);
        return template().query(connection -> {
            var statement = connection.prepareStatement(SELECT_BY_PLATFORM_GROUP_SCOPED);
            statement.setObject(1, from);
            statement.setObject(2, to);
            statement.setArray(3, connection.createArrayOf("bigint", ids));
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
        return jdbcTemplate;
    }

    private HikariDataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("sub2api-channel-ro");
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
        log.info("初始化 Sub2API 渠道状态只读数据源: url={}", properties.getUrl());
        return new HikariDataSource(config);
    }

    @PreDestroy
    public void close() {
        HikariDataSource current = dataSource;
        if (current != null) {
            current.close();
            dataSource = null;
            log.info("已关闭 Sub2API 渠道状态只读数据源");
        }
    }

    /** 一个「平台 + 分组」的原始聚合值。 */
    public record ChannelStatusRow(
            String platform,
            long groupId,
            String groupName,
            long successRequests,
            long errorRequests,
            long inputTokens,
            long outputTokens,
            long cacheCreationTokens,
            long cacheReadTokens,
            long ttftSumMs,
            long ttftCount
    ) {

        /** 请求数 = 成功 + 失败。 */
        public long requestCount() {
            return successRequests + errorRequests;
        }

        /** Token 合计，含 output。 */
        public long tokenCount() {
            return inputTokens + outputTokens + cacheCreationTokens + cacheReadTokens;
        }

        /** 缓存率的分母：input + cache_creation + cache_read（<strong>不含 output</strong>）。 */
        public long cacheDenominator() {
            return inputTokens + cacheCreationTokens + cacheReadTokens;
        }

        /** 缓存率；没有样本时为 null。 */
        public Double cacheRate() {
            long denominator = cacheDenominator();
            return denominator <= 0 ? null : (double) cacheReadTokens / denominator;
        }

        /** 平均首 Token 耗时（毫秒）；没有样本时为 null。 */
        public Double averageTtftMs() {
            return ttftCount <= 0 ? null : (double) ttftSumMs / ttftCount;
        }
    }
}
