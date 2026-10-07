package com.monitor.platform.adapter.sub2api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Sub2API 刷新令牌响应。
 *
 * <p>刷新成功会返回新的 access_token，并轮转出新的 refresh_token。</p>
 *
 * @param code    Sub2API 业务状态码，0 表示成功
 * @param message 业务提示信息
 * @param data    刷新结果
 */
public record Sub2RefreshTokenResponse(
        int code,
        String message,
        Data data
) {

    /**
     * 刷新后的令牌数据。
     *
     * @param accessToken  新的访问令牌
     * @param refreshToken 轮转后的刷新令牌
     * @param expiresIn    访问令牌有效期（秒）
     * @param tokenType    令牌类型
     */
    public record Data(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") Long expiresIn,
            @JsonProperty("token_type") String tokenType
    ) {
    }
}