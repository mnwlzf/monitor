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
    /**
     * 变更对应的渠道名（只有 GROUP 事件有）。
     *
     * <p>它不是本表的列，而是查询时 join upstream_groups 带出来的。
     * 页面和邮件上只显示渠道名 —— 只给 ID 等于让运维自己去猜是哪个渠道。</p>
     */
    @TableField(exist = false)
    private String groupName;
    /**
     * 变更所属账号的名字（accounts 表的展示名 / 用户名）。
     *
     * <p>不是本表的列，查询时 join 出来的：事件里只有 account_id，
     * 而管理员要看到的是「我自己的哪个 sub2api 账号」。</p>
     */
    @TableField(exist = false)
    private String accountName;
    /**
     * 上游密钥名（API_KEY 事件才有）。
     *
     * <p>不是本表的列：key_name 在 account_api_keys 里，
     * 用 account_id + external_key_id 匹配出来；没匹配到为 null。</p>
     */
    @TableField(exist = false)
    private String keyName;
    /** 受影响的「上游账号 Key」名字，多个用「、」连接（渠道事件按同分组匹配）。 */
    @TableField(exist = false)
    private String upstreamKeyNames;
    /** 对应的「本平台 Key」名字（号池账号名），多个用「、」连接。 */
    @TableField(exist = false)
    private String localKeyNames;
    /** 所在分组的当前倍率（渠道事件取 upstream_groups.current_ratio）。 */
    @TableField(exist = false)
    private java.math.BigDecimal groupRatio;
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
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public String getKeyName() { return keyName; }
    public void setKeyName(String keyName) { this.keyName = keyName; }
    public String getUpstreamKeyNames() { return upstreamKeyNames; }
    public void setUpstreamKeyNames(String upstreamKeyNames) { this.upstreamKeyNames = upstreamKeyNames; }
    public String getLocalKeyNames() { return localKeyNames; }
    public void setLocalKeyNames(String localKeyNames) { this.localKeyNames = localKeyNames; }
    public java.math.BigDecimal getGroupRatio() { return groupRatio; }
    public void setGroupRatio(java.math.BigDecimal groupRatio) { this.groupRatio = groupRatio; }
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