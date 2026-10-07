package com.monitor.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建采集账号请求。
 *
 * <p>支持两种认证方式：</p>
 * <ul>
 *   <li>{@code PASSWORD}（默认）：需要 {@code password}；</li>
 *   <li>{@code TOKEN}：需要 {@code refreshToken} 或 {@code accessToken}，用于上游开启验证码、
 *       无法自动登录时，手动粘贴浏览器登录后拿到的令牌。</li>
 * </ul>
 *
 * @param displayName  展示名称
 * @param loginName    登录账号
 * @param password     登录密码，密码登录时必填
 * @param authType     认证类型，默认 PASSWORD
 * @param accessToken  手动填写的访问令牌，可选
 * @param refreshToken 手动填写的刷新令牌，可选（推荐，可自动续期）
 */
public record CreateAccountRequest(
        @Size(max = 255, message = "显示名称不能超过 255 个字符")
        String displayName,

        @NotBlank(message = "登录账号不能为空")
        @Size(max = 255, message = "登录账号不能超过 255 个字符")
        String loginName,

        @Size(max = 500, message = "登录密码不能超过 500 个字符")
        String password,

        @Size(max = 50, message = "认证类型不能超过 50 个字符")
        String authType,

        @Size(max = 4000, message = "access_token 不能超过 4000 个字符")
        String accessToken,

        @Size(max = 4000, message = "refresh_token 不能超过 4000 个字符")
        String refreshToken
) {
}