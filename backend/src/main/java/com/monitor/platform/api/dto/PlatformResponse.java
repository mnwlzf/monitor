package com.monitor.platform.api.dto;

/**
 * 上游平台响应。
 *
 * @param id       平台主键
 * @param name     平台名称
 * @param baseUrl  平台基础地址
 * @param platform 平台类型
 * @param status   平台是否启用
 */
public record PlatformResponse(
        Integer id,
        String name,
        String baseUrl,
        String platform,
        Boolean status
) {
}