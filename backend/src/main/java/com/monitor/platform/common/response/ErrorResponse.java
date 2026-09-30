package com.monitor.platform.common.response;

import java.time.Instant;
import java.util.Map;

/**
 * 统一错误响应。
 *
 * @param code      稳定的业务错误码，供前端或调用方判断
 * @param message   面向调用方的错误说明
 * @param requestId 请求链路标识
 * @param timestamp 错误发生时间
 * @param details   字段级或上下文级补充信息
 */
public record ErrorResponse(
        String code,
        String message,
        String requestId,
        Instant timestamp,
        Map<String, String> details
) {

    /**
     * 创建不包含补充信息的错误响应。
     */
    public static ErrorResponse of(String code, String message, String requestId) {
        return new ErrorResponse(code, message, requestId, Instant.now(), Map.of());
    }
}