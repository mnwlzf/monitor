package com.monitor.platform.adapter.sub2api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Sub2API 刷新令牌请求。
 *
 * @param refreshToken 刷新令牌
 */
public record Sub2RefreshTokenRequest(
        @JsonProperty("refresh_token") String refreshToken
) {
}