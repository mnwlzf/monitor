package com.monitor.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 上游请求的浏览器伪装与超时配置。
 *
 * <p>New API / Sub2API 都是面向浏览器使用的站点，采集端默认的
 * {@code Java-http-client/21} 标识过于显眼，容易被网关或风控直接拦下。
 * 这里统一按常见桌面浏览器发送请求头，让采集请求与真实用户访问保持一致。</p>
 *
 * <p>可用环境变量覆盖，例如 {@code MONITOR_UPSTREAM_USER_AGENT}、
 * {@code MONITOR_UPSTREAM_ACCEPT_LANGUAGE}。</p>
 */
@ConfigurationProperties(prefix = "monitor.upstream")
public class UpstreamHttpProperties {

    /** User-Agent，默认伪装成桌面版 Chrome。 */
    private String userAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36";

    /** Accept，与浏览器 fetch/XHR 请求一致。 */
    private String accept = "application/json, text/plain, */*";

    /** Accept-Language，与中文浏览器默认值一致。 */
    private String acceptLanguage = "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6";

    /** 普通采集请求的读取超时。 */
    private Duration readTimeout = Duration.ofSeconds(15);

    /**
     * Sub2API 管理员接口的读取超时。
     *
     * <p>{@code /api/v1/admin/accounts} 等管理端点会一次性返回全量号池账号及其凭证，
     * 单页可达数百 KB、耗时数十秒，必须使用明显更长的读取超时，否则会在读取响应体时
     * 被截断（表现为 {@code java.io.IOException: closed}）。</p>
     */
    private Duration adminReadTimeout = Duration.ofMinutes(3);

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getAccept() {
        return accept;
    }

    public void setAccept(String accept) {
        this.accept = accept;
    }

    public String getAcceptLanguage() {
        return acceptLanguage;
    }

    public void setAcceptLanguage(String acceptLanguage) {
        this.acceptLanguage = acceptLanguage;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Duration getAdminReadTimeout() {
        return adminReadTimeout;
    }

    public void setAdminReadTimeout(Duration adminReadTimeout) {
        this.adminReadTimeout = adminReadTimeout;
    }
}