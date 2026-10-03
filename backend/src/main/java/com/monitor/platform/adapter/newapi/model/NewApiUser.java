package com.monitor.platform.adapter.newapi.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * New API 用户信息。
 *
 * <p>登录接口和 {@code GET /api/user/self} 返回的用户结构一致，统一使用该模型。</p>
 *
 * @param id                 用户 ID
 * @param username           用户名
 * @param email              邮箱
 * @param displayName        展示名称
 * @param group              用户分组
 * @param role               用户角色
 * @param status             用户状态
 * @param quota              剩余额度，展示时除以 500000 换算为 USD
 * @param usedQuota          累计已用额度，展示时除以 500000 换算为 USD
 * @param requestCount       累计请求数
 * @param affCode            推广码
 * @param affCount           推广人数
 * @param affHistoryQuota    历史推广额度
 * @param affQuota           可用推广额度
 * @param discordId          Discord 绑定 ID
 * @param githubId           GitHub 绑定 ID
 * @param hasPassword        是否设置密码
 * @param inviterId          邀请人 ID
 * @param linuxDoId          Linux.do 绑定 ID
 * @param oidcId             OIDC 绑定 ID
 * @param permissions        权限信息
 * @param setting            用户设置 JSON
 * @param sidebarModules     侧边栏模块配置
 * @param stripeCustomer     Stripe 客户 ID
 * @param telegramId         Telegram 绑定 ID
 * @param wechatId           微信绑定 ID
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
     *
     * @param adminPermissions 管理员权限
     * @param sidebarModules   侧边栏模块权限
     * @param sidebarSettings  侧边栏设置权限
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
     *
     * @param audit       审计权限
     * @param channel     渠道权限
     * @param taskPlugin  任务插件权限
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
     *
     * @param read 是否允许读取审计信息
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Audit(Boolean read) {
    }

    /**
     * 渠道权限。
     *
     * @param operate        是否允许操作渠道
     * @param read           是否允许读取渠道
     * @param secretView     是否允许查看密钥
     * @param sensitiveWrite 是否允许执行敏感写入
     * @param write          是否允许写入渠道
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
     *
     * @param bind 是否允许绑定任务插件
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaskPlugin(Boolean bind) {
    }

    /**
     * 侧边栏模块权限。
     *
     * @param admin 是否允许访问管理员模块
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SidebarModules(Boolean admin) {
    }
}