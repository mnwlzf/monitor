package com.monitor.platform.api.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 更新上游平台请求。
 *
 * <p>字段为空表示不修改；平台类型变更后，该平台下所有账号会按新适配器采集。</p>
 *
 * @param name     平台名称
 * @param baseUrl  平台基础地址
 * @param platform 平台类型
 * @param status   平台是否启用
 */
public record UpdatePlatformRequest(
        @Size(max = 100, message = "平台名称不能超过 100 个字符")
        String name,

        @Size(max = 500, message = "Base URL 不能超过 500 个字符")
        @Pattern(regexp = "^https?://.+", message = "Base URL 必须以 http:// 或 https:// 开头")
        String baseUrl,

        @Pattern(regexp = "newapi|sub2api", message = "平台类型仅支持 newapi 或 sub2api")
        String platform,

        Boolean status
) {
}