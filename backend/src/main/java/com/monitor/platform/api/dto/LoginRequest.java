package com.monitor.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。
 *
 * @param username 登录名
 * @param password 登录密码
 */
public record LoginRequest(
        @NotBlank(message = "登录名不能为空")
        @Size(max = 100, message = "登录名不能超过 100 个字符")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(max = 200, message = "密码不能超过 200 个字符")
        String password
) {
}