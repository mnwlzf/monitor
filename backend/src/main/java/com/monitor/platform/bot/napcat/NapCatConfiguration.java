package com.monitor.platform.bot.napcat;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 注册 NapCat 集成配置。
 */
@Configuration
@EnableConfigurationProperties(NapCatProperties.class)
public class NapCatConfiguration {

    /** NapCat WebUI 专用 RestClient：短超时、无采集侧重试，避免状态页被上游拖慢。 */
    public static final String NAPCAT_REST_CLIENT = "napCatRestClient";

    @Bean(NAPCAT_REST_CLIENT)
    public RestClient napCatRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory).build();
    }
}
