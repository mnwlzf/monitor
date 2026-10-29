package com.monitor.platform.api.dto;

import com.monitor.platform.ops.OpsIncidentEventEntity;

import java.time.OffsetDateTime;

/** 事故时间线响应。 */
public record OpsIncidentEventResponse(
        Long id,
        String eventType,
        String actor,
        String content,
        OffsetDateTime createdAt
) {
    public static OpsIncidentEventResponse from(OpsIncidentEventEntity entity) {
        return new OpsIncidentEventResponse(entity.getId(), entity.getEventType(),
                entity.getActor(), entity.getContent(), entity.getCreatedAt());
    }
}