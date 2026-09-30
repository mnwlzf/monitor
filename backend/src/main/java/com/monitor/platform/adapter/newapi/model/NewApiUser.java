package com.monitor.platform.adapter.newapi.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * New API 用户信息。
 *
 * <p>登录接口和 {@code GET /api/user/self} 返回的用户结构一致，统一使用该模型。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NewApiUser(
        Long id,
        String username,
        String email,
        @JsonProperty("display_name") String displayName,
        String group,
        Integer role,
        Integer status,
        Long quota,   // 额度  需要换算  /50w 后即为展示的余额
        @JsonProperty("used_quota") Long usedQuota, // 历史使用额度  换算同上
        @JsonProperty("request_count") Long requestCount,
        @JsonProperty("aff_code") String affCode,
        @JsonProperty("aff_count") Long affCount,
        @JsonProperty("aff_history_quota") Long affHistoryQuota,
        @JsonProperty("aff_quota") Long affQuota,
        @JsonProperty("discord_id") String discordId,
        @JsonProperty("github_id") String githubId,
        @JsonProperty("has_password") Boolean hasPassword,
        @JsonProperty("inviter_id") Long inviterId,
        @JsonProperty("linux_do_id") String linuxDoId,
        @JsonProperty("oidc_id") String oidcId,
        Permissions permissions,
        String setting,
        @JsonProperty("sidebar_modules") String sidebarModules,
        @JsonProperty("stripe_customer") String stripeCustomer,
        @JsonProperty("telegram_id") String telegramId,
        @JsonProperty("wechat_id") String wechatId
) {

    /**
     * 兼容只使用基础用户字段的调用方。
     */
    public NewApiUser(Long id, String username, String email, String displayName,
                      String group, Integer role, Integer status, Long quota,
                      Long usedQuota, Long requestCount) {
        this(id, username, email, displayName, group, role, status, quota,
                usedQuota, requestCount, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 用户权限信息。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Permissions(
            @JsonProperty("admin_permissions") AdminPermissions adminPermissions,
            @JsonProperty("sidebar_modules") SidebarModules sidebarModules,
            @JsonProperty("sidebar_settings") Boolean sidebarSettings
    ) {
    }

    /**
     * 管理员权限信息。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AdminPermissions(
            Audit audit,
            Channel channel,
            @JsonProperty("task_plugin") TaskPlugin taskPlugin
    ) {
    }

    /**
     * 审计权限。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Audit(Boolean read) {
    }

    /**
     * 渠道权限。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Channel(
            Boolean operate,
            Boolean read,
            @JsonProperty("secret_view") Boolean secretView,
            @JsonProperty("sensitive_write") Boolean sensitiveWrite,
            Boolean write
    ) {
    }

    /**
     * 任务插件权限。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaskPlugin(Boolean bind) {
    }

    /**
     * 侧边栏模块权限。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SidebarModules(Boolean admin) {
    }
}