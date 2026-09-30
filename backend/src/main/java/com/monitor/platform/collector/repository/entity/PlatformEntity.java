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
    private Integer id;
    private String platformName;
    private String url;
    private String platformType;
    private Boolean status;
    private String description;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String settings;
    private OffsetDateTime lastCollectedAt;
    private OffsetDateTime deletedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

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
}