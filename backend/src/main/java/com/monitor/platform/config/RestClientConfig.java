package com.monitor.platform.config;

import cn.hutool.core.util.StrUtil;
import com.monitor.platform.common.interceptor.RetryInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 全局 HTTP 客户端配置。
 *
 * <p>所有上游适配器共用同一个 {@link RestClient}，统一连接超时、读取超时和网络重试策略，
 * 避免各适配器自行创建客户端导致配置漂移。</p>
 *
 * <p>同时统一加上浏览器风格的请求头：上游 New API / Sub2API 都是面向浏览器的站点，
 * 默认的 {@code Java-http-client/21} 标识过于显眼，容易被网关或风控拦截。</p>
 */
@Configuration
@EnableConfigurationProperties(UpstreamHttpProperties.class)
public class RestClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final int RETRY_MAX_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 500L;

    /**
     * 创建上游采集共用的 RestClient。
     */
    @Bean
    public RestClient restClient(UpstreamHttpProperties upstreamHttpProperties) {
        // JDK HttpClient 负责建立连接并设置连接超时。
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();

        // 请求工厂负责单次请求的读取超时。
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);

        // 网络层重试只处理连接类异常，HTTP 业务错误仍由各适配器处理。
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(factory)
                .requestInterceptor(new RetryInterceptor(RETRY_MAX_ATTEMPTS, RETRY_BACKOFF_MILLIS));
        return applyBrowserHeaders(builder, upstreamHttpProperties).build();
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
