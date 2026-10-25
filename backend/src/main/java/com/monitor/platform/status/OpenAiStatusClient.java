package com.monitor.platform.status;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;

/**
 * 读取 OpenAI 官方 Statuspage。
 *
 * <p>使用 Statuspage 公开的 summary.json，而不是解析 HTML：结构稳定，且能同时拿到总体状态、
 * 组件状态与进行中的事件。</p>
 */
@Component
public class OpenAiStatusClient {

    static final URI SUMMARY_URI = URI.create("https://status.openai.com/api/v2/summary.json");

    private final RestClient restClient;

    public OpenAiStatusClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 拉取最新状态。
     *
     * @throws IllegalStateException 响应为空或缺少 indicator 时抛出，避免把解析异常误判成正常
     */
    public OpenAiStatusSummary fetch() {
        OpenAiStatusSummary summary = restClient.get()
                .uri(SUMMARY_URI)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(OpenAiStatusSummary.class);
        if (summary == null || summary.status() == null
                || summary.status().indicator() == null
                || summary.status().indicator().isBlank()) {
            throw new IllegalStateException("OpenAI 状态页响应缺少 status.indicator");
        }
        return summary;
    }
}