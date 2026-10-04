package com.monitor.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建定时任务请求。
 *
 * @param taskName       任务名称
 * @param taskCode       任务处理器编码
 * @param cronExpression 秒级六段 Cron 表达式
 * @param timezone       Cron 时区
 * @param enabled        是否启用
 * @param description    任务说明
 */
public record CreateScheduledTaskRequest(
        @NotBlank(message = "任务名称不能为空")
        @Size(max = 100, message = "任务名称不能超过 100 个字符")
        String taskName,

        @NotBlank(message = "任务类型不能为空")
        @Size(max = 100, message = "任务类型不能超过 100 个字符")
        String taskCode,

        @NotBlank(message = "Cron 表达式不能为空")
        @Size(max = 120, message = "Cron 表达式不能超过 120 个字符")
        String cronExpression,

        @Size(max = 50, message = "时区不能超过 50 个字符")
        String timezone,

        Boolean enabled,

        @Size(max = 255, message = "任务说明不能超过 255 个字符")
        String description
) {
}

