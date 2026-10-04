package com.monitor.platform.common.schedule;

/**
 * 定时任务处理器。
 *
 * <p>页面创建的定时任务最终都会根据 {@code taskCode} 路由到对应处理器执行。
 * 新增任务类型时，只需要实现该接口并注册为 Spring Bean。</p>
 */
public interface ScheduledTaskHandler {

    /** 处理器唯一编码，页面任务配置中的 taskCode。 */
    String code();

    /** 处理器显示名称。 */
    String name();

    /** 处理器用途说明。 */
    String description();

    /** 执行一次任务。 */
    void execute();
}