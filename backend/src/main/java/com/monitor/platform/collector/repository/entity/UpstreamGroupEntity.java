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
    /** 渠道主键。 */
    private Long id;
    /** 所属账号主键。 */
    private Integer accountId;
    /** 平台类型。 */
    private String platformType;
    /** 上游渠道 ID。 */
    private String externalGroupId;
    /** 渠道名称。 */
    private String groupName;
    /** 渠道描述。 */
    private String description;
    /** 渠道所属平台。 */
    private String platform;
    /** 当前倍率。 */
    private BigDecimal currentRatio;
    /** 当前基础倍率。 */
    private BigDecimal currentBaseRatio;
    /** 上游返回的状态。 */
    private String status;
    /** 本地是否仍有效。 */
    private Boolean isActive;
    /** 首次发现时间。 */
    private OffsetDateTime firstSeenAt;
    /** 最近一次可见时间。 */
    private OffsetDateTime lastSeenAt;
    /** 最近一次变更时间。 */
    private OffsetDateTime lastChangedAt;
    /** 上游原始数据或扩展元数据，JSON 文本。 */
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