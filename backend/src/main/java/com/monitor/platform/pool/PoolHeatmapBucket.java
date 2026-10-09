package com.monitor.platform.pool;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 号池热力图的一个时间桶聚合（按「账号 × 时间桶」分组）。
 *
 * <p>保留原始求和量而不是直接算好比率：命中率、每秒 token 等都需要先在更大的窗口上
 * 重新加权（例如整行汇总不能对每格的比率取平均），所以这里只提供分子分母。</p>
 */
public class PoolHeatmapBucket {

    private Long externalAccountId;
    private OffsetDateTime bucket;
    private Long requests;
    private Long inputTokens;
    private Long outputTokens;
    private Long cacheReadTokens;
    private Long cacheCreationTokens;
    private Long firstTokenSamples;
    private Double avgFirstTokenMs;
    private Double avgDurationMs;
    private BigDecimal totalActualCost;

    public Long getExternalAccountId() { return externalAccountId; }
    public void setExternalAccountId(Long externalAccountId) { this.externalAccountId = externalAccountId; }
    public OffsetDateTime getBucket() { return bucket; }
    public void setBucket(OffsetDateTime bucket) { this.bucket = bucket; }
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
    public Double getAvgDurationMs() { return avgDurationMs; }
    public void setAvgDurationMs(Double avgDurationMs) { this.avgDurationMs = avgDurationMs; }
    public BigDecimal getTotalActualCost() { return totalActualCost; }
    public void setTotalActualCost(BigDecimal totalActualCost) { this.totalActualCost = totalActualCost; }
}