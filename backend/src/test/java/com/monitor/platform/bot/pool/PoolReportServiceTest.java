package com.monitor.platform.bot.pool;

import com.monitor.platform.api.dto.PoolHeatmapResponse;
import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.pool.PoolQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 号池报表的结构约定：默认时间窗、有/无流量两种形态、热力图标记。
 */
class PoolReportServiceTest {

    private PlatformRepository platformRepository;
    private PoolQueryService poolQueryService;
    private PoolReportService service;

    @BeforeEach
    void setUp() {
        platformRepository = mock(PlatformRepository.class);
        poolQueryService = mock(PoolQueryService.class);
        service = new PoolReportService(platformRepository, poolQueryService);
        when(platformRepository.findAll()).thenReturn(List.of(poolPlatform()));
    }

    @Test
    void shouldRenderTrafficRowWithHeatColumnsAndDefaultRange() {
        when(poolQueryService.heatmap(eq(1), eq("24h"), any(), any(), any(), any()))
                .thenReturn(heatmap(trafficRow()));

        BotReport report = service.report(null);

        assertEquals("号池监控（近 24h）", report.title());
        assertEquals(1, report.blocks().size(), "有流量时只应出现「有流量的渠道」一块");
        String plain = report.toPlainText();
        assertTrue(plain.contains("openai / 满血"), plain);
        assertTrue(plain.contains("80.0%"), plain);
        assertTrue(plain.contains("6.4s"), plain);
        assertTrue(plain.contains("30.5"), plain);
        verify(poolQueryService).heatmap(eq(1), eq("24h"), any(), any(), any(), any());
    }

    @Test
    void shouldListIdleChannelsWhenNoTraffic() {
        when(poolQueryService.heatmap(eq(1), eq("1h"), any(), any(), any(), any()))
                .thenReturn(heatmap(idleRow()));

        BotReport report = service.report("1h");

        assertEquals("号池监控（近 1h）", report.title());
        assertEquals(1, report.blocks().size(), "没有流量时不生成热力矩阵，只列无流量渠道");
        assertTrue(report.blocks().get(0).heading().contains("无流量的渠道"));
        assertTrue(report.notes().stream().anyMatch(note -> note.contains("没有任何流量")));
    }

    @Test
    void shouldTurnHeatmapDirectiveIntoMarkerWithKeyNumbers() {
        when(poolQueryService.heatmap(eq(1), eq("24h"), any(), any(), any(), any()))
                .thenReturn(heatmap(trafficRow()));

        String directive = service.heatmapDirective(null);

        assertTrue(directive.startsWith("[[HEATMAP:24h]]"), directive);
        assertTrue(directive.contains("缓存率最高 80.0%"), directive);
        assertTrue(directive.contains("openai / 满血"), directive);
    }

    @Test
    void shouldTellUserWhenNoPoolPlatformConfigured() {
        when(platformRepository.findAll()).thenReturn(List.of());

        BotReport report = service.report("24h");

        assertTrue(report.toPlainText().contains("还没有把任何平台标记为「号池监控源」"), report.toPlainText());
        verifyNoInteractions(poolQueryService);
    }

    private static PlatformEntity poolPlatform() {
        PlatformEntity entity = new PlatformEntity();
        entity.setId(1);
        entity.setStatus(true);
        entity.setPoolMonitoringEnabled(true);
        return entity;
    }

    private static PoolHeatmapResponse heatmap(PoolHeatmapResponse.Row row) {
        OffsetDateTime from = OffsetDateTime.now().minusHours(1);
        List<OffsetDateTime> buckets = List.of(from, from.plusMinutes(15), from.plusMinutes(30));
        return new PoolHeatmapResponse(from, OffsetDateTime.now(), "hour", buckets, row.total(), List.of(row));
    }

    private static PoolHeatmapResponse.Row trafficRow() {
        PoolHeatmapResponse.Metrics metrics = new PoolHeatmapResponse.Metrics(12, 0.8, 6400.0, 30.5, 4.0, null);
        PoolHeatmapResponse.Metrics empty = new PoolHeatmapResponse.Metrics(0, null, null, null, null, null);
        return new PoolHeatmapResponse.Row(52064L, "满血", "openai", null, null, metrics,
                List.of(metrics, empty, empty));
    }

    private static PoolHeatmapResponse.Row idleRow() {
        PoolHeatmapResponse.Metrics empty = new PoolHeatmapResponse.Metrics(0, null, null, null, null, null);
        return new PoolHeatmapResponse.Row(52065L, "闲置号", "openai", null, null, empty,
                List.of(empty, empty, empty));
    }
}