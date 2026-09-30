package com.monitor.platform.adapter.sub2api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Sub2API 密钥列表接口响应。
 *
 * <p>接口路径：{@code GET /api/v1/keys}。当前模型完整映射采集接口已返回的字段，
 * 便于后续清洗阶段直接读取，不在适配层做业务裁剪。</p>
 *
 * @param code    Sub2API 业务状态码，0 表示成功
 * @param message 业务提示信息
 * @param data    分页数据
 */
public record Sub2KeysResponse(
        int code,
        String message,
        Data data
) {

    /**
     * 密钥分页结果。
     *
     * @param items    当前页密钥列表
     * @param total    总记录数
     * @param page     当前页码
     * @param pageSize 每页记录数
     * @param pages    总页数
     */
    public record Data(
            List<KeyItem> items,
            int total,
            int page,
            @JsonProperty("page_size") int pageSize,
            int pages
    ) {
    }

    /**
     * 单个 API Key 及其关联分组信息。
     *
     * <p>配额、窗口用量和时间字段保留上游原始类型，避免在适配层提前做单位转换。
     * 单位统一和字段清洗属于后续 normalizer 模块的职责。</p>
     */
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
    ) {
    }

    /**
     * API Key 关联的分组配置。
     *
     * <p>该对象字段较多，当前直接保留上游结构；后续如接入清洗模块，
     * 可在这里或 Mapper 中映射为内部统一模型。</p>
     */
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
    ) {
    }
}