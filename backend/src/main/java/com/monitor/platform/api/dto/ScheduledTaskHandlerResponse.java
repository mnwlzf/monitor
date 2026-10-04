package com.monitor.platform.api.dto;

/**
 * 定时任务处理器响应。
 *
 * @param code        处理器编码
 * @param name        处理器名称
 * @param description 处理器说明
 */
public record ScheduledTaskHandlerResponse(
        String code,
        String name,
        String description
) {
}

