package com.monitor.platform.api.dto;

import com.monitor.platform.ops.OpsIncidentEntity;

import java.time.OffsetDateTime;

/** 智能运维事故响应。 */
public record OpsIncidentResponse(
        Long id,
        String title,
        String severity,
        String status,
        String scopeType,
        String scopeId,
        String rootCauseCode,
        String impactSummary,
        String evidenceJson,
        OffsetDateTime firstSeenAt,
        OffsetDateTime lastSeenAt,
        OffsetDateTime resolvedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static OpsIncidentResponse from(OpsIncidentEntity entity) {
        return new OpsIncidentResponse(
                entity.getId(), entity.getTitle(), entity.getSeverity(), entity.getStatus(),
                entity.getScopeType(), entity.getScopeId(), entity.getRootCauseCode(),
                entity.getImpactSummary(), entity.getEvidenceJson(), entity.getFirstSeenAt(),
                entity.getLastSeenAt(), entity.getResolvedAt(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}