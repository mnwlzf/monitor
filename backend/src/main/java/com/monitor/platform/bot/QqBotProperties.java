package com.monitor.platform.bot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * QQ 机器人配置。
 *
 * <p>默认关闭。真正开始工作需同时满足 {@code monitor.bot.enabled=true}、
 * 配置了回调共享密钥 {@code webhook-token} 与 OneBot 的 HTTP API 地址 {@code api-base-url}。</p>
 *
 * <p>对接的是 OneBot v11 协议（NapCat / Lagrange / go-cqhttp 都支持）：
 * 机器人把收到的消息「反向 HTTP」POST 到 {@code /api/v1/bot/onebot}，
 * 本项目再通过 {@code api-base-url} 调它的 HTTP API 把回答发回去。</p>
 */
@ConfigurationProperties(prefix = "monitor.bot")
public class QqBotProperties {

    /** 是否启用。 */
    private boolean enabled = false;

    /** 反向 HTTP 上报的共享密钥；未配置时一律拒绝回调（fail closed）。 */
    private String webhookToken;

    /** OneBot 实现的 HTTP API 根地址，例如 http://127.0.0.1:3000。 */
    private String apiBaseUrl;

    /** OneBot HTTP API 的 access token，没设置就留空。 */
    private String apiToken;

    /** 允许响应的群号（逗号分隔）；留空表示不限制。 */
    private List<String> allowedGroups = new ArrayList<>();

    /** 允许响应的私聊 QQ（逗号分隔）；留空表示不限制。 */
    private List<String> allowedUsers = new ArrayList<>();

    /** 群里是否必须 @机器人 才响应，避免机器人抢话。 */
    private boolean requireMention = true;

    /** 命令前缀，例如 {@code /help}。 */
    private String commandPrefix = "/";

    /** 单条回复最大字符数（QQ 对消息长度有限制，超长截断）。 */
    private int maxReplyLength = 900;

    /** 每个会话保留的历史消息条数，用于多轮追问。 */
    private int memoryWindow = 10;

    /** 群号是否在白名单内；白名单为空表示不限制。 */
    public boolean isGroupAllowed(Long groupId) {
        return matches(allowedGroups, groupId);
    }

    /** 私聊 QQ 是否在白名单内；白名单为空表示不限制。 */
    public boolean isUserAllowed(Long userId) {
        return matches(allowedUsers, userId);
    }

    private static boolean matches(List<String> whitelist, Long id) {
        if (whitelist == null || whitelist.isEmpty() || id == null) {
            return whitelist == null || whitelist.isEmpty();
        }
        String target = String.valueOf(id);
        for (String item : whitelist) {
            if (item != null && item.trim().equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** 是否具备实际工作的条件。 */
    public boolean isConfigured() {
        return enabled
                && webhookToken != null && !webhookToken.isBlank()
                && apiBaseUrl != null && !apiBaseUrl.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getWebhookToken() {
        return webhookToken;
    }

    public void setWebhookToken(String webhookToken) {
        this.webhookToken = webhookToken;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public List<String> getAllowedGroups() {
        return allowedGroups;
    }

    public void setAllowedGroups(List<String> allowedGroups) {
        this.allowedGroups = allowedGroups;
    }

    public List<String> getAllowedUsers() {
        return allowedUsers;
    }

    public void setAllowedUsers(List<String> allowedUsers) {
        this.allowedUsers = allowedUsers;
    }

    public boolean isRequireMention() {
        return requireMention;
    }

    public void setRequireMention(boolean requireMention) {
        this.requireMention = requireMention;
    }

    public String getCommandPrefix() {
        return commandPrefix;
    }

    public void setCommandPrefix(String commandPrefix) {
        this.commandPrefix = commandPrefix;
    }

    public int getMaxReplyLength() {
        return maxReplyLength;
    }

    public void setMaxReplyLength(int maxReplyLength) {
        this.maxReplyLength = maxReplyLength;
    }

    public int getMemoryWindow() {
        return memoryWindow;
    }

    public void setMemoryWindow(int memoryWindow) {
        this.memoryWindow = memoryWindow;
    }
}