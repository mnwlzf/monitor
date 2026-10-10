package com.monitor.platform.bot.identity;

/**
 * 自建 Sub2API 的一个平台用户（只取识别身份需要的字段）。
 *
 * @param id       用户 ID
 * @param email    邮箱，识别用户的唯一凭据
 * @param username 用户名
 * @param role     角色，{@code admin} / {@code user}
 * @param status   账号状态，{@code active} / {@code disabled} 等
 */
public record Sub2ApiUser(
        Long id,
        String email,
        String username,
        String role,
        String status
) {

    /** 是否为管理员角色。 */
    public boolean isAdmin() {
        return role != null && "admin".equalsIgnoreCase(role.trim());
    }

    /** 账号是否可用；状态缺失时按可用处理。 */
    public boolean isActive() {
        if (status == null || status.isBlank()) {
            return true;
        }
        return "active".equalsIgnoreCase(status.trim());
    }
}