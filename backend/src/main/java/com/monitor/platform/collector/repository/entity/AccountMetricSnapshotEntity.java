package com.monitor.platform.collector.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.platform.infrastructure.mybatis.handler.JsonbStringTypeHandler;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 账号指标快照实体。
 */
@TableName(value = "account_metric_snapshots", autoResultMap = true)
public class AccountMetricSnapshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Integer accountId;
    private Long collectionRunId;
    private String platformType;
    private BigDecimal balance;
    private BigDecimal frozenBalance;
    private BigDecimal quota;
    private BigDecimal usedQuota;
    private BigDecimal affQuota;
    private BigDecimal affHistoryQuota;
    private Long requestCount;
    private String quotaUnit;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String rawData;
    private String contentHash;
    private OffsetDateTime collectedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getAccountId() { return accountId; }
    public void setAccountId(Integer accountId) { this.accountId = accountId; }
    public Long getCollectionRunId() { return collectionRunId; }
    public void setCollectionRunId(Long collectionRunId) { this.collectionRunId = collectionRunId; }
    public String getPlatformType() { return platformType; }
    public void setPlatformType(String platformType) { this.platformType = platformType; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public BigDecimal getFrozenBalance() { return frozenBalance; }
    public void setFrozenBalance(BigDecimal frozenBalance) { this.frozenBalance = frozenBalance; }
    public BigDecimal getQuota() { return quota; }
    public void setQuota(BigDecimal quota) { this.quota = quota; }
    public BigDecimal getUsedQuota() { return usedQuota; }
    public void setUsedQuota(BigDecimal usedQuota) { this.usedQuota = usedQuota; }
    public BigDecimal getAffQuota() { return affQuota; }
    public void setAffQuota(BigDecimal affQuota) { this.affQuota = affQuota; }
    public BigDecimal getAffHistoryQuota() { return affHistoryQuota; }
    public void setAffHistoryQuota(BigDecimal affHistoryQuota) { this.affHistoryQuota = affHistoryQuota; }
    public Long getRequestCount() { return requestCount; }
    public void setRequestCount(Long requestCount) { this.requestCount = requestCount; }
    public String getQuotaUnit() { return quotaUnit; }
    public void setQuotaUnit(String quotaUnit) { this.quotaUnit = quotaUnit; }
    public String getRawData() { return rawData; }
    public void setRawData(String rawData) { this.rawData = rawData; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public OffsetDateTime getCollectedAt() { return collectedAt; }
    public void setCollectedAt(OffsetDateTime collectedAt) { this.collectedAt = collectedAt; }
}