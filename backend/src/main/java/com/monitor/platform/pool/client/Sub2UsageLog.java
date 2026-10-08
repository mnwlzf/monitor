package com.monitor.platform.pool.client;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Sub2API 管理员用量接口返回的逐请求明细。
 *
 * @param requestId           请求 ID
 * @param apiKeyId            调用的密钥 ID
 * @param model               模型名
 * @param channelId           渠道 ID
 * @param endpoint            请求端点
 * @param stream              是否流式
 * @param createdAt           请求时间
 * @param firstTokenMs        首 token 耗时（毫秒）
 * @param durationMs          总耗时（毫秒）
 * @param inputTokens         输入 token
 * @param outputTokens        输出 token
 * @param cacheReadTokens     缓存读取 token
 * @param cacheCreationTokens 缓存写入 token
 * @param totalCost           原价成本
 * @param actualCost          实际成本
 */
public record Sub2UsageLog(
        String requestId,
        Long apiKeyId,
        String model,
        Long channelId,
        String endpoint,
        Boolean stream,
        OffsetDateTime createdAt,
        Integer firstTokenMs,
        Integer durationMs,
        Long inputTokens,
        Long outputTokens,
        Long cacheReadTokens,
        Long cacheCreationTokens,
        BigDecimal totalCost,
        BigDecimal actualCost
) {
}