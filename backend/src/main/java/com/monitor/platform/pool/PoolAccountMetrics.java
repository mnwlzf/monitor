package com.monitor.platform.pool;

import java.math.BigDecimal;

/**
 * 号池账号聚合指标（按时间窗聚合）。
 */
public class PoolAccountMetrics {

    private Long externalAccountId;
    private Long requests;
    private Long inputTokens;
    private Long outputTokens;
    private Long cacheReadTokens;
    private Long cacheCreationTokens;
    /** 有效的首 token 样本数（first_token_ms 非空） */
    private Long firstTokenSamples;
    private Double avgFirstTokenMs;
    private Double p95FirstTokenMs;
    private Double avgDurationMs;
    private BigDecimal totalCost;
    private BigDecimal totalActualCost;

    public Long getExternalAccountId() { return externalAccountId; }
    public void setExternalAccountId(Long externalAccountId) { this.externalAccountId = externalAccountId; }
    public Long getRequests() { return requests; }
    public void setRequests(Long requests) { this.requests = requests; }
    public Long getInputTokens() { return inputTokens; }
    public void setInputTokens(Long inputTokens) { this.inputTokens = inputTokens; }
    public Long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Long outputTokens) { this.outputTokens = outputTokens; }
    public Long getCacheReadTokens() { return cacheReadTokens; }
    public void setCacheReadTokens(Long cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; }
    public Long getCacheCreationTokens() { return cacheCreationTokens; }
    public void setCacheCreationTokens(Long cacheCreationTokens) { this.cacheCreationTokens = cacheCreationTokens; }
    public Long getFirstTokenSamples() { return firstTokenSamples; }
    public void setFirstTokenSamples(Long firstTokenSamples) { this.firstTokenSamples = firstTokenSamples; }
    public Double getAvgFirstTokenMs() { return avgFirstTokenMs; }
    public void setAvgFirstTokenMs(Double avgFirstTokenMs) { this.avgFirstTokenMs = avgFirstTokenMs; }
    public Double getP95FirstTokenMs() { return p95FirstTokenMs; }
    public void setP95FirstTokenMs(Double p95FirstTokenMs) { this.p95FirstTokenMs = p95FirstTokenMs; }
    public Double getAvgDurationMs() { return avgDurationMs; }
    public void setAvgDurationMs(Double avgDurationMs) { this.avgDurationMs = avgDurationMs; }
    public BigDecimal getTotalCost() { return totalCost; }
    public void setTotalCost(BigDecimal totalCost) { this.totalCost = totalCost; }
    public BigDecimal getTotalActualCost() { return totalActualCost; }
    public void setTotalActualCost(BigDecimal totalActualCost) { this.totalActualCost = totalActualCost; }
}