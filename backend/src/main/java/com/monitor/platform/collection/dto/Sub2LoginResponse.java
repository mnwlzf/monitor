package com.monitor.platform.collection.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

public record Sub2LoginResponse(
        int code,
        String message,
        LoginData data
) {

    public record LoginData(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("token_type") String tokenType,
            User user
    ) {
    }

    public record User(
            Long id,
            String email,
            String username,
            String role,
            Double balance,
            @JsonProperty("frozen_balance") Double frozenBalance,
            Integer concurrency,
            String status,
            @JsonProperty("allowed_groups") List<Long> allowedGroups,
            @JsonProperty("last_active_at") OffsetDateTime lastActiveAt,
            @JsonProperty("created_at") OffsetDateTime createdAt,
            @JsonProperty("updated_at") OffsetDateTime updatedAt,
            @JsonProperty("balance_notify_enabled") Boolean balanceNotifyEnabled,
            @JsonProperty("balance_notify_threshold_type") String balanceNotifyThresholdType,
            @JsonProperty("balance_notify_threshold") Double balanceNotifyThreshold,
            @JsonProperty("balance_notify_extra_emails") String balanceNotifyExtraEmails,
            @JsonProperty("total_recharged") Double totalRecharged,
            @JsonProperty("rpm_limit") Integer rpmLimit
    ) {
    }
}
