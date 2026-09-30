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
    private Integer id;
    private String email;
    private String username;
    private String platform;
    private String url;
    private Boolean status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private String password;
    private Integer platformId;
    private String displayName;
    private String externalUserId;
    private String authType;
    private String credentialStatus;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String settings;
    private OffsetDateTime lastCollectedAt;
    private String lastCollectStatus;
    private String lastCollectError;
    private Integer consecutiveFailures;
    private OffsetDateTime nextCollectAt;
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