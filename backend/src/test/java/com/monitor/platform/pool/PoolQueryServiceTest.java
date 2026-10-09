package com.monitor.platform.pool;

import com.monitor.platform.api.dto.PoolAccountResponse;
import com.monitor.platform.api.dto.PoolCacheRateMatrixResponse;
import com.monitor.platform.api.dto.PoolHeatmapResponse;
import com.monitor.platform.api.dto.PoolIngestStatusResponse;
import com.monitor.platform.api.dto.PoolSeriesPointResponse;
import com.monitor.platform.collector.repository.AccountApiKeyRepository;
import com.monitor.platform.collector.repository.entity.AccountApiKeyEntity;
import com.monitor.platform.common.exception.BusinessException;
import com.monitor.platform.pool.ingest.PoolIngestProperties;
import com.monitor.platform.pool.ingest.Sub2ApiIngestCursorRepository;
import com.monitor.platform.pool.repository.PoolAccountRepository;
import com.monitor.platform.pool.repository.PoolSampleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 号池查询服务的聚合映射与缓存命中率计算测试。
 */
@ExtendWith(MockitoExtension.class)
class PoolQueryServiceTest {

    @Mock private PoolAccountRepository poolAccountRepository;
    @Mock private PoolSampleRepository poolSampleRepository;
    @Mock private AccountApiKeyRepository apiKeyRepository;
    @Mock private Sub2ApiIngestCursorRepository ingestCursorRepository;
    @Mock private PoolIngestProperties ingestProperties;

    private PoolQueryService service;

    @BeforeEach
    void setUp() {
        service = new PoolQueryService(poolAccountRepository, poolSampleRepository, apiKeyRepository,
                ingestCursorRepository, ingestProperties);
    }

    private PoolAccountEntity account(long externalId, String name, Long boundKeyId) {
        PoolAccountEntity entity = new PoolAccountEntity();
        entity.setPlatformId(1);
        entity.setExternalAccountId(externalId);
        entity.setName(name);
        entity.setStatus("active");
        entity.setSchedulable(true);
        entity.setBoundKeyId(boundKeyId);
        return entity;
    }

    private PoolAccountMetrics metrics(long externalId, long input, long read, long creation) {
        PoolAccountMetrics m = new PoolAccountMetrics();
        m.setExternalAccountId(externalId);
        m.setRequests(160L);
        m.setInputTokens(input);
        m.setOutputTokens(154483L);
        m.setCacheReadTokens(read);
        m.setCacheCreationTokens(creation);
        m.setFirstTokenSamples(158L);
        m.setAvgFirstTokenMs(820.0);
        m.setP95FirstTokenMs(2100.0);
        m.setAvgDurationMs(46950.5);
        m.setTotalCost(new BigDecimal("24.28697552"));
        m.setTotalActualCost(new BigDecimal("3.8859160832"));
        return m;
    }

