package com.monitor.platform.bot.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 联网搜索配置（DeepSeek 官方 Search / Fetch 接口）。
 *
 * <p>接口：{@code POST https://api.deepseek.com/v1/tools/search} 与 {@code /v1/tools/fetch}，
 * 用 DeepSeek 官方 API Key 鉴权。计费按成功请求次数（各 5 元 / 1000 次），每个账号
 * Search / Fetch 各自 5 QPS。</p>
 *
 * <p>不配 {@code api-key} 时 {@link #isConfigured()} 为 false，工具只回一句「未启用」，
 * 不会发起任何请求。</p>
 *
 * <p><strong>谁可以用</strong>不在这里：管理员默认可用；普通人由页面
 * {@code bot_settings.web_search_for_non_admin} 控制（保存即生效，不用重启）。</p>
 */
@ConfigurationProperties(prefix = "monitor.web-search")
public class WebSearchProperties {

    /** 是否启用联网搜索。 */
    private boolean enabled = true;

    /** DeepSeek API 根地址。 */
    private String baseUrl = "https://api.deepseek.com";

    /** DeepSeek 官方 API Key；为空表示未配置。 */
    private String apiKey;

    /** 单次请求超时。 */
    private Duration timeout = Duration.ofSeconds(15);

    /** 默认返回条数。 */
    private int defaultCount = 5;

    /** 单次最多返回条数（接口上限 50，这里默认压到 10，控制 token 与成本）。 */
    private int maxCount = 10;

    /** 搜索结果拼给模型的最大字符数，超出截断。 */
    private int searchMaxChars = 12000;

    /** Fetch 返回正文的最大字符数，超出截断。 */
    private int fetchMaxChars = 8000;

    /** 是否具备实际调用条件（启用 + 配了 Key）。 */
    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank();
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

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getDefaultCount() {
        return defaultCount;
    }

    public void setDefaultCount(int defaultCount) {
        this.defaultCount = defaultCount;
    }

    public int getMaxCount() {
        return maxCount;
    }

    public void setMaxCount(int maxCount) {
        this.maxCount = maxCount;
    }

    public int getSearchMaxChars() {
        return searchMaxChars;
    }

    public void setSearchMaxChars(int searchMaxChars) {
        this.searchMaxChars = searchMaxChars;
    }

    public int getFetchMaxChars() {
        return fetchMaxChars;
    }

    public void setFetchMaxChars(int fetchMaxChars) {
        this.fetchMaxChars = fetchMaxChars;
    }
}
