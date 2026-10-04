package com.monitor.platform.common.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 定时任务框架配置。
 *
 * <p>创建独立的 {@link ThreadPoolTaskScheduler}，使多个定时任务可以并行执行，
 * 避免 Spring 默认单线程调度器在任务耗时较长时互相阻塞。</p>
 */
@Configuration
@EnableConfigurationProperties(SchedulingProperties.class)
public class SchedulingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SchedulingConfiguration.class);

    /**
     * 调度线程池 Bean。
     *
     * <p>Spring 的 {@code @EnableScheduling} 也会复用该 Bean，因此后续新增的
     * {@code @Scheduled} 方法默认运行在同一个可配置线程池中。</p>
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler(SchedulingProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.getPoolSize());
        scheduler.setThreadNamePrefix(properties.getThreadNamePrefix());
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(properties.getAwaitTerminationSeconds());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        log.info("定时任务调度线程池初始化完成: poolSize={}, threadNamePrefix={}",
                properties.getPoolSize(), properties.getThreadNamePrefix());
        return scheduler;
    }
}

