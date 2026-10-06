package com.monitor.platform.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 邮件通知设置响应。
 *
 * @param balanceAlertEnabled  是否启用余额不足提醒
 * @param balanceThreshold     余额提醒阈值
 * @param alertIntervalMinutes 重复提醒间隔（分钟）
 * @param updatedAt            最近更新时间
 */
public record NotificationSettingsResponse(
        boolean balanceAlertEnabled,
        BigDecimal balanceThreshold,
        int alertIntervalMinutes,
        OffsetDateTime updatedAt
) {
}