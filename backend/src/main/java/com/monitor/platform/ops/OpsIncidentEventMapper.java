package com.monitor.platform.ops;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 事故时间线 Mapper。 */
public interface OpsIncidentEventMapper {

    int insert(OpsIncidentEventEntity entity);

    List<OpsIncidentEventEntity> selectByIncidentId(@Param("incidentId") Long incidentId,
                                                    @Param("limit") int limit);
}