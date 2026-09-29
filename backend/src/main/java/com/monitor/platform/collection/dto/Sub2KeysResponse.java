package com.monitor.platform.collection.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record Sub2KeysResponse(
        int code,
        String message,
        Data data
) {
    public record Data(
            List<KeyItem> items,
            int total,
            int page,
            @JsonProperty("page_size") int pageSize,
            int pages
    ) {}

    public record KeyItem(
            Long id,
            @JsonProperty("user_id") Long userId,
            String key,
            String name,
            @JsonProperty("group_id") Long groupId,
            String status,
            @JsonProperty("ip_whitelist") String ipWhitelist,
            @JsonProperty("ip_blacklist") String ipBlacklist,
            @JsonProperty("last_used_at") String lastUsedAt,
            @JsonProperty("last_used_ip") String lastUsedIp,
            Double quota,
            @JsonProperty("quota_used") Double quotaUsed,
            @JsonProperty("expires_at") String expiresAt,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt,
            @JsonProperty("current_concurrency") Integer currentConcurrency,
            @JsonProperty("rate_limit_5h") Integer rateLimit5h,
            @JsonProperty("rate_limit_1d") Integer rateLimit1d,
            @JsonProperty("rate_limit_7d") Integer rateLimit7d,
            @JsonProperty("usage_5h") Double usage5h,
            @JsonProperty("usage_1d") Double usage1d,
            @JsonProperty("usage_7d") Double usage7d,
            @JsonProperty("window_5h_start") String window5hStart,
            @JsonProperty("window_1d_start") String window1dStart,
            @JsonProperty("window_7d_start") String window7dStart,
            Group group
    ) {}

    public record Group(
            Long id,
            String name,
            String description,
            String platform,
            @JsonProperty("rate_multiplier") Double rateMultiplier,
            @JsonProperty("is_exclusive") Boolean isExclusive,
            String status,
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
    ) {}
}