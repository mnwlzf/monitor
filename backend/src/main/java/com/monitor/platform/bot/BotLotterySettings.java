package com.monitor.platform.bot;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 抽奖运行配置的内存快照。 */
public record BotLotterySettings(
        boolean enabled,
        BigDecimal minUsageDaily,
        BigDecimal payoutRatio,
        BigDecimal multiplierMin,
        BigDecimal multiplierMax,
        BigDecimal maxReward,
        BigDecimal dailyBudget
) {

    /** 平均随机系数。 */
    public BigDecimal averageMultiplier() {
        return multiplierMin.add(multiplierMax).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
    }

    /** 未触发最高奖励时的平均奖励率。 */
    public BigDecimal effectiveAverageRatio() {
        return payoutRatio.multiply(averageMultiplier());
    }
}
