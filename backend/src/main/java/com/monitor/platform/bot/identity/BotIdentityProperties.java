package com.monitor.platform.bot.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * QQ 机器人用户身份识别配置。
 *
 * <p>识别链路：QQ 号 →（绑定或自动匹配）邮箱 → 命中 Sub2API 用户缓存 → 得到「是否平台用户 + 角色」。
 * 识别结果决定机器人用哪套人设、能不能查平台数据。</p>
 */
@ConfigurationProperties(prefix = "monitor.bot.identity")
public class BotIdentityProperties {

    /** 是否启用身份识别。关闭后所有人一律按「普通聊天」处理。 */
    private boolean enabled = true;

    /**
     * 是否允许按邮箱本地部分自动匹配 QQ 号。
     *
     * <p>开启时，缓存里形如 {@code 123456@qq.com} 的邮箱会自动对应 QQ 123456，
     * 用户无需手动绑定。</p>
     */
    private boolean qqLocalPartMatch = true;


    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isQqLocalPartMatch() {
        return qqLocalPartMatch;
    }

    public void setQqLocalPartMatch(boolean qqLocalPartMatch) {
        this.qqLocalPartMatch = qqLocalPartMatch;
    }

}