package com.monitor.platform.ops;

import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/** 自愈动作计划 Mapper。 */
public interface OpsActionPlanMapper {

    OpsActionPlanEntity selectById(@Param("id") Long id);

    List<OpsActionPlanEntity> selectRecent(@Param("limit") int limit);

    int insert(OpsActionPlanEntity entity);

    int claimForExecution(@Param("id") Long id,
                          @Param("confirmationCode") String confirmationCode,
                          @Param("approvedBy") String approvedBy,
                          @Param("approvedAt") OffsetDateTime approvedAt,
                          @Param("updatedAt") OffsetDateTime updatedAt);

    int markExecuted(@Param("id") Long id,
                     @Param("beforeJson") String beforeJson,
                     @Param("afterJson") String afterJson,
                     @Param("executedAt") OffsetDateTime executedAt,
                     @Param("updatedAt") OffsetDateTime updatedAt);

    int markFailed(@Param("id") Long id,
                   @Param("afterJson") String afterJson,
                   @Param("updatedAt") OffsetDateTime updatedAt);

    int expireStale(@Param("now") OffsetDateTime now,
                    @Param("updatedAt") OffsetDateTime updatedAt);
}