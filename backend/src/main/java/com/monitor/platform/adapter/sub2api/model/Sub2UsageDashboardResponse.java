package com.monitor.platform.adapter.sub2api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * Sub2API 用量看板统计响应。
 *
 * <p>接口路径：{@code GET /api/v1/usage/dashboard/stats?timezone=Asia/Shanghai}。</p>
 *
 * @param code    Sub2API 业务状态码，0 表示成功
 * @param message 业务提示信息
 * @param data    看板统计数据
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Sub2UsageDashboardResponse(
        int code,
        String message,
        DashboardStats data
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DashboardStats(
            @JsonProperty("total_api_keys") Integer totalApiKeys,
            @JsonProperty("active_api_keys") Integer activeApiKeys,
            @JsonProperty("total_requests") Long totalRequests,
            @JsonProperty("total_input_tokens") Long totalInputTokens,
            @JsonProperty("total_output_tokens") Long totalOutputTokens,
            @JsonProperty("total_cache_creation_tokens") Long totalCacheCreationTokens,
            @JsonProperty("total_cache_read_tokens") Long totalCacheReadTokens,
            @JsonProperty("total_tokens") Long totalTokens,
            @JsonProperty("total_cost") BigDecimal totalCost,
            @JsonProperty("total_actual_cost") BigDecimal totalActualCost,
            @JsonProperty("today_requests") Long todayRequests,
            @JsonProperty("today_input_tokens") Long todayInputTokens,
            @JsonProperty("today_output_tokens") Long todayOutputTokens,
            @JsonProperty("today_cache_creation_tokens") Long todayCacheCreationTokens,
            @JsonProperty("today_cache_read_tokens") Long todayCacheReadTokens,
            @JsonProperty("today_tokens") Long todayTokens,
            @JsonProperty("today_cost") BigDecimal todayCost,
            @JsonProperty("today_actual_cost") BigDecimal todayActualCost,
            @JsonProperty("average_duration_ms") Double averageDurationMs,
            Double rpm,
            Double tpm,
            @JsonProperty("by_platform") List<PlatformStats> byPlatform
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlatformStats(
            String platform,
            @JsonProperty("total_requests") Long totalRequests,
            @JsonProperty("total_tokens") Long totalTokens,
            @JsonProperty("total_actual_cost") BigDecimal totalActualCost,
            @JsonProperty("today_requests") Long todayRequests,
            @JsonProperty("today_tokens") Long todayTokens,
            @JsonProperty("today_actual_cost") BigDecimal todayActualCost
    ) {
    }
}
