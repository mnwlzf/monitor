package com.monitor.platform.bot.vision;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 图片理解配置。
 *
 * <p>用户发的图片会被下载下来、作为多模态消息交给模型识别。
 * 下载有大小与数量上限，避免群里有人连发大图把内存和带宽打满。</p>
 */
@ConfigurationProperties(prefix = "monitor.bot.vision")
public class BotVisionProperties {

    /** 是否让机器人理解图片内容。关闭后图片消息只回一句提示，不再送给模型。 */
    private boolean enabled = true;

    /** 单张图片大小上限（字节），超过直接跳过。 */
    private long maxImageBytes = 8L * 1024 * 1024;

    /** 单条消息最多处理几张图。 */
    private int maxImagesPerMessage = 3;

    /** 下载超时。 */
    private Duration timeout = Duration.ofSeconds(15);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getMaxImageBytes() {
        return maxImageBytes;
    }

    public void setMaxImageBytes(long maxImageBytes) {
        this.maxImageBytes = maxImageBytes;
    }

    public int getMaxImagesPerMessage() {
        return maxImagesPerMessage;
    }

    public void setMaxImagesPerMessage(int maxImagesPerMessage) {
        this.maxImagesPerMessage = maxImagesPerMessage;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}