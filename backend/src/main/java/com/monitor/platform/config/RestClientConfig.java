package com.monitor.platform.config;

import cn.hutool.core.util.StrUtil;
import com.monitor.platform.common.interceptor.RetryInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局 HTTP 客户端配置。
 *
 * <p>所有上游适配器共用同一个 {@link RestClient}，统一连接超时、读取超时和网络重试策略，
 * 避免各适配器自行创建客户端导致配置漂移。</p>
 *
 * <p>Sub2API 管理员接口的响应体远大于普通采集接口（全量号池账号 + 凭证），
 * 单独提供一个 {@code sub2AdminRestClient} 使用更长的读取超时，避免读取响应体阶段被截断。</p>
 *
 * <p>同时统一加上浏览器风格的请求头：上游 New API / Sub2API 都是面向浏览器的站点，
 * 默认的 {@code Java-http-client/21} 标识过于显眼，容易被网关或风控拦截。</p>
 */
@Configuration
@EnableConfigurationProperties(UpstreamHttpProperties.class)
public class RestClientConfig {

    /** 提供 Sub2API 管理员接口专用 RestClient 的 Bean 名称。 */
    public static final String SUB2_ADMIN_REST_CLIENT = "sub2AdminRestClient";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final int RETRY_MAX_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 500L;

    /**
     * Chromium 系浏览器固定发送的客户端提示头。
     *
     * <p>与默认 User-Agent 的版本号保持一致，避免 UA 与 sec-ch-ua 互相矛盾反而更可疑。
     * 刻意不含 {@code Accept-Encoding}：JDK HttpClient 不自动解压，声明了 br/zstd 会解析失败。</p>
     */
    private static final Map<String, String> CHROMIUM_CLIENT_HINTS = chromiumClientHints();

    private static Map<String, String> chromiumClientHints() {
        Map<String, String> hints = new LinkedHashMap<>();
        hints.put("sec-ch-ua", "\"Chromium\";v=\"154\", \"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"154\"");
        hints.put("sec-ch-ua-mobile", "?0");
        hints.put("sec-ch-ua-platform", "\"Windows\"");
        hints.put("sec-fetch-dest", "empty");
        hints.put("sec-fetch-mode", "cors");
        hints.put("sec-fetch-site", "same-origin");
        hints.put("priority", "u=1, i");
        return hints;
    }

    /**
     * 创建上游采集共用的 RestClient（普通采集接口使用）。
     */
    @Bean
    @Primary
    public RestClient restClient(UpstreamHttpProperties upstreamHttpProperties) {
        return buildRestClient(upstreamHttpProperties.getReadTimeout(), upstreamHttpProperties);
    }

    /**
     * 创建 Sub2API 管理员接口专用的 RestClient。
     *
     * <p>管理员接口单次响应体大、耗时长，使用独立的读取超时。</p>
     */
    @Bean(SUB2_ADMIN_REST_CLIENT)
    public RestClient sub2AdminRestClient(UpstreamHttpProperties upstreamHttpProperties) {
        return buildRestClient(upstreamHttpProperties.getAdminReadTimeout(), upstreamHttpProperties);
    }

    /**
     * 按给定读取超时构建 RestClient，其余策略（连接超时、重试、浏览器请求头）完全一致。
     */
    private RestClient buildRestClient(Duration readTimeout, UpstreamHttpProperties properties) {
        // JDK HttpClient 负责建立连接并设置连接超时。
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();

        // 请求工厂负责单次请求的读取超时。
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);

        // 网络层重试只处理连接类异常，HTTP 业务错误仍由各适配器处理。
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(factory)
                .requestInterceptor(new RetryInterceptor(RETRY_MAX_ATTEMPTS, RETRY_BACKOFF_MILLIS));
        return applyBrowserHeaders(builder, properties).build();
    }

    /**
     * 给所有上游请求加上浏览器风格的默认请求头。
     *
     * <p>这里刻意不加 {@code Accept-Encoding}：JDK HttpClient 不会自动解压响应体，
     * 一旦上游按 br/gzip 返回，反序列化就会失败。</p>
     */
    RestClient.Builder applyBrowserHeaders(RestClient.Builder builder, UpstreamHttpProperties properties) {
        addDefaultHeader(builder, HttpHeaders.USER_AGENT, properties.getUserAgent());
        addDefaultHeader(builder, HttpHeaders.ACCEPT, properties.getAccept());
        addDefaultHeader(builder, HttpHeaders.ACCEPT_LANGUAGE, properties.getAcceptLanguage());
        CHROMIUM_CLIENT_HINTS.forEach((name, value) -> addDefaultHeader(builder, name, value));
        return builder;
    }

    /**
     * 仅设置非空请求头，避免把空值当成请求头发出去。
     */
    private void addDefaultHeader(RestClient.Builder builder, String name, String value) {
        if (StrUtil.isNotBlank(value)) {
            builder.defaultHeader(name, value);
        }
    }
}