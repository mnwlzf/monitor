package com.monitor.platform.api.dto;

/**
 * 手动绑定号池账号与本地密钥请求。
 *
 * @param keyId 本地密钥 ID；为空表示解除绑定
 */
public record BindPoolAccountRequest(Long keyId) {
}