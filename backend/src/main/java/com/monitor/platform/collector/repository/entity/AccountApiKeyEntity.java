package com.monitor.platform.collector.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.platform.infrastructure.mybatis.handler.JsonbStringTypeHandler;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 账号 API Key 当前状态实体。
 *
 * <p>完整明文密钥使用 AES-GCM 加密后存放在 {@code keyEncryptedPayload} 中，
 * {@code keyHash} 保存 SHA-256 用于判断密钥是否轮换；额度统一换算为 USD 存储。</p>
 */
@TableName(value = "account_api_keys", autoResultMap = true)
public class AccountApiKeyEntity {

    @TableId(type = IdType.AUTO)
    /** 主键。 */
    private Long id;
    /** 所属账号主键。 */
    private Integer accountId;
    /** 平台类型。 */
    private String platformType;
    /** 上游密钥 ID。 */
    private String externalKeyId;
    /** 密钥名称。 */
    private String keyName;
    /** 脱敏后的密钥。 */
    private String keyMasked;
    /** 完整密钥的 SHA-256。 */
    private String keyHash;
    /** 完整密钥的加密算法。 */
    private String keyEncryptionAlgorithm;
    /** 完整密钥密文。 */
    private String keyEncryptedPayload;
    /** 加密初始化向量。 */
    private String keyInitializationVector;
    /** 加密密钥版本。 */
    private Integer keyKeyVersion;
    /** 归一化状态。 */
    private String status;
    /** 上游原始状态。 */
    private String upstreamStatus;
    /** 所属分组名称。 */
    private String groupName;
    /** 所属分组的平台标识。 */
    private String groupPlatform;
    /** 是否不限额度。 */
    private Boolean unlimitedQuota;
    /** 剩余额度，USD。 */
    private BigDecimal remainQuota;
    /** 累计已用额度，USD。 */
    private BigDecimal usedQuota;
    /** 额度单位。 */
    private String quotaUnit;
    /** 是否启用模型限制。 */
    private Boolean modelLimitsEnabled;
    /** 模型限制内容。 */
    private String modelLimits;
    /** IP 白名单。 */
    private String allowIps;
    /** 过期时间。 */
    private OffsetDateTime expiresAt;
    /** 上游创建时间。 */
    private OffsetDateTime upstreamCreatedAt;
    /** 最近使用时间。 */
    private OffsetDateTime lastUsedAt;
    /** 本地是否仍有效。 */
    private Boolean isActive;
    /** 首次发现时间。 */
    private OffsetDateTime firstSeenAt;
    /** 最近可见时间。 */
    private OffsetDateTime lastSeenAt;
    /** 最近变更时间。 */
    private OffsetDateTime lastChangedAt;
    /** 平台特有明细，JSON 文本。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String metrics;
    /** 上游原始响应，JSON 文本。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String rawData;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getAccountId() { return accountId; }
    public void setAccountId(Integer accountId) { this.accountId = accountId; }
    public String getPlatformType() { return platformType; }
    public void setPlatformType(String platformType) { this.platformType = platformType; }
    public String getExternalKeyId() { return externalKeyId; }
    public void setExternalKeyId(String externalKeyId) { this.externalKeyId = externalKeyId; }
    public String getKeyName() { return keyName; }
    public void setKeyName(String keyName) { this.keyName = keyName; }
    public String getKeyMasked() { return keyMasked; }
    public void setKeyMasked(String keyMasked) { this.keyMasked = keyMasked; }
    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }
    public String getKeyEncryptionAlgorithm() { return keyEncryptionAlgorithm; }
    public void setKeyEncryptionAlgorithm(String keyEncryptionAlgorithm) { this.keyEncryptionAlgorithm = keyEncryptionAlgorithm; }
    public String getKeyEncryptedPayload() { return keyEncryptedPayload; }
    public void setKeyEncryptedPayload(String keyEncryptedPayload) { this.keyEncryptedPayload = keyEncryptedPayload; }
    public String getKeyInitializationVector() { return keyInitializationVector; }
    public void setKeyInitializationVector(String keyInitializationVector) { this.keyInitializationVector = keyInitializationVector; }
    public Integer getKeyKeyVersion() { return keyKeyVersion; }
    public void setKeyKeyVersion(Integer keyKeyVersion) { this.keyKeyVersion = keyKeyVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getUpstreamStatus() { return upstreamStatus; }
    public void setUpstreamStatus(String upstreamStatus) { this.upstreamStatus = upstreamStatus; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getGroupPlatform() { return groupPlatform; }
    public void setGroupPlatform(String groupPlatform) { this.groupPlatform = groupPlatform; }
    public Boolean getUnlimitedQuota() { return unlimitedQuota; }
    public void setUnlimitedQuota(Boolean unlimitedQuota) { this.unlimitedQuota = unlimitedQuota; }
    public BigDecimal getRemainQuota() { return remainQuota; }
    public void setRemainQuota(BigDecimal remainQuota) { this.remainQuota = remainQuota; }
    public BigDecimal getUsedQuota() { return usedQuota; }
    public void setUsedQuota(BigDecimal usedQuota) { this.usedQuota = usedQuota; }
    public String getQuotaUnit() { return quotaUnit; }
    public void setQuotaUnit(String quotaUnit) { this.quotaUnit = quotaUnit; }
    public Boolean getModelLimitsEnabled() { return modelLimitsEnabled; }
    public void setModelLimitsEnabled(Boolean modelLimitsEnabled) { this.modelLimitsEnabled = modelLimitsEnabled; }
    public String getModelLimits() { return modelLimits; }
    public void setModelLimits(String modelLimits) { this.modelLimits = modelLimits; }
    public String getAllowIps() { return allowIps; }
    public void setAllowIps(String allowIps) { this.allowIps = allowIps; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getUpstreamCreatedAt() { return upstreamCreatedAt; }
    public void setUpstreamCreatedAt(OffsetDateTime upstreamCreatedAt) { this.upstreamCreatedAt = upstreamCreatedAt; }
    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(OffsetDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public OffsetDateTime getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(OffsetDateTime firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(OffsetDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public OffsetDateTime getLastChangedAt() { return lastChangedAt; }
    public void setLastChangedAt(OffsetDateTime lastChangedAt) { this.lastChangedAt = lastChangedAt; }
    public String getMetrics() { return metrics; }
    public void setMetrics(String metrics) { this.metrics = metrics; }
    public String getRawData() { return rawData; }
    public void setRawData(String rawData) { this.rawData = rawData; }
}
