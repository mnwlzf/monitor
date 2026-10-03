package com.monitor.platform.collector.repository.mapper;

import com.monitor.platform.collector.repository.entity.UpstreamGroupSnapshotEntity;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 上游渠道/分组快照 Mapper。
 */
public interface UpstreamGroupSnapshotMapper  {

    /** 新增渠道快照。 */
    int insertSnapshot(UpstreamGroupSnapshotEntity entity);

    /** 查询渠道在时间区间内的历史快照。 */
    List<UpstreamGroupSnapshotEntity> selectSnapshotsByGroupRange(
            @Param("groupId") Long groupId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            @Param("limit") int limit);
}