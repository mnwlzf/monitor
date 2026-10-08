package com.monitor.platform.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 号池指标时序点响应。
 *
 * @param bucket            聚合时间桶
 * @param requests          请求数
 * @param inputTokens       输入 token
 * @param outputTokens      输出 token
 * @param cacheReadTokens   缓存读取 token
 * @param cacheCreationTokens 缓存写入 token
 * @param firstTokenSamples 首 token 有效样本数
 * @param avgFirstTokenMs   平均首 token 耗时（毫秒）
 * @param p95FirstTokenMs   首 token P95 耗时（毫秒）
 * @param avgDurationMs     平均总耗时（毫秒）
 * @param totalActualCost   实际成本
 * @param cacheHitRate      缓存命中率（0~1）
 */
public record PoolSeriesPointResponse(
        OffsetDateTime bucket,
        Long requests,
        Long inputTokens,
        Long outputTokens,
        Long cacheReadTokens,
        Long cacheCreationTokens,
        Long firstTokenSamples,
        Double avgFirstTokenMs,
        Double p95FirstTokenMs,
        Double avgDurationMs,
        BigDecimal totalActualCost,
        Double cacheHitRate
) {
}