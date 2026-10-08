package com.monitor.platform.collector.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.platform.infrastructure.mybatis.handler.JsonbStringTypeHandler;

import java.time.OffsetDateTime;

/**
 * 上游变更事件实体。
 */
@TableName(value = "upstream_change_events", autoResultMap = true)
public class UpstreamChangeEventEntity {

    @TableId(type = IdType.AUTO)
    /** 变更事件主键。 */
    private Long id;
    /** 所属账号主键。 */
    private Integer accountId;
    /** 平台类型。 */
    private String platformType;
    /** 产生该事件的采集批次主键。 */
    private Long collectionRunId;
    /** 变更实体类型，例如 GROUP。 */
    private String entityType;
    /** 本地实体主键。 */
    private Long entityId;
    /** 上游实体标识。 */
    private String entityKey;
    /** 变更类型。 */
    private String changeType;
    /** 发生变化的字段名。 */
    private String fieldName;
    /** 变更前值，JSON 文本。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String oldValue;
    /** 变更后值，JSON 文本。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String newValue;
    /** 事件级别。 */
    private String severity;
    /** 可读事件说明。 */
    private String message;
    /** 发现时间。 */
    private OffsetDateTime detectedAt;
    /** 变更是否涉及正在使用（启用）的密钥，仅 API_KEY 事件有意义。 */
    private Boolean inUse;
    /** 扩展元数据，JSON 文本。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String metadata;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getAccountId() { return accountId; }
    public void setAccountId(Integer accountId) { this.accountId = accountId; }
    public String getPlatformType() { return platformType; }
    public void setPlatformType(String platformType) { this.platformType = platformType; }
    public Long getCollectionRunId() { return collectionRunId; }
    public void setCollectionRunId(Long collectionRunId) { this.collectionRunId = collectionRunId; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public Long getEntityId() { return entityId; }
    public void setEntityId(Long entityId) { this.entityId = entityId; }
    public String getEntityKey() { return entityKey; }
    public void setEntityKey(String entityKey) { this.entityKey = entityKey; }
    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
    public String getOldValue() { return oldValue; }
    public void setOldValue(String oldValue) { this.oldValue = oldValue; }
    public String getNewValue() { return newValue; }
    public void setNewValue(String newValue) { this.newValue = newValue; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public OffsetDateTime getDetectedAt() { return detectedAt; }
    public void setDetectedAt(OffsetDateTime detectedAt) { this.detectedAt = detectedAt; }
    public Boolean getInUse() { return inUse; }
    public void setInUse(Boolean inUse) { this.inUse = inUse; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
}