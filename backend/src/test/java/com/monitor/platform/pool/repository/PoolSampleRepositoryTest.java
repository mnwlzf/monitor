package com.monitor.platform.pool.repository;

import com.monitor.platform.pool.PoolSampleEntity;
import com.monitor.platform.pool.repository.mapper.PoolSampleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 号池明细批量写入的分片测试。
 *
 * <p>PostgreSQL 单条语句最多 65535 个绑定参数，明细写入必须分片，
 * 否则大批量采集会直接失败。</p>
 */
@ExtendWith(MockitoExtension.class)
class PoolSampleRepositoryTest {

    /** 单条明细 INSERT 的绑定参数个数，与 PoolSampleMapper.xml 保持一致。 */
    private static final int PARAMS_PER_ROW = 18;

    @Mock private PoolSampleMapper poolSampleMapper;

    private PoolSampleRepository repository;

    @BeforeEach
    void setUp() {
        repository = new PoolSampleRepository(poolSampleMapper);
    }

    private List<PoolSampleEntity> samples(int count) {
        List<PoolSampleEntity> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            PoolSampleEntity entity = new PoolSampleEntity();
            entity.setRequestId("req-" + i);
            list.add(entity);
        }
        return list;
    }

    @Test
    void shouldSplitLargeBatchIntoChunks() {
        when(poolSampleMapper.insertSamples(anyList())).thenReturn(500, 500, 200);

        int inserted = repository.insertBatch(samples(1200));

        assertEquals(1200, inserted);
        ArgumentCaptor<List<PoolSampleEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(poolSampleMapper, org.mockito.Mockito.times(3)).insertSamples(captor.capture());
        List<List<PoolSampleEntity>> calls = captor.getAllValues();
        assertEquals(500, calls.get(0).size());
        assertEquals(500, calls.get(1).size());
        assertEquals(200, calls.get(2).size());
    }

    @Test
    void shouldNotCallMapperForEmptyBatch() {
        assertEquals(0, repository.insertBatch(List.of()));
        assertEquals(0, repository.insertBatch(null));
        verify(poolSampleMapper, never()).insertSamples(anyList());
    }

    @Test
    void shouldKeepChunkBelowPostgresParameterLimit() {
        assertTrue(PoolSampleRepository.MAX_BATCH_ROWS * PARAMS_PER_ROW < 65535,
                "分片大小必须保证单条语句参数个数低于 PostgreSQL 的 65535 上限");
    }
}