package com.monitor.platform.bot.user;

import com.monitor.platform.bot.identity.Sub2ApiUserCache;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.pool.PoolAdminKeyResolver;
import com.monitor.platform.common.time.BusinessTime;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotKeyUsageServiceTest {

    @Test
    void shouldDefaultToTodayFromMidnightUntilNow() {
        BotKeyUsageService.DateRange range = BotKeyUsageService.parseRange(null, null);

        OffsetDateTime expectedStart = LocalDate.now(BusinessTime.ZONE)
                .atStartOfDay(BusinessTime.ZONE).toOffsetDateTime();
        assertThat(range.start()).isEqualTo(expectedStart);
        assertThat(range.end()).isAfterOrEqualTo(range.start());
        assertThat(range.days()).isEqualTo(1);
    }

    @Test
    void shouldAllowExactlyNinetyCalendarDays() {
        BotKeyUsageService.DateRange range = BotKeyUsageService.parseRange(
                "2026-01-01", "2026-03-31");

        assertThat(range.days()).isEqualTo(90);
        assertThat(range.start().toLocalDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(range.end().toLocalDate()).isEqualTo(LocalDate.of(2026, 4, 1));
    }

    @Test
    void shouldRejectRangeOverNinetyDays() {
        assertThatThrownBy(() -> BotKeyUsageService.parseRange("2026-01-01", "2026-04-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多 90 天");
    }

    @Test
    void shouldRejectInvalidDateFormat() {
        assertThatThrownBy(() -> BotKeyUsageService.parseRange("2026/01/01", "2026/01/31"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }

    @Test
    void shouldParseAndDeduplicateKeyIndexes() {
        List<Integer> indexes = BotKeyUsageService.parseKeyIndexes("1, 3，3、2");

        assertThat(indexes).containsExactly(1, 3, 2);
    }

    @Test
    void shouldRejectMoreThanThreeKeyIndexes() {
        assertThatThrownBy(() -> BotKeyUsageService.parseKeyIndexes("1,2,3,4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多选择 3 个");
    }
    @Test
    void shouldRenderTotalThenModelsWithoutRealKeyId() {
        PlatformRepository platformRepository = mock(PlatformRepository.class);
        PoolAdminKeyResolver adminKeyResolver = mock(PoolAdminKeyResolver.class);
        Sub2ApiUserCache userCache = mock(Sub2ApiUserCache.class);
        Sub2UserAdminClient client = mock(Sub2UserAdminClient.class);
        Sub2KeyUsageReader reader = mock(Sub2KeyUsageReader.class);
        PlatformEntity platform = new PlatformEntity();
        platform.setId(1);
        platform.setUrl("https://sub2.example.com");
        platform.setStatus(true);
        platform.setPoolMonitoringEnabled(true);
        when(platformRepository.findAll()).thenReturn(List.of(platform));
        when(adminKeyResolver.resolve(platform)).thenReturn(Optional.of("admin-key"));
        when(userCache.userId("user@example.com")).thenReturn(Optional.of(7L));
        Sub2AdminApiKey key = new Sub2AdminApiKey(12L, "prod", "sk-abc****wxyz",
                "active", 0.0, 0.0, null, null, null, null, null);
        when(client.fetchApiKeys("https://sub2.example.com", "admin-key", 7L, 1, 50))
                .thenReturn(List.of(key));
        when(reader.isAvailable()).thenReturn(true);
        when(reader.byKeyIds(eq(List.of(12L)), any(), any()))
                .thenReturn(Map.of(12L, new Sub2KeyUsageReader.KeyUsage(12L, 10, 100, 50, 20, 30,
                        new BigDecimal("0.20"), new BigDecimal("0.10"), 1200)));
        when(reader.byKeyAndModel(eq(List.of(12L)), any(), any()))
                .thenReturn(List.of(
                        new Sub2KeyUsageReader.ModelUsage(12L, "gpt-4o", 6, 60, 30, 10, 20,
                                new BigDecimal("0.12"), new BigDecimal("0.06"), 1000),
                        new Sub2KeyUsageReader.ModelUsage(12L, "claude", 4, 40, 20, 10, 10,
                                new BigDecimal("0.08"), new BigDecimal("0.04"), 1500)));

        BotKeyUsageService service = new BotKeyUsageService(
                platformRepository, adminKeyResolver, userCache, client, reader);
        String text = service.selfReport("user@example.com", null, null, "1").toPlainText();

        assertThat(text)
                .contains("Key 1 · prod · 总计")
                .contains("Key 1 · 模型 gpt-4o")
                .contains("Key 1 · 模型 claude")
                .contains("实际消耗")
                .doesNotContain("ID 12");
    }

}
