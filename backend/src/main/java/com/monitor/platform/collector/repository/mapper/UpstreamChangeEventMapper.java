package com.monitor.platform.collector.repository.mapper;

import com.monitor.platform.collector.repository.entity.UpstreamChangeEventEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 上游变更事件 Mapper。
 */
public interface UpstreamChangeEventMapper  {

    int insertEvent(UpstreamChangeEventEntity entity);

    List<UpstreamChangeEventEntity> selectRecentEvents(@Param("accountId") Integer accountId,
                                                       @Param("limit") int limit);
}