package com.monitor.platform.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 上游渠道/分组响应。
 */
public record UpstreamGroupResponse(
        Long id,
        Integer accountId,
        String externalGroupId,
        String groupName,
        String description,
        String platform,
        BigDecimal currentRatio,
        BigDecimal currentBaseRatio,
        String status,
        Boolean active,
        OffsetDateTime firstSeenAt,
        OffsetDateTime lastSeenAt,
        OffsetDateTime lastChangedAt
) {
}