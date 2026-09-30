package com.monitor.platform.api.dto;

import jakarta.validation.constraints.Size;

/**
 * 更新采集账号请求。密码为空时保留原凭证。
 */
public record UpdateAccountRequest(
        @Size(max = 255, message = "显示名称不能超过 255 个字符")
        String displayName,

        @Size(max = 255, message = "登录账号不能超过 255 个字符")
        String loginName,

        @Size(max = 500, message = "登录密码不能超过 500 个字符")
        String password,

        @Size(max = 50, message = "认证类型不能超过 50 个字符")
        String authType
) {
}