package com.monitor.platform.adapter.sub2api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Sub2API 登录响应。
 *
 * <p>该模型只映射当前采集流程实际使用的字段，并通过
 * {@code @JsonProperty} 兼容 Sub2API 的 snake_case 字段命名。</p>
 *
 * @param code    Sub2API 业务状态码，0 表示成功
 * @param message 业务提示信息
 * @param data    登录结果数据
 */
public record Sub2LoginResponse(
        int code,
        String message,
        LoginData data
) {

    /**
     * 登录成功后返回的令牌及用户信息。
     *
     * @param accessToken  访问令牌
     * @param refreshToken 刷新令牌
     * @param expiresIn    令牌有效期，单位由上游接口定义
     * @param tokenType    令牌类型
     * @param user         当前登录用户
     */
    public record LoginData(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("token_type") String tokenType,
            User user
    ) {
    }

    /**
     * 登录响应中的用户基础信息。
     *
     * <p>字段与 Sub2API 用户模型保持映射，未使用的字段由 Jackson 自动忽略。</p>
     */
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