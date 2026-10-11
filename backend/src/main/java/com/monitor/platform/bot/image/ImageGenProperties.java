package com.monitor.platform.bot.image;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 生图工具配置。
 *
 * <p>指向 OpenAI 兼容的图片接口（{@code POST {base-url}/v1/images/generations}）。
 * 与聊天模型共用同一个中转时，{@code base-url} 填中转根地址即可，
 * 例如 {@code https://codex.trovebox.online}。</p>
 *
 * <p>默认限频 {@code max-per-minute=3}：生图一次要 20 秒以上，且要花钱/耗额度，
 * 不能让人刷。</p>
 */
@ConfigurationProperties(prefix = "monitor.image-gen")
public class ImageGenProperties {

    /** 总开关。关掉时工具会直接回「未启用」，不发起请求。 */
    private boolean enabled = true;

    /** OpenAI 兼容接口根地址（不要带 /v1）。 */
    private String baseUrl = "https://codex.trovebox.online";

    /** 密钥。未配置时工具不可用。 */
    private String apiKey;

    /** 生图模型。 */
    private String model = "gpt-image-2.5";

    /** 出图尺寸，OpenAI 图片接口只支持固定几档。 */
    private String size = "1024x1024";

    /** 单次请求超时。实测 1024x1024 约 24 秒，留足余量。 */
    private Duration timeout = Duration.ofSeconds(150);

    /** 限频：每个窗口最多几张。 */
    private int maxPerMinute = 3;

    /** 限频窗口长度。 */
    private Duration rateWindow = Duration.ofMinutes(1);

    /** 提示词长度上限，避免有人塞整篇文章进来。 */
    private int maxPromptLength = 400;

    /** 是否可用：开关打开且配了密钥与模型。 */
    public boolean isUsable() {
        return enabled && apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxPerMinute() {
        return maxPerMinute;
    }

    public void setMaxPerMinute(int maxPerMinute) {
        this.maxPerMinute = maxPerMinute;
    }

    public Duration getRateWindow() {
        return rateWindow;
    }

    public void setRateWindow(Duration rateWindow) {
        this.rateWindow = rateWindow;
    }

    public int getMaxPromptLength() {
        return maxPromptLength;
    }

    public void setMaxPromptLength(int maxPromptLength) {
        this.maxPromptLength = maxPromptLength;
    }
}
