package com.monitor.platform.ops;

import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;

/** 自愈动作执行 Mapper。 */
public interface OpsActionExecutionMapper {

    int insert(OpsActionExecutionEntity entity);

    int markFinished(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("responseJson") String responseJson,
                     @Param("errorMessage") String errorMessage,
                     @Param("finishedAt") OffsetDateTime finishedAt);
}