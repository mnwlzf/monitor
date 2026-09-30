package com.monitor.platform.adapter.sub2api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Sub2API 可用分组接口响应。
 *
 * <p>接口路径：{@code GET /api/v1/groups/available?timezone=Asia/Shanghai}。</p>
 *
 * @param code    Sub2API 业务状态码，0 表示成功
 * @param message 业务提示信息
 * @param data    可用分组列表
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Sub2GroupsResponse(
        int code,
        String message,
        List<Group> data
) {

    /**
     * Sub2API 分组配置。
     *
     * <p>保留上游返回的分组字段，后续清洗和展示层可以按需读取。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Group(
            Long id,
            String name,//分组名称
            String description,//分组描述
            String platform,//分组所属平台 如 kimi deepseek  openai
            @JsonProperty("rate_multiplier") Double rateMultiplier, // 分组倍率
            @JsonProperty("is_exclusive") Boolean isExclusive,
            String status,//分组状态
            @JsonProperty("subscription_type") String subscriptionType,
            @JsonProperty("daily_limit_usd") Double dailyLimitUsd,
            @JsonProperty("weekly_limit_usd") Double weeklyLimitUsd,
            @JsonProperty("monthly_limit_usd") Double monthlyLimitUsd,
            @JsonProperty("long_context_pricing_enabled") Boolean longContextPricingEnabled,
            @JsonProperty("allow_image_generation") Boolean allowImageGeneration,
            @JsonProperty("allow_batch_image_generation") Boolean allowBatchImageGeneration,
            @JsonProperty("image_rate_independent") Boolean imageRateIndependent,
            @JsonProperty("image_rate_multiplier") Double imageRateMultiplier,
            @JsonProperty("batch_image_discount_multiplier") Double batchImageDiscountMultiplier,
            @JsonProperty("batch_image_hold_multiplier") Double batchImageHoldMultiplier,
            @JsonProperty("video_rate_independent") Boolean videoRateIndependent,
            @JsonProperty("video_rate_multiplier") Double videoRateMultiplier,
            @JsonProperty("peak_rate_enabled") Boolean peakRateEnabled,
            @JsonProperty("peak_start") String peakStart,
            @JsonProperty("peak_end") String peakEnd,
            @JsonProperty("peak_rate_multiplier") Double peakRateMultiplier,
            @JsonProperty("image_price_1k") Double imagePrice1k,
            @JsonProperty("image_price_2k") Double imagePrice2k,
            @JsonProperty("image_price_4k") Double imagePrice4k,
            @JsonProperty("video_price_480p") Double videoPrice480p,
            @JsonProperty("video_price_720p") Double videoPrice720p,
            @JsonProperty("video_price_1080p") Double videoPrice1080p,
            @JsonProperty("web_search_price_per_call") Double webSearchPricePerCall,
            @JsonProperty("search_price_per_1k") Double searchPricePer1k,
            @JsonProperty("audio_realtime_price_per_min") Double audioRealtimePricePerMin,
            @JsonProperty("audio_tts_price_per_million_chars") Double audioTtsPricePerMillionChars,
            @JsonProperty("audio_stt_price_per_hour") Double audioSttPricePerHour,
            @JsonProperty("claude_code_only") Boolean claudeCodeOnly,
            @JsonProperty("fallback_group_id") Long fallbackGroupId,
            @JsonProperty("fallback_group_id_on_invalid_request") Long fallbackGroupIdOnInvalidRequest,
            @JsonProperty("allow_messages_dispatch") Boolean allowMessagesDispatch,
            @JsonProperty("allow_live") Boolean allowLive,
            @JsonProperty("require_oauth_only") Boolean requireOauthOnly,
            @JsonProperty("require_privacy_set") Boolean requirePrivacySet,
            @JsonProperty("rpm_limit") Integer rpmLimit,
            @JsonProperty("max_reasoning_effort") String maxReasoningEffort,
            @JsonProperty("max_reasoning_effort_over_limit") String maxReasoningEffortOverLimit,
            @JsonProperty("reasoning_effort_mappings") List<Object> reasoningEffortMappings,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt
    ) {
    }
}