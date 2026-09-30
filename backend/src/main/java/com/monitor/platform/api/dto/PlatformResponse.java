package com.monitor.platform.api.dto;

/**
 * 上游平台响应。
 */
public record PlatformResponse(
        Integer id,
        String name,
        String baseUrl,
        String platform,
        Boolean status
) {
}