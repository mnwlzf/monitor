package com.monitor.platform.pool.repository;

import com.monitor.platform.pool.PoolAccountMetrics;
import com.monitor.platform.pool.PoolModelMetrics;
import com.monitor.platform.pool.PoolSampleEntity;
import com.monitor.platform.pool.PoolSeriesPoint;
import com.monitor.platform.pool.repository.mapper.PoolSampleMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 号池逐请求明细持久化仓储。
 */
@Repository
public class PoolSampleRepository {

    private final PoolSampleMapper poolSampleMapper;

    public PoolSampleRepository(PoolSampleMapper poolSampleMapper) {
        this.poolSampleMapper = poolSampleMapper;
    }

    /** 批量写入请求明细，返回实际新增条数。 */
    public int insertBatch(List<PoolSampleEntity> samples) {
        if (samples == null || samples.isEmpty()) {
            return 0;
        }
        return poolSampleMapper.insertSamples(samples);
    }

    /**
     * 按外部账号 ID 批量删除逐请求明细。
     *
     * @return 实际删除行数
     */
    public int deleteByExternalIds(Integer platformId, Collection<Long> externalAccountIds) {
        if (externalAccountIds == null || externalAccountIds.isEmpty()) {
            return 0;
        }
        return poolSampleMapper.deleteByExternalIds(platformId, externalAccountIds);
    }

    /** 查询某号已入库明细的最大请求时间。 */
    public OffsetDateTime findMaxCreatedAt(Integer platformId, Long externalAccountId) {
        return poolSampleMapper.selectMaxCreatedAt(platformId, externalAccountId);
    }

    /** 按时间窗聚合单个号池账号的指标。 */
    public PoolAccountMetrics aggregate(Integer platformId, Long externalAccountId,
                                        OffsetDateTime from, OffsetDateTime to) {
        return poolSampleMapper.selectAggregate(platformId, externalAccountId, from, to);
    }

    /** 按时间窗聚合平台下全部号池账号的指标。 */
    public List<PoolAccountMetrics> aggregateByPlatform(Integer platformId, OffsetDateTime from, OffsetDateTime to) {
        return poolSampleMapper.selectAggregatesByPlatform(platformId, from, to);
    }

    /** 按时间粒度聚合单个号池账号的时序指标。 */
    public List<PoolSeriesPoint> series(Integer platformId, Long externalAccountId,
                                        OffsetDateTime from, OffsetDateTime to, String granularity) {
        return poolSampleMapper.selectSeries(platformId, externalAccountId, from, to, granularity);
    }

    /** 按模型聚合单个号池账号的指标。 */
    public List<PoolModelMetrics> byModel(Integer platformId, Long externalAccountId,
                                          OffsetDateTime from, OffsetDateTime to) {
        return poolSampleMapper.selectByModel(platformId, externalAccountId, from, to);
    }
}