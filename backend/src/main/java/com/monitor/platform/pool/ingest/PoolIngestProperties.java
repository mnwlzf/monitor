package com.monitor.platform.pool.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 直连自建 Sub2API 数据库做增量采集的配置。
 *
 * <p>上游 admin 接口只支持按天过滤，无法支撑分钟级缓存率；直连只读库可以按
 * {@code usage_logs.id} 做真增量，把滞后压到轮询间隔量级。</p>
 *
 * <p>默认关闭：只有显式配置了 {@code monitor.pool.ingest.enabled=true} 与数据源才会工作，
 * 未配置时相关任务每轮直接跳过。</p>
 */
@ConfigurationProperties(prefix = "monitor.pool.ingest")
public class PoolIngestProperties {

    /** 是否启用直连库增量采集。 */
    private boolean enabled = false;

    /** Sub2API 只读数据库 JDBC 地址（建议只给 SELECT 权限的账号）。 */
    private String url;

    /** 只读账号。 */
    private String username;

    /** 只读账号密码。 */
    private String password;

    /** 单批拉取的明细条数。 */
    private int batchSize = 2000;

    /** 单轮最多拉取的批数，避免停机后一次性拉爆。 */
    private int maxBatchesPerRun = 20;

    /** 连接超时。 */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** 单条查询超时。 */
    private Duration queryTimeout = Duration.ofSeconds(30);

    /** 连接池最大连接数（只读，给 2 个足够）。 */
    private int maximumPoolSize = 2;

    /** 是否已配置好可用的数据源（增量采集开关打开且连接信息完整）。 */
    public boolean isConfigured() {
        return enabled && hasConnection();
    }

    /**
     * 是否配置了可用的只读库连接，<strong>不看 enabled 开关</strong>。
     *
     * <p>用于复用同一套连接信息、但独立于增量采集的功能（例如 QQ 机器人的平台用户识别），
     * 这样关掉增量采集不会连带把用户识别也停掉。</p>
     */
    public boolean hasConnection() {
        return url != null && !url.isBlank() && username != null && !username.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxBatchesPerRun() {
        return maxBatchesPerRun;
    }

    public void setMaxBatchesPerRun(int maxBatchesPerRun) {
        this.maxBatchesPerRun = maxBatchesPerRun;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getQueryTimeout() {
        return queryTimeout;
    }

    public void setQueryTimeout(Duration queryTimeout) {
        this.queryTimeout = queryTimeout;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }
}