package com.monitor.platform.api.dto;

import java.math.BigDecimal;

/** 保存抽奖设置。{@code payoutRatioPercent=3} 表示 3%。 */
public record BotLotterySettingsRequest(
        Boolean enabled,
        BigDecimal minUsageDaily,
        BigDecimal payoutRatioPercent,
        BigDecimal multiplierMin,
        BigDecimal multiplierMax,
        BigDecimal maxReward,
        BigDecimal dailyBudget
) {
}
