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

    /**
     * 账号整体用量统计。
     *
     * @param totalApiKeys               API Key 总数
     * @param activeApiKeys              有效 API Key 数
     * @param totalRequests              累计请求数
     * @param totalInputTokens           累计输入 Token 数
     * @param totalOutputTokens          累计输出 Token 数
     * @param totalCacheCreationTokens   累计缓存创建 Token 数
     * @param totalCacheReadTokens       累计缓存读取 Token 数
     * @param totalTokens                累计 Token 总数
     * @param totalCost                  累计标准消耗
     * @param totalActualCost            累计实际消耗
     * @param todayRequests              今日请求数
     * @param todayInputTokens           今日输入 Token 数
     * @param todayOutputTokens          今日输出 Token 数
     * @param todayCacheCreationTokens   今日缓存创建 Token 数
     * @param todayCacheReadTokens       今日缓存读取 Token 数
     * @param todayTokens                今日 Token 数
     * @param todayCost                  今日标准消耗
     * @param todayActualCost            今日实际消耗
     * @param averageDurationMs          平均请求耗时
     * @param rpm                        每分钟请求数
     * @param tpm                        每分钟 Token 数
     * @param byPlatform                 按渠道平台聚合的统计
     */
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

    /**
     * 按渠道平台聚合的用量统计。
     *
     * @param platform         平台标识
     * @param totalRequests    累计请求数
     * @param totalTokens      累计 Token 数
     * @param totalActualCost  累计实际消耗
     * @param todayRequests    今日请求数
     * @param todayTokens      今日 Token 数
     * @param todayActualCost  今日实际消耗
     */
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
