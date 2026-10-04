package com.monitor.platform.adapter.newapi.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * New API 完整密钥响应。
 *
 * <p>接口路径：{@code POST /api/token/{id}/key}。返回的是可用的完整明文密钥，
 * 采集端加密后落库，并保留哈希用于轮换检测。</p>
 *
 * @param data    完整密钥
 * @param message 业务提示信息
 * @param success 是否请求成功
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NewApiTokenKeyResponse(
        Data data,
        String message,
        boolean success
) {

    /**
     * 完整密钥数据。
     *
     * @param key 完整明文密钥
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(String key) {
    }
}
