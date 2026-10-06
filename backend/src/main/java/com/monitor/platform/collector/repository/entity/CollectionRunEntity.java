package com.monitor.platform.collector.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.platform.infrastructure.mybatis.handler.JsonbStringTypeHandler;

import java.time.OffsetDateTime;

/**
 * 采集批次实体。
 */
@TableName(value = "collection_runs", autoResultMap = true)
public class CollectionRunEntity {

    @TableId(type = IdType.AUTO)
    /** 采集批次主键。 */
    private Long id;
    /** 账号主键。 */
    private Integer accountId;
    /** 平台类型。 */
    private String platformType;
    /** 采集范围：FULL、BALANCE、GROUPS、API_KEYS。 */
    private String scope;
    /** 批次状态，例如 RUNNING、SUCCESS、FAILED。 */
    private String status;
    /** 开始时间。 */
    private OffsetDateTime startedAt;
    /** 结束时间。 */
    private OffsetDateTime finishedAt;
    /** 采集耗时，单位毫秒。 */
    private Long durationMs;
    /** 失败错误码。 */
    private String errorCode;
    /** 失败错误信息。 */
    private String errorMessage;
    /** 批次扩展元数据，JSON 文本。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String metadata;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getAccountId() { return accountId; }
    public void setAccountId(Integer accountId) { this.accountId = accountId; }
    public String getPlatformType() { return platformType; }
    public void setPlatformType(String platformType) { this.platformType = platformType; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
}
