package com.monitor.platform.adapter.sub2api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Sub2API 更新密钥响应。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Sub2UpdateKeyResponse(
        int code,
        String message,
        Sub2KeysResponse.KeyItem data
) {
}
