package com.monitor.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上游请求的浏览器伪装配置。
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
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /** Accept，与浏览器 fetch/XHR 请求一致。 */
    private String accept = "application/json, text/plain, */*";

    /** Accept-Language，与中文浏览器默认值一致。 */
    private String acceptLanguage = "zh-CN,zh;q=0.9,en;q=0.8";

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
}
