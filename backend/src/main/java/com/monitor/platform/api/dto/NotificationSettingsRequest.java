package com.monitor.platform.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;

/**
 * 保存邮件通知设置请求。
 *
 * @param balanceAlertEnabled  是否启用余额不足提醒
 * @param balanceThreshold     余额提醒阈值
 * @param alertIntervalMinutes 重复提醒间隔（分钟）
 */
public record NotificationSettingsRequest(
        Boolean balanceAlertEnabled,

        @DecimalMin(value = "0", message = "余额阈值不能为负数")
        BigDecimal balanceThreshold,

        @Min(value = 1, message = "提醒间隔至少 1 分钟")
        Integer alertIntervalMinutes
) {
}