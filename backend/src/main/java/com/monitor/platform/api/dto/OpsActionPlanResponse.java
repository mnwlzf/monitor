package com.monitor.platform.api.dto;

import com.monitor.platform.ops.OpsActionPlanEntity;

import java.time.OffsetDateTime;

/** 自愈动作计划响应。 */
public record OpsActionPlanResponse(
        Long id,
        Long incidentId,
        String planCode,
        String actionType,
        String targetType,
        Long targetId,
        String targetName,
        String riskLevel,
        String status,
        String summary,
        String expectedEffect,
        String beforeJson,
        String afterJson,
        String confirmationCode,
        OffsetDateTime expiresAt,
        String createdBy,
        String approvedBy,
        OffsetDateTime approvedAt,
        OffsetDateTime executedAt,
        OffsetDateTime createdAt
) {
    public static OpsActionPlanResponse from(OpsActionPlanEntity entity) {
        return new OpsActionPlanResponse(
                entity.getId(), entity.getIncidentId(), entity.getPlanCode(), entity.getActionType(),
                entity.getTargetType(), entity.getTargetId(), entity.getTargetName(), entity.getRiskLevel(),
                entity.getStatus(), entity.getSummary(), entity.getExpectedEffect(), entity.getBeforeJson(),
                entity.getAfterJson(), entity.getConfirmationCode(), entity.getExpiresAt(), entity.getCreatedBy(),
                entity.getApprovedBy(), entity.getApprovedAt(), entity.getExecutedAt(), entity.getCreatedAt());
    }
}