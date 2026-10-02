package com.monitor.platform.api.dto;

import java.time.OffsetDateTime;

/**
 * 上游变更事件响应。
 */
public record UpstreamChangeEventResponse(
        Long id,
        Integer accountId,
        String platformType,
        String entityType,
        String entityKey,
        String changeType,
        String fieldName,
        String oldValue,
        String newValue,
        String severity,
        String message,
        OffsetDateTime detectedAt
) {
}