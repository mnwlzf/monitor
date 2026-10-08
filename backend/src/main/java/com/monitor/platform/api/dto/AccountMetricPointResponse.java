package com.monitor.platform.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 账号指标时序点（余额详情页折线图使用）。
 *
 * @param collectedAt   采集时间
 * @param balance       余额（统一 USD）
 * @param frozenBalance 冻结余额（USD，部分平台提供）
 * @param quota         剩余额度（上游原始单位，部分平台提供）
 * @param usedQuota     累计已用额度（上游原始单位，部分平台提供）
 * @param requestCount  累计请求数（部分平台提供）
 * @param quotaUnit     额度单位
 */
public record AccountMetricPointResponse(
        OffsetDateTime collectedAt,
        BigDecimal balance,
        BigDecimal frozenBalance,
        BigDecimal quota,
        BigDecimal usedQuota,
        Long requestCount,
        String quotaUnit
) {
}