    @Test
    void shouldComputeCacheHitRateAndAttachBoundKey() {
        PoolAccountEntity entity = account(52064L, "满血", 9L);
        when(poolAccountRepository.findByPlatform(1)).thenReturn(List.of(entity));
        when(poolSampleRepository.aggregateByPlatform(ArgumentMatchers.eq(1),
                ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(List.of(metrics(52064L, 4081379L, 12672804L, 0L)));

        AccountApiKeyEntity key = new AccountApiKeyEntity();
        key.setKeyName("上游满血");
        key.setKeyMasked("sk-abc****1234");
        when(apiKeyRepository.findById(9L)).thenReturn(Optional.of(key));

        List<PoolAccountResponse> result = service.listAccounts(1, "7d");

        assertEquals(1, result.size());
        PoolAccountResponse response = result.get(0);
        assertEquals("满血", response.name());
        assertEquals(160L, response.requests());
        assertEquals("上游满血", response.boundKeyName());
        assertEquals("sk-abc****1234", response.boundKeyMasked());
        // 缓存命中率 = 12672804 / (4081379 + 12672804 + 0) ≈ 0.7563
        assertEquals(0.7563, response.cacheHitRate(), 0.0001);
    }

    @Test
    void shouldReturnNullCacheHitRateWhenDenominatorIsZero() {
        when(poolAccountRepository.findByPlatform(1)).thenReturn(List.of(account(1L, "空", null)));
        when(poolSampleRepository.aggregateByPlatform(ArgumentMatchers.eq(1),
                ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(List.of());

        List<PoolAccountResponse> result = service.listAccounts(1, "7d");

        assertEquals(1, result.size());
        assertEquals(0L, result.get(0).requests());
        assertNull(result.get(0).cacheHitRate());
    }

    @Test
    void shouldMapSeriesWithCacheHitRate() {
        when(poolAccountRepository.findByPlatformAndExternalId(1, 52064L))
                .thenReturn(Optional.of(account(52064L, "满血", null)));

        PoolSeriesPoint point = new PoolSeriesPoint();
        point.setBucket(OffsetDateTime.parse("2026-10-08T10:00:00+08:00"));
        point.setRequests(12L);
        point.setInputTokens(100L);
        point.setCacheReadTokens(300L);
        point.setCacheCreationTokens(100L);
        point.setFirstTokenSamples(12L);
        point.setAvgFirstTokenMs(900.0);
        point.setP95FirstTokenMs(2000.0);
        point.setAvgDurationMs(30000.0);
        point.setTotalActualCost(new BigDecimal("0.5"));
        when(poolSampleRepository.series(ArgumentMatchers.eq(1), ArgumentMatchers.eq(52064L),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.eq("hour")))
                .thenReturn(List.of(point));

        List<PoolSeriesPointResponse> result = service.series(1, 52064L, "1d", null);

        assertEquals(1, result.size());
        assertEquals(12L, result.get(0).requests());
        // 300 / (100 + 300 + 100) = 0.6
        assertEquals(0.6, result.get(0).cacheHitRate(), 0.0001);
    }

    @Test
    void shouldBuildCacheRateMatrixAcrossWindows() {
        when(poolAccountRepository.findByPlatform(1)).thenReturn(List.of(account(52064L, "满血", null)));
        // 每个时间窗一次聚合：1h 有样本、6h 无样本、24h 有样本
        when(poolSampleRepository.aggregateByPlatform(ArgumentMatchers.eq(1),
                ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(List.of(metrics(52064L, 100L, 300L, 0L)))
                .thenReturn(List.of())
                .thenReturn(List.of(metrics(52064L, 200L, 200L, 0L)));

        PoolCacheRateMatrixResponse matrix = service.cacheRateMatrix(1, "1h,6h,24h");

        assertEquals(3, matrix.windows().size());
        assertEquals("1h", matrix.windows().get(0).key());
        assertEquals("近 1 小时", matrix.windows().get(0).label());
        assertEquals("24h", matrix.windows().get(2).key());

        assertEquals(1, matrix.accounts().size());
        List<PoolCacheRateMatrixResponse.Cell> cells = matrix.accounts().get(0).cells();
        assertEquals(3, cells.size());
        assertEquals(160L, cells.get(0).requests());
        assertEquals(0.75, cells.get(0).cacheHitRate(), 0.0001);
        // 6h 窗口没有样本：请求数为 0、命中率为 null（不补 0，避免误判成 0%）
        assertEquals(0L, cells.get(1).requests());
        assertNull(cells.get(1).cacheHitRate());
        assertEquals(0.5, cells.get(2).cacheHitRate(), 0.0001);
    }

    @Test
    void shouldFallBackToDefaultWindowsWhenParamInvalid() {
        when(poolAccountRepository.findByPlatform(1)).thenReturn(List.of());
        when(poolSampleRepository.aggregateByPlatform(ArgumentMatchers.eq(1),
                ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(List.of());

        PoolCacheRateMatrixResponse matrix = service.cacheRateMatrix(1, "bogus,,,");

        // 默认 5m,15m,1h,24h,7d（含分钟档）
        assertEquals(5, matrix.windows().size());
        assertEquals(List.of("5m", "15m", "1h", "24h", "7d"),
                matrix.windows().stream().map(PoolCacheRateMatrixResponse.Window::key).toList());
    }

    @Test
    void shouldDefaultToMinuteGranularityForShortWindows() {
        when(poolAccountRepository.findByPlatformAndExternalId(1, 52064L))
                .thenReturn(Optional.of(account(52064L, "满血", null)));
        when(poolSampleRepository.series(ArgumentMatchers.eq(1), ArgumentMatchers.eq(52064L),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.eq("minute")))
                .thenReturn(List.of());

        service.series(1, 52064L, "6h", null);

        verify(poolSampleRepository).series(ArgumentMatchers.eq(1), ArgumentMatchers.eq(52064L),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.eq("minute"));
    }

    @Test
    void shouldDowngradeMinuteGranularityForLongWindows() {
        when(poolAccountRepository.findByPlatformAndExternalId(1, 52064L))
                .thenReturn(Optional.of(account(52064L, "满血", null)));
        when(poolSampleRepository.series(ArgumentMatchers.eq(1), ArgumentMatchers.eq(52064L),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.eq("hour")))
                .thenReturn(List.of());

        service.series(1, 52064L, "30d", "minute");

        // 30 天 × 每分钟 = 12.9 万个桶，超出 1 天自动降级为小时
        verify(poolSampleRepository).series(ArgumentMatchers.eq(1), ArgumentMatchers.eq(52064L),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.eq("hour"));
    }

    @Test
    void shouldReportIngestStatusWithLag() {
        OffsetDateTime latest = OffsetDateTime.now().minusSeconds(45);
        when(poolSampleRepository.findLatestCreatedAt(1)).thenReturn(latest);
        when(ingestCursorRepository.lastUsageLogId()).thenReturn(12345L);
        when(ingestCursorRepository.lastRunAt()).thenReturn(latest);
        when(ingestProperties.isEnabled()).thenReturn(true);
        when(ingestProperties.isConfigured()).thenReturn(true);
        when(ingestProperties.getPassword()).thenReturn("readonly-secret");

        PoolIngestStatusResponse status = service.ingestStatus(1);

        assertTrue(status.enabled());
        assertTrue(status.configured());
        assertTrue(status.passwordConfigured());
        assertEquals(12345L, status.lastUsageLogId());
        assertEquals(latest, status.latestSampleAt());
        assertNotNull(status.lagSeconds());
        assertTrue(status.lagSeconds() >= 45);
    }

    @Test
    void shouldBuildHeatmapMatrixByAccountAndBucket() {
        when(poolAccountRepository.findByPlatform(1)).thenReturn(List.of(account(52064L, "满血", null)));
        // 取「5 分钟前所在的整点」，保证落在窗口内且不会正好等于窗口终点
        OffsetDateTime bucket = OffsetDateTime.now().minusMinutes(5).truncatedTo(ChronoUnit.HOURS);
        when(poolSampleRepository.heatmapBuckets(ArgumentMatchers.eq(1), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.eq("hour"), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(List.of(heatmapBucket(52064L, bucket, 10L, 100L, 300L, 0L)));

        PoolHeatmapResponse heatmap = service.heatmap(1, "24h", null, List.of(), List.of(), List.of());

        assertEquals("hour", heatmap.granularity());
        assertTrue(heatmap.buckets().size() >= 24);
        assertEquals(1, heatmap.rows().size());
        PoolHeatmapResponse.Row row = heatmap.rows().get(0);
        assertEquals(heatmap.buckets().size(), row.cells().size());
        assertEquals(10L, row.total().requests());
        // 300 / (100 + 300 + 0) = 0.75
        assertEquals(0.75, row.total().cacheHitRate(), 0.0001);
        // 只有命中的那个桶有数据，其余为空（requests=0、命中率 null）
        assertEquals(1L, row.cells().stream().filter(cell -> cell.requests() > 0).count());
        assertEquals(10L, heatmap.summary().requests());
    }

    private PoolHeatmapBucket heatmapBucket(long externalId, OffsetDateTime bucket,
                                            long requests, long input, long read, long creation) {
        PoolHeatmapBucket row = new PoolHeatmapBucket();
        row.setExternalAccountId(externalId);
        row.setBucket(bucket);
        row.setRequests(requests);
        row.setInputTokens(input);
        row.setOutputTokens(200L);
        row.setCacheReadTokens(read);
        row.setCacheCreationTokens(creation);
        row.setFirstTokenSamples(requests);
        row.setAvgFirstTokenMs(1000.0);
        row.setAvgDurationMs(2000.0);
        row.setTotalActualCost(new BigDecimal("0.1"));
        return row;
    }

    @Test
    void shouldRejectUnknownPoolAccount() {
        when(poolAccountRepository.findByPlatformAndExternalId(1, 999L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.series(1, 999L, "7d", null));
    }
}