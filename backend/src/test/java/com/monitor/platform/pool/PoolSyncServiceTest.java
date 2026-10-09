package com.monitor.platform.pool;

import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.pool.client.Sub2AdminClient;
import com.monitor.platform.pool.client.Sub2UsageLog;
import com.monitor.platform.pool.repository.PoolAccountRepository;
import com.monitor.platform.pool.repository.PoolSampleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 号池明细采集「触顶 → 往回补齐」的游标逻辑测试。
 *
 * <p>上游按天过滤 + 分页倒序，触顶时只会拿到「最新」的一段；旧实现仍推进水位，
 * 导致中间缺口永久丢失（实测丢过 1811 条）。这里锁定修复后的行为。</p>
 */
@ExtendWith(MockitoExtension.class)
class PoolSyncServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 与 PoolSyncService 中的每页条数保持一致。 */
    private static final int SAMPLE_PAGE_SIZE = 1000;

    @Mock private PlatformRepository platformRepository;
    @Mock private PoolAccountRepository poolAccountRepository;
    @Mock private PoolSampleRepository poolSampleRepository;
    @Mock private Sub2AdminClient adminClient;
    @Mock private PoolAdminKeyResolver adminKeyResolver;
    @Mock private PoolAccountBindService bindService;

    private PoolSyncService service;
    private PlatformEntity platform;

    @BeforeEach
    void setUp() {
        service = new PoolSyncService(platformRepository, poolAccountRepository, poolSampleRepository,
                adminClient, adminKeyResolver, bindService);
        platform = new PlatformEntity();
        platform.setId(1);
        platform.setPlatformName("自建");
        platform.setPlatformType("sub2api");
        platform.setUrl("https://pool.example.com");
        platform.setStatus(true);
        platform.setPoolMonitoringEnabled(true);
        when(adminKeyResolver.resolve(platform)).thenReturn(Optional.of("admin-key"));
    }

    private PoolAccountEntity account(OffsetDateTime watermark, OffsetDateTime backfillUntil) {
        PoolAccountEntity entity = new PoolAccountEntity();
        entity.setPlatformId(1);
        entity.setExternalAccountId(52064L);
        entity.setName("满血");
        entity.setLastSampleAt(watermark);
        entity.setBackfillUntil(backfillUntil);
        return entity;
    }

    private Sub2UsageLog usage(String requestId, OffsetDateTime createdAt) {
        return new Sub2UsageLog(requestId, 1L, "gpt-5", 1L, "/v1/responses", true, createdAt,
                100, 1000, 10L, 10L, 0L, 0L, null, null);
    }

    /** 每页都返回满页，模拟「还有更早的数据没取到」。 */
    private void stubAlwaysFullPage(OffsetDateTime newest) {
        when(adminClient.fetchUsage(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    List<Sub2UsageLog> page = new ArrayList<>(SAMPLE_PAGE_SIZE);
                    for (int i = 0; i < SAMPLE_PAGE_SIZE; i++) {
                        page.add(usage("req-" + i, newest.minusMinutes(i)));
                    }
                    return page;
                });
    }

    @Test
    void shouldSwitchToBackfillInsteadOfAdvancingWatermarkWhenTruncated() {
        OffsetDateTime newest = OffsetDateTime.now(ZONE).minusMinutes(5);
        stubAlwaysFullPage(newest);
        when(poolAccountRepository.findByPlatform(1)).thenReturn(List.of(account(null, null)));

        service.syncSamples(platform);

        // 只拿到「最新」一段 → 记录回补游标（本批最早一条），且绝不推进水位
        ArgumentCaptor<OffsetDateTime> cursor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(poolAccountRepository).updateBackfillUntil(
                ArgumentMatchers.eq(1), ArgumentMatchers.eq(52064L), cursor.capture());
        assertEquals(newest.minusMinutes(SAMPLE_PAGE_SIZE - 1), cursor.getValue());
        verify(poolAccountRepository, never()).updateSampleWatermark(
                ArgumentMatchers.anyInt(), ArgumentMatchers.anyLong(), ArgumentMatchers.any());
    }

    @Test
    void shouldClearBackfillCursorOnceGapIsFilled() {
        OffsetDateTime watermark = OffsetDateTime.now(ZONE).minusHours(2);
        OffsetDateTime cursor = OffsetDateTime.now(ZONE).minusDays(3);
        when(poolAccountRepository.findByPlatform(1)).thenReturn(List.of(account(watermark, cursor)));
        // 往回补齐区间为 [today-7d, 游标]；本批全部早于水位且不足一页 → 缺口取完了
        when(adminClient.fetchUsage(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt()))
                .thenReturn(List.of(
                        usage("old-1", cursor.minusHours(2)),
                        usage("old-2", cursor.minusHours(1))));

        service.syncSamples(platform);

        // 缺口补齐 → 清空游标；取回的都是旧数据，水位不能被回退
        verify(poolAccountRepository).updateBackfillUntil(
                ArgumentMatchers.eq(1), ArgumentMatchers.eq(52064L), ArgumentMatchers.isNull());
        verify(poolAccountRepository, never()).updateSampleWatermark(
                ArgumentMatchers.anyInt(), ArgumentMatchers.anyLong(), ArgumentMatchers.any());
    }

    @Test
    void shouldAdvanceWatermarkOnNormalIncrementalRun() {
        OffsetDateTime watermark = OffsetDateTime.now(ZONE).minusHours(2);
        OffsetDateTime newest = OffsetDateTime.now(ZONE).minusMinutes(1);
        when(poolAccountRepository.findByPlatform(1)).thenReturn(List.of(account(watermark, null)));
        when(adminClient.fetchUsage(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt()))
                .thenReturn(List.of(usage("new-1", newest), usage("old-1", watermark.minusMinutes(30))));

        service.syncSamples(platform);

        verify(poolAccountRepository).updateSampleWatermark(1, 52064L, newest);
        verify(poolAccountRepository, never()).updateBackfillUntil(
                ArgumentMatchers.anyInt(), ArgumentMatchers.anyLong(), ArgumentMatchers.any());
    }

    @Test
    void shouldNotLookBackBeyondConfiguredWindow() {
        OffsetDateTime veryOld = OffsetDateTime.now(ZONE).minusDays(60);
        when(poolAccountRepository.findByPlatform(1)).thenReturn(List.of(account(veryOld, null)));
        when(adminClient.fetchUsage(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        service.syncSamples(platform);

        ArgumentCaptor<LocalDate> startCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(adminClient).fetchUsage(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyLong(), startCaptor.capture(), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt());
        // 水位虽然在 60 天前，但首次回填最多只回溯 7 天
        assertEquals(LocalDate.now(ZONE).minusDays(7), startCaptor.getValue());
    }
}