package com.monitor.platform.api.dto;

/**
 * 采集账号响应。
 */
public record AccountResponse(
        Integer id,
        Integer platformId,
        String displayName,
        String loginName,
        String platformType,
        String authStatus,
        Boolean status
) {
}