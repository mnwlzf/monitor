package com.monitor.platform.config;

import com.monitor.platform.common.interceptor.RetryInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 全局 HTTP 客户端配置。
 *
 * <p>所有上游适配器共用同一个 {@link RestClient}，统一连接超时、读取超时和网络重试策略，
 * 避免各适配器自行创建客户端导致配置漂移。</p>
 */
@Configuration
public class RestClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final int RETRY_MAX_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 500L;

    /**
     * 创建上游采集共用的 RestClient。
     */
    @Bean
    public RestClient restClient() {
        // JDK HttpClient 负责建立连接并设置连接超时。
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();

        // 请求工厂负责单次请求的读取超时。
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);

        // 网络层重试只处理连接类异常，HTTP 业务错误仍由各适配器处理。
        return RestClient.builder()
                .requestFactory(factory)
                .requestInterceptor(new RetryInterceptor(RETRY_MAX_ATTEMPTS, RETRY_BACKOFF_MILLIS))
                .build();
    }
}