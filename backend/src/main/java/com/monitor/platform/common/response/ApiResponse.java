package com.monitor.platform.common.response;

/**
 * 统一成功响应包装。
 *
 * @param data      业务数据，具体类型由接口决定
 * @param requestId 请求链路标识，便于日志追踪
 * @param <T>       业务数据类型
 */
public record ApiResponse<T>(T data, String requestId) {

    /**
     * 创建成功响应。
     */
    public static <T> ApiResponse<T> of(T data, String requestId) {
        return new ApiResponse<>(data, requestId);
    }
}