package com.monitor.platform.bot;

import java.time.OffsetDateTime;

/**
 * 页面可配置的 QQ 机器人设置实体（全局单行）。
 *
 * <p>白名单等字段以逗号分隔的字符串落库，读写时由 {@link BotSettingsService} 负责拆装。</p>
 */
public class BotSettingsEntity {

    private Integer id;
    private Boolean enabled;
    private String allowedGroups;
    private String allowedUsers;
    /** 指定群（平台功能群），逗号分隔；必须是 allowedGroups 的子集。 */
    private String platformGroups;
    private Boolean requireMention;
    private String commandPrefix;
    private Integer maxReplyLength;
    private Integer memoryWindow;
    private OffsetDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getAllowedGroups() { return allowedGroups; }
    public void setAllowedGroups(String allowedGroups) { this.allowedGroups = allowedGroups; }
    public String getAllowedUsers() { return allowedUsers; }
    public void setAllowedUsers(String allowedUsers) { this.allowedUsers = allowedUsers; }
    public String getPlatformGroups() { return platformGroups; }
    public void setPlatformGroups(String platformGroups) { this.platformGroups = platformGroups; }
    public Boolean getRequireMention() { return requireMention; }
    public void setRequireMention(Boolean requireMention) { this.requireMention = requireMention; }
    public String getCommandPrefix() { return commandPrefix; }
    public void setCommandPrefix(String commandPrefix) { this.commandPrefix = commandPrefix; }
    public Integer getMaxReplyLength() { return maxReplyLength; }
    public void setMaxReplyLength(Integer maxReplyLength) { this.maxReplyLength = maxReplyLength; }
    public Integer getMemoryWindow() { return memoryWindow; }
    public void setMemoryWindow(Integer memoryWindow) { this.memoryWindow = memoryWindow; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}