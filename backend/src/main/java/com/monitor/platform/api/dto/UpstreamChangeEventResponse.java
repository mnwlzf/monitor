package com.monitor.platform.api.dto;

import java.time.OffsetDateTime;

/**
 * 上游变更事件响应。
 *
 * @param id           事件主键
 * @param accountId    账号主键
 * @param platformType 平台类型
 * @param entityType   变更实体类型
 * @param entityKey    上游实体标识
 * @param changeType   变更类型
 * @param fieldName    发生变化的字段
 * @param oldValue     变更前值
 * @param newValue     变更后值
 * @param severity     事件级别
 * @param message      可读说明
 * @param detectedAt   发现时间
 */
public record UpstreamChangeEventResponse(
        Long id,
        Integer accountId,
        String platformType,
        String entityType,
        String entityKey,
        String changeType,
        String fieldName,
        String oldValue,
        String newValue,
        String severity,
        String message,
        OffsetDateTime detectedAt
) {
}