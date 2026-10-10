package com.monitor.platform.bot.weather;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 天气查询配置。
 *
 * <p>用的是 uapis.cn 的免费天气接口（GET /api/v1/misc/weather），无需注册与 Key。
 * 免费额度按次计（2 积分/次），因此默认开了短缓存，避免群里反复问同一个城市时把额度打满。</p>
 */
@ConfigurationProperties(prefix = "monitor.weather")
public class WeatherProperties {

    /** 是否启用天气查询。 */
    private boolean enabled = true;

    /** 接口根地址。 */
    private String baseUrl = "https://uapis.cn";

    /** 单次请求超时。 */
    private Duration timeout = Duration.ofSeconds(8);

    /** 同一城市的缓存时长；设为 0 表示不缓存。 */
    private Duration cacheTtl = Duration.ofMinutes(10);

    /** 默认预报天数（0 表示只查当前天气）。 */
    private int defaultForecastDays = 3;

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

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public int getDefaultForecastDays() {
        return defaultForecastDays;
    }

    public void setDefaultForecastDays(int defaultForecastDays) {
        this.defaultForecastDays = defaultForecastDays;
    }
}