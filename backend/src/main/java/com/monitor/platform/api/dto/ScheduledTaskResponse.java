package com.monitor.platform.api.dto;

import java.time.OffsetDateTime;

/**
 * 定时任务响应。
 *
 * @param id             任务主键
 * @param taskName       任务名称
 * @param taskCode       任务处理器编码
 * @param handlerName    处理器显示名称
 * @param cronExpression Cron 表达式
 * @param timezone       Cron 时区
 * @param enabled        是否启用
 * @param description    任务说明
 * @param lastRunAt      最近执行时间
 * @param lastRunStatus  最近执行状态
 * @param lastRunMessage 最近执行信息
 */
public record ScheduledTaskResponse(
        Long id,
        String taskName,
        String taskCode,
        String handlerName,
        String cronExpression,
        String timezone,
        Boolean enabled,
        String description,
        OffsetDateTime lastRunAt,
        String lastRunStatus,
        String lastRunMessage
) {
}

