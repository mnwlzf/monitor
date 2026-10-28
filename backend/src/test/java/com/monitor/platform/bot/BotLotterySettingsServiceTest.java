package com.monitor.platform.bot;

import com.monitor.platform.api.dto.BotLotterySettingsRequest;
import com.monitor.platform.api.dto.BotLotterySettingsResponse;
import com.monitor.platform.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotLotterySettingsServiceTest {

    @Test
    void shouldConvertDisplayedPercentToStoredRatio() {
        BotLotterySettingsRepository repository = mock(BotLotterySettingsRepository.class);
        BotLotterySettingsService service = new BotLotterySettingsService(repository);
        BotLotterySettingsRequest request = new BotLotterySettingsRequest(
                true,
                new BigDecimal("10"),
                new BigDecimal("3"),
                new BigDecimal("0.8"),
                new BigDecimal("1.2"),
                new BigDecimal("5"),
                new BigDecimal("50"));

        BotLotterySettingsResponse response = service.save(request);

        assertEquals(new BigDecimal("3.0000"), response.payoutRatioPercent());
        assertEquals(0, new BigDecimal("0.03").compareTo(service.current().payoutRatio()));
        verify(repository).save(any(BotLotterySettingsEntity.class));
    }

    @Test
    void shouldRejectReversedMultiplierRange() {
        BotLotterySettingsRepository repository = mock(BotLotterySettingsRepository.class);
        BotLotterySettingsService service = new BotLotterySettingsService(repository);
        BotLotterySettingsRequest request = new BotLotterySettingsRequest(
                true,
                new BigDecimal("10"),
                new BigDecimal("3"),
                new BigDecimal("1.2"),
                new BigDecimal("0.8"),
                new BigDecimal("5"),
                new BigDecimal("50"));

        assertThrows(BusinessException.class, () -> service.save(request));
    }
}
