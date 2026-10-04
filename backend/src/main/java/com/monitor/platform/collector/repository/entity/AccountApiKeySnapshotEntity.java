package com.monitor.platform.collector.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.platform.infrastructure.mybatis.handler.JsonbStringTypeHandler;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * API Key 用量时序快照实体。
 *
 * <p>公共指标使用强类型列，平台特有明细统一存放在 {@code metrics} JSONB 中。</p>
 */
@TableName(value = "account_api_key_snapshots", autoResultMap = true)
public class AccountApiKeySnapshotEntity {

    @TableId(type = IdType.AUTO)
    /** 快照主键。 */
    private Long id;
    /** 所属密钥主键。 */
    private Long apiKeyId;
    /** 所属账号主键。 */
    private Integer accountId;
    /** 所属采集批次主键。 */
    private Long collectionRunId;
    /** 平台类型。 */
    private String platformType;
    /** 上游密钥 ID。 */
    private String externalKeyId;
    /** 密钥名称。 */
    private String keyName;
    /** 归一化状态。 */
    private String status;
    /** 所属分组名称。 */
    private String groupName;
    /** 是否不限额度。 */
    private Boolean unlimitedQuota;
    /** 剩余额度，USD。 */
    private BigDecimal remainQuota;
    /** 累计已用额度，USD。 */
    private BigDecimal usedQuota;
    /** 额度单位。 */
    private String quotaUnit;
    /** 平台特有明细，JSON 文本。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String metrics;
    /** 上游原始响应，JSON 文本。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String rawData;
    /** 采集时间。 */
    private OffsetDateTime collectedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getApiKeyId() { return apiKeyId; }
    public void setApiKeyId(Long apiKeyId) { this.apiKeyId = apiKeyId; }
    public Integer getAccountId() { return accountId; }
    public void setAccountId(Integer accountId) { this.accountId = accountId; }
    public Long getCollectionRunId() { return collectionRunId; }
    public void setCollectionRunId(Long collectionRunId) { this.collectionRunId = collectionRunId; }
    public String getPlatformType() { return platformType; }
    public void setPlatformType(String platformType) { this.platformType = platformType; }
    public String getExternalKeyId() { return externalKeyId; }
    public void setExternalKeyId(String externalKeyId) { this.externalKeyId = externalKeyId; }
    public String getKeyName() { return keyName; }
    public void setKeyName(String keyName) { this.keyName = keyName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public Boolean getUnlimitedQuota() { return unlimitedQuota; }
    public void setUnlimitedQuota(Boolean unlimitedQuota) { this.unlimitedQuota = unlimitedQuota; }
    public BigDecimal getRemainQuota() { return remainQuota; }
    public void setRemainQuota(BigDecimal remainQuota) { this.remainQuota = remainQuota; }
    public BigDecimal getUsedQuota() { return usedQuota; }
    public void setUsedQuota(BigDecimal usedQuota) { this.usedQuota = usedQuota; }
    public String getQuotaUnit() { return quotaUnit; }
    public void setQuotaUnit(String quotaUnit) { this.quotaUnit = quotaUnit; }
    public String getMetrics() { return metrics; }
    public void setMetrics(String metrics) { this.metrics = metrics; }
    public String getRawData() { return rawData; }
    public void setRawData(String rawData) { this.rawData = rawData; }
    public OffsetDateTime getCollectedAt() { return collectedAt; }
    public void setCollectedAt(OffsetDateTime collectedAt) { this.collectedAt = collectedAt; }
}
