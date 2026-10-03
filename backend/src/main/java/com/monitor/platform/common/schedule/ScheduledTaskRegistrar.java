package com.monitor.platform.common.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务自动注册器。
 *
 * <p>应用启动完成后，发现所有实现 {@link ScheduledTask} 且标注
 * {@link ScheduledTaskDefinition} 的 Bean，并按照注解配置自动提交到
 * {@link ThreadPoolTaskScheduler}。</p>
 */
@Component
@ConditionalOnProperty(prefix = "monitor.scheduling", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ScheduledTaskRegistrar implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskRegistrar.class);

    private final ThreadPoolTaskScheduler taskScheduler;
    private final List<ScheduledTask> tasks;

    public ScheduledTaskRegistrar(ThreadPoolTaskScheduler taskScheduler,
                                  List<ScheduledTask> tasks) {
        this.taskScheduler = taskScheduler;
        this.tasks = tasks;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, ScheduledTaskDefinition> registered = new HashMap<>();
        for (ScheduledTask task : tasks) {
            ScheduledTaskDefinition definition = task.getClass().getAnnotation(ScheduledTaskDefinition.class);
            if (definition == null) {
                log.warn("跳过未标注 @ScheduledTaskDefinition 的定时任务: {}", task.getClass().getName());
                continue;
            }
            if (registered.containsKey(definition.name())) {
                throw new IllegalStateException("定时任务名称重复: " + definition.name());
            }
            registered.put(definition.name(), definition);

            Trigger trigger = resolveTrigger(definition);
            taskScheduler.schedule(() -> runSafely(definition.name(), task), trigger);
            log.info("定时任务已注册: name={}, class={}, cron={}, fixedDelay={}, fixedRate={}, zone={}",
                    definition.name(), task.getClass().getName(), definition.cron(),
                    definition.fixedDelay(), definition.fixedRate(), definition.zone());
        }
        log.info("定时任务框架初始化完成，共注册 {} 个任务", registered.size());
    }

    /**
     * 根据注解生成 Spring 调度触发器。
     */
    private Trigger resolveTrigger(ScheduledTaskDefinition definition) {
        if (StringUtils.hasText(definition.cron())) {
            return new CronTrigger(definition.cron(), ZoneId.of(definition.zone()));
        }
        if (definition.fixedRate() > 0) {
            PeriodicTrigger trigger = new PeriodicTrigger(Duration.ofMillis(definition.fixedRate()));
            trigger.setFixedRate(true);
            trigger.setInitialDelay(Duration.ofMillis(Math.max(0, definition.initialDelay())));
            return trigger;
        }
        if (definition.fixedDelay() > 0) {
            PeriodicTrigger trigger = new PeriodicTrigger(Duration.ofMillis(definition.fixedDelay()));
            trigger.setInitialDelay(Duration.ofMillis(Math.max(0, definition.initialDelay())));
            return trigger;
        }
        throw new IllegalStateException("定时任务缺少调度配置: " + definition.name());
    }

    /**
     * 统一捕获任务异常，保证异常不会中断后续调度。
     */
    private void runSafely(String name, ScheduledTask task) {
        try {
            task.execute();
        } catch (Exception ex) {
            log.error("定时任务执行失败: name={}, class={}", name, task.getClass().getName(), ex);
        }
    }
}

