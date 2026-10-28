package com.monitor.platform.bot;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BotLotteryServiceTest {

    private static final BotLotterySettings SETTINGS = new BotLotterySettings(
            true,
            new BigDecimal("10"),
            new BigDecimal("0.03"),
            new BigDecimal("0.8"),
            new BigDecimal("1.2"),
            new BigDecimal("5"),
            new BigDecimal("50"));

    @Test
    void shouldCalculateRatioAndRandomMultiplier() {
        assertEquals(new BigDecimal("0.72"), BotLotteryService.calculateReward(
                new BigDecimal("20"), SETTINGS, new BigDecimal("1.2")));
    }

    @Test
    void shouldApplyOnlyUpperRewardCap() {
        assertEquals(new BigDecimal("5.00"), BotLotteryService.calculateReward(
                new BigDecimal("500"), SETTINGS, new BigDecimal("1.2")));
        assertEquals(new BigDecimal("0.00"), BotLotteryService.calculateReward(
                new BigDecimal("0.01"), SETTINGS, new BigDecimal("0.8")));
    }

    @Test
    void shouldUsePreviousCompleteCalendarDay() {
        BotLotteryService.UsageWindow window = BotLotteryService.usageWindowFor(LocalDate.of(2026, 10, 28));

        assertEquals(OffsetDateTime.parse("2026-10-27T00:00:00+08:00"), window.start());
        assertEquals(OffsetDateTime.parse("2026-10-28T00:00:00+08:00"), window.end());
    }
}
