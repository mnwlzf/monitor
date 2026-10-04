package com.monitor.platform.common.schedule;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 定时任务框架配置。
 */
@ConfigurationProperties(prefix = "monitor.scheduling")
public class SchedulingProperties {

    /** 调度线程池大小，避免默认单线程导致任务互相阻塞。 */
    private int poolSize = 4;

    /** 调度线程名称前缀。 */
    private String threadNamePrefix = "scheduled-task-";

    /** 应用关闭时等待任务结束的秒数。 */
    private int awaitTerminationSeconds = 30;

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public String getThreadNamePrefix() {
        return threadNamePrefix;
    }

    public void setThreadNamePrefix(String threadNamePrefix) {
        this.threadNamePrefix = threadNamePrefix;
    }

    public int getAwaitTerminationSeconds() {
        return awaitTerminationSeconds;
    }

    public void setAwaitTerminationSeconds(int awaitTerminationSeconds) {
        this.awaitTerminationSeconds = awaitTerminationSeconds;
    }
}

