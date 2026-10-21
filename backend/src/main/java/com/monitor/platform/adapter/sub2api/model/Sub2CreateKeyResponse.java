package com.monitor.platform.adapter.sub2api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Sub2API 创建密钥响应。
 *
 * <p>成功时 {@code data} 是创建出来的完整密钥（含明文 {@code key}）。</p>
 *
 * @param code    Sub2API 业务状态码，0 表示成功
 * @param message 业务提示信息
 * @param data    创建出的密钥
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Sub2CreateKeyResponse(
        int code,
        String message,
        Sub2KeysResponse.KeyItem data
) {
}
