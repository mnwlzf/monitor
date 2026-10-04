package com.monitor.platform.adapter.sub2api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Sub2API 密钥用量批量响应。
 *
 * <p>接口路径：{@code POST /api/v1/usage/dashboard/api-keys-usage}，请求体为
 * {@code {"api_key_ids":[...]}}。密钥列表中的 {@code quota}/{@code usage_*} 恒为 0，
 * 真实用量只能通过该接口按 ID 批量获取。</p>
 *
 * @param code    Sub2API 业务状态码，0 表示成功
 * @param message 业务提示信息
 * @param data    用量数据
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Sub2ApiKeysUsageResponse(
        int code,
        String message,
        Data data
) {

    /**
     * 用量数据，{@code stats} 以密钥 ID 字符串为 key。
     *
     * @param stats 密钥 ID 到用量的映射
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(Map<String, Stat> stats) {
    }

    /**
     * 单个密钥的用量。
     *
     * @param apiKeyId         密钥 ID
     * @param todayActualCost  今日实际消耗
     * @param totalActualCost  累计实际消耗
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stat(
            @JsonProperty("api_key_id") Long apiKeyId,
            @JsonProperty("today_actual_cost") BigDecimal todayActualCost,
            @JsonProperty("total_actual_cost") BigDecimal totalActualCost
    ) {
    }
}
