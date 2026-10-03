package com.monitor.platform.common.schedule;

/**
 * 定时任务统一接口。
 *
 * <p>所有需要被 {@link ScheduledTaskRegistrar} 自动调度的任务都应实现该接口，
 * 并使用 {@link ScheduledTaskDefinition} 标注调度策略。任务执行过程中抛出的异常
 * 由注册器统一捕获并记录，不会影响其他任务或后续调度。</p>
 */
public interface ScheduledTask {

    /**
     * 执行一次定时任务。
     */
    void execute();
}

