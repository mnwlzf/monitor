package com.monitor.platform.pool.repository.mapper;

import com.monitor.platform.pool.PoolAccountMetrics;
import com.monitor.platform.pool.PoolModelMetrics;
import com.monitor.platform.pool.PoolSampleEntity;
import com.monitor.platform.pool.PoolSeriesPoint;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 号池逐请求明细 Mapper。
 */
public interface PoolSampleMapper {

    /** 批量写入请求明细，按 (platform_id, request_id) 幂等去重。 */
    int insertSamples(@Param("samples") List<PoolSampleEntity> samples);

    /** 查询某号已入库明细的最大请求时间，作为增量采集水位。 */
    OffsetDateTime selectMaxCreatedAt(@Param("platformId") Integer platformId,
                                      @Param("externalAccountId") Long externalAccountId);

    /** 平台下已入库明细的最新请求时间（用于展示采集滞后）。 */
    OffsetDateTime selectLatestCreatedAt(@Param("platformId") Integer platformId);

    /** 按时间窗聚合单个号池账号的指标。 */
    PoolAccountMetrics selectAggregate(@Param("platformId") Integer platformId,
                                       @Param("externalAccountId") Long externalAccountId,
                                       @Param("from") OffsetDateTime from,
                                       @Param("to") OffsetDateTime to);

    /** 按时间窗聚合平台下全部号池账号的指标。 */
    List<PoolAccountMetrics> selectAggregatesByPlatform(@Param("platformId") Integer platformId,
                                                        @Param("from") OffsetDateTime from,
                                                        @Param("to") OffsetDateTime to);

    /** 按时间粒度聚合单个号池账号的时序指标。 */
    List<PoolSeriesPoint> selectSeries(@Param("platformId") Integer platformId,
                                       @Param("externalAccountId") Long externalAccountId,
                                       @Param("from") OffsetDateTime from,
                                       @Param("to") OffsetDateTime to,
                                       @Param("granularity") String granularity);

    /** 按外部账号 ID 批量删除逐请求明细（清理已不再监控的账号时同步删除）。 */
    int deleteByExternalIds(@Param("platformId") Integer platformId,
                            @Param("externalAccountIds") Collection<Long> externalAccountIds);

    /** 按模型聚合单个号池账号的指标。 */
    List<PoolModelMetrics> selectByModel(@Param("platformId") Integer platformId,
                                         @Param("externalAccountId") Long externalAccountId,
                                         @Param("from") OffsetDateTime from,
                                         @Param("to") OffsetDateTime to);
}