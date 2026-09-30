package com.monitor.platform.collector.repository.mapper;

import com.monitor.platform.collector.repository.entity.CollectionRunEntity;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 采集批次 Mapper。
 */
public interface CollectionRunMapper  {

    int insertRun(CollectionRunEntity entity);

    int updateRunFinish(@Param("id") Long id,
                        @Param("status") String status,
                        @Param("finishedAt") OffsetDateTime finishedAt,
                        @Param("durationMs") Long durationMs,
                        @Param("errorCode") String errorCode,
                        @Param("errorMessage") String errorMessage);

    CollectionRunEntity selectRunById(@Param("id") Long id);

    List<CollectionRunEntity> selectRecentRuns(@Param("accountId") Integer accountId,
                                               @Param("limit") int limit);
}