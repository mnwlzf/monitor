package com.monitor.platform.bot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 主聊天模型不可用时的备用 OpenAI 兼容模型配置。 */
@ConfigurationProperties(prefix = "monitor.bot.model-fallback")
public class BotModelFallbackProperties {

    /** 是否启用备用模型。 */
    private boolean enabled = true;

    /** 备用 API Key；为空时不启用。 */
    private String apiKey;

    /** 备用 OpenAI 兼容接口根地址。 */
    private String baseUrl = "https://api.openai.com";

    /** 备用模型名称。 */
    private String model = "gpt-4o-mini";

    /** 备用模型温度。 */
    private Double temperature = 0.2;

    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank()
                && baseUrl != null && !baseUrl.isBlank()
                && model != null && !model.isBlank();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
}
