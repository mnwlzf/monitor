package com.monitor.platform.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 采集账号响应。
 */
public record AccountResponse(
        Integer id,
        Integer platformId,
        String displayName,
        String loginName,
        String platformType,
        String authStatus,
        Boolean status,
        BigDecimal balance,
        BigDecimal frozenBalance,
        BigDecimal quota,
        BigDecimal usedQuota,
        String quotaUnit,
        Long requestCount,
        String lastCollectStatus,
        OffsetDateTime lastCollectedAt,
        OffsetDateTime nextCollectAt
) {
}