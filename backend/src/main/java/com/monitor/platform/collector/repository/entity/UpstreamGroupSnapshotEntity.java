package com.monitor.platform.collector.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.platform.infrastructure.mybatis.handler.JsonbStringTypeHandler;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 上游渠道/分组快照实体。
 */
@TableName(value = "upstream_group_snapshots", autoResultMap = true)
public class UpstreamGroupSnapshotEntity {

    @TableId(type = IdType.AUTO)
    /** 渠道快照主键。 */
    private Long id;
    /** 渠道主键。 */
    private Long groupId;
    /** 所属采集批次主键。 */
    private Long collectionRunId;
    /** 快照时的渠道倍率。 */
    private BigDecimal ratio;
    /** 快照时的基础倍率。 */
    private BigDecimal baseRatio;
    /** 快照时的上游状态。 */
    private String status;
    /** 快照时本地是否有效。 */
    private Boolean isActive;
    /** 快照原始数据，JSON 文本。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String rawData;
    /** 原始数据内容哈希。 */
    private String contentHash;
    /** 采集时间。 */
    private OffsetDateTime collectedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public Long getCollectionRunId() { return collectionRunId; }
    public void setCollectionRunId(Long collectionRunId) { this.collectionRunId = collectionRunId; }
    public BigDecimal getRatio() { return ratio; }
    public void setRatio(BigDecimal ratio) { this.ratio = ratio; }
    public BigDecimal getBaseRatio() { return baseRatio; }
    public void setBaseRatio(BigDecimal baseRatio) { this.baseRatio = baseRatio; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public String getRawData() { return rawData; }
    public void setRawData(String rawData) { this.rawData = rawData; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public OffsetDateTime getCollectedAt() { return collectedAt; }
    public void setCollectedAt(OffsetDateTime collectedAt) { this.collectedAt = collectedAt; }
}