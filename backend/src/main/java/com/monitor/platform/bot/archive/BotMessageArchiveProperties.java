package com.monitor.platform.bot.archive;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 机器人消息存档配置。
 *
 * <p>存档用于事后查证「谁在什么时候说了什么、机器人回了什么、当时是什么身份」，
 * 因此默认开启。写入是 best-effort：失败只记日志，绝不影响机器人回复。</p>
 */
@ConfigurationProperties(prefix = "monitor.bot.archive")
public class BotMessageArchiveProperties {

    /** 是否存档。 */
    private boolean enabled = true;

    /** 保留天数；超过的由清理任务删除。0 或负数表示永久保留。 */
    private int retentionDays = 90;

    /** 单条内容最长字符数，超出截断（图片消息本身不存，只存摘要）。 */
    private int maxContentLength = 4000;

    /** 页面一次最多返回多少条。 */
    private int maxPageSize = 500;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public int getMaxContentLength() {
        return maxContentLength;
    }

    public void setMaxContentLength(int maxContentLength) {
        this.maxContentLength = maxContentLength;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }
}