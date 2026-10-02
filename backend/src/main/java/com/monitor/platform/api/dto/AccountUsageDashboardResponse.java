package com.monitor.platform.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 账号用量看板响应。
 *
 * <p>跨平台统一结构：公共指标为强类型字段，平台特有明细以 JSON 对象放在
 * {@code metrics} 中；平台未提供的指标为 {@code null}，由前端降级展示。</p>
 */
public record AccountUsageDashboardResponse(
        Long id,
        Integer accountId,
        Integer platformId,
        String displayName,
        String platformType,
        BigDecimal balance,
        BigDecimal frozenBalance,
        Long totalRequests,
        Long totalTokens,
        BigDecimal totalCost,
        BigDecimal totalActualCost,
        JsonNode metrics,
        JsonNode platformStats,
        OffsetDateTime collectedAt
) {
}