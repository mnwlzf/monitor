package com.monitor.platform.collector.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.platform.infrastructure.mybatis.handler.JsonbStringTypeHandler;

import java.time.OffsetDateTime;

/**
 * 账号登录凭证实体。
 */
@TableName(value = "account_credentials", autoResultMap = true)
public class AccountCredentialEntity {

    @TableId(type = IdType.AUTO)
    /** 凭证主键。 */
    private Long id;
    /** 所属账号主键。 */
    private Integer accountId;
    /** 凭证类型，例如 PASSWORD。 */
    private String credentialType;
    /** 加密算法标识。 */
    private String encryptionAlgorithm;
    /** Base64 编码后的密文。 */
    private String encryptedPayload;
    /** Base64 编码后的 AES-GCM 初始化向量。 */
    private String initializationVector;
    /** 加密密钥版本。 */
    private Integer keyVersion;
    /** 凭证过期时间。 */
    private OffsetDateTime expiresAt;
    /** 最近一次验证成功时间。 */
    private OffsetDateTime lastVerifiedAt;
    /** 最近一次验证失败信息。 */
    private String verificationError;
    /** 是否为当前有效凭证。 */
    private Boolean isActive;
    /** 扩展元数据，JSON 文本。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String metadata;
    /** 创建时间。 */
    private OffsetDateTime createdAt;
    /** 更新时间。 */
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getAccountId() { return accountId; }
    public void setAccountId(Integer accountId) { this.accountId = accountId; }
    public String getCredentialType() { return credentialType; }
    public void setCredentialType(String credentialType) { this.credentialType = credentialType; }
    public String getEncryptionAlgorithm() { return encryptionAlgorithm; }
    public void setEncryptionAlgorithm(String encryptionAlgorithm) { this.encryptionAlgorithm = encryptionAlgorithm; }
    public String getEncryptedPayload() { return encryptedPayload; }
    public void setEncryptedPayload(String encryptedPayload) { this.encryptedPayload = encryptedPayload; }
    public String getInitializationVector() { return initializationVector; }
    public void setInitializationVector(String initializationVector) { this.initializationVector = initializationVector; }
    public Integer getKeyVersion() { return keyVersion; }
    public void setKeyVersion(Integer keyVersion) { this.keyVersion = keyVersion; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getLastVerifiedAt() { return lastVerifiedAt; }
    public void setLastVerifiedAt(OffsetDateTime lastVerifiedAt) { this.lastVerifiedAt = lastVerifiedAt; }
    public String getVerificationError() { return verificationError; }
    public void setVerificationError(String verificationError) { this.verificationError = verificationError; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}