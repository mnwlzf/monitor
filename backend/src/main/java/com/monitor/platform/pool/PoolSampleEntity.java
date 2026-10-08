package com.monitor.platform.pool;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 号池逐请求明细（精简字段，不含原始响应）。
 */
public class PoolSampleEntity {

    private Long id;
    private Integer platformId;
    private Long externalAccountId;
    private String requestId;
    private Long apiKeyId;
    private String model;
    private Long channelId;
    private String endpoint;
    private Boolean stream;
    private OffsetDateTime createdAt;
    private Integer firstTokenMs;
    private Integer durationMs;
    private Long inputTokens;
    private Long outputTokens;
    private Long cacheReadTokens;
    private Long cacheCreationTokens;
    private BigDecimal totalCost;
    private BigDecimal actualCost;
    private OffsetDateTime ingestedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getPlatformId() { return platformId; }
    public void setPlatformId(Integer platformId) { this.platformId = platformId; }
    public Long getExternalAccountId() { return externalAccountId; }
    public void setExternalAccountId(Long externalAccountId) { this.externalAccountId = externalAccountId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Long getApiKeyId() { return apiKeyId; }
    public void setApiKeyId(Long apiKeyId) { this.apiKeyId = apiKeyId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public Boolean getStream() { return stream; }
    public void setStream(Boolean stream) { this.stream = stream; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public Integer getFirstTokenMs() { return firstTokenMs; }
    public void setFirstTokenMs(Integer firstTokenMs) { this.firstTokenMs = firstTokenMs; }
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
    public Long getInputTokens() { return inputTokens; }
    public void setInputTokens(Long inputTokens) { this.inputTokens = inputTokens; }
    public Long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Long outputTokens) { this.outputTokens = outputTokens; }
    public Long getCacheReadTokens() { return cacheReadTokens; }
    public void setCacheReadTokens(Long cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; }
    public Long getCacheCreationTokens() { return cacheCreationTokens; }
    public void setCacheCreationTokens(Long cacheCreationTokens) { this.cacheCreationTokens = cacheCreationTokens; }
    public BigDecimal getTotalCost() { return totalCost; }
    public void setTotalCost(BigDecimal totalCost) { this.totalCost = totalCost; }
    public BigDecimal getActualCost() { return actualCost; }
    public void setActualCost(BigDecimal actualCost) { this.actualCost = actualCost; }
    public OffsetDateTime getIngestedAt() { return ingestedAt; }
    public void setIngestedAt(OffsetDateTime ingestedAt) { this.ingestedAt = ingestedAt; }
}