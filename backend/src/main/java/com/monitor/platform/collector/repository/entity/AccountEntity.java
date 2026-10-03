package com.monitor.platform.collector.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.platform.infrastructure.mybatis.handler.JsonbStringTypeHandler;

import java.time.OffsetDateTime;

/**
 * 采集账号实体。
 */
@TableName(value = "accounts", autoResultMap = true)
public class AccountEntity {

    @TableId(type = IdType.AUTO)
    /** 账号主键。 */
    private Integer id;
    /** 登录账号或邮箱。 */
    private String email;
    /** 登录用户名。 */
    private String username;
    /** 平台类型的冗余副本，便于查询和兼容历史数据。 */
    private String platform;
    /** 平台地址的冗余副本。 */
    private String url;
    /** 账号是否启用。 */
    private Boolean status;
    /** 创建时间。 */
    private OffsetDateTime createdAt;
    /** 更新时间。 */
    private OffsetDateTime updatedAt;
    /** 历史明文密码字段，当前凭证统一存于 account_credentials。 */
    private String password;
    /** 所属平台主键。 */
    private Integer platformId;
    /** 前端展示名称。 */
    private String displayName;
    /** 上游平台中的用户 ID。 */
    private String externalUserId;
    /** 认证类型，当前默认 PASSWORD。 */
    private String authType;
    /** 凭证状态，例如 VALID、UNKNOWN。 */
    private String credentialStatus;
    /** 账号扩展配置，JSON 文本。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String settings;
    /** 最近一次采集时间。 */
    private OffsetDateTime lastCollectedAt;
    /** 最近一次采集状态。 */
    private String lastCollectStatus;
    /** 最近一次采集错误信息。 */
    private String lastCollectError;
    /** 连续采集失败次数，用于计算退避时间。 */
    private Integer consecutiveFailures;
    /** 下一次计划采集时间。 */
    private OffsetDateTime nextCollectAt;
    /** 软删除时间。 */
    private OffsetDateTime deletedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public Boolean getStatus() { return status; }
    public void setStatus(Boolean status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public Integer getPlatformId() { return platformId; }
    public void setPlatformId(Integer platformId) { this.platformId = platformId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getExternalUserId() { return externalUserId; }
    public void setExternalUserId(String externalUserId) { this.externalUserId = externalUserId; }
    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }
    public String getCredentialStatus() { return credentialStatus; }
    public void setCredentialStatus(String credentialStatus) { this.credentialStatus = credentialStatus; }
    public String getSettings() { return settings; }
    public void setSettings(String settings) { this.settings = settings; }
    public OffsetDateTime getLastCollectedAt() { return lastCollectedAt; }
    public void setLastCollectedAt(OffsetDateTime lastCollectedAt) { this.lastCollectedAt = lastCollectedAt; }
    public String getLastCollectStatus() { return lastCollectStatus; }
    public void setLastCollectStatus(String lastCollectStatus) { this.lastCollectStatus = lastCollectStatus; }
    public String getLastCollectError() { return lastCollectError; }
    public void setLastCollectError(String lastCollectError) { this.lastCollectError = lastCollectError; }
    public Integer getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(Integer consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
    public OffsetDateTime getNextCollectAt() { return nextCollectAt; }
    public void setNextCollectAt(OffsetDateTime nextCollectAt) { this.nextCollectAt = nextCollectAt; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }
}