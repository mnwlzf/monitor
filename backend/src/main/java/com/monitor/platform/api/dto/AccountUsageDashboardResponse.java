package com.monitor.platform.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 账号用量看板响应。
 *
 * <p>跨平台统一结构：公共指标为强类型字段，平台特有明细以 JSON 对象放在
 * {@code metrics} 中；平台未提供的指标为 {@code null}，由前端降级展示。</p>
 *
 * @param id               快照主键
 * @param accountId        账号主键
 * @param platformId       平台主键
 * @param displayName      账号展示名称
 * @param platformType     平台类型
 * @param balance          余额
 * @param frozenBalance    冻结余额
 * @param totalRequests    累计请求数
 * @param totalTokens      累计 Token 数
 * @param totalCost        累计标准消耗
 * @param totalActualCost  累计实际消耗
 * @param metrics          跨平台公共指标 JSON
 * @param platformStats    平台特有统计 JSON
 * @param collectedAt      快照采集时间
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