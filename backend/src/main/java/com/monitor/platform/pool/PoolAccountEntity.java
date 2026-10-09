package com.monitor.platform.pool;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 号池账号实体（对应用户自建 Sub2API 的 accounts）。
 */
public class PoolAccountEntity {

    private Long id;
    private Integer platformId;
    private Long externalAccountId;
    private String name;
    private String platform;
    private String accountType;
    private String status;
    private Boolean schedulable;
    private String errorMessage;
    private OffsetDateTime rateLimitedAt;
    private OffsetDateTime rateLimitResetAt;
    private OffsetDateTime overloadUntil;
    private OffsetDateTime tempUnschedulableUntil;
    private String tempUnschedulableReason;
    private Integer concurrency;
    private Integer priority;
    private BigDecimal rateMultiplier;
    private OffsetDateTime lastUsedAt;
    private Long boundKeyId;
    private OffsetDateTime lastSampleAt;
    /** 往回补齐游标：非空表示还有更早的缺口未取。 */
    private OffsetDateTime backfillUntil;
    private String lastSyncError;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getPlatformId() { return platformId; }
    public void setPlatformId(Integer platformId) { this.platformId = platformId; }
    public Long getExternalAccountId() { return externalAccountId; }
    public void setExternalAccountId(Long externalAccountId) { this.externalAccountId = externalAccountId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getAccountType() { return accountType; }
    public void setAccountType(String accountType) { this.accountType = accountType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Boolean getSchedulable() { return schedulable; }
    public void setSchedulable(Boolean schedulable) { this.schedulable = schedulable; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public OffsetDateTime getRateLimitedAt() { return rateLimitedAt; }
    public void setRateLimitedAt(OffsetDateTime rateLimitedAt) { this.rateLimitedAt = rateLimitedAt; }
    public OffsetDateTime getRateLimitResetAt() { return rateLimitResetAt; }
    public void setRateLimitResetAt(OffsetDateTime rateLimitResetAt) { this.rateLimitResetAt = rateLimitResetAt; }
    public OffsetDateTime getOverloadUntil() { return overloadUntil; }
    public void setOverloadUntil(OffsetDateTime overloadUntil) { this.overloadUntil = overloadUntil; }
    public OffsetDateTime getTempUnschedulableUntil() { return tempUnschedulableUntil; }
    public void setTempUnschedulableUntil(OffsetDateTime tempUnschedulableUntil) { this.tempUnschedulableUntil = tempUnschedulableUntil; }
    public String getTempUnschedulableReason() { return tempUnschedulableReason; }
    public void setTempUnschedulableReason(String tempUnschedulableReason) { this.tempUnschedulableReason = tempUnschedulableReason; }
    public Integer getConcurrency() { return concurrency; }
    public void setConcurrency(Integer concurrency) { this.concurrency = concurrency; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public BigDecimal getRateMultiplier() { return rateMultiplier; }
    public void setRateMultiplier(BigDecimal rateMultiplier) { this.rateMultiplier = rateMultiplier; }
    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(OffsetDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public Long getBoundKeyId() { return boundKeyId; }
    public void setBoundKeyId(Long boundKeyId) { this.boundKeyId = boundKeyId; }
    public OffsetDateTime getLastSampleAt() { return lastSampleAt; }
    public void setLastSampleAt(OffsetDateTime lastSampleAt) { this.lastSampleAt = lastSampleAt; }
    public OffsetDateTime getBackfillUntil() { return backfillUntil; }
    public void setBackfillUntil(OffsetDateTime backfillUntil) { this.backfillUntil = backfillUntil; }
    public String getLastSyncError() { return lastSyncError; }
    public void setLastSyncError(String lastSyncError) { this.lastSyncError = lastSyncError; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}