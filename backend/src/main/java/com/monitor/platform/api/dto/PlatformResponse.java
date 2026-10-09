package com.monitor.platform.api.dto;

/**
 * 上游平台响应。
 *
 * @param id                    平台主键
 * @param name                  平台名称
 * @param baseUrl               平台基础地址
 * @param platform              平台类型
 * @param status                平台是否启用
 * @param adminKeyConfigured    是否已配置 Sub2API 管理员密钥
 * @param poolMonitoringEnabled 是否作为号池监控源（用户自建的 Sub2API）
 */
public record PlatformResponse(
        Integer id,
        String name,
        String baseUrl,
        String platform,
        Boolean status,
        Boolean adminKeyConfigured,
        Boolean poolMonitoringEnabled
) {
}