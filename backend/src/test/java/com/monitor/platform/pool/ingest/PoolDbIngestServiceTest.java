package com.monitor.platform.pool.ingest;

import com.monitor.platform.collector.repository.PlatformRepository;
import com.monitor.platform.collector.repository.entity.PlatformEntity;
import com.monitor.platform.pool.PoolAccountEntity;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 直连库增量采集的游标推进逻辑测试。
 */
@ExtendWith(MockitoExtension.class)
class PoolDbIngestServiceTest {

    @Mock private PoolIngestProperties properties;
    @Mock private Sub2ApiUsageLogReader reader;
    @Mock private Sub2ApiIngestCursorRepository cursorRepository;
    @Mock private PlatformRepository platformRepository;
    @Mock private PoolAccountRepository poolAccountRepository;
    @Mock private PoolSampleRepository poolSampleRepository;

    private PoolDbIngestService service;
    private PlatformEntity platform;

    @BeforeEach
    void setUp() {
        service = new PoolDbIngestService(properties, reader, cursorRepository, platformRepository,
                poolAccountRepository, poolSampleRepository);
        platform = new PlatformEntity();
        platform.setId(1);
        platform.setPlatformName("自建");
        platform.setStatus(true);
        platform.setPoolMonitoringEnabled(true);
    }

    private PoolAccountEntity account(long externalId) {
        PoolAccountEntity entity = new PoolAccountEntity();
        entity.setPlatformId(1);
        entity.setExternalAccountId(externalId);
        return entity;
    }

    private Sub2ApiUsageLogRow row(long id, long accountId) {
        return new Sub2ApiUsageLogRow(id, accountId, "req-" + id, 1L, "gpt-5",
                OffsetDateTime.now(), 100, 1000, 10L, 5L, 100L, 0L,
                new BigDecimal("0.01"), new BigDecimal("0.001"));
    }

    @Test
    void shouldSkipWhenReaderNotConfigured() {
        // isConfigured() 为 false 时短路，不会再去问 reader
        when(properties.isConfigured()).thenReturn(false);

        service.ingestOnce();

        verifyNoInteractions(cursorRepository, platformRepository, poolAccountRepository, poolSampleRepository);
    }

    @Test
    void shouldInitializeCursorOnFirstRunWithoutBackfillingHistory() {
        when(properties.isConfigured()).thenReturn(true);
        when(reader.isAvailable()).thenReturn(true);
        when(platformRepository.findAll()).thenReturn(List.of(platform));
        when(poolAccountRepository.findByPlatform(1)).thenReturn(List.of(account(52064L)));
        when(cursorRepository.lastUsageLogId()).thenReturn(0L);
        when(reader.currentMaxId()).thenReturn(123456L);

        service.ingestOnce();

        verify(cursorRepository).save(123456L);
        verify(reader, never()).fetchAfter(ArgumentMatchers.anyLong(), ArgumentMatchers.anyList(), ArgumentMatchers.anyInt());
        verify(poolSampleRepository, never()).insertBatch(ArgumentMatchers.anyList());
    }

    @Test
    void shouldAdvanceCursorAndInsertSamples() {
        when(properties.isConfigured()).thenReturn(true);
        when(reader.isAvailable()).thenReturn(true);
        when(properties.getBatchSize()).thenReturn(2000);
        when(properties.getMaxBatchesPerRun()).thenReturn(20);
        when(platformRepository.findAll()).thenReturn(List.of(platform));
        when(poolAccountRepository.findByPlatform(1)).thenReturn(List.of(account(52064L)));
        when(cursorRepository.lastUsageLogId()).thenReturn(100L);
        when(reader.fetchAfter(ArgumentMatchers.eq(100L), ArgumentMatchers.anyList(), ArgumentMatchers.eq(2000)))
                .thenReturn(List.of(row(101L, 52064L), row(102L, 52064L)));
        when(poolSampleRepository.insertBatch(ArgumentMatchers.anyList())).thenReturn(2);

        service.ingestOnce();

        // 每批写入成功后立刻推进游标，保证中断也不会重复拉取
        verify(cursorRepository).save(102L);
        verify(poolSampleRepository).insertBatch(ArgumentMatchers.argThat(list -> list.size() == 2));
    }

    @Test
    void shouldSkipWhenNoPoolMonitoringSource() {
        when(properties.isConfigured()).thenReturn(true);
        when(reader.isAvailable()).thenReturn(true);
        when(platformRepository.findAll()).thenReturn(List.of());

        service.ingestOnce();

        verifyNoInteractions(cursorRepository, poolSampleRepository);
    }

    @Test
    void shouldNotFetchWhenCursorNotInitializedEvenIfAccountsExist() {
        when(properties.isConfigured()).thenReturn(true);
        when(reader.isAvailable()).thenReturn(true);
        when(platformRepository.findAll()).thenReturn(List.of(platform));
        when(poolAccountRepository.findByPlatform(1)).thenReturn(List.of());
        when(cursorRepository.lastUsageLogId()).thenReturn(0L);

        service.ingestOnce();

        // 没有监控账号时直接返回，不初始化游标
        verify(cursorRepository, never()).save(ArgumentMatchers.anyLong());
        assertEquals(0L, cursorRepository.lastUsageLogId());
    }
}