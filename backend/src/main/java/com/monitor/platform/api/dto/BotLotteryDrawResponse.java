package com.monitor.platform.api.dto;

import com.monitor.platform.bot.BotLotteryDrawEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 页面展示的抽奖记录。 */
public record BotLotteryDrawResponse(
        Long id,
        String email,
        LocalDate drawDate,
        BigDecimal eligibleUsage,
        BigDecimal payoutRatioPercent,
        BigDecimal multiplier,
        BigDecimal rewardAmount,
        String status,
        String failureReason,
        OffsetDateTime createdAt,
        OffsetDateTime creditedAt
) {

    public static BotLotteryDrawResponse from(BotLotteryDrawEntity entity) {
        return new BotLotteryDrawResponse(
                entity.getId(),
                entity.getEmail(),
                entity.getDrawDate(),
                entity.getEligibleUsage(),
                entity.getPayoutRatio() == null ? null
                        : entity.getPayoutRatio().multiply(BigDecimal.valueOf(100)),
                entity.getMultiplier(),
                entity.getRewardAmount(),
                entity.getStatus(),
                entity.getFailureReason(),
                entity.getCreatedAt(),
                entity.getCreditedAt());
    }
}
