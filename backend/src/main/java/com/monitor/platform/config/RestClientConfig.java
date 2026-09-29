package com.monitor.platform.config;


import com.monitor.platform.common.Interceptor.RetryInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient restClient() {
        // 1. 底层 HttpClient（JDK 自带，Java 11+）
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))   // 连接超时
                .build();

        // 2. 请求工厂
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));  // 读超时

        // 3. RestClient + 网络重试拦截器
        return RestClient.builder()
                .requestFactory(factory)
                .requestInterceptor(new RetryInterceptor(3, 500))
                .build();
    }
}
