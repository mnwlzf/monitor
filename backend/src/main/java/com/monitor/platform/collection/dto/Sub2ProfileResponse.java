package com.monitor.platform.collection.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 个人信息接口响应
 * GET /api/v1/user/profile
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Sub2ProfileResponse(
        int code,
        String message,
        UserProfile data
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserProfile(
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
            @JsonProperty("rpm_limit") Integer rpmLimit,
            @JsonProperty("identities") Map<String, IdentityBinding> identities,
            @JsonProperty("auth_bindings") Map<String, IdentityBinding> authBindings,
            @JsonProperty("identity_bindings") Map<String, IdentityBinding> identityBindings,
            @JsonProperty("email_bound") Boolean emailBound,
            @JsonProperty("linuxdo_bound") Boolean linuxdoBound,
            @JsonProperty("oidc_bound") Boolean oidcBound,
            @JsonProperty("wechat_bound") Boolean wechatBound,
            @JsonProperty("dingtalk_bound") Boolean dingtalkBound,
            @JsonProperty("run_mode") String runMode
    ) {
    }

    /**
     * 身份绑定信息（identities / auth_bindings / identity_bindings 结构一致）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IdentityBinding(
            String provider,
            Boolean bound,
            @JsonProperty("bound_count") Integer boundCount,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("subject_hint") String subjectHint,
            @JsonProperty("provider_key") String providerKey,
            @JsonProperty("verified_at") OffsetDateTime verifiedAt,
            @JsonProperty("can_bind") Boolean canBind,
            @JsonProperty("can_unbind") Boolean canUnbind,
            @JsonProperty("note_key") String noteKey,
            String note
    ) {
    }
}