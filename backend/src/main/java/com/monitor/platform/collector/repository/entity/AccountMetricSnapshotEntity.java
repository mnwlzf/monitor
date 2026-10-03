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
    /** 快照主键。 */
    private Long id;
    /** 账号主键。 */
    private Integer accountId;
    /** 所属采集批次主键。 */
    private Long collectionRunId;
    /** 平台类型。 */
    private String platformType;
    /** 账户余额。 */
    private BigDecimal balance;
    /** 冻结余额。 */
    private BigDecimal frozenBalance;
    /** 剩余额度。 */
    private BigDecimal quota;
    /** 累计已用额度。 */
    private BigDecimal usedQuota;
    /** 可提现或可用推广额度。 */
    private BigDecimal affQuota;
    /** 历史推广额度。 */
    private BigDecimal affHistoryQuota;
    /** 累计请求数。 */
    private Long requestCount;
    /** 额度单位，例如 USD。 */
    private String quotaUnit;
    /** 上游原始响应，JSON 文本。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String rawData;
    /** 原始响应内容哈希。 */
    private String contentHash;
    /** 采集时间。 */
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