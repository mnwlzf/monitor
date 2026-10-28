package com.monitor.platform.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 页面展示的抽奖设置。 */
public record BotLotterySettingsResponse(
        boolean enabled,
        BigDecimal minUsageDaily,
        BigDecimal payoutRatioPercent,
        BigDecimal multiplierMin,
        BigDecimal multiplierMax,
        BigDecimal maxReward,
        BigDecimal dailyBudget,
        OffsetDateTime updatedAt
) {
}
