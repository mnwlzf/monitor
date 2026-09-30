package com.monitor.platform.adapter.newapi.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * New API 登录响应。
 *
 * <p>接口路径：{@code POST /api/user/login?turnstile=}。成功时
 * {@code success=true}，令牌位于 {@code data.access_token}。</p>
 *
 * @param data    登录结果
 * @param message 业务提示信息
 * @param success 是否登录成功
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NewApiLoginResponse(
        LoginData data,
        String message,
        boolean success
) {

    /**
     * 登录成功后的令牌、会话和用户信息。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoginData(
            @JsonProperty("access_expires_at") Long accessExpiresAt,
            @JsonProperty("access_token") String accessToken,
            Session session,
            @JsonProperty("token_type") String tokenType,
            NewApiUser user
    ) {
    }

    /**
     * New API 会话信息。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Session(
            String sid,
            Boolean current,
            @JsonProperty("login_method") String loginMethod,
            String ip,
            @JsonProperty("user_agent") String userAgent,
            @JsonProperty("created_at") Long createdAt,
            @JsonProperty("last_active_at") Long lastActiveAt,
            @JsonProperty("expires_at") Long expiresAt
    ) {
    }
}