package com.monitor.platform.common.response;

import java.time.Instant;
import java.util.Map;

public record ErrorResponse(
        String code,
        String message,
        String requestId,
        Instant timestamp,
        Map<String, String> details
) {
    public static ErrorResponse of(String code, String message, String requestId) {
        return new ErrorResponse(code, message, requestId, Instant.now(), Map.of());
    }
}