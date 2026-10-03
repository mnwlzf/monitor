package com.monitor.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建上游平台请求。
 *
 * @param name     平台名称
 * @param baseUrl  平台基础地址
 * @param platform 平台类型，newapi 或 sub2api
 */
public record CreatePlatformRequest(
        @NotBlank(message = "平台名称不能为空")
        @Size(max = 100, message = "平台名称不能超过 100 个字符")
        String name,

        @NotBlank(message = "Base URL 不能为空")
        @Size(max = 500, message = "Base URL 不能超过 500 个字符")
        @Pattern(regexp = "^https?://.+", message = "Base URL 必须以 http:// 或 https:// 开头")
        String baseUrl,

        @NotBlank(message = "平台类型不能为空")
        @Pattern(regexp = "newapi|sub2api", message = "平台类型仅支持 newapi 或 sub2api")
        String platform
) {
}