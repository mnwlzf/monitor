package com.monitor.platform.api.dto;

import java.util.List;

/**
 * 当前登录用户响应。
 *
 * @param username 登录名
 * @param roles    角色列表
 * @param admin    是否拥有管理员权限（可写）
 */
public record CurrentUserResponse(
        String username,
        List<String> roles,
        boolean admin
) {
}