package com.monitor.platform.adapter.sub2api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Sub2API 更新密钥请求（只用于设置精确到期时间）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Sub2UpdateKeyRequest(
        @JsonProperty("expires_at") String expiresAt
) {
}
