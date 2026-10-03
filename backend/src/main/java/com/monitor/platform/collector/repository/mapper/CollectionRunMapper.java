package com.monitor.platform.collector.repository.mapper;

import com.monitor.platform.collector.repository.entity.CollectionRunEntity;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 采集批次 Mapper。
 */
public interface CollectionRunMapper  {

    /** 新增采集批次。 */
    int insertRun(CollectionRunEntity entity);

    /** 写入采集批次的完成状态。 */
    int updateRunFinish(@Param("id") Long id,
                        @Param("status") String status,
                        @Param("finishedAt") OffsetDateTime finishedAt,
                        @Param("durationMs") Long durationMs,
                        @Param("errorCode") String errorCode,
                        @Param("errorMessage") String errorMessage);

    /** 按主键查询采集批次。 */
    CollectionRunEntity selectRunById(@Param("id") Long id);

    /** 查询账号最近若干次采集批次。 */
    List<CollectionRunEntity> selectRecentRuns(@Param("accountId") Integer accountId,
                                               @Param("limit") int limit);
}