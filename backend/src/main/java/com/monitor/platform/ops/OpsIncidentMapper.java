package com.monitor.platform.ops;

import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/** 事故 Mapper。 */
public interface OpsIncidentMapper {

    OpsIncidentEntity selectById(@Param("id") Long id);

    OpsIncidentEntity selectActiveByFingerprint(@Param("fingerprint") String fingerprint);

    List<OpsIncidentEntity> selectRecent(@Param("status") String status, @Param("limit") int limit);

    int insert(OpsIncidentEntity entity);

    int updateObserved(@Param("id") Long id,
                       @Param("title") String title,
                       @Param("severity") String severity,
                       @Param("impactSummary") String impactSummary,
                       @Param("evidenceJson") String evidenceJson,
                       @Param("lastSeenAt") OffsetDateTime lastSeenAt,
                       @Param("updatedAt") OffsetDateTime updatedAt);

    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("updatedAt") OffsetDateTime updatedAt);

    int resolveStale(@Param("before") OffsetDateTime before,
                     @Param("resolvedAt") OffsetDateTime resolvedAt,
                     @Param("updatedAt") OffsetDateTime updatedAt);
}