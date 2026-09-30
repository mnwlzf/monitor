package com.monitor.platform.collector.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.platform.infrastructure.mybatis.handler.JsonbStringTypeHandler;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 上游渠道/分组当前状态实体。
 */
@TableName(value = "upstream_groups", autoResultMap = true)
public class UpstreamGroupEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Integer accountId;
    private String platformType;
    private String externalGroupId;
    private String groupName;
    private String description;
    private String platform;
    private BigDecimal currentRatio;
    private BigDecimal currentBaseRatio;
    private String status;
    private Boolean isActive;
    private OffsetDateTime firstSeenAt;
    private OffsetDateTime lastSeenAt;
    private OffsetDateTime lastChangedAt;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String metadata;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getAccountId() { return accountId; }
    public void setAccountId(Integer accountId) { this.accountId = accountId; }
    public String getPlatformType() { return platformType; }
    public void setPlatformType(String platformType) { this.platformType = platformType; }
    public String getExternalGroupId() { return externalGroupId; }
    public void setExternalGroupId(String externalGroupId) { this.externalGroupId = externalGroupId; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public BigDecimal getCurrentRatio() { return currentRatio; }
    public void setCurrentRatio(BigDecimal currentRatio) { this.currentRatio = currentRatio; }
    public BigDecimal getCurrentBaseRatio() { return currentBaseRatio; }
    public void setCurrentBaseRatio(BigDecimal currentBaseRatio) { this.currentBaseRatio = currentBaseRatio; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public OffsetDateTime getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(OffsetDateTime firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(OffsetDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public OffsetDateTime getLastChangedAt() { return lastChangedAt; }
    public void setLastChangedAt(OffsetDateTime lastChangedAt) { this.lastChangedAt = lastChangedAt; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
}