package com.monitor.platform.adapter.sub2api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Sub2API 个人信息接口响应。
 *
 * <p>接口路径：{@code GET /api/v1/auth/me}。使用
 * {@link JsonIgnoreProperties} 忽略上游新增字段，避免上游扩展响应结构时导致反序列化失败。</p>
 *
 * @param code    Sub2API 业务状态码，0 表示成功
 * @param message 业务提示信息
 * @param data    用户个人信息
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Sub2ProfileResponse(
        int code,
        String message,
        UserProfile data
) {

    /**
     * 用户个人信息。
     *
     * <p>金额字段按上游返回的 Double 保留；时间字段使用
     * {@link OffsetDateTime} 承载上游 ISO-8601 时间。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserProfile(
            Long id,
            String email,
            String username,
            String role,
            Double balance,  // 当前剩余余额，如果充值了 这个数字会变大，如果使用会变小，单纯使用此字段判断每天消耗不靠谱
            @JsonProperty("frozen_balance") Double frozenBalance,
            Integer concurrency,// 并发数
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
     * 身份绑定信息。
     *
     * <p>{@code identities}、{@code auth_bindings} 和
     * {@code identity_bindings} 在 Sub2API 中具有相同结构，因此复用该模型。</p>
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