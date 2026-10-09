package com.monitor.platform.pool.repository;

import com.monitor.platform.pool.PoolAccountMetrics;
import com.monitor.platform.pool.PoolHeatmapBucket;
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

    /**
     * 单条 INSERT 语句最多写入的行数。
     *
     * <p>一条明细有 18 个绑定参数，PostgreSQL 的 JDBC 驱动硬上限是 65535 个参数，
     * 超过会直接抛 {@code PreparedStatement can have at most 65,535 parameters}。
     * 500 行约 9000 个参数，留足余量；单次采集可能产生上万条明细，必须分片写入。</p>
     */
    static final int MAX_BATCH_ROWS = 500;

    private final PoolSampleMapper poolSampleMapper;

    public PoolSampleRepository(PoolSampleMapper poolSampleMapper) {
        this.poolSampleMapper = poolSampleMapper;
    }

    /**
     * 分批写入请求明细，返回实际新增条数。
     *
     * <p>按 {@link #MAX_BATCH_ROWS} 切片，避免单条语句参数个数超过数据库上限。</p>
     */
    public int insertBatch(List<PoolSampleEntity> samples) {
        if (samples == null || samples.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (int start = 0; start < samples.size(); start += MAX_BATCH_ROWS) {
            int end = Math.min(start + MAX_BATCH_ROWS, samples.size());
            inserted += poolSampleMapper.insertSamples(samples.subList(start, end));
        }
        return inserted;
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

    /** 平台下已入库明细的最新请求时间（用于展示采集滞后）。 */
    public OffsetDateTime findLatestCreatedAt(Integer platformId) {
        return poolSampleMapper.selectLatestCreatedAt(platformId);
    }

    /** 按「账号 × 时间桶」聚合热力图数据，可按模型 / 账号过滤。 */
    public List<PoolHeatmapBucket> heatmapBuckets(Integer platformId, OffsetDateTime from, OffsetDateTime to,
                                                  String granularity, Collection<String> models,
                                                  Collection<Long> externalAccountIds) {
        return poolSampleMapper.selectHeatmapBuckets(platformId, from, to, granularity, models, externalAccountIds);
    }

    /** 平台下出现过的模型名（用于筛选下拉）。 */
    public List<String> modelOptions(Integer platformId, OffsetDateTime from, OffsetDateTime to) {
        return poolSampleMapper.selectModelOptions(platformId, from, to);
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