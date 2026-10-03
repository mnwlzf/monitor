package com.monitor.platform.common.schedule;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 定时任务调度策略注解。
 *
 * <p>标记在 {@link ScheduledTask} 实现类上，注册器启动时读取该注解并自动注册调度。</p>
 *
 * <p>{@code cron}、{@code fixedDelay}、{@code fixedRate} 三者至少配置一项，
 * 优先级为 cron &gt; fixedRate &gt; fixedDelay。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ScheduledTaskDefinition {

    /** 任务名称，需在应用中唯一，用于日志和调度信息展示。 */
    String name();

    /** Cron 表达式，秒级六段格式，例如 {@code 0 * * * * ?}。 */
    String cron() default "";

    /** 上一次执行完成后的固定延迟，单位毫秒；大于 0 时生效。 */
    long fixedDelay() default -1;

    /** 固定执行频率，单位毫秒；大于 0 时生效。 */
    long fixedRate() default -1;

    /** 首次执行延迟，单位毫秒；仅对 fixedDelay 和 fixedRate 生效。 */
    long initialDelay() default 0;

    /** Cron 表达式使用的时区。 */
    String zone() default "Asia/Shanghai";
}

