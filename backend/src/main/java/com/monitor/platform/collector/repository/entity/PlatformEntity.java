package com.monitor.platform.collector.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.platform.infrastructure.mybatis.handler.JsonbStringTypeHandler;

import java.time.OffsetDateTime;

/**
 * 平台实例实体。
 */
@TableName(value = "platforms", autoResultMap = true)
public class PlatformEntity {

    @TableId(type = IdType.AUTO)
    /** 平台主键。 */
    private Integer id;
    /** 平台名称。 */
    private String platformName;
    /** 平台基础地址。 */
    private String url;
    /** 平台类型，newapi 或 sub2api。 */
    private String platformType;
    /** 平台是否启用。 */
    private Boolean status;
    /** 平台描述。 */
    private String description;
    /** 平台扩展配置，JSON 文本。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String settings;
    /** 平台最近一次采集时间。 */
    private OffsetDateTime lastCollectedAt;
    /** 软删除时间。 */
    private OffsetDateTime deletedAt;
    /** 创建时间。 */
    private OffsetDateTime createdAt;
    /** 更新时间。 */
    private OffsetDateTime updatedAt;

    // ----- Sub2API 管理员密钥（AES-GCM 加密，仅用于只读调用管理员接口） -----
    /** 管理员密钥加密算法。 */
    private String adminKeyEncryptionAlgorithm;
    /** 管理员密钥密文。 */
    private String adminKeyEncryptedPayload;
    /** 管理员密钥加密初始化向量。 */
    private String adminKeyInitializationVector;
    /** 管理员密钥加密密钥版本。 */
    private Integer adminKeyKeyVersion;
    /**
     * 是否作为号池监控源。
     *
     * <p>只有用户自己搭建的 Sub2API 才提供管理员只读接口，因此需要显式标记，
     * 而不是按平台类型推断；其他 Sub2API / New API 平台只是它的上游。</p>
     */
    private Boolean poolMonitoringEnabled;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getPlatformName() { return platformName; }
    public void setPlatformName(String platformName) { this.platformName = platformName; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getPlatformType() { return platformType; }
    public void setPlatformType(String platformType) { this.platformType = platformType; }
    public Boolean getStatus() { return status; }
    public void setStatus(Boolean status) { this.status = status; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSettings() { return settings; }
    public void setSettings(String settings) { this.settings = settings; }
    public OffsetDateTime getLastCollectedAt() { return lastCollectedAt; }
    public void setLastCollectedAt(OffsetDateTime lastCollectedAt) { this.lastCollectedAt = lastCollectedAt; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getAdminKeyEncryptionAlgorithm() { return adminKeyEncryptionAlgorithm; }
    public void setAdminKeyEncryptionAlgorithm(String adminKeyEncryptionAlgorithm) { this.adminKeyEncryptionAlgorithm = adminKeyEncryptionAlgorithm; }
    public String getAdminKeyEncryptedPayload() { return adminKeyEncryptedPayload; }
    public void setAdminKeyEncryptedPayload(String adminKeyEncryptedPayload) { this.adminKeyEncryptedPayload = adminKeyEncryptedPayload; }
    public String getAdminKeyInitializationVector() { return adminKeyInitializationVector; }
    public void setAdminKeyInitializationVector(String adminKeyInitializationVector) { this.adminKeyInitializationVector = adminKeyInitializationVector; }
    public Integer getAdminKeyKeyVersion() { return adminKeyKeyVersion; }
    public void setAdminKeyKeyVersion(Integer adminKeyKeyVersion) { this.adminKeyKeyVersion = adminKeyKeyVersion; }
    public Boolean getPoolMonitoringEnabled() { return poolMonitoringEnabled; }
    public void setPoolMonitoringEnabled(Boolean poolMonitoringEnabled) { this.poolMonitoringEnabled = poolMonitoringEnabled; }

    /** 是否已配置 Sub2API 管理员密钥。 */
    public boolean hasAdminKey() {
        return adminKeyEncryptedPayload != null && !adminKeyEncryptedPayload.isBlank();
    }
}