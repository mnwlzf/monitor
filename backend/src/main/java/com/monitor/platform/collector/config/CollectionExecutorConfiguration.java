package com.monitor.platform.collector.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 账号采集线程池配置。
 *
 * <p>采集任务涉及上游网络调用，串行执行会明显拖慢整体速度。该线程池独立于
 * 调度线程池，专门用于并发执行各账号采集，线程数和队列容量可通过配置调整。</p>
 */
@Configuration
public class CollectionExecutorConfiguration {

    /**
     * 采集任务线程池。
     *
     * @param poolSize      并发采集线程数
     * @param queueCapacity 等待队列容量
     */
    @Bean(name = "collectionTaskExecutor")
    public ThreadPoolTaskExecutor collectionTaskExecutor(
            @Value("${monitor.collection.pool-size:8}") int poolSize,
            @Value("${monitor.collection.queue-capacity:200}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        // 队列满时由调用线程继续执行，避免账号较多时因拒绝策略丢任务。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setThreadNamePrefix("collection-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
