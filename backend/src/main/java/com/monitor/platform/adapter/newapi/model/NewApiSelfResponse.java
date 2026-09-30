package com.monitor.platform.adapter.newapi.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * New API 当前用户信息响应。
 *
 * <p>接口路径：{@code GET /api/user/self}。</p>
 *
 * @param data    当前用户信息
 * @param message 业务提示信息
 * @param success 是否请求成功
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NewApiSelfResponse(
        NewApiUser data,
        String message,
        boolean success
) {
